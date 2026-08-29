package com.quake.alert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quake.alert.settings.AppSettings
import com.quake.alert.ui.theme.QuakeColors
import com.quake.alert.util.formatIntensity
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 设置面板（Miuix OverlayDialog，需由 Miuix Scaffold 包裹）。
 *
 * 所有修改即时生效（回调 [onUpdate] 传入完整新设置），确认按钮仅负责关闭面板。
 */
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "设置",
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        ) {
            // ---- 烈度阈值（主） ----
            Text("所在地烈度阈值（中国烈度表，0~12 度）", fontSize = 13.sp, color = QuakeColors.OnSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = settings.intensityThreshold.toFloat(),
                    onValueChange = { v ->
                        onUpdate(settings.copy(intensityThreshold = v.toDouble().roundTo(1)))
                    },
                    valueRange = 0.5f..10.0f,
                    steps = 18,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${formatIntensity(settings.intensityThreshold)}（${settings.intensityThreshold}）",
                    fontSize = 14.sp,
                    color = QuakeColors.Primary,
                )
            }
            Text(
                "报警由所在地预测烈度决定；低于此值不报警。",
                fontSize = 11.sp,
                color = QuakeColors.Outline,
            )

            // ---- 降级震级阈值 ----
            Text("降级震级阈值（API 未下发烈度时生效）", fontSize = 13.sp, color = QuakeColors.OnSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = settings.fallbackMagnitudeThreshold.toFloat(),
                    onValueChange = { v ->
                        onUpdate(settings.copy(fallbackMagnitudeThreshold = v.toDouble().roundTo(1)))
                    },
                    valueRange = 2.0f..7.0f,
                    steps = 9,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "M ${settings.fallbackMagnitudeThreshold}",
                    fontSize = 14.sp,
                    color = QuakeColors.Primary,
                )
            }

            // ---- 去重窗口 ----
            Text("去重窗口（分钟）", fontSize = 13.sp, color = QuakeColors.OnSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = (settings.dedupeWindowMs / 60_000L).toFloat(),
                    onValueChange = { v ->
                        val minutes = v.toInt().coerceAtLeast(1)
                        onUpdate(settings.copy(dedupeWindowMs = minutes * 60_000L))
                    },
                    valueRange = 1f..120f,
                    steps = 23,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${settings.dedupeWindowMs / 60_000L} 分钟",
                    fontSize = 14.sp,
                    color = QuakeColors.Primary,
                )
            }

            // ---- 升级复报 / 报警音 / 震动 ----
            SwitchRow(
                checked = settings.allowEscalationReAlert,
                title = "烈度/震级升级复报",
                subtitle = "去重窗口内强度上调 ≥ ${settings.escalationDelta} 时再次弹窗",
                onCheckedChange = { checked -> onUpdate(settings.copy(allowEscalationReAlert = checked)) },
            )
            SwitchRow(
                checked = settings.soundEnabled,
                title = "报警音",
                subtitle = "触发预警时播放蜂鸣/自定义音频",
                onCheckedChange = { checked -> onUpdate(settings.copy(soundEnabled = checked)) },
            )
            SwitchRow(
                checked = settings.vibrationEnabled,
                title = "震动",
                subtitle = "触发预警时同步震动",
                onCheckedChange = { checked -> onUpdate(settings.copy(vibrationEnabled = checked)) },
            )

            // ---- 本地估算（默认关闭） ----
            SwitchRow(
                checked = settings.enableLocalEstimation,
                title = "本地估算（倒计时 / 所在地烈度）",
                subtitle = "按观测点坐标估算 S 波到达时间与本地烈度（默认关闭，以 API 为准）",
                onCheckedChange = { checked -> onUpdate(settings.copy(enableLocalEstimation = checked)) },
            )
            if (settings.enableLocalEstimation) {
                Text(
                    "观测点坐标（填了才会估算；API 无到达时间字段时，倒计时由估算补上）",
                    fontSize = 11.sp,
                    color = QuakeColors.Outline,
                )
                CoordinateInput(
                    label = "纬度（如 31.23）",
                    value = settings.observerLat,
                    onValueChange = { v -> onUpdate(settings.copy(observerLat = v)) },
                )
                CoordinateInput(
                    label = "经度（如 121.47）",
                    value = settings.observerLon,
                    onValueChange = { v -> onUpdate(settings.copy(observerLon = v)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = QuakeColors.OnSurface)
            Text(subtitle, fontSize = 12.sp, color = QuakeColors.Outline)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CoordinateInput(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
) {
    // 本地文本态防止输入抖动；输入时解析成 Double? 回写设置
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input.trim().toDoubleOrNull())
        },
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Double.roundTo(decimal: Int): Double {
    val factor = 10.0.pow(decimal)
    return (this * factor).roundToInt() / factor
}

private fun Double.pow(exp: Int): Double {
    var result = 1.0
    repeat(exp) { result *= this }
    return result
}
