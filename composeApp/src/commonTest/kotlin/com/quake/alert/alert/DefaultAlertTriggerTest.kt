package com.quake.alert.alert

import com.quake.alert.model.AlertLevel
import com.quake.alert.model.GeoPoint
import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DefaultAlertTrigger（烈度优先规则引擎）单元测试。
 */
class DefaultAlertTriggerTest {

    private fun event(
        id: String = "evt-1",
        magnitude: Double = 4.0,
        intensity: Double? = 4.0,
        depthKm: Double? = 10.0,
        remainTimeSec: Int? = null,
        arrivalTime: Long? = null,
    ) = QuakeEvent(
        id = id,
        source = QuakeSource.CENC_EEW,
        magnitude = magnitude,
        latitude = 31.2,
        longitude = 121.4,
        placeName = "测试地点",
        originTime = 1_000_000_000_000L,
        receivedAt = 1_000_000_000_000L,
        depthKm = depthKm,
        updateSerial = 1,
        isEew = true,
        intensity = intensity,
        arrivalTime = arrivalTime,
        remainTimeSec = remainTimeSec,
    )

    // ------------------------------------------------------------------
    // 烈度优先判定
    // ------------------------------------------------------------------

    @Test
    fun 烈度达到阈值触发() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(event(intensity = 3.0))
        assertTrue(decision.shouldAlert, "烈度 3.0 >= 阈值 3.0 应触发")
        assertNotNull(decision.alert)
        assertTrue(decision.reason.contains("烈度"))
    }

    @Test
    fun 烈度低于阈值不触发() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(event(intensity = 2.9, magnitude = 6.0))
        assertFalse(decision.shouldAlert, "烈度 2.9 < 3.0 即使震级 6.0 也不应触发（烈度优先）")
        assertNull(decision.alert)
    }

    @Test
    fun 无烈度时降级按震级触发() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(event(intensity = null, magnitude = 3.5))
        assertTrue(decision.shouldAlert, "无烈度字段时应降级按震级判定")
        assertTrue(decision.reason.contains("降级"), "降级路径应在 reason 中标注: ${decision.reason}")
    }

    @Test
    fun 无烈度且震级低于降级阈值不触发() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(event(intensity = null, magnitude = 2.9))
        assertFalse(decision.shouldAlert)
    }

    @Test
    fun 阈值热更新立即生效() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        trigger.rules = AlertRuleConfig(intensityThreshold = 5.0)

        val decision = trigger.evaluate(event(intensity = 4.0))
        assertFalse(decision.shouldAlert, "阈值提高到 5.0 后，烈度 4.0 不应再触发")
    }

    // ------------------------------------------------------------------
    // 分级
    // ------------------------------------------------------------------

    @Test
    fun 烈度分级() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        // 每个分级用独立事件 ID，避免撞上去重窗口
        assertEquals(AlertLevel.SEVERE, trigger.evaluate(event(id = "s", intensity = 6.0)).alert?.level)
        assertEquals(AlertLevel.WARNING, trigger.evaluate(event(id = "w", intensity = 4.0)).alert?.level)
        assertEquals(AlertLevel.INFO, trigger.evaluate(event(id = "i", intensity = 3.0)).alert?.level)
    }

    // ------------------------------------------------------------------
    // 30 分钟去重
    // ------------------------------------------------------------------

    @Test
    fun 去重窗口内同ID不重复弹窗() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        assertTrue(trigger.evaluate(event(intensity = 5.0)).shouldAlert)

        // 同一事件，烈度未显著上调 → 抑制
        val second = trigger.evaluate(event(intensity = 5.1))
        assertFalse(second.shouldAlert, "30 分钟窗口内同 ID 应抑制")
        assertTrue(second.reason.contains("抑制"))
    }

    @Test
    fun 窗口内烈度显著上调触发升级复报() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        assertTrue(trigger.evaluate(event(intensity = 4.0)).shouldAlert)

        // 烈度上调 >= 0.5 → 允许复报
        assertTrue(trigger.evaluate(event(intensity = 5.0)).shouldAlert, "烈度 4.0→5.0 升级应复报")
    }

    @Test
    fun 关闭升级复报后严格一次() = runTest {
        val trigger = DefaultAlertTrigger(
            rules = AlertRuleConfig(allowEscalationReAlert = false),
            now = { 1_000_000_000_000L },
        )
        assertTrue(trigger.evaluate(event(intensity = 4.0)).shouldAlert)
        assertFalse(trigger.evaluate(event(intensity = 6.0)).shouldAlert, "关闭复报后窗口内不再弹")
    }

    // ------------------------------------------------------------------
    // 倒计时：以 API 为准
    // ------------------------------------------------------------------

    @Test
    fun 倒计时优先取剩余秒数() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(
            event(intensity = 5.0, remainTimeSec = 30, arrivalTime = 1_000_000_000_000L + 10_000L)
        )
        assertEquals(30, decision.alert?.countdownSeconds, "remainTimeSec 应优先于 arrivalTime")
    }

    @Test
    fun 无剩余秒数时用到达时刻换算() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(
            event(intensity = 5.0, arrivalTime = 1_000_000_000_000L + 10_000L)
        )
        assertEquals(10, decision.alert?.countdownSeconds)
    }

    @Test
    fun 两者都没有时倒计时为未知() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(event(intensity = 5.0))
        assertNull(decision.alert?.countdownSeconds, "API 无到达时间字段时应返回 null（UI 显示未知）")
    }

    @Test
    fun 到达时刻已过显示0() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val decision = trigger.evaluate(
            event(intensity = 5.0, arrivalTime = 1_000_000_000_000L - 5_000L)
        )
        assertEquals(0, decision.alert?.countdownSeconds)
    }

    // ------------------------------------------------------------------
    // 本地估算集成（可选，默认关闭）
    // ------------------------------------------------------------------

    /** 观测点与震中重合的估算器：估算烈度封顶 12，倒计时 0 */
    private fun fullLocalEstimator() = LocalEstimator(
        observerProvider = { GeoPoint(31.0, 121.0) },
        enabledProvider = { true },
        now = { 1_000_000_000_000L },
    )

    @Test
    fun 本地估算烈度参与判定() = runTest {
        val trigger = DefaultAlertTrigger(
            localEstimator = fullLocalEstimator(),
            now = { 1_000_000_000_000L },
        )
        // 事件无 API 烈度字段、震级 3.5 低于降级阈值 3.0 的触发条件？不——3.5 >= 3.0 会降级命中。
        // 改为震级 2.5：只有本地估算烈度（12）能把它顶上阈值。
        val decision = trigger.evaluate(event(id = "est", magnitude = 2.5, intensity = null))
        assertTrue(decision.shouldAlert, "无 API 烈度时，本地估算烈度应参与判定: ${decision.reason}")
        assertTrue(decision.reason.contains("本地估算"), decision.reason)
        assertNotNull(decision.alert?.localIntensity)
        assertNotNull(decision.alert?.countdownSeconds, "估算应补上倒计时")
    }

    @Test
    fun 未开启估算时回落API字段与降级() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L }) // 无 localEstimator
        val decision = trigger.evaluate(event(id = "no-est", magnitude = 2.5, intensity = null))
        assertFalse(decision.shouldAlert, "无估算且无 API 烈度、震级不足时应不触发")
    }

    @Test
    fun 倒计时API字段优先于本地估算() = runTest {
        val trigger = DefaultAlertTrigger(
            localEstimator = fullLocalEstimator(),
            now = { 1_000_000_000_000L },
        )
        val decision = trigger.evaluate(
            event(id = "cd-prio", intensity = 5.0, remainTimeSec = 45)
        )
        assertEquals(45, decision.alert?.countdownSeconds, "API remainTimeSec 应优先于估算")
    }

    @Test
    fun 速报目录事件不触发预警() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        val directoryEvent = event(id = "dir-1", intensity = 6.0).copy(isEew = false)
        val decision = trigger.evaluate(directoryEvent)
        assertFalse(decision.shouldAlert, "目录/测定事件（isEew=false）即使烈度高也不应触发预警")
    }

    // ------------------------------------------------------------------
    // 确认与重置
    // ------------------------------------------------------------------

    @Test
    fun 确认后清除当前预警() = runTest {
        val trigger = DefaultAlertTrigger(now = { 1_000_000_000_000L })
        assertNotNull(trigger.evaluate(event(intensity = 5.0)).alert)
        assertNotNull(trigger.activeAlert.value)

        assertTrue(trigger.acknowledge())
        assertNull(trigger.activeAlert.value)
        assertFalse(trigger.acknowledge(), "无预警时 acknowledge 应返回 false")
    }
}
