package com.quake.alert.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quake.alert.alert.AlertHistoryEntry
import com.quake.alert.data.ConnectionState
import com.quake.alert.data.SourceStatus
import com.quake.alert.model.AlertLevel
import com.quake.alert.model.QuakeEvent
import com.quake.alert.model.QuakeSource
import com.quake.alert.platform.AlertEffects
import com.quake.alert.ui.theme.QuakeTheme
import com.quake.alert.ui.theme.magnitudeColor
import com.quake.alert.util.formatCountdown
import com.quake.alert.util.formatDegree
import com.quake.alert.util.formatDepth
import com.quake.alert.util.formatIntensity
import com.quake.alert.util.formatLocalTime
import com.quake.alert.util.formatMagnitude

/** 记住 ViewModel 实例。跨配置变更的保活由各平台自行处理（Android 可用 ViewModelStoreOwner）。 */
@Composable
fun rememberQuakeViewModel(): QuakeViewModel = remember { QuakeViewModel() }

/** 状态条展示顺序：4 个 EEW 源 + 速报目录 */
private val SOURCE_ORDER = listOf(
    QuakeSource.CENC_EEW,
    QuakeSource.SC_EEW,
    QuakeSource.CQ_EEW,
    QuakeSource.CWA_EEW,
    QuakeSource.CENC_EQLIST,
)

