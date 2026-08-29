package com.quake.alert.platform

/**
 * 平台差异化能力（声音 / 震动 / 亮屏 / 通知 / 窗口置顶）的 expect 声明。
 *
 * 各平台 actual 实现：
 * - androidMain：`MediaPlayer`（`res/raw/alarm.wav`，缺失时回落 `ToneGenerator` 循环蜂鸣）
 *   + `Vibrator` + 全屏通知（FullScreenIntent）+ `FLAG_TURN_SCREEN_ON`
 * - iosMain：`AVAudioPlayer`（bundle 内 `alarm.wav`）+ `AudioServicesPlaySystemSound`
 *   + `UNUserNotificationCenter` + `idleTimerDisabled`
 * - desktopMain：`Toolkit.beep()` + `javax.sound.sampled` 生成警笛音 + Compose Window `alwaysOnTop`
 *
 * 所有方法都必须是"永不抛异常"的：预警链路上的一次崩溃比没有声音严重得多，
 * 因此各 actual 内部统一用 runCatching 兜底。
 */
expect class AlertEffects() {

    /** 开始播放报警音（循环）。重复调用应先停后播，不叠加。 */
    fun playAlarm()

    /** 停止报警音。 */
    fun stopAlarm()

    /** 触发一次震动模式。桌面/iOS 不支持时静默降级。 */
    fun vibrate()

    /** 点亮屏幕并阻止息屏，保证预警弹窗可见。 */
    fun wakeScreen()

    /**
     * 桌面端把窗口置顶（移动端无对应能力，实现为空）。
     * @param enabled true 置顶，false 恢复常规层级
     */
    fun setWindowAlwaysOnTop(enabled: Boolean)

    /** 发布系统通知（App 在后台时的兜底触达）。 */
    fun showNotification(title: String, text: String)

    /** 撤销本应用发出的预警通知。 */
    fun cancelNotification()

    /** 释放播放器 / 唤醒锁等资源，随 Composable 退出时调用。 */
    fun dispose()
}
