package com.quake.alert.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.quake.alert.MainActivity
import com.quake.alert.R
import com.quake.alert.app.ContextHolder

/**
 * Android 侧通知（全屏意图）。用于在 App 处于后台/息屏时把预警顶到最前。
 * 需要在系统设置中授予"全屏通知"权限（Android 10+ 会自动引导）。
 */
object QuakeNotifier {

    const val CHANNEL_ID = "quake_alert"
    private const val CHANNEL_NAME = "地震预警"
    private const val NOTIFICATION_ID = 9001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "达到阈值时立即弹出地震预警"
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            // setBypassDnd 自 API 29 起才有，低版本上调用会 NoSuchMethodError
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setBypassDnd(true)
            }
        }
        manager.createNotificationChannel(channel)
    }

    /** 弹出带全屏意图的预警通知。返回 true 表示已成功发出。 */
    fun notifyAlert(
        title: String,
        text: String,
        magnitude: String,
    ): Boolean {
        val context = runCatching { ContextHolder.context }.getOrNull() ?: return false
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_ALERT_MAGNITUDE, magnitude)
                putExtra(MainActivity.EXTRA_ALERT_TEXT, text)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    fun cancelAll() {
        val context = runCatching { ContextHolder.context }.getOrNull() ?: return
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
