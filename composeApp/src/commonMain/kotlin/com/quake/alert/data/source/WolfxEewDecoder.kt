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
import kotlinx.serialization.json.parseToJsonElement

/**
 * Wolfx EEW 报文解码器（cenc_eew / sc_eew / cq_eew / cwa_eew 共用）。
 *
 * 设计要点：Wolfx 的 WebSocket 推送字段名为大写驼峰（`Magnitude` / `HypoCenter` / `No` ...），
 * 且不同源之间存在大小写与命名漂移（实测：sc/fj/cwa 源把震级拼写为 `Magunitude`，
 * cwa 源的 `MaxIntensity` 是字符串 `"3"`），并且会周期性下发 `{"type":"heartbeat"}` 保活帧。
 *
 * 因此这里**不用 @Serializable 直接绑定 data class**，而是先用 JsonElement 做宽容读取：
 * 同义字段按优先级依次尝试、缺失字段直接丢弃该帧并记录日志，避免因为上游改一个字段名就整体崩掉。
 *
 * ## 业务字段（以 API 为准）
 * - 预测烈度：`MaxIntensity`（中国烈度表 0~12 度），用于报警阈值判定；
 * - 到达时间：`ArrivalTime`（时刻）与 `RemainTime`（剩余秒数）二选一，
 *   倒计时**只信这两个字段**，客户端不做任何走时估算。
 */
class WolfxEewDecoder(
    private val source: QuakeSource,
    private val logger: (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    /** Wolfx 下发的时间为北京时间且通常不带时区后缀，按 Asia/Shanghai 解释。 */
    private val chinaZone = TimeZone.of("Asia/Shanghai")

    /**
     * @return 解析成功的事件；心跳帧、字段不全的畸形帧返回 null（调用方静默丢弃）。
     */
    fun decode(raw: String): QuakeEvent? {
        if (raw.isBlank()) return null

        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { e ->
            logger("[${source.apiId}] 报文不是合法 JSON，已丢弃: ${e.message} | ${raw.take(120)}")
            return null
        }

        // 保活帧 / 控制帧 / 取消帧
        val type = obj.firstString("Type", "type", "MsgType", "msgType")
        if (type != null && (type.equals("heartbeat", true) || type.equals("ping", true))) {
            return null
        }
        if (obj.firstBoolean("isCancel", "cancel", "IsCancel") == true) {
            logger("[${source.apiId}] 预警已取消: ${raw.take(120)}")
            return null
        }

        // 实测：cenc/cq 源拼写为 Magnitude；sc/cwa/fj 源实测为 Magunitude（上游拼写错误），双兼容
        val magnitude = obj.firstDouble("Magnitude", "magnitude", "Magunitude", "magunitude", "Mag", "mag", "M", "m")
        val latitude = obj.firstDouble("Latitude", "latitude", "Lat", "lat")
        val longitude = obj.firstDouble("Longitude", "longitude", "Lon", "lon", "Lng", "lng")

        if (magnitude == null || latitude == null || longitude == null) {
            logger("[${source.apiId}] 缺少震级/经纬度，已丢弃: ${raw.take(160)}")
            return null
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val rawId = obj.firstString("ID", "Id", "id", "EventID", "eventId", "EventId")
        val place = obj.firstString(
            // 实测：cenc / sc / cq / cwa 源的震中地点字段均为 HypoCenter
            "HypoCenter", "hypoCenter",
            "RegionName", "regionName", "Place", "place",
            "Location", "location", "Area", "area",
        ) ?: "未知区域"
        val originTime = parseWolfxTime(
            obj.firstString("StartTime", "startTime", "Time", "time", "OriginTime", "originTime")
        )

        // ---- 预警业务字段（以 API 为准） ----
        val intensity = obj.firstDouble(
            "MaxIntensity", "maxIntensity",
            "Intensity", "intensity",
            "PredIntensity", "predIntensity",
            "AreaIntensity", "areaIntensity",
        )
        val arrivalTime = parseArrivalTime(
            obj.firstString("ArrivalTime", "arrivalTime", "Arrival_Time", "arrival_time", "ETA")
        )
        val remainTimeSec = obj.firstLong(
            "RemainTime", "remainTime", "Remain_Time", "remain_time",
            "S_P_Time", "S_P_sec", "CountDown", "countDown",
        )?.takeIf { it >= 0L }?.toInt()

        return QuakeEvent(
            id = "${source.apiId}-${rawId ?: "${originTime}_${latitude}_${longitude}_$magnitude"}",
            source = source,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            placeName = place,
            originTime = originTime,
            receivedAt = now,
            depthKm = obj.firstDouble("Depth", "depth"),
            updateSerial = obj.firstInt(
                "No", "no", "Serial", "serial",
                "ReportNum", "reportNum", "UpdateNum", "updateNum",
            ),
            isEew = true,
            intensity = intensity,
            arrivalTime = arrivalTime,
            remainTimeSec = remainTimeSec,
            raw = raw.take(RAW_LIMIT),
        )
    }

    /**
     * 兼容三种时间格式：
     * 1. ISO-8601 带时区：`2025-08-29T12:34:56+08:00`
     * 2. 无时区的北京时间：`2025-08-29 12:34:56`
     * 3. 时间戳（秒或毫秒）
     */
    private fun parseWolfxTime(raw: String?): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return now

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

        return now
    }

    /**
     * 解析 API 下发的"预计到达时刻"。
     * 与 [parseWolfxTime] 相同的时间格式；解析失败返回 null（UI 显示"未知"）。
     */
    private fun parseArrivalTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

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

        return null
    }

    private companion object {
        const val RAW_LIMIT = 2000
    }
}

// ---------------------------------------------------------------------------
// 宽容读取工具：数字/字符串都通过 JsonPrimitive.content 取原始文本再转换，
// 这样即使上游把数值写成字符串（"4.5"）也能兼容。
// ---------------------------------------------------------------------------
private fun JsonObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)
            ?.content?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

private fun JsonObject.firstDouble(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
    }

private fun JsonObject.firstLong(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
    }

private fun JsonObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt()
    }

private fun JsonObject.firstBoolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.trim()?.toBooleanStrictOrNull()
    }
