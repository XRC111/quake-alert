package com.quake.alert.data.source

import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 中国地震台网速报目录（`cenc_eqlist.json`）解码器。
 *
 * 实测报文（2026-08-29）：顶层为 `No1..NoN` 编号对象，每条含
 * `type` / `EventID` / `time`（发震时刻）/ `ReportTime` / `location` / `placeName` /
 * `magnitude`（字符串）/ `depth` / `latitude` / `longitude` / `intensity`（测定烈度，字符串）。
 *
 * 这是**测定/速报目录**（`isEew = false`），用于事件列表与历史展示，**不参与弹窗预警**。
 */
class CencEqListDecoder(
    private val logger: (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val chinaZone = TimeZone.of("Asia/Shanghai")

    /** @return 解析出的目录事件列表；整体失败返回空列表（不中断轮询）。 */
    fun decode(raw: String): List<QuakeEvent> {
        if (raw.isBlank()) return emptyList()

        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { e ->
            logger("[cenc_eqlist] 目录解析失败: ${e.message}")
            return emptyList()
        }

        val now = Clock.System.now().toEpochMilliseconds()
        return root.values.mapNotNull { entry ->
            val obj = (entry as? JsonObject) ?: return@mapNotNull null
            val magnitude = obj.stringOf("magnitude", "Magnitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val latitude = obj.stringOf("latitude", "Latitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = obj.stringOf("longitude", "Longitude")?.toDoubleOrNull() ?: return@mapNotNull null

            val eventId = obj.stringOf("EventID", "eventId") ?: "unknown"
            val place = obj.stringOf("placeName", "place", "location", "Location") ?: "未知区域"

            QuakeEvent(
                id = "${QuakeSource.CENC_EQLIST.apiId}-$eventId",
                source = QuakeSource.CENC_EQLIST,
                magnitude = magnitude,
                latitude = latitude,
                longitude = longitude,
                placeName = place,
                originTime = parseTime(obj.stringOf("time", "Time", "OriginTime"), now),
                receivedAt = now,
                depthKm = obj.stringOf("depth", "Depth")?.toDoubleOrNull(),
                isEew = false,
                intensity = obj.stringOf("intensity", "Intensity")?.toDoubleOrNull(),
                raw = null,
            )
        }
    }

    private fun parseTime(raw: String?, fallback: Long): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return fallback

        runCatching {
            val normalized = text.replace(' ', 'T')
            val withZone = when {
                normalized.contains('+') || normalized.endsWith("Z", ignoreCase = true) -> normalized
                else -> normalized + "Z"
            }
            Instant.parse(withZone).toEpochMilliseconds()
        }.getOrNull()?.let { return it }

        runCatching {
            LocalDateTime.parse(text.replace(' ', 'T')).toInstant(chinaZone).toEpochMilliseconds()
        }.getOrNull()?.let { return it }

        text.toLongOrNull()?.let {
            return if (it > 1_000_000_000_000L) it else it * 1000L
        }
        return fallback
    }

    private fun JsonObject.stringOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)
                ?.content?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
}
