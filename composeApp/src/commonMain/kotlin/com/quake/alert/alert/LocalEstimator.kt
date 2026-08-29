package com.quake.alert.alert

import com.quake.alert.model.GeoPoint
import com.quake.alert.model.QuakeEvent
import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 本地估算结果。 */
data class LocalEstimate(
    /** 本地烈度（估算，0~12 度，已 clamp） */
    val localIntensity: Double,
    /** 本地 S 波剩余到达秒数（估算）；发震早于到达窗口时可能为 0 */
    val countdownSeconds: Int?,
)

/**
 * 可选"本地估算"引擎。
 *
 * 背景：实测确认中国区 CENC / sc（成都）两个源均不含到达时间字段，
 * 社区实现（如 ClassIsland 插件的 `HuaniaEarthQuakeCalculator`）都是拿用户坐标自算。
 * 本项目遵循"以 API 为准"原则，因此本估算器**默认关闭**：
 * - 只有用户在设置里开启「本地估算」并填写观测点坐标时才生效；
 * - 未开启或未填坐标 → [estimate] 返回 null，倒计时/烈度回落到 API 字段。
 *
 * ⚠️ 所有数值均为工程近似（走时差 + Kawasumi 衰减式），替代不了官方烈度速报。
 */
class LocalEstimator(
    private val observerProvider: () -> GeoPoint?,
    private val enabledProvider: () -> Boolean,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun estimate(event: QuakeEvent): LocalEstimate? {
        if (!enabledProvider()) return null
        val observer = observerProvider() ?: return null

        val distanceKm = haversineKm(
            observer.latitude, observer.longitude,
            event.latitude, event.longitude,
        )
        return LocalEstimate(
            localIntensity = estimateLocalIntensity(event.magnitude, distanceKm),
            countdownSeconds = estimateCountdownSeconds(event, distanceKm, now()),
        )
    }
}

/** 大圆距离（km）。 */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val lat1Rad = lat1 * PI / 180.0
    val lat2Rad = lat2 * PI / 180.0

    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusKm * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * S 波剩余到达秒数（走时差近似）：
 * `lead = distanceKm × (1/vS − 1/vP)`，再扣除从发震到现在的流逝时间。
 * vP ≈ 6.0 km/s、vS ≈ 3.5 km/s（地壳平均）。
 */
fun estimateCountdownSeconds(
    event: QuakeEvent,
    distanceKm: Double,
    now: Long = Clock.System.now().toEpochMilliseconds(),
): Int? {
    val leadSeconds = distanceKm * (1.0 / S_WAVE_KM_S - 1.0 / P_WAVE_KM_S)
    val elapsedSeconds = ((now - event.originTime).coerceAtLeast(0L)) / 1000.0
    return (leadSeconds - elapsedSeconds).coerceAtLeast(0.0).roundToInt()
}

/**
 * 本地烈度估算（Kawasumi 简化衰减式，工程近似）：
 * `I(R) = 1.5·M − 3.4·log₁₀(R) + 4.6`，clamp 到 0~12 度。
 *
 * ⚠️ 未考虑场地放大效应、震源深度与方向性，仅供预警参考；
 * 生产环境应替换为官方烈度速报（如 GB 18306-2015 衰减模型 + 场地修正）。
 */
fun estimateLocalIntensity(magnitude: Double, distanceKm: Double): Double {
    val r = distanceKm.coerceAtLeast(1.0)
    val intensity = 1.5 * magnitude - 3.4 * log10(r) + 4.6
    return intensity.coerceIn(0.0, 12.0)
}

private const val P_WAVE_KM_S = 6.0
private const val S_WAVE_KM_S = 3.5
