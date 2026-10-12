package io.github.mangi.eta.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.mangi.eta.ui.MainActivity

/**
 * 主界面是否对用户可见。Runtime 与 App 同进程，据此决定 ask_user 的问题由 App 输入框上方的面板
 * 还是状态胶囊承接；系统助手入口的 Activity 不计入。
 */
internal object MainUiVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var startedCount = 0

    val isVisible: Boolean
        get() = startedCount > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) startedCount += 1
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity) startedCount = (startedCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
