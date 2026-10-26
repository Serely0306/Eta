package io.github.mangi.eta.agent.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 屏幕是否点亮，供浮层的帧动画判断。熄屏时浮层窗口仍在，无限动画会逐帧请求重绘
 * （日志刷 “Not drawing due to screen off”），白白耗电；熄屏时改画静止状态，亮屏后恢复。
 * 进程内注册一次，主线程调用。
 */
internal object ScreenInteractivity {
    var interactive by mutableStateOf(true)
        private set

    private var registered = false

    fun ensureRegistered(context: Context) {
        if (registered) return
        val app = context.applicationContext
        interactive = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    interactive = intent.action != Intent.ACTION_SCREEN_OFF
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }
}
