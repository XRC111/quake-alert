package com.quake.alert.data

import com.quake.alert.alert.haversineKm
import com.quake.alert.model.QuakeEvent

/**
 * 跨源同震去重器。
 *
 * 背景：多个预警源会报告**同一次地震**（实测：2026-08-28 四川内江隆昌 M5.1，
 * sc_eew 与 cq_eew 各自报了一版，震级评估 M5.2 vs M4.6），且同一源对同一事件会有 EEW 多报。
 * 因此需要一个统一的"事件身份"判定，保证列表里一次地震只有一条记录（就地覆盖更新）。
 *
 * ## 判定规则
 * - **同源多报**：同一源同一原始 ID（EEW 报数递增）→ 同一事件；
 * - **跨源同震**：发震时刻差 <= [originTimeToleranceMs] 且 震中距离 <= [locationToleranceKm] → 同一事件。
 *
 * ## 合并策略
 * - canonical ID 采用**首个来源事件的原始 ID**（列表 key 稳定）；
 * - 保留"更优"版本：报数更高 > 震级更大 > 后到者；
 * - [QuakeEvent.mergedSources] 累积合并进来的其他源，UI 可见"多源冗余"。
 */
class EventDeduplicator(
    private val originTimeToleranceMs: Long = 2 * 60 * 1000L,
    private val locationToleranceKm: Double = 35.0,
) {
    data class Result(
        val event: QuakeEvent,
        val isNew: Boolean,
    )

    /** canonicalId -> 当前保留的事件 */
    private val entries = LinkedHashMap<String, QuakeEvent>()

    /** 原始事件 ID -> canonicalId（同源 EEW 多报快速命中） */
    private val idIndex = HashMap<String, String>()

    /** @return 去重/合并后的事件。isNew=true 表示首次出现（新增进列表），false 表示就地覆盖。 */
    fun resolve(incoming: QuakeEvent): Result {
        // 1) 同源 EEW 多报：原始 ID 直接命中
        idIndex[incoming.id]?.let { canonicalId ->
            entries[canonicalId]?.let { existing ->
                return Result(merge(existing, incoming), isNew = false)
            }
        }

        // 2) 跨源同震：线性扫描已有事件（事件量小，每日个位数）
        entries.values.firstOrNull { existing -> isSameQuake(existing, incoming) }?.let { existing ->
            val canonicalId = existing.id
            idIndex[incoming.id] = canonicalId
            entries[canonicalId] = merge(existing, incoming)
            return Result(entries.getValue(canonicalId), isNew = false)
        }

        // 3) 全新事件
        entries[incoming.id] = incoming
        idIndex[incoming.id] = incoming.id
        return Result(incoming, isNew = true)
    }

    /** 是否同一地震。 */
    private fun isSameQuake(a: QuakeEvent, b: QuakeEvent): Boolean {
        if (a.id == b.id) return true
        if (kotlin.math.abs(a.originTime - b.originTime) > originTimeToleranceMs) return false
        val distance = haversineKm(a.latitude, a.longitude, b.latitude, b.longitude)
        return distance <= locationToleranceKm
    }

    /**
     * 合并两版同一地震的报告：
     * - 报数高者信息更全 → 作为"更优"版本；报数相同比震级；再相同取后到者；
     * - 保留 canonical ID 与主来源，合并 [QuakeEvent.mergedSources]。
     */
    private fun merge(current: QuakeEvent, incoming: QuakeEvent): QuakeEvent {
        val currentSerial = current.updateSerial ?: 0
        val incomingSerial = incoming.updateSerial ?: 0
        val better = when {
            incomingSerial > currentSerial -> incoming
            incomingSerial == currentSerial && incoming.magnitude > current.magnitude -> incoming
            incoming.receivedAt > current.receivedAt -> incoming
            else -> current
        }

        // 地名：优先保留非"未知区域"的那个
        val place = when {
            current.placeName != "未知区域" -> current.placeName
            else -> incoming.placeName
        }

        return better.copy(
            id = current.id,
            source = current.source,
            placeName = place,
            mergedSources = (current.mergedSources + incoming.source).distinct(),
            raw = null,
        )
    }

    fun clear() {
        entries.clear()
        idIndex.clear()
    }
}
