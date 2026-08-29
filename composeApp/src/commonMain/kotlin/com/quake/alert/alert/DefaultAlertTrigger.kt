package com.quake.alert.alert

import com.quake.alert.model.AlertLevel
import com.quake.alert.model.QuakeEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 默认规则引擎实现（单源：Wolfx CENC EEW）。
 *
 * 职责：
 * 1. **烈度优先**判定，烈度取值优先级：
 *    - 本地估算烈度（用户开启「本地估算」且配置观测点坐标时，见 [LocalEstimator]）——最贴合"所在地烈度"；
 *    - 否则 API 下发的预测烈度（`QuakeEvent.intensity`，即 `MaxIntensity`，最大预测烈度的保守上界）；
 *    - 都没有 → 降级按震级阈值触发（防漏报，命中时在 reason 中标注"降级"）。
 * 2. 30 分钟窗口内按事件 ID 去重，抑制重复弹窗；烈度/震级显著上调时允许升级复报。
 * 3. 倒计时优先级：**API 字段**（`remainTimeSec` / `arrivalTime`）→ 本地估算（仅开启时）→ null。
 */
class DefaultAlertTrigger(
    rules: AlertRuleConfig = AlertRuleConfig(),
    private val localEstimator: LocalEstimator? = null,
    private val now: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
    private val logger: (String) -> Unit = {},
) : AlertTrigger {

    /** 阈值热更新：设置面板修改后直接生效，无需重建触发器 */
    @Volatile
    override var rules: AlertRuleConfig = rules

    private data class AlertRecord(
        val magnitude: Double,
        val lastTriggeredAt: Long,
        val hits: Int,
    )

    private val history = LinkedHashMap<String, AlertRecord>()
    private val _activeAlert = MutableStateFlow<ActiveAlert?>(null)
    override val activeAlert: StateFlow<ActiveAlert?> = _activeAlert.asStateFlow()

    override suspend fun evaluate(event: QuakeEvent): AlertDecision {
        // 速报目录/测定事件（cenc_eqlist，isEew=false）不触发弹窗预警
        if (!event.isEew) {
            return AlertDecision(shouldAlert = false, reason = "地震目录/测定事件，不触发预警")
        }

        val timestamp = now()

        // ---- 本地估算（可选，默认关闭）----
        val local = localEstimator?.estimate(event)
        val effectiveIntensity = local?.localIntensity ?: event.intensity
        val intensitySource = when {
            local != null -> "本地估算"
            event.intensity != null -> "API 最大预测烈度"
            else -> null
        }

        val matched = matchRule(event, effectiveIntensity, intensitySource)
        if (!matched.matched) {
            return AlertDecision(shouldAlert = false, reason = matched.reason)
        }

        val previous = history[event.id]

        // ---- 30 分钟去重 ----
        if (previous != null && timestamp - previous.lastTriggeredAt < rules.dedupeWindowMs) {
            val escalated = rules.allowEscalationReAlert &&
                severity(event, effectiveIntensity) - previous.magnitude >= rules.escalationDelta

            if (!escalated) {
                history[event.id] = previous.copy(hits = previous.hits + 1)
                val suppressed = AlertDecision(
                    shouldAlert = false,
                    reason = "${event.id} 处于 ${rules.dedupeWindowMs / 60_000} 分钟去重窗口内，已抑制" +
                        "（第 ${previous.hits + 1} 次命中）",
                )
                logger("[Alert] ${suppressed.reason}")
                return suppressed
            }
            logger("[Alert] ${event.id} 烈度/震级由 ${previous.magnitude} 升至 ${severity(event, effectiveIntensity)}，升级复报")
        }

        history[event.id] = AlertRecord(
            magnitude = severity(event, effectiveIntensity),
            lastTriggeredAt = timestamp,
            hits = 1,
        )

        val level = classify(event, rules, effectiveIntensity)
        // 倒计时：API 字段优先，本地估算兜底
        val countdownSeconds = apiCountdownSeconds(event, timestamp) ?: local?.countdownSeconds

        val alert = ActiveAlert(
            id = "${event.id}@$timestamp",
            event = event.copy(alertLevel = level),
            triggeredAt = timestamp,
            level = level,
            rule = matched.reason,
            countdownSeconds = countdownSeconds,
            localIntensity = local?.localIntensity,
        )
        _activeAlert.value = alert
        logger("[Alert] 触发预警：${describe(event)}（${matched.reason}）")
        return AlertDecision(shouldAlert = true, reason = matched.reason, alert = alert)
    }

    override fun acknowledge(): Boolean {
        val cleared = _activeAlert.value
        _activeAlert.value = null
        if (cleared != null) logger("[Alert] 用户已确认安全，清除预警 ${cleared.id}")
        return cleared != null
    }

    override fun reset() {
        history.clear()
        _activeAlert.value = null
    }

    // -----------------------------------------------------------------------

    private data class RuleResult(val matched: Boolean, val reason: String)

    /**
     * 烈度优先判定。
     *
     * @param effectiveIntensity 最终用于判定的烈度（本地估算 > API MaxIntensity > null）
     * @param intensitySource    该烈度的来源描述（"本地估算" / "API 最大预测烈度" / null）
     */
    private fun matchRule(
        event: QuakeEvent,
        effectiveIntensity: Double?,
        intensitySource: String?,
    ): RuleResult {
        if (effectiveIntensity != null) {
            return if (effectiveIntensity >= rules.intensityThreshold) {
                RuleResult(true, "$intensitySource 烈度 ${effectiveIntensity} 度 >= ${rules.intensityThreshold} 度")
            } else {
                RuleResult(false, "$intensitySource 烈度 ${effectiveIntensity} 度 < ${rules.intensityThreshold} 度")
            }
        }
        logger("[Alert] ${event.id} 无烈度（未估算且 API 未下发），降级按震级判定")
        return if (event.magnitude >= rules.fallbackMagnitudeThreshold) {
            RuleResult(
                true,
                "无烈度字段，降级：震级 ${event.magnitude} >= ${rules.fallbackMagnitudeThreshold}",
            )
        } else {
            RuleResult(
                false,
                "无烈度字段，降级：震级 ${event.magnitude} < ${rules.fallbackMagnitudeThreshold}",
            )
        }
    }

    /** 事件强度：有效烈度优先，否则用震级（用于去重/升级复报比较）。 */
    private fun severity(event: QuakeEvent, effectiveIntensity: Double?): Double =
        effectiveIntensity ?: event.magnitude

    private fun classify(
        event: QuakeEvent,
        rules: AlertRuleConfig,
        effectiveIntensity: Double?,
    ): AlertLevel {
        val value = severity(event, effectiveIntensity)
        return when {
            value >= rules.severeThreshold -> AlertLevel.SEVERE
            value >= rules.warningThreshold -> AlertLevel.WARNING
            else -> AlertLevel.INFO
        }
    }

    private fun describe(event: QuakeEvent): String = buildString {
        event.intensity?.let { append("烈度 ${it} 度 · ") }
        append("M${event.magnitude} ${event.placeName}")
    }
}

/**
 * 从 API 字段计算剩余倒计时（秒）。
 *
 * 优先级：
 * 1. `remainTimeSec`：API 直接下发的剩余秒数（若小于 0 视为 0，即已到达）；
 * 2. `arrivalTime`：API 下发的预计到达时刻，减去当前时间；
 * 3. 都没有 → null（由调用方决定是否回落到本地估算）。
 *
 * 客户端**不**自行做 P/S 波走时估算（除非用户显式开启"本地估算"）。
 */
private fun apiCountdownSeconds(event: QuakeEvent, timestamp: Long): Int? {
    event.remainTimeSec?.let { return it.coerceAtLeast(0) }
    event.arrivalTime?.let { arrival ->
        return ((arrival - timestamp) / 1000L).toInt().coerceAtLeast(0)
    }
    return null
}
