package io.github.mangi.eta.agent.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 页面按会话保留，运行引用与挂载容器共同决定何时可以回收。 */
internal object BrowserSessionRegistry {
    const val MANUAL_SESSION_KEY = "browser:manual"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = mutableMapOf<String, Entry>()
    private val clearingData = AtomicBoolean(false)

    private class Entry(val session: AgentBrowserSession) {
        val runs = mutableMapOf<String, Int>()
        var removalRequested = false
    }

    fun get(sessionKey: String): AgentBrowserSession = synchronized(lock) {
        entry(sessionKey).session
    }

    fun acquireRun(sessionKey: String, runId: String): AutoCloseable {
        require(runId.isNotBlank())
        val owner = synchronized(lock) {
            entry(sessionKey).also { it.runs[runId] = (it.runs[runId] ?: 0) + 1 }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    val count = (owner.runs[runId] ?: 1) - 1
                    if (count == 0) owner.runs.remove(runId) else owner.runs[runId] = count
                }
                collectRemovedPages()
            }
        }
    }

    fun requestRemove(sessionKey: String) {
        synchronized(lock) { entries[sessionKey]?.removalRequested = true }
        collectRemovedPages()
    }

    fun releaseIdlePages() = onMain {
        synchronized(lock) {
            entries.values.filter { it.runs.isEmpty() }.forEach { it.session.releaseIdlePage() }
            removeReadyEntries()
        }
    }

    /** 全局清理先唤醒动作等待，再在后台逐页重置，主线程从不等待页面操作锁。 */
    fun clearSharedBrowsingData(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Browser data clearing requires a worker thread" }
        check(clearingData.compareAndSet(false, true)) { "Browser data clearing is already in progress" }
        try {
            val sessions = synchronized(lock) { entries.values.map { it.session } }
            sessions.forEach { it.stopFromUser() }
            sessions.forEach { it.resetFromUser() }
            val cleared = CompletableFuture<Unit>()
            mainHandler.post {
                try {
                    val cacheCleaner = WebView(context.applicationContext)
                    try { cacheCleaner.clearCache(true) } finally { cacheCleaner.destroy() }
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies {
                        try {
                            CookieManager.getInstance().flush()
                            cleared.complete(Unit)
                        } catch (failure: Exception) {
                            cleared.completeExceptionally(failure)
                        }
                    }
                } catch (failure: Exception) {
                    cleared.completeExceptionally(failure)
                }
            }
            cleared.get(15L, TimeUnit.SECONDS)
        } finally {
            clearingData.set(false)
        }
    }

    private fun entry(sessionKey: String): Entry {
        require(sessionKey.isNotBlank())
        return entries.getOrPut(sessionKey) {
            Entry(AgentBrowserSession(onIdle = ::collectRemovedPages, isDataClearing = clearingData::get))
        }
    }

    private fun collectRemovedPages() = onMain { synchronized(lock) { removeReadyEntries() } }

    private fun removeReadyEntries() {
        entries.entries.removeAll { (_, entry) ->
            entry.removalRequested && entry.runs.isEmpty() && entry.session.releaseIdlePage(dispose = true)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
