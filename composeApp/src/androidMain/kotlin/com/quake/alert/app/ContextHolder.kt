package com.quake.alert.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * 供 expect/actual 的 Android 侧实现访问 Context / 当前 Activity。
 * 由 [com.quake.alert.MainActivity] 在 onCreate / attachBaseContext 时注入。
 */
object ContextHolder {

    @Volatile
    private var application: Application? = null

    @Volatile
    private var resumedActivity: Activity? = null

    fun attach(app: Application) {
        if (application === app) return
        application = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }
        })
    }

    val context: Context
        get() = application ?: error("ContextHolder 未初始化，请确认 MainActivity 已调用 attach()")

    val activity: Activity? get() = resumedActivity
}
