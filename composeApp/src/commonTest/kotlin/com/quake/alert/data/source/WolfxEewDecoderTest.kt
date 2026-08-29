package com.quake.alert.data.source

import com.quake.alert.model.QuakeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WolfxEewDecoder 单元测试。
 *
 * 所有 fixture 均来自 2026-08-29 对本机的实测：
 * - REAL_CENC_FRAME：GET https://api.wolfx.jp/cenc_eew.json 返回的真实 CENC EEW 最新事件
 * - HEARTBEAT_FRAME：WebSocket wss://ws-api.wolfx.jp/cenc_eew 实测收到的心跳帧
 */
class WolfxEewDecoderTest {

    private val decoder = WolfxEewDecoder(source = QuakeSource.CENC_EEW, logger = {})

    /** 真实 CENC EEW 报文（2026-08-29 新疆阿克苏地区温宿县 M4.0，第 3 报） */
    private val realCencFrame = """
        {"ID":"b4dmzcqoricyy","EventID":"202608290607.0001",
         "ReportTime":"2026-08-29 06:07:28","ReportNum":3,
         "OriginTime":"2026-08-29 06:07:28","HypoCenter":"新疆阿克苏地区温宿县",
         "Latitude":42.2,"Longitude":80.502,"Magnitude":4.0,
         "Depth":null,"MaxIntensity":5.1}
    """.trimIndent()

    /** WebSocket 实测心跳帧（小写 type） */
    private val heartbeatFrame = """{"type":"heartbeat","ver":22,"id":"2778613","timestamp":1788008050926}"""

    /** 真实 sc（四川省地震局）源报文（2026-08-29 四川内江隆昌 M5.2）：
     *  注意上游字段拼写为 Magunitude（多一个 u），ID 为数字 */
    private val realScFrame = """
        {"ID":8187,"EventID":"202608281313.0001_2","ReportTime":"2026-08-28 13:13:56","ReportNum":2,
         "OriginTime":"2026-08-28 13:13:34","HypoCenter":"四川内江市隆昌市","Latitude":29.239,"Longitude":105.227,
         "Magunitude":5.2,"Depth":0.0,"MaxIntensity":7.1}
    """.trimIndent()

    @Test
    fun 真实成都源报文解析() {
        val event = decoder.decode(realScFrame)
        assertNotNull(event, "sc 源真实报文应解析成功")

        assertEquals("cenc_eew-8187", event.id)                 // ID 为数字
        assertEquals(5.2, event.magnitude)                    // Magunitude（上游拼写错误）
        assertEquals("四川内江市隆昌市", event.placeName)
        assertEquals(2, event.updateSerial)                   // ReportNum
        assertEquals(7.1, event.intensity)                    // MaxIntensity
        assertEquals(0.0, event.depthKm)
        assertNull(event.arrivalTime, "sc 源同样不携带到达时间字段")
        assertNull(event.remainTimeSec)
    }

    @Test
    fun 真实CENC报文完整解析() {
        val event = decoder.decode(realCencFrame)
        assertNotNull(event, "真实报文应解析成功")

        assertEquals(QuakeSource.CENC_EEW, event.source)
        assertEquals("cenc_eew-b4dmzcqoricyy", event.id)
        assertEquals(4.0, event.magnitude)
        assertEquals(42.2, event.latitude)
        assertEquals(80.502, event.longitude)
        assertEquals("新疆阿克苏地区温宿县", event.placeName) // HypoCenter
        assertEquals(3, event.updateSerial)                   // ReportNum
        assertEquals(5.1, event.intensity)                    // MaxIntensity
        assertNull(event.depthKm)                              // Depth: null
        assertTrue(event.isEew)
        // 该报文未携带到达时间字段 → 如实返回 null，由 UI 显示"未知"
        assertNull(event.arrivalTime)
        assertNull(event.remainTimeSec)
    }

