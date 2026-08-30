package com.quake.alert.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AudioToolbox.AudioServicesPlaySystemSound
// Xcode 26 起 AVAudioPlayer/AVAudioSession 从 AVFoundation 拆分为独立 AVFAudio 框架
// （Kotlin 2.4.10 的 platform libs 对应 platform.AVFAudio 包）
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSBundle
import platform.UIKit.UIApplication
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

@OptIn(ExperimentalForeignApi::class)
actual class AlertEffects {

    private var player: AVAudioPlayer? = null

    actual fun playAlarm() {
        // 让声音在静音开关打开 / 后台时也能播放：Playback 类别即覆盖静音键，
        // AVAudioPlayer.play() 会隐式激活会话，无需显式 setActive
        //（Xcode 26 SDK 的 Kotlin/Native AVFAudio 绑定已不含 setActive 成员）。
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, null)
        }

        // 优先使用打进 App Bundle 的 alarm.wav（在 Xcode 里加到 Copy Bundle Resources）
        val url = NSBundle.mainBundle.URLForResource("alarm", withExtension = "wav")
        if (url != null) {
            player = runCatching { AVAudioPlayer(url, null) }.getOrNull()?.apply {
                numberOfLoops = -1 // 无限循环，直到 stopAlarm()
                prepareToPlay()
                play()
            }
        }

        // 无自定义音频时退化为系统提示音 + 震动
        if (player == null) {
            AudioServicesPlaySystemSound(SYSTEM_SOUND_ALERT)
            vibrate()
        }
    }

    actual fun stopAlarm() {
        runCatching { player?.stop() }
        player = null
        // 会话由系统管理，play 结束即隐式释放，无需显式停用（setActive 在
        // Xcode 26 的 Kotlin/Native 绑定中不存在）
    }

    actual fun vibrate() {
        AudioServicesPlaySystemSound(SYSTEM_SOUND_VIBRATE)
    }

    actual fun wakeScreen() {
        // 阻止自动息屏，保证弹窗可见（必须在主线程调用）
        UIApplication.sharedApplication().idleTimerDisabled = true
    }

    actual fun setWindowAlwaysOnTop(enabled: Boolean) {
        // iOS 无通用窗口层级概念；可通过把承载 Compose 的 UIViewController
        // 以 .overFullScreen 模态方式 present 来实现同等效果。
    }

    /**
     * 投递本地通知。
     * 前置条件：宿主 App 已在启动阶段调用
     * `UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(...)` 完成授权，
     * 否则系统会静默丢弃。授权代码放在 iosApp 的 Swift 侧更合适。
     */
    actual fun showNotification(title: String, text: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "quake-alert",
            content = content,
            // trigger 为 null 表示立即投递
            trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(0.1, repeats = false),
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    actual fun cancelNotification() {
        UNUserNotificationCenter.currentNotificationCenter()
            .removeDeliveredNotificationsWithIdentifiers(listOf("quake-alert"))
    }

    actual fun dispose() {
        stopAlarm()
        UIApplication.sharedApplication().idleTimerDisabled = false
    }

    private companion object {
        /** kSystemSoundID_Vibrate */
        const val SYSTEM_SOUND_VIBRATE: UInt = 0x00000FFFu
        /** 系统警告音 */
        const val SYSTEM_SOUND_ALERT: UInt = 1005u
    }
}
