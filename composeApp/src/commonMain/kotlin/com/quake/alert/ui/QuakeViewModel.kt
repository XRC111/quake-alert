package com.quake.alert.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quake.alert.alert.ActiveAlert
import com.quake.alert.alert.AlertHistoryEntry
import com.quake.alert.alert.DefaultAlertTrigger
import com.quake.alert.alert.LocalEstimator
import com.quake.alert.data.AggregatorConfig
import com.quake.alert.data.QuakeAggregator
import com.quake.alert.data.SourceStatus
import com.quake.alert.data.createQuakeHttpClient
import com.quake.alert.model.GeoPoint
import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import com.quake.alert.settings.AppSettings
import com.quake.alert.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * MVVM 中的 ViewModel：把 [QuakeAggregator] 的流（实时列表 / 事件历史 / 各源状态 / 当前预警）与
 * 设置 / 触发历史暴露给 Compose，并把用户操作反向传回。
 *
 * 音效、亮屏、置顶等副作用**不在**这里触发，而是由 UI 层观察 [alert] 后调用
 * [com.quake.alert.platform.AlertEffects]，保持 ViewModel 的平台无关性。
 */
class QuakeViewModel(
    aggregatorConfig: AggregatorConfig = AggregatorConfig(),
    initialSettings: AppSettings = AppSettings(),
    enableHttpLogging: Boolean = false,
) : ViewModel() {

    // 注意声明顺序：settingsStore 必须先于 alertTrigger 初始化
    val settingsStore = SettingsStore(initialSettings)
    val appSettings: StateFlow<AppSettings> = settingsStore.state

    private val httpClient = createQuakeHttpClient(
        config = aggregatorConfig,
        enableLogging = enableHttpLogging,
    )

    private val alertTrigger = DefaultAlertTrigger(
        rules = initialSettings.toAlertRuleConfig(),
        // 可选本地估算：默认关闭，仅当用户在设置里开启并填写观测点坐标时生效
        localEstimator = LocalEstimator(
            observerProvider = {
                val s = settingsStore.state.value
                val lat = s.observerLat
                val lon = s.observerLon
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            },
            enabledProvider = { settingsStore.state.value.enableLocalEstimation },
        ),
        logger = aggregatorConfig.logger,
    )

    private val aggregator = QuakeAggregator(
        client = httpClient,
        alertTrigger = alertTrigger,
        scope = viewModelScope,
        config = aggregatorConfig,
    )

    /** 实时列表（最近 [AggregatorConfig.historyLimit] 条，跨源同震已合并） */
    val history: StateFlow<List<QuakeEvent>> = aggregator.history

    /** 事件历史（最多 [AggregatorConfig.fullHistoryLimit] 条，查看历史用） */
    val fullHistory: StateFlow<List<QuakeEvent>> = aggregator.fullHistory

    /** 增量事件流（用于埋点 / 通知等一次性消费场景） */
    val events: SharedFlow<QuakeEvent> = aggregator.events

    /** 各数据源连接状态（cenc_eew / sc_eew / cq_eew / cwa_eew / cenc_eqlist） */
    val sourceStatuses: StateFlow<Map<QuakeSource, SourceStatus>> = aggregator.sourceStatuses

    /** 非空即应弹出全屏预警覆盖层 */
    val alert: StateFlow<ActiveAlert?> = aggregator.activeAlert

    /** 预警触发记录（含确认状态），最多保留 100 条 */
    private val _alertHistory = MutableStateFlow<List<AlertHistoryEntry>>(emptyList())
    val alertHistory: StateFlow<List<AlertHistoryEntry>> = _alertHistory.asStateFlow()

    init {
        // 设置热更新 → 同步到规则引擎（烈度阈值 / 去重窗口 / 升级复报）
        viewModelScope.launch {
            settingsStore.state.collect { settings ->
                alertTrigger.rules = settings.toAlertRuleConfig()
            }
        }

        // 预警出现 → 记入触发历史（按 alert.id 去重）
        viewModelScope.launch {
            aggregator.activeAlert.collect { active ->
                if (active != null) {
                    _alertHistory.update { list ->
                        (list.filterNot { it.id == active.id } +
                            AlertHistoryEntry(
                                id = active.id,
                                event = active.event,
                                triggeredAt = active.triggeredAt,
                            ))
                            .sortedByDescending { it.triggeredAt }
                            .take(MAX_ALERT_HISTORY)
                    }
                }
            }
        }

        aggregator.start()
    }

    // ------------------------------------------------------------------
    // 用户操作
    // ------------------------------------------------------------------

    /** 用户点击"我已安全"：清除预警并标记历史为已确认。 */
    fun acknowledgeAlert() {
        val alertId = alert.value?.id
        alertTrigger.acknowledge()
        if (alertId != null) {
            _alertHistory.update { list ->
                list.map { if (it.id == alertId) it.copy(acknowledged = true) else it }
            }
        }
    }

    /**
     * 测试预警：伪造一条带"预测烈度 + API 剩余时间"的高强度事件，走完整规则链路，
     * 验证**本机**的弹窗/声音/震动/通知链路是否工作。
     *
     * ⚠️ 仅供开发者调试本机链路使用，不代表任何真实地震预警；
     * 该事件不会写入事件列表，但会记入触发记录。
     */
    fun simulateAlert() {
        val now = Clock.System.now().toEpochMilliseconds()
        val fake = QuakeEvent(
            id = "sim-$now",
            source = QuakeSource.CENC_EEW,
            magnitude = 6.5,
            latitude = 31.23,
            longitude = 121.47,
            placeName = "测试震中（模拟预警）",
            originTime = now,
            receivedAt = now,
            depthKm = 10.0,
            updateSerial = 1,
            isEew = true,
            intensity = 5.0,       // V 度：API 下发的预测烈度
            remainTimeSec = 30,    // API 下发的剩余到达秒数
        )
        viewModelScope.launch {
            alertTrigger.evaluate(fake)
        }
    }

    /** 清空触发历史。 */
    fun clearAlertHistory() {
        _alertHistory.value = emptyList()
    }

    /** 清空实时列表与事件历史（不影响连接与预警状态）。 */
    fun clearEvents() {
        aggregator.clearHistory()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsStore.update(transform)
    }

    override fun onCleared() {
        aggregator.close()
        httpClient.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_ALERT_HISTORY = 100
    }
}
