package com.quake.alert.data.source

import com.quake.alert.model.QuakeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 中国地震台网速报目录（cenc_eqlist）解码器单元测试。
 * fixture 来自 2026-08-29 实测 GET https://api.wolfx.jp/cenc_eqlist.json。
 */
class CencEqListDecoderTest {

    private val decoder = CencEqListDecoder(logger = {})

    /** 实测报文片段（No1 温宿 M3.1 / No2 内江隆昌 M5.1） */
    private val realFeed = """
        {"No1":{"type":"reviewed","EventID":"CD.20260829063652.125","time":"2026-08-29 06:36:52",
         "ReportTime":"2026-08-29 06:44:43","location":"新疆阿克苏地区温宿县","placeName":"新疆阿克苏地区温宿县",
         "magnitude":"3.1","depth":"10","latitude":"42.22","longitude":"80.54","intensity":"4"},
         "No2":{"type":"reviewed","EventID":"CD.20260828132430.7","time":"2026-08-28 13:13:34",
         "ReportTime":"2026-08-28 13:24:42","location":"四川内江市隆昌市","placeName":"四川内江市隆昌市",
         "magnitude":"5.1","depth":"6","latitude":"29.23","longitude":"105.22","intensity":"7"}}
    """.trimIndent()

    @Test
    fun 真实目录报文解析() {
        val events = decoder.decode(realFeed)
        assertEquals(2, events.size)

        val first = events.first()
        assertEquals("cenc_eqlist-CD.20260829063652.125", first.id)
        assertEquals(QuakeSource.CENC_EQLIST, first.source)
        assertEquals(3.1, first.magnitude)      // 字符串 "3.1"
        assertEquals(42.22, first.latitude)     // 字符串 "42.22"
        assertEquals(80.54, first.longitude)
        assertEquals("新疆阿克苏地区温宿县", first.placeName)
        assertEquals(10.0, first.depthKm)       // 字符串 "10"
        assertEquals(4.0, first.intensity)      // 测定烈度 "4"
        assertFalse(first.isEew, "目录事件 isEew=false，不触发弹窗")
    }

    @Test
    fun 空与畸形输入返回空列表() {
        assertTrue(decoder.decode("").isEmpty())
        assertTrue(decoder.decode("   ").isEmpty())
        assertTrue(decoder.decode("not json").isEmpty())
    }

    @Test
    fun 缺震级或缺坐标的条目被跳过() {
        val feed = """{"No1":{"EventID":"x1","magnitude":"3.0","latitude":"30.0"},
                        "No2":{"EventID":"x2","longitude":"100.0"}}"""
        val events = decoder.decode(feed)
        // No1 缺经度、No2 缺震级 → 都应被跳过
        assertTrue(events.isEmpty())
    }
}
