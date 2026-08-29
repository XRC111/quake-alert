package com.quake.alert.data

import com.quake.alert.alert.AlertTrigger
import com.quake.alert.data.source.CencEqListDecoder
import com.quake.alert.data.source.WolfxEewDecoder
import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import com.quake.alert.model.sortKey
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

/** 单个 EEW WebSocket 源配置。 */
data class EewSourceConfig(
    val source: QuakeSource,
    /** WS 路径，与 HTTP 端点同名，如 `/cenc_eew` */
    val path: String,
    val host: String = "ws-api.wolfx.jp",
    val port: Int = 443,
    val secure: Boolean = true,
)

/**
 * 聚合器运行参数。
 *
 * 全部使用毫秒（Long）而非 kotlin.time.Duration，方便在 gradle.properties /
 * 远程配置中以纯数字下发。
 */
data class AggregatorConfig(
    /** EEW WebSocket 源（实测 2026-08-29：cenc/sc/cq/cwa 均两天内活跃） */
    val eewSources: List<EewSourceConfig> = listOf(
        EewSourceConfig(QuakeSource.CENC_EEW, "/cenc_eew"),
        EewSourceConfig(QuakeSource.SC_EEW, "/sc_eew"),
        EewSourceConfig(QuakeSource.CQ_EEW, "/cq_eew"),
        EewSourceConfig(QuakeSource.CWA_EEW, "/cwa_eew"),
    ),
    /** 中国地震台网速报目录（HTTP 轮询） */
    val eqlistUrl: String = "https://api.wolfx.jp/cenc_eqlist.json",
    val eqlistPollIntervalMs: Long = 30_000L,

    /** 超过该时长没收到任何帧（含心跳）即判定为假连接，主动断开重连 */
    val wolfxIdleTimeoutMs: Long = 90_000L,
    val wolfxIdleCheckIntervalMs: Long = 15_000L,
    val wsPingIntervalMs: Long = 15_000L,

    // ---- 通用 ----
    val httpTimeoutMs: Long = 15_000L,
    val reconnectBaseDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 60_000L,
    val reconnectMaxAttempts: Int = Int.MAX_VALUE,
    /** 实时列表保留条数 */
    val historyLimit: Int = 50,
    /** 事件历史（查看历史用）保留条数 */
    val fullHistoryLimit: Int = 500,
    val userAgent: String = "QuakeAlert/1.0 (+https://github.com/your-org/quake-alert)",
    val logger: (String) -> Unit = {},
)

/**
 * 多源地震事件聚合器。
 *
 * ## 数据源
 * - 4 个 EEW WebSocket 推送（CENC / 四川 / 重庆 / 台湾 CWA），各自独立协程 + 指数退避重连 + 心跳看门狗；
 * - 1 个速报目录 HTTP 轮询（cenc_eqlist，30s）。
 *
 * ## 去重
 * - [EventDeduplicator] 做跨源同震合并（发震时刻 ±2min + 震中距 ≤35km），
 *   同一次地震在列表里只有一条记录，EEW 多报与多源报告都就地覆盖更新；
 * - 合并后的事件 `mergedSources` 记录来源集合，UI 可展示"多源冗余"。
 *
 * ## 输出
 * - [events]：SharedFlow 增量事件（用于音效/通知等一次性消费）；
 * - [history]：最近 [AggregatorConfig.historyLimit] 条快照（实时列表）；
 * - [fullHistory]：最多 [AggregatorConfig.fullHistoryLimit] 条（历史查看）。
 */
