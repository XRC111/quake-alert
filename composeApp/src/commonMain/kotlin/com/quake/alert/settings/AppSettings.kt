package com.quake.alert.settings

import com.quake.alert.alert.AlertRuleConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 用户可调设置。
 *
 * ⚠️ 当前为内存态（进程内生效）。需要跨启动持久化时，接入平台键值存储：
 * - Android：`SharedPreferences`（androidMain actual）
 * - iOS：`NSUserDefaults`（iosMain actual）
 * - Desktop：`java.util.prefs.Preferences`（desktopMain actual）
 * 在 [SettingsStore] 初始化处读入、[update] 处写回即可，UI 层无需改动。
 */
data class AppSettings(
    /** 所在地预测烈度阈值（中国烈度表 0~12 度），API 下发烈度时据此报警 */
    val intensityThreshold: Double = 3.0,
    /** 降级阈值：API 未下发烈度时按震级报警 */
    val fallbackMagnitudeThreshold: Double = 3.0,
    /** 去重窗口（毫秒） */
    val dedupeWindowMs: Long = 30 * 60 * 1000L,
    /** 允许升级复报 */
    val allowEscalationReAlert: Boolean = true,
    /** 升级复报最小增量（烈度或震级） */
    val escalationDelta: Double = 0.5,
    /** 报警音开关 */
    val soundEnabled: Boolean = true,
    /** 震动开关 */
    val vibrationEnabled: Boolean = true,
    /**
     * 本地估算开关（**默认关闭**）：开启后用 [observerLat]/[observerLon] 估算所在地烈度与到达倒计时。
     * 关闭时倒计时/烈度严格以 API 字段为准（中国区源无到达时间字段，将显示"未知"）。
     */
    val enableLocalEstimation: Boolean = false,
    /** 观测点纬度（本地估算用，可选） */
    val observerLat: Double? = null,
    /** 观测点经度（本地估算用，可选） */
    val observerLon: Double? = null,
) {
    fun toAlertRuleConfig(): AlertRuleConfig = AlertRuleConfig(
        intensityThreshold = intensityThreshold,
        fallbackMagnitudeThreshold = fallbackMagnitudeThreshold,
        dedupeWindowMs = dedupeWindowMs,
        allowEscalationReAlert = allowEscalationReAlert,
        escalationDelta = escalationDelta,
    )
}

/** 设置仓库：StateFlow 提供响应式读取，[update] 提供函数式更新。 */
class SettingsStore(initial: AppSettings = AppSettings()) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        _state.update(transform)
    }
}
