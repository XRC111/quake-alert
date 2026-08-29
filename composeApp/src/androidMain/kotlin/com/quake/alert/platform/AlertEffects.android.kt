package com.quake.alert.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import com.quake.alert.app.ContextHolder
import com.quake.alert.notification.QuakeNotifier

@Suppress("DEPRECATION")
actual class AlertEffects {

    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneLoop: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    actual fun playAlarm() {
        stopAlarm()
        val ctx = runCatching { ContextHolder.context }.getOrNull() ?: return

        // 优先使用放在 res/raw/alarm.wav 的自定义报警音；缺失则回落到系统蜂鸣
        val resId = ctx.resources.getIdentifier("alarm", "raw", ctx.packageName)
        if (resId != 0) {
            runCatching {
                MediaPlayer.create(ctx, resId)?.apply {
                    isLooping = true
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setVolume(1f, 1f)
                    start()
                    mediaPlayer = this
                }
            }
        }

        if (mediaPlayer == null) {
            // ToneGenerator 无法无限长鸣，用 1s 周期循环逼近持续报警
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
            startToneLoop()
        }
    }

    private fun startToneLoop() {
        stopToneLoop()
        val runnable = object : Runnable {
            override fun run() {
                val generator = toneGenerator ?: return
                generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_DURATION_MS)
                handler.postDelayed(this, TONE_INTERVAL_MS)
            }
        }
        toneLoop = runnable
        handler.post(runnable)
    }

    private fun stopToneLoop() {
        toneLoop?.let { handler.removeCallbacks(it) }
        toneLoop = null
        toneGenerator?.stopTone()
    }

    actual fun stopAlarm() {
        stopToneLoop()
        toneGenerator?.release()
        toneGenerator = null
        mediaPlayer?.apply {
            runCatching { if (isPlaying) stop() }
            release()
        }
        mediaPlayer = null
    }

    actual fun vibrate() {
        val ctx = runCatching { ContextHolder.context }.getOrNull() ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 700), 0)
        )
    }

    actual fun wakeScreen() {
        val ctx = runCatching { ContextHolder.context }.getOrNull() ?: return

        // 1) 点亮屏幕并保持在锁屏之上
        val activity = ContextHolder.activity
        activity?.runOnUiThread {
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            activity.findViewById<android.view.View>(android.R.id.content)?.keepScreenOn = true
        }

        // 2) 兜底唤醒锁，应对 Activity 不在前台的情况（60s 自动释放）
        runCatching {
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "QuakeAlert:AlertWakeLock",
            )
            lock.acquire(60_000L)
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = lock
        }
    }

    actual fun showNotification(title: String, text: String) {
        QuakeNotifier.notifyAlert(
            title = title,
            text = text,
            magnitude = title,
        )
    }

    actual fun cancelNotification() {
        runCatching { QuakeNotifier.cancelAll() }
    }

    actual fun setWindowAlwaysOnTop(enabled: Boolean) {
        // 移动端无窗口层级概念：已通过 wakeScreen() 亮屏 + 全屏通知覆盖同等效果。
        if (!enabled) {
            val activity = ContextHolder.activity
            activity?.runOnUiThread {
                activity.window.clearFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }
    }

    actual fun dispose() {
        stopAlarm()
        vibrateCancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { QuakeNotifier.cancelAll() }
    }

    private fun vibrateCancel() {
        val ctx = runCatching { ContextHolder.context }.getOrNull() ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vibrator?.cancel() }
    }

    private companion object {
        const val TONE_VOLUME = 100
        const val TONE_DURATION_MS = 800
        const val TONE_INTERVAL_MS = 1000L
    }
}