class QuakeAggregator(
    private val client: HttpClient,
    private val alertTrigger: AlertTrigger,
    private val scope: CoroutineScope,
    private val config: AggregatorConfig = AggregatorConfig(),
) {
    private val logger: (String) -> Unit = config.logger
    private val deduplicator = EventDeduplicator()
    private val eqlistDecoder = CencEqListDecoder(logger)
    private val eewDecoders = config.eewSources.associate { it.source to WolfxEewDecoder(it.source, logger) }

    private val supervisor = SupervisorJob()
    private val internalScope = CoroutineScope(
        scope.coroutineContext + supervisor + CoroutineName("QuakeAggregator")
    )

    private val _events = MutableSharedFlow<QuakeEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<QuakeEvent> = _events.asSharedFlow()

    private val _history = MutableStateFlow<List<QuakeEvent>>(emptyList())
    val history: StateFlow<List<QuakeEvent>> = _history.asStateFlow()

    private val _fullHistory = MutableStateFlow<List<QuakeEvent>>(emptyList())
    val fullHistory: StateFlow<List<QuakeEvent>> = _fullHistory.asStateFlow()

    /** 各源连接状态（按 QuakeSource 索引） */
    private val _sourceStatus = MutableStateFlow<Map<QuakeSource, SourceStatus>>(
        config.eewSources.associate { it.source to SourceStatus(it.source, ConnectionState.Idle) } +
            (QuakeSource.CENC_EQLIST to SourceStatus(QuakeSource.CENC_EQLIST, ConnectionState.Idle))
    )
    val sourceStatuses: StateFlow<Map<QuakeSource, SourceStatus>> = _sourceStatus.asStateFlow()

    /** 转发自 [AlertTrigger.activeAlert]，UI 无需再单独持有 trigger。 */
    val activeAlert: StateFlow<com.quake.alert.alert.ActiveAlert?> = alertTrigger.activeAlert

    @Volatile
    private var running: Boolean = false

    private var wsJobs: Map<QuakeSource, Job> = emptyMap()
    private var eqlistJob: Job? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 幂等启动。重复调用无副作用。 */
    fun start(): Unit = synchronized(this) {
        if (running) return
        running = true
        wsJobs = config.eewSources.associate { sourceConfig ->
            sourceConfig.source to internalScope.launch(CoroutineName("ws-${sourceConfig.source.apiId}")) {
                wolfxLoop(sourceConfig)
            }
        }
        eqlistJob = internalScope.launch(CoroutineName("eqlist-poll")) { eqlistLoop() }
        logger("[Aggregator] 已启动 ${config.eewSources.size} 个 EEW 源 + 速报目录轮询")
    }

    /** 停止数据拉取。可再次 [start]。 */
    fun stop(): Unit = synchronized(this) {
        if (!running) return
        running = false
        wsJobs.values.forEach { it.cancel(CancellationException("QuakeAggregator.stop()")) }
        eqlistJob?.cancel(CancellationException("QuakeAggregator.stop()"))
        wsJobs = emptyMap()
        eqlistJob = null
        _sourceStatus.update { statuses ->
            statuses.mapValues { it.value.copy(state = ConnectionState.Idle) }
        }
        logger("[Aggregator] 已停止")
    }

    /** 彻底释放。之后该实例不可再用；[client] 由调用方自行决定是否 close。 */
    fun close() {
        stop()
        supervisor.cancel(CancellationException("QuakeAggregator.close()"))
    }

    /** 清空实时列表与历史（不影响连接与预警状态）。 */
    fun clearHistory() {
        _history.value = emptyList()
        _fullHistory.value = emptyList()
        deduplicator.clear()
        logger("[Aggregator] 事件列表已清空")
    }

    /** 用户点击"我已安全"。 */
    fun acknowledgeAlert() {
        alertTrigger.acknowledge()
    }

    // ------------------------------------------------------------------
    // EEW WebSocket 主循环（每源一个）
    // ------------------------------------------------------------------

    private suspend fun wolfxLoop(sourceConfig: EewSourceConfig) {
        val source = sourceConfig.source
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                setStatus(source) {
                    SourceStatus(
                        source = source,
                        state = if (attempt == 0) ConnectionState.Connecting else ConnectionState.Reconnecting,
                        reconnectAttempt = attempt,
                    )
                }

                client.webSocket(
                    method = HttpMethod.Get,
                    host = sourceConfig.host,
                    port = sourceConfig.port,
                    path = sourceConfig.path,
                    request = {
                        url {
                            protocol = if (sourceConfig.secure) URLProtocol.WSS else URLProtocol.WS
                        }
                        header("User-Agent", config.userAgent)
                    },
                ) {
                    attempt = 0
                    markAlive(source)
                    setStatus(source) { it.copy(state = ConnectionState.Connected) }
                    logger("[${source.apiId}] WebSocket 已连接")

                    val session = this
                    val watchdog = launch(CoroutineName("watchdog-${source.apiId}")) {
                        session.idleWatchdog(source)
                    }
                    val decoder = eewDecoders.getValue(source)
                    try {
                        incoming.consumeEach { frame ->
                            when (frame) {
                                is Frame.Text -> {
                                    markAlive(source)
                                    val event = decoder.decode(frame.readText())
                                    if (event != null) onEvent(event)
                                }

                                is Frame.Binary -> markAlive(source)
                                is Frame.Pong -> markAlive(source)

                                is Frame.Close -> {
                                    // readReason() 是 FrameCommonKt 顶层扩展，需 import io.ktor.websocket.readReason
                                    logger("[${source.apiId}] 服务端关闭连接: ${frame.readReason()?.message}")
                                    return@consumeEach
                                }

                                else -> Unit // Ping 由 Ktor 自动应答
                            }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }

                logger("[${source.apiId}] 会话结束，准备重连")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                attempt += 1
                setStatus(source) {
                    it.copy(state = ConnectionState.Error, lastError = t.message ?: t::class.simpleName)
                }
                logger("[${source.apiId}] 连接异常（第 $attempt 次）: ${t.message}")
            }

            if (!currentCoroutineContext().isActive) break
            if (attempt > config.reconnectMaxAttempts) {
                logger("[${source.apiId}] 重连次数超过上限，停止")
                break
            }

            val waitMs = backoffDelay((attempt - 1).coerceAtLeast(0))
            setStatus(source) { it.copy(state = ConnectionState.Reconnecting, reconnectAttempt = attempt) }
            logger("[${source.apiId}] ${waitMs}ms 后重连")
            delay(waitMs)
        }
    }

    private suspend fun DefaultClientWebSocketSession.idleWatchdog(source: QuakeSource) {
        while (currentCoroutineContext().isActive) {
            delay(config.wolfxIdleCheckIntervalMs)
            val idle = nowMs() - lastFrameAt(source)
            if (idle > config.wolfxIdleTimeoutMs) {
                logger("[${source.apiId}] ${idle}ms 未收到任何帧，判定为假连接，主动断开")
                runCatching {
                    close(CloseReason(CloseReason.Codes.NORMAL, "idle timeout"))
                }
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // 速报目录轮询
    // ------------------------------------------------------------------

    private suspend fun eqlistLoop() {
        val source = QuakeSource.CENC_EQLIST
        while (currentCoroutineContext().isActive) {
            try {
                setStatus(source) { it.copy(state = ConnectionState.Connecting) }
                val body = retryWithBackoff("cenc_eqlist") { attempt ->
                    if (attempt > 0) {
                        setStatus(source) { it.copy(state = ConnectionState.Reconnecting, reconnectAttempt = attempt) }
                    }
                    client.get(config.eqlistUrl) {
                        header("User-Agent", config.userAgent)
                    }.bodyAsText()
                }
                setStatus(source) {
                    it.copy(state = ConnectionState.Connected, lastMessageAt = nowMs())
                }
                val events = eqlistDecoder.decode(body)
                events.forEach { onEvent(it) }
                logger("[cenc_eqlist] 本轮拉取 ${events.size} 条目录")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                setStatus(source) {
                    it.copy(state = ConnectionState.Error, lastError = t.message ?: t::class.simpleName)
                }
                logger("[cenc_eqlist] 轮询失败: ${t.message}")
            }

            if (!currentCoroutineContext().isActive) break
            delay(config.eqlistPollIntervalMs)
        }
    }

    // ------------------------------------------------------------------
    // 事件收敛（跨源去重）
    // ------------------------------------------------------------------

    private suspend fun onEvent(rawEvent: QuakeEvent) {
        val resolved = deduplicator.resolve(rawEvent)
        val event = resolved.event

        // 实时列表（key 为 canonical id，就地覆盖）
        _history.update { list ->
            (list.filterNot { it.id == event.id } + event)
                .sortedByDescending { it.sortKey() }
                .take(config.historyLimit)
        }

        // 历史（查看历史用，容量更大）
        _fullHistory.update { list ->
            (list.filterNot { it.id == event.id } + event)
                .sortedByDescending { it.sortKey() }
                .take(config.fullHistoryLimit)
        }

        _events.emit(event)

        // 规则引擎判定：仅 EEW 预警事件参与弹窗；命中且未被去重抑制时 activeAlert 更新
        val decision = alertTrigger.evaluate(event)
        if (decision.shouldAlert) {
            logger("[Aggregator] 已触发预警：${decision.alert?.id}")
        }
    }

    // ------------------------------------------------------------------
    // 状态与工具
    // ------------------------------------------------------------------

    private fun setStatus(source: QuakeSource, transform: (SourceStatus) -> SourceStatus) {
        _sourceStatus.update { map ->
            val current = map[source] ?: SourceStatus(source, ConnectionState.Idle)
            map + (source to transform(current))
        }
    }

    private val lastFrameLock = Any()
    private var lastFrameAtMap: Map<QuakeSource, Long> = emptyMap()

    private fun markAlive(source: QuakeSource) {
        val now = nowMs()
        synchronized(lastFrameLock) {
            lastFrameAtMap = lastFrameAtMap + (source to now)
        }
        setStatus(source) { it.copy(lastMessageAt = now) }
    }

    private fun lastFrameAt(source: QuakeSource): Long = synchronized(lastFrameLock) {
        lastFrameAtMap[source] ?: 0L
    }

    /** 指数退避 + 抖动重试（HTTP 用）。 */
    private suspend fun <T> retryWithBackoff(
        tag: String,
        maxAttempts: Int = 3,
        block: suspend (attempt: Int) -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block(attempt)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxAttempts - 1) {
                    val waitMs = backoffDelay(attempt)
                    logger("[$tag] 第 ${attempt + 1}/$maxAttempts 次失败：${t.message}，${waitMs}ms 后重试")
                    delay(waitMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("$tag 重试失败且无异常信息")
    }

    /** 指数退避：base * 2^attempt，上限 [AggregatorConfig.reconnectMaxDelayMs]，叠加 ±30% 抖动。 */
    private fun backoffDelay(attempt: Int): Long {
        val exponential = config.reconnectBaseDelayMs * (1L shl attempt.coerceIn(0, 20))
        val capped = exponential.coerceAtMost(config.reconnectMaxDelayMs)
        val jitter = 0.7 + Random.nextDouble() * 0.6 // 0.7x ~ 1.3x
        return (capped * jitter).toLong().coerceAtLeast(config.reconnectBaseDelayMs)
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
