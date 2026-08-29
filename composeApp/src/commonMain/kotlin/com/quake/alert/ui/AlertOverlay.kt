package com.quake.alert.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.quake.alert.alert.ActiveAlert
import com.quake.alert.util.formatCountdown
import com.quake.alert.util.formatDepth
import com.quake.alert.util.formatIntensity
import com.quake.alert.util.formatLocalTime
import com.quake.alert.util.formatMagnitude

/**
 * 全屏强提醒覆盖层。
 *
 * - 背景红色闪烁（[rememberInfiniteTransition] 驱动 alpha 呼吸）
 * - `dismissOnBackPress` / `dismissOnClickOutside` 均为 false，返回键与点击外部无法关闭
 * - 唯一出口是"我已安全"按钮 → [onAcknowledge]
 * - 桌面端置顶由 `AlertEffects.setWindowAlwaysOnTop(true)` 在弹窗前完成
 */
@Composable
fun AlertOverlay(
    alert: ActiveAlert,
    onAcknowledge: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "quake-flash")
    val flashAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flash-alpha",
    )

    Dialog(
        onDismissRequest = { /* 强制交互，禁止自动关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF8B0000).copy(alpha = flashAlpha))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF160000).copy(alpha = 0.94f),
                ),
                border = BorderStroke(2.dp, Color(0xFFFF5252)),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "地 震 预 警",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF8A80),
                    )

                    // 主数字：报警判定用的烈度（本地估算 > API MaxIntensity）
                    val displayIntensity = alert.localIntensity ?: alert.event.intensity
                    Text(
                        text = "烈度 ${formatIntensity(displayIntensity)}",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFF5252),
                    )
                    Text(
                        text = "M ${formatMagnitude(alert.event.magnitude)} · ${alert.event.placeName}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    InfoRow("预计到达", formatCountdown(alert.countdownSeconds))
                    if (alert.localIntensity != null) {
                        InfoRow("本地烈度（估算）", formatIntensity(alert.localIntensity))
                    }
                    if (alert.event.intensity != null) {
                        InfoRow("最大预测烈度（API）", formatIntensity(alert.event.intensity))
                    }
                    InfoRow("震源深度", formatDepth(alert.event.depthKm))
                    InfoRow("发震时刻", formatLocalTime(alert.event.originTime))
                    InfoRow("数据来源", alert.event.source.displayName)

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = onAcknowledge,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("我 已 安 全", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "请立即采取避险措施，远离窗户与悬挂物",
                        fontSize = 12.sp,
                        color = Color(0xFFB0A0A0),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFFB0A0A0))
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}
