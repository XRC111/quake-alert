package com.quake.alert.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix（MIUI / HyperOS 风格）主题。
 *
 * 预警应用固定深色模式，由 [ThemeController] 驱动 Miuix 组件（Scaffold / TopAppBar /
 * TabRow / OverlayDialog / Switch / Slider 等）的内置配色。
 * 自定义绘制区域（状态条 / 卡片 / 预警弹窗）使用 [QuakeColors] 固定深色调色板，
 * 避免依赖主题 colorScheme 的字段差异。
 */
@Composable
fun QuakeTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.Dark) }
    MiuixTheme(controller = controller) {
        content()
    }
}

/** 按震级给出配色，采用中国习惯的"越大越红"。 */
fun magnitudeColor(magnitude: Double): Color = when {
    magnitude >= 6.0 -> Color(0xFFFF1744)
    magnitude >= 5.0 -> Color(0xFFFF5252)
    magnitude >= 4.0 -> Color(0xFFFF8A65)
    magnitude >= 3.0 -> Color(0xFFFFB74D)
    else -> Color(0xFF90CAF9)
}

/** 固定深色调色板（与 Miuix 深色模式视觉一致）。 */
object QuakeColors {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1C1B1F)
    val SurfaceVariant = Color(0xFF26232A)
    val OnSurface = Color(0xFFE6E1E5)
    val OnSurfaceVariant = Color(0xFFC8C4D0)
    val Outline = Color(0xFF8F8A96)
    val Primary = Color(0xFFFF6B6B)
    val Error = Color(0xFFFF5252)
    val Green = Color(0xFF4CAF50)
    val Yellow = Color(0xFFFFC107)
    val Orange = Color(0xFFFF9800)
    val EewOrange = Color(0xFFFF8A65)
}
