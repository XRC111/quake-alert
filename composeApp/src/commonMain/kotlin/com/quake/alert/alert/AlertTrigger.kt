package com.quake.alert.alert

import com.quake.alert.model.AlertLevel
import com.quake.alert.model.QuakeEvent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * 预警规则配置。
 *
 * ## 判定规则（烈度优先）
 * 报警与否由**当前所在地的预测烈度**决定（数据来自 API 的 `Intensity` 字段，中国烈度表 0~12 度）：
 * - API 下发了烈度 → `intensity >= [intensityThreshold]` 触发；
 * - API 未下发烈度 → 降级按震级 `magnitude >= [fallbackMagnitudeThreshold]` 触发（避免漏报）。
 *
 * 30 分钟内同一 ID 只弹一次（震级/烈度显著上调时允许升级复报）。
 */
@Serializable
data class AlertRuleConfig(
    /** 所在地预测烈度阈值（中国烈度表，0~12 度） */
    val intensityThreshold: Double = 3.0,
    /** 降级阈值：API 未下发烈度字段时，按震级判定 */
    val fallbackMagnitudeThreshold: Double = 3.0,
    /** 去重窗口（毫秒），默认 30 分钟 */
    val dedupeWindowMs: Long = 30 * 60 * 1000L,
    /**
     * 是否允许"升级复报"：EEW 后续报数烈度/震级显著变大时，即使处于去重窗口内也重新弹窗。
     * 关闭后即严格 30 分钟只弹一次。
     */
    val allowEscalationReAlert: Boolean = true,
    /** 触发升级复报所需的最小烈度增量（或降级模式下最小震级增量） */
    val escalationDelta: Double = 0.5,
    /** 分级阈值：>= severe 为"严重"，>= warning 为"警告"（烈度优先，降级模式下同值作用于震级） */
    val severeThreshold: Double = 6.0,
    val warningThreshold: Double = 4.0,
)

/** 一次已生效的预警。UI 直接据此渲染全屏弹窗。 */
data class ActiveAlert(
    /** 唯一 ID（`事件ID@触发时刻`），UI 用它做动画/音效的 key */
    val id: String,
    val event: QuakeEvent,
    val triggeredAt: Long,
    val level: AlertLevel,
    /** 命中规则的文字说明，便于排查与灰度调参 */
    val rule: String,
    /**
     * 预计到达倒计时（秒）。
     * 优先级：API 字段（`RemainTime` / `ArrivalTime`）→ 本地估算（用户开启且填了坐标）→ null。
     */
    val countdownSeconds: Int?,
    /**
     * 本地估算烈度（仅用户开启"本地估算"且有观测点坐标时非空）。
     * 报警判定优先用本值（所在地烈度），API 的最大预测烈度见 [QuakeEvent.intensity]。
     */
    val localIntensity: Double? = null,
)

/** 预警触发历史条目：供"触发记录"页展示，含用户确认状态。 */
data class AlertHistoryEntry(
    val id: String,
    val event: QuakeEvent,
    val triggeredAt: Long,
    val acknowledged: Boolean = false,
)

/** 规则引擎对单个事件的判定结果。 */
data class AlertDecision(
    val shouldAlert: Boolean,
    val reason: String,
    val alert: ActiveAlert? = null,
)

/**
 * 预警触发器（规则引擎）抽象。
 *
 * [QuakeAggregator] 只负责"把数据源收敛成事件流"，是否弹窗由本接口决定，
 * 二者通过 [evaluate] 单一入口解耦——替换成 A/B 实验策略或远程配置策略都不需要动聚合器。
 * 运行期可通过 [rules] 热更新阈值（设置面板实时生效）。
 */
interface AlertTrigger {
    var rules: AlertRuleConfig

    /** 当前生效中的预警；为 null 表示无预警。UI 观察它来决定是否弹出覆盖层。 */
    val activeAlert: StateFlow<ActiveAlert?>

    /** 对事件做规则判定。匹配且未被去重抑制时，会更新 [activeAlert]。 */
    suspend fun evaluate(event: QuakeEvent): AlertDecision

    /** 用户点击"我已安全"后调用，清除当前预警。@return 是否确实清除了一次预警。 */
    fun acknowledge(): Boolean

    /** 清空全部历史记录（换数据源 / 切换账号 / 测试用）。 */
    fun reset()
}
