package com.quake.alert.data.source

import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import kotlinx.datetime.Clock
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

            val eventId = obj.stringOf("EventID", "eventId")
            val place = obj.stringOf("placeName", "place", "location", "Location") ?: "未知区域"
            val originTime = parseApiTime(obj.stringOf("time", "Time", "OriginTime")) ?: now

            // 没有 EventID 时用"时刻+坐标+震级"兜底：否则所有缺 ID 的条目都会撞成同一个 id，
            // 被去重器合并成一条。
            val id = if (eventId != null) {
                "${QuakeSource.CENC_EQLIST.apiId}-$eventId"
            } else {
                "${QuakeSource.CENC_EQLIST.apiId}-${originTime}_${latitude}_${longitude}_$magnitude"
            }

            QuakeEvent(
                id = id,
                source = QuakeSource.CENC_EQLIST,
                magnitude = magnitude,
                latitude = latitude,
                longitude = longitude,
                placeName = place,
                originTime = originTime,
                receivedAt = now,
                depthKm = obj.stringOf("depth", "Depth")?.toDoubleOrNull(),
                isEew = false,
                intensity = obj.stringOf("intensity", "Intensity")?.toDoubleOrNull(),
                raw = null,
            )
        }
    }

    private fun JsonObject.stringOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)
                ?.content?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
}
