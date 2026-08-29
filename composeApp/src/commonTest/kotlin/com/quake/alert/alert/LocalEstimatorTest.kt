package com.quake.alert.alert

import com.quake.alert.model.GeoPoint
import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LocalEstimator / 估算函数单元测试。
 */
class LocalEstimatorTest {

    private fun event(
        magnitude: Double = 5.0,
        latitude: Double = 31.0,
        longitude: Double = 121.0,
        originTime: Long = 1_000_000_000_000L,
    ) = QuakeEvent(
        id = "t",
        source = QuakeSource.CENC_EEW,
        magnitude = magnitude,
        latitude = latitude,
        longitude = longitude,
        placeName = "测试",
        originTime = originTime,
        receivedAt = originTime,
        isEew = true,
    )

    private val observer = GeoPoint(latitude = 31.0, longitude = 122.0) // 震中正东约 92km（北纬 31°）

    @Test
    fun haversine距离计算正确() {
        // 赤道上经度 1° ≈ 111.19 km
        val d = haversineKm(0.0, 0.0, 0.0, 1.0)
        assertTrue(abs(d - 111.19) < 1.0, "赤道 1° 经度应约 111.19km，实际 $d")
        // 相同点距离为 0
        assertEquals(0.0, haversineKm(30.0, 120.0, 30.0, 120.0))
    }

    @Test
    fun 倒计时随距离增大而增大() {
        val near = estimateCountdownSeconds(event(), 10.0, 1_000_000_000_000L)!!
        val far = estimateCountdownSeconds(event(), 300.0, 1_000_000_000_000L)!!
        assertTrue(far > near, "远震中距应获得更长预警时间: near=$near far=$far")
    }

    @Test
    fun 发震已过时倒计时为0() {
        val now = 1_000_000_000_000L
        val elapsedLong = estimateCountdownSeconds(event(originTime = now - 3_600_000L), 50.0, now)
        assertEquals(0, elapsedLong, "地震已发生很久，倒计时应为 0 而非负数")
    }

    @Test
    fun 烈度随距离增大而衰减() {
        val near = estimateLocalIntensity(6.0, 10.0)
        val far = estimateLocalIntensity(6.0, 300.0)
        assertTrue(near > far, "远距离烈度应更低: near=$near far=$far")
        assertTrue(near in 0.0..12.0 && far in 0.0..12.0)
    }

    @Test
    fun 烈度被限制在0到12度() {
        assertEquals(0.0, estimateLocalIntensity(2.0, 2000.0)) // 太远太低 → 0
        assertEquals(12.0, estimateLocalIntensity(9.0, 1.0))   // 超大 → 封顶 12
    }

    // ------------------------------------------------------------------
    // LocalEstimator 开关 / 坐标
    // ------------------------------------------------------------------

    @Test
    fun 关闭估算时返回null() {
        val estimator = LocalEstimator(
            observerProvider = { observer },
            enabledProvider = { false },
        )
        assertNull(estimator.estimate(event()))
    }

    @Test
    fun 开启但无坐标时返回null() {
        val estimator = LocalEstimator(
            observerProvider = { null },
            enabledProvider = { true },
        )
        assertNull(estimator.estimate(event()))
    }

    @Test
    fun 开启且有坐标时产出估算() {
        val estimator = LocalEstimator(
            observerProvider = { observer },
            enabledProvider = { true },
            now = { 1_000_000_000_000L },
        )
        val estimate = estimator.estimate(event(magnitude = 6.5))
        assertNotNull(estimate)
        assertTrue(estimate.localIntensity in 0.0..12.0)
        assertNotNull(estimate.countdownSeconds)
        assertTrue(estimate.countdownSeconds!! >= 0)
    }
}
