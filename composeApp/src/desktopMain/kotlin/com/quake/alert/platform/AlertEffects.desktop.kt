package com.quake.alert.platform

import com.quake.alert.DesktopWindowState
import java.awt.Toolkit
import java.net.URL
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * 桌面端报警实现。
 *
 * 报警音优先级：
 * 1. classpath 根目录下的 `alarm.wav`（放到 `desktopMain/resources/alarm.wav`）→ 循环播放
 * 2. 否则用 `javax.sound.sampled` 现场合成 880Hz / 1320Hz 交替的警笛音
 *
 * 两条路径都会先来一记 `Toolkit.getDefaultToolkit().beep()`，
 * 保证在音频设备异常（无声卡、被占用）时用户至少能得到一次系统提示音。
 */
actual class AlertEffects {

    @Volatile
    private var playing = false

    private var clip: Clip? = null
    private var line: SourceDataLine? = null
    private var toneThread: Thread? = null

    actual fun playAlarm() {
        stopAlarm()

        // 兜底：系统蜂鸣（需求明确要求桌面端使用 Toolkit.beep）
        runCatching { Toolkit.getDefaultToolkit().beep() }

        val wav: URL? = AlertEffects::class.java.getResource("/alarm.wav")
        if (wav != null && runCatching { playWav(wav) }.getOrDefault(false)) {
            return
        }
        startSiren()
    }

    actual fun stopAlarm() {
        playing = false
        runCatching { clip?.stop(); clip?.close() }
        clip = null
        runCatching { line?.stop(); line?.drain(); line?.close() }
        line = null
        toneThread?.interrupt()
        toneThread = null
    }

    actual fun vibrate() {
        // 桌面端无震动马达；改为连续三声短促蜂鸣做提示
        thread(start = true, isDaemon = true, name = "quake-beep-burst") {
            repeat(3) {
                runCatching { Toolkit.getDefaultToolkit().beep() }
                Thread.sleep(180)
            }
        }
    }

    actual fun wakeScreen() {
        // 桌面端无息屏概念；置顶窗口本身即是最强提醒
        DesktopWindowState.setAlwaysOnTop(true)
    }

    actual fun setWindowAlwaysOnTop(enabled: Boolean) {
        DesktopWindowState.setAlwaysOnTop(enabled)
    }

    actual fun showNotification(title: String, text: String) {
        // 桌面端已有全屏弹窗；如需托盘通知，可在此接入 java.awt.SystemTray。
        // 注意：必须在 JVM 启动参数里加 -Djava.awt.headless=false。
        println("[QuakeAlert] $title — $text")
    }

    actual fun cancelNotification() = Unit

    actual fun dispose() {
        stopAlarm()
        DesktopWindowState.setAlwaysOnTop(false)
    }

    // ------------------------------------------------------------------

    private fun playWav(url: URL): Boolean {
        val clip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(url).use { stream ->
            clip.open(stream)
        }
        clip.loop(Clip.LOOP_CONTINUOUSLY)
        clip.start()
        this.clip = clip
        return true
    }

    private fun startSiren() {
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        val dataLine = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull() ?: return
        runCatching {
            dataLine.open(format, SAMPLE_RATE.toInt())
            dataLine.start()
        }.getOrNull() ?: return

        line = dataLine
        playing = true

        val buffer = buildSirenBuffer()
        toneThread = thread(start = true, isDaemon = true, name = "quake-siren") {
            while (playing && !Thread.currentThread().isInterrupted) {
                runCatching { dataLine.write(buffer, 0, buffer.size) }
                    .onFailure { break }
            }
            runCatching { dataLine.stop(); dataLine.close() }
        }
    }

    /** 生成 1 秒的警笛波形：前半 880Hz、后半 1320Hz，循环播放即为"呜—呜—"。 */
    private fun buildSirenBuffer(): ByteArray {
        val rate = SAMPLE_RATE.toInt()
        val out = ByteArray(rate * 2) // 16-bit mono
        var index = 0
        for (n in 0 until rate) {
            val freq = if (n < rate / 2) 880.0 else 1320.0
            val amplitude = sin(2.0 * PI * freq * (n / SAMPLE_RATE)) * 12_000.0
            val sample = amplitude.toInt().coerceIn(-32_768, 32_767)
            out[index++] = (sample and 0xFF).toByte()
            out[index++] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100f
    }
}