    @Test
    fun 台湾cwa源报文解析与取消帧过滤() {
        // cwa 真实报文：MaxIntensity 为字符串 "3"，Magunitude 拼写错误，带 isCancel
        val frame = """{"ID":1150064,"ReportTime":"2026-08-28 10:21:12","ReportNum":1,"OriginTime":"2026-08-28 10:21:04","HypoCenter":"恆春半島近海","Latitude":21.99,"Longitude":120.65,"Magunitude":4.5,"Depth":10.0,"MaxIntensity":"3","isCancel":false}"""
        val decoder = WolfxEewDecoder(source = QuakeSource.CWA_EEW, logger = {})
        val event = decoder.decode(frame)
        assertNotNull(event)
        assertEquals("cwa_eew-1150064", event.id)
        assertEquals(4.5, event.magnitude)
        assertEquals(3.0, event.intensity) // 字符串 "3" → 3.0
        assertEquals("恆春半島近海", event.placeName)
        assertEquals(QuakeSource.CWA_EEW, event.source)

        // 取消帧 → null
        val cancel = """{"ID":1150065,"Magunitude":4.5,"Latitude":21.99,"Longitude":120.65,"isCancel":true}"""
        assertNull(decoder.decode(cancel), "isCancel=true 的帧应被过滤")
    }

    @Test
    fun 心跳帧被过滤() {
        assertNull(decoder.decode(heartbeatFrame), "heartbeat 帧应返回 null 且不抛异常")
    }

    @Test
    fun 空报文与畸形JSON不崩溃() {
        assertNull(decoder.decode(""))
        assertNull(decoder.decode("   "))
        assertNull(decoder.decode("not json at all"))
        assertNull(decoder.decode("""{"Type":"heartbeat"}"""))
    }

    @Test
    fun 缺少震级或坐标的报文被丢弃() {
        assertNull(decoder.decode("""{"ID":"x1","Magnitude":4.0,"Latitude":30.0}""")) // 缺经度
        assertNull(decoder.decode("""{"ID":"x2","Latitude":30.0,"Longitude":100.0}""")) // 缺震级
    }

    @Test
    fun 小写别名与字符串化数值兼容() {
        val frame = """{"id":"a1","mag":"4.5","lat":31.2,"lon":121.4,"place":"上海","depth":"10","maxIntensity":4.5}"""
        val event = decoder.decode(frame)
        assertNotNull(event)
        assertEquals(4.5, event.magnitude)
        assertEquals(31.2, event.latitude)
        assertEquals(121.4, event.longitude)
        assertEquals("上海", event.placeName)
        assertEquals(10.0, event.depthKm)
        assertEquals(4.5, event.intensity)
    }

    @Test
    fun 到达时间字段解析() {
        // RemainTime：剩余秒数
        val withRemain = decoder.decode("""{"id":"b1","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"RemainTime":45}""")
        assertNotNull(withRemain)
        assertEquals(45, withRemain.remainTimeSec)

        // ArrivalTime：无时区北京时间 → 按 Asia/Shanghai 解释
        val withArrival = decoder.decode("""{"id":"b2","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"ArrivalTime":"2026-08-29 06:08:00"}""")
        assertNotNull(withArrival)
        assertNotNull(withArrival.arrivalTime, "ArrivalTime 应被解析为 epoch millis")
        assertTrue(withArrival.arrivalTime!! > 0L)

        // S_P_Time：另一常见别名
        val withSpTime = decoder.decode("""{"id":"b3","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"S_P_Time":15}""")
        assertNotNull(withSpTime)
        assertEquals(15, withSpTime.remainTimeSec)
    }

    @Test
    fun 时间格式兼容() {
        // ISO 带时区
        val iso = decoder.decode("""{"id":"c1","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"OriginTime":"2026-08-29T06:07:28+08:00"}""")
        assertNotNull(iso)
        // 无时区北京时间
        val local = decoder.decode("""{"id":"c2","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"OriginTime":"2026-08-29 06:07:28"}""")
        assertNotNull(local)
        // 解析失败回退为当前时间（不崩）
        val garbage = decoder.decode("""{"id":"c3","Magnitude":4.0,"Latitude":30.0,"Longitude":100.0,"OriginTime":"昨天"}""")
        assertNotNull(garbage)
        assertTrue(garbage.originTime > 0L)
    }
}
