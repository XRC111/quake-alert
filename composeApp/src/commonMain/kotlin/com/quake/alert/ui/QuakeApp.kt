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
import com.quake.alert.ui.theme.QuakeColors
import com.quake.alert.ui.theme.QuakeTheme
import com.quake.alert.ui.theme.magnitudeColor
import com.quake.alert.util.formatCountdown
import com.quake.alert.util.formatDegree
import com.quake.alert.util.formatDepth
import com.quake.alert.util.formatIntensity
import com.quake.alert.util.formatLocalTime
import com.quake.alert.util.formatMagnitude
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text

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
        Box(Modifier.fillMaxSize().background(QuakeColors.Background)) {
            // Miuix Scaffold：提供 TopAppBar / 底部 TabRow / OverlayDialog 弹窗宿主
            Scaffold(
                topBar = {
                    SmallTopAppBar(title = "地震预警")
                },
                bottomBar = {
                    TabRow(
                        tabs = listOf(
                            "地震事件",
                            if (fullHistory.isNotEmpty()) "事件历史 (${fullHistory.size})" else "事件历史",
                            if (alertHistory.isNotEmpty()) "触发记录 (${alertHistory.size})" else "触发记录",
                        ),
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                ) {
                    SourceStatusBar(statuses = sourceStatuses)
                    ActionBar(
                        onTestAlert = viewModel::simulateAlert,
                        onOpenSettings = { showSettings = true },
                        onClearEvents = viewModel::clearEvents,
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

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            // SettingsDialog 回调完整新设置；ViewModel 的 updateSettings 接收变换函数
            onUpdate = { new -> viewModel.updateSettings { new } },
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
            .background(QuakeColors.SurfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SOURCE_ORDER.forEach { source ->
            val status = statuses[source] ?: SourceStatus(source, ConnectionState.Idle)
            val (color, text) = when (status.state) {
                ConnectionState.Connected -> QuakeColors.Green to "在线"
                ConnectionState.Connecting -> QuakeColors.Yellow to "连接中"
                ConnectionState.Reconnecting -> QuakeColors.Orange to "重连 ${status.reconnectAttempt}"
                ConnectionState.Error -> QuakeColors.Error to "异常"
                ConnectionState.Idle -> QuakeColors.Outline to "空闲"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(source.displayName, fontSize = 12.sp, color = QuakeColors.OnSurfaceVariant)
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
        ActionButton("▶ 测试预警", QuakeColors.Error, onTestAlert)
        ActionButton("⚙ 设置", QuakeColors.Primary, onOpenSettings)
        ActionButton("🗑 清空列表", QuakeColors.OnSurfaceVariant, onClearEvents)
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
// 空态
// ---------------------------------------------------------------------------

@Composable
internal fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在监听地震预警…", fontSize = 16.sp, color = QuakeColors.OnSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "CENC / 四川 / 重庆 / 台湾 4 路实时预警 + 速报目录，同震自动合并。",
            fontSize = 12.sp,
            color = QuakeColors.Outline,
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
        Text("暂无触发记录", fontSize = 16.sp, color = QuakeColors.OnSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "达到阈值弹出预警时会自动记录；也可用\"测试预警\"按钮验证链路。",
            fontSize = 12.sp,
            color = QuakeColors.Outline,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSevere) Color(0xFF5C1414).copy(alpha = 0.35f)
                else QuakeColors.SurfaceVariant
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp),
            ) {
                Text("M", fontSize = 11.sp, color = QuakeColors.Outline)
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
                    color = QuakeColors.OnSurface,
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
                    color = QuakeColors.OnSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isEew) {
                        Text(
                            text = "预警 · 第 ${event.updateSerial ?: 1} 报",
                            fontSize = 11.sp,
                            color = QuakeColors.EewOrange,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = "速报目录",
                            fontSize = 11.sp,
                            color = QuakeColors.Outline,
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
                        color = QuakeColors.OnSurfaceVariant,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDegree(event.latitude),
                    fontSize = 11.sp,
                    color = QuakeColors.Outline,
                )
                Text(
                    text = formatDegree(event.longitude),
                    fontSize = 11.sp,
                    color = QuakeColors.Outline,
                )
            }
        }
    }
}

@Composable
private fun AlertHistoryCard(entry: AlertHistoryEntry) {
    val color = magnitudeColor(entry.event.magnitude)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(QuakeColors.SurfaceVariant)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Text("M", fontSize = 11.sp, color = QuakeColors.Outline)
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
                    color = QuakeColors.OnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("触发于 ${formatLocalTime(entry.triggeredAt)}")
                        entry.event.intensity?.let { append(" · 烈度 "); append(formatIntensity(it)) }
                    },
                    fontSize = 12.sp,
                    color = QuakeColors.OnSurfaceVariant,
                )
            }
            Text(
                text = if (entry.acknowledged) "已确认" else "未确认",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (entry.acknowledged) QuakeColors.Green else QuakeColors.Yellow,
            )
        }
    }
}

@Composable
private fun SourceFooter() {
    Text(
        text = "数据源：Wolfx（CENC · 四川 · 重庆 · 台湾 CWA）· https://wolfx.jp",
        fontSize = 11.sp,
        color = QuakeColors.Outline,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}