@Composable
fun QuakeApp(
    viewModel: QuakeViewModel = rememberQuakeViewModel(),
) {
    val history by viewModel.history.collectAsState()
    val fullHistory by viewModel.fullHistory.collectAsState()
    val alert by viewModel.alert.collectAsState()
    val sourceStatuses by viewModel.sourceStatuses.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val alertHistory by viewModel.alertHistory.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val effects = remember { AlertEffects() }
    DisposableEffect(Unit) {
        onDispose {
            effects.stopAlarm()
            effects.setWindowAlwaysOnTop(false)
            effects.dispose()
        }
    }

    // 预警状态变化 → 触发平台副作用（亮屏 / 置顶 / 声音 / 震动 / 通知）
    LaunchedEffect(alert?.id, settings.soundEnabled, settings.vibrationEnabled) {
        val current = alert
        if (current != null) {
            effects.wakeScreen()
            effects.setWindowAlwaysOnTop(true)
            if (settings.soundEnabled) effects.playAlarm()
            if (settings.vibrationEnabled) effects.vibrate()
            effects.showNotification(
                title = "地震预警 · 烈度 ${formatIntensity(current.localIntensity ?: current.event.intensity)}",
                text = "${current.event.placeName} · ${formatCountdown(current.countdownSeconds)}后可能到达",
            )
        } else {
            effects.stopAlarm()
            effects.setWindowAlwaysOnTop(false)
            effects.cancelNotification()
        }
    }

    QuakeTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    SourceStatusBar(statuses = sourceStatuses)

                    ActionBar(
                        onTestAlert = viewModel::simulateAlert,
                        onOpenSettings = { showSettings = true },
                        onClearEvents = viewModel::clearEvents,
                    )

                    TabSelector(
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        historyCount = fullHistory.size,
                        alertCount = alertHistory.size,
                    )

                    when (selectedTab) {
                        // 实时列表（最近 50 条）
                        0 -> if (history.isEmpty()) {
                            EmptyHint(modifier = Modifier.fillMaxSize())
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(items = history, key = { it.id }) { event ->
                                    QuakeEventCard(event)
                                }
                                item { SourceFooter() }
                            }
                        }

                        // 事件历史（全量，可回看更早）
                        1 -> if (fullHistory.isEmpty()) {
                            EmptyHint(modifier = Modifier.fillMaxSize())
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(items = fullHistory, key = { it.id }) { event ->
                                    QuakeEventCard(event)
                                }
                            }
                        }

                        // 触发记录
                        else -> if (alertHistory.isEmpty()) {
                            EmptyAlertHistoryHint(modifier = Modifier.fillMaxSize())
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(items = alertHistory, key = { it.id }) { entry ->
                                    AlertHistoryCard(entry)
                                }
                            }
                        }
                    }
                }

                // 全屏预警覆盖层：仅当用户点击"我已安全"后才消失
                AnimatedVisibility(
                    visible = alert != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    alert?.let { current ->
                        AlertOverlay(
                            alert = current,
                            onAcknowledge = {
                                effects.stopAlarm()
                                effects.setWindowAlwaysOnTop(false)
                                viewModel.acknowledgeAlert()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onUpdate = viewModel::updateSettings,
            onDismiss = { showSettings = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部状态条（多源横向滚动）
// ---------------------------------------------------------------------------

@Composable
private fun SourceStatusBar(statuses: Map<QuakeSource, SourceStatus>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SOURCE_ORDER.forEach { source ->
            val status = statuses[source] ?: SourceStatus(source, ConnectionState.Idle)
            val (color, text) = when (status.state) {
                ConnectionState.Connected -> Color(0xFF4CAF50) to "在线"
                ConnectionState.Connecting -> Color(0xFFFFC107) to "连接中"
                ConnectionState.Reconnecting -> Color(0xFFFF9800) to "重连 ${status.reconnectAttempt}"
                ConnectionState.Error -> Color(0xFFF44336) to "异常"
                ConnectionState.Idle -> Color(0xFF9E9E9E) to "空闲"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(source.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 操作行
// ---------------------------------------------------------------------------

@Composable
private fun ActionBar(
    onTestAlert: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearEvents: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionButton("▶ 测试预警", Color(0xFFFF5252), onTestAlert)
        ActionButton("⚙ 设置", MaterialTheme.colorScheme.primary, onOpenSettings)
        ActionButton("🗑 清空列表", MaterialTheme.colorScheme.onSurfaceVariant, onClearEvents)
    }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// Tab 切换（3 个：实时 / 历史 / 触发记录）
// ---------------------------------------------------------------------------

@Composable
private fun TabSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
    historyCount: Int,
    alertCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        TabItem("地震事件", selected == 0) { onSelect(0) }
        Spacer(Modifier.width(4.dp))
        TabItem(if (historyCount > 0) "事件历史 ($historyCount)" else "事件历史", selected == 1) { onSelect(1) }
        Spacer(Modifier.width(4.dp))
        TabItem(if (alertCount > 0) "触发记录 ($alertCount)" else "触发记录", selected == 2) { onSelect(2) }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

@Composable
internal fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在监听地震预警…", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "CENC / 四川 / 重庆 / 台湾 4 路实时预警 + 速报目录，同震自动合并。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EmptyAlertHistoryHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("暂无触发记录", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "达到阈值弹出预警时会自动记录；也可用\"测试预警\"按钮验证链路。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---------------------------------------------------------------------------
// 列表项
// ---------------------------------------------------------------------------

@Composable
private fun QuakeEventCard(event: QuakeEvent) {
    val color = magnitudeColor(event.magnitude)
    val isSevere = event.alertLevel == AlertLevel.SEVERE
    val multiSource = event.mergedSources.isNotEmpty()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSevere) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp),
            ) {
                Text("M", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = formatMagnitude(event.magnitude),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = event.placeName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(formatLocalTime(event.originTime))
                        event.intensity?.let { append(" · 烈度 "); append(formatIntensity(it)) }
                        append(" · 深 ")
                        append(formatDepth(event.depthKm))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isEew) {
                        Text(
                            text = "预警 · 第 ${event.updateSerial ?: 1} 报",
                            fontSize = 11.sp,
                            color = Color(0xFFFF8A65),
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = "速报目录",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (multiSource) {
                            "${event.source.displayName} +${event.mergedSources.size} 源"
                        } else {
                            event.source.displayName
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDegree(event.latitude),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = formatDegree(event.longitude),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun AlertHistoryCard(entry: AlertHistoryEntry) {
    val color = magnitudeColor(entry.event.magnitude)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Text("M", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = formatMagnitude(entry.event.magnitude),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.event.placeName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("触发于 ${formatLocalTime(entry.triggeredAt)}")
                        entry.event.intensity?.let { append(" · 烈度 "); append(formatIntensity(it)) }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (entry.acknowledged) "已确认" else "未确认",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (entry.acknowledged) Color(0xFF4CAF50) else Color(0xFFFFC107),
            )
        }
    }
}

@Composable
private fun SourceFooter() {
    Text(
        text = "数据源：Wolfx（CENC · 四川 · 重庆 · 台湾 CWA）· https://wolfx.jp",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}
