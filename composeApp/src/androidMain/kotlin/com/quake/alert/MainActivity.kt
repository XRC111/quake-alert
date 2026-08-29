package com.quake.alert

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.quake.alert.app.ContextHolder
import com.quake.alert.notification.QuakeNotifier
import com.quake.alert.platform.AlertEffects
import com.quake.alert.ui.QuakeApp

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ALERT_MAGNITUDE = "extra_alert_magnitude"
        const val EXTRA_ALERT_TEXT = "extra_alert_text"
    }

    private val effects by lazy { AlertEffects() }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝则退化为仅应用内弹窗 */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        ContextHolder.attach(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextHolder.attach(application)
        QuakeNotifier.ensureChannel(this)
        askNotificationPermissionIfNeeded()

        enableEdgeToEdge()

        setContent {
            // 全屏通知被点击后进入此 Activity：此时 UI 的 AlertOverlay 会接管，
            // 这里只负责确保报警音在 Activity 启动后仍然响着。
            val fromAlert = intent?.hasExtra(EXTRA_ALERT_MAGNITUDE) == true
            if (fromAlert) {
                effects.wakeScreen()
            }
            QuakeApp()
        }
    }

    override fun onDestroy() {
        effects.dispose()
        super.onDestroy()
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
