package com.quake.alert.data

import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 跨源同震去重器单元测试。
 *
 * 背景参照实测：2026-08-28 四川内江隆昌地震，sc_eew 报 M5.2、cq_eew 报 M4.6，须合并为一条。
 */
class EventDeduplicatorTest {

    private fun event(
        id: String,
        source: QuakeSource,
        magnitude: Double,
        lat: Double = 29.23,
        lon: Double = 105.22,
        originTime: Long = 1_752_200_000_000L,
        serial: Int? = 1,
        intensity: Double? = null,
    ) = QuakeEvent(
        id = id,
        source = source,
        magnitude = magnitude,
        latitude = lat,
        longitude = lon,
        placeName = "四川内江隆昌",
        originTime = originTime,
        receivedAt = originTime,
        depthKm = 10.0,
        updateSerial = serial,
        isEew = true,
        intensity = intensity,
    )

    @Test
    fun 同源EEW多报合并() {
        val d = EventDeduplicator()
        val first = d.resolve(event("sc_eew-8187", QuakeSource.SC_EEW, 5.2, serial = 1))
        val second = d.resolve(event("sc_eew-8187", QuakeSource.SC_EEW, 5.2, serial = 2))
        assertTrue(first.isNew)
        assertFalse(second.isNew, "同源同 ID 多报应就地覆盖")
        assertEquals(first.event.id, second.event.id)
        assertEquals(2, second.event.updateSerial)
    }

    @Test
    fun 跨源同震合并() {
        val d = EventDeduplicator()
        val sc = d.resolve(event("sc_eew-8187", QuakeSource.SC_EEW, 5.2))
        val cq = d.resolve(event("cq_eew-9abc", QuakeSource.CQ_EEW, 4.6))
        assertTrue(sc.isNew)
        assertFalse(cq.isNew, "发震时刻 2min 内 + 震中距 35km 内应合并为同一地震")
        assertEquals(sc.event.id, cq.event.id, "canonical ID 应保持首个来源")
        assertEquals(listOf(QuakeSource.CQ_EEW), cq.event.mergedSources)
        assertEquals(QuakeSource.SC_EEW, cq.event.source, "主来源保持首个")
    }

    @Test
    fun 发震时间差超容差不合并() {
        val d = EventDeduplicator()
        d.resolve(event("a", QuakeSource.CENC_EEW, 4.0, originTime = 1_752_200_000_000L))
        val far = d.resolve(
            event("b", QuakeSource.CQ_EEW, 4.0, originTime = 1_752_200_000_000L + 5 * 60 * 1000L)
        )
        assertTrue(far.isNew, "发震时刻差 5min 超过 2min 容差，应视为不同地震")
    }

    @Test
    fun 距离超容差不合并() {
        val d = EventDeduplicator()
        d.resolve(event("a", QuakeSource.CENC_EEW, 4.0, lat = 30.0, lon = 100.0))
        // 北纬 30°→36° 约 660km，远超 35km 容差
        val far = d.resolve(event("b", QuakeSource.CQ_EEW, 4.0, lat = 36.0, lon = 100.0))
        assertTrue(far.isNew, "震中距 660km 应视为不同地震")
    }

    @Test
    fun 报数更高者胜() {
        val d = EventDeduplicator()
        val first = d.resolve(event("cq-1", QuakeSource.CQ_EEW, 4.6, serial = 1, intensity = 5.0))
        val updated = d.resolve(event("cq-1", QuakeSource.CQ_EEW, 5.0, serial = 3, intensity = 6.0))
        assertEquals(first.event.id, updated.event.id)
        assertEquals(3, updated.event.updateSerial)
        assertEquals(5.0, updated.event.magnitude)
        assertEquals(6.0, updated.event.intensity)
        assertTrue(updated.event.mergedSources.isEmpty(), "同源合并不产生 mergedSources")
    }

    @Test
    fun 多源合并后mergedSources累积() {
        val d = EventDeduplicator()
        d.resolve(event("c-1", QuakeSource.CENC_EEW, 5.1))
        val sc = d.resolve(event("sc-9", QuakeSource.SC_EEW, 5.2)).event
        val cq = d.resolve(event("cq-8", QuakeSource.CQ_EEW, 4.6)).event
        assertTrue(sc.mergedSources.contains(QuakeSource.SC_EEW))
        assertTrue(cq.mergedSources.containsAll(listOf(QuakeSource.SC_EEW, QuakeSource.CQ_EEW)))
    }
}
