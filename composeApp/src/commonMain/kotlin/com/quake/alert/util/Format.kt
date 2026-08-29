package com.quake.alert.util

import kotlin.math.roundToInt

/**
 * 通用格式化工具。
 *
 * common 代码里没有 JVM 的 `String.format`，这里统一提供等价能力，
 * 避免在各平台 sourceSet 里重复实现。
 */

/** 震级：保留 1 位小数，如 `4.5` / `5.0`。 */
fun formatMagnitude(magnitude: Double): String =
    ((magnitude * 10).roundToInt() / 10.0).toString()

/** 经纬度：保留 2 位小数并带度符号，如 `31.23°`。 */
fun formatDegree(value: Double): String =
    ((value * 100).roundToInt() / 100.0).toString() + "°"

/** 深度：取整并带单位，null 时返回占位符。 */
fun formatDepth(depthKm: Double?): String =
    depthKm?.let { "${it.roundToInt()} km" } ?: "未知"

private val ROMAN_DEGREE = arrayOf(
    "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
)

/**
 * 中国烈度表的罗马数字表示（1~12 度 → I~XII），如 `V 度`。
 * 烈度低于 1 或缺失时返回 "未知"。
 */
fun formatIntensity(intensity: Double?): String {
    if (intensity == null) return "未知"
    val index = (intensity.roundToInt() - 1).coerceIn(0, ROMAN_DEGREE.lastIndex)
    return "${ROMAN_DEGREE[index]} 度"
}
