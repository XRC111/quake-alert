package com.quake.alert.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 数据来源。
 *
 * 实测（2026-08-29）：以下 Wolfx 端点两天内活跃，均已接入聚合器：
 * - CENC_EEW：`cenc_eew` 中国地震预警网（国家地震台网）预警推送
 * - SC_EEW：`sc_eew` 四川省地震局预警推送（非成都高新减灾研究所；后者已于 2026-07-22 被终止播发授权）
 * - CQ_EEW：`cq_eew` 重庆市地震局预警推送
 * - CWA_EEW：`cwa_eew` 中国台湾中央气象署预警推送
 * - CENC_EQLIST：`cenc_eqlist` 中国地震台网速报目录（HTTP 轮询，非预警）
 *
 * 福建省地震局（`fj_eew`）已停更 3 个月，未接入。
 */
@Serializable
enum class QuakeSource(
    /** Wolfx 端点路径名，同时作为事件 ID 前缀 */
    val apiId: String,
    val displayName: String,
    val homepage: String,
    /** 是否为实时预警推送（true）还是测定/速报目录（false，不触发弹窗） */
    val isEew: Boolean,
) {
    @SerialName("cenc_eew")
    CENC_EEW("cenc_eew", "中国地震预警网 · CENC", "https://wolfx.jp", isEew = true),

    @SerialName("sc_eew")
    SC_EEW("sc_eew", "四川省地震局", "https://wolfx.jp", isEew = true),

    @SerialName("cq_eew")
    CQ_EEW("cq_eew", "重庆市地震局", "https://wolfx.jp", isEew = true),

    @SerialName("cwa_eew")
    CWA_EEW("cwa_eew", "中国台湾 · 中央气象署", "https://www.cwa.gov.tw", isEew = true),

    @SerialName("cenc_eqlist")
    CENC_EQLIST("cenc_eqlist", "中国地震台网 · 速报", "https://wolfx.jp", isEew = false),
}

/** 预警等级：由规则引擎 [com.quake.alert.alert.AlertTrigger] 计算，供 UI 着色。 */
@Serializable
enum class AlertLevel(val displayName: String) {
    NONE("无"),
    INFO("关注"),
    WARNING("警告"),
    SEVERE("严重"),
}

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 统一地震事件模型。
 *
 * 所有源（4 个 EEW WebSocket 推送 + 1 个速报目录）最终都映射到本结构。
 *
 * @param id            全局唯一 ID。经 [com.quake.alert.data.EventDeduplicator] 处理后，
 *                      跨源同一地震会统一为**首个来源的事件 ID**（就地覆盖，列表不重复）。
 * @param source        主来源（首次收到的源）；[mergedSources] 记录后续合并进来的其他源
 * @param originTime    发震时刻（epoch millis），解析失败时回退为收到时刻
 * @param receivedAt    本端收到该报文的时刻（epoch millis）
 * @param updateSerial  EEW 报数（第几报），非 EEW 来源为 null
 * @param isEew         是否为实时预警推送（决定是否参与规则引擎弹窗判定）
 * @param intensity     **API 下发的预测烈度**（中国烈度表 0~12 度）。报警阈值据此判定；
 *                     上游未下发时为空，规则引擎降级按 [QuakeEvent.magnitude] 判定。
 * @param arrivalTime   **API 下发的预计到达时刻**（epoch millis）；倒计时以它为准，绝不自行估算。
 * @param remainTimeSec **API 下发的剩余到达秒数**；与 [QuakeEvent.arrivalTime] 二选一，优先取本字段。
 * @param mergedSources 同震合并进来的其他来源（多源冗余的可见性记录）
 */
@Serializable
data class QuakeEvent(
    val id: String,
    val source: QuakeSource,
    val magnitude: Double,
    val latitude: Double,
    val longitude: Double,
    val placeName: String,
    val originTime: Long,
    val receivedAt: Long,
    val depthKm: Double? = null,
    val updateSerial: Int? = null,
    val isEew: Boolean = false,
    val alertLevel: AlertLevel = AlertLevel.NONE,
    val intensity: Double? = null,
    val arrivalTime: Long? = null,
    val remainTimeSec: Int? = null,
    val mergedSources: List<QuakeSource> = emptyList(),
    /** 原始报文截断片段，便于线上排查字段漂移；默认不入 UI。 */
    val raw: String? = null,
)

/** 列表排序键：发震时刻与收到时刻取较大值，避免个别异常历史时间把事件顶到最前。 */
fun QuakeEvent.sortKey(): Long = if (originTime > receivedAt) originTime else receivedAt
