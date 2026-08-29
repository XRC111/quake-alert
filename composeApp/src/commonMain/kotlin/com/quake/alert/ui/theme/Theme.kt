package com.quake.alert.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 预警应用默认深色：夜间收到预警时不刺眼，同时省电
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF2B0000),
    primaryContainer = Color(0xFF5C1414),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFFFB4A2),
    onSecondary = Color(0xFF460D00),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF26232A),
    onSurfaceVariant = Color(0xFFC8C4D0),
    error = Color(0xFFFF5252),
    onError = Color(0xFF3B0000),
    outline = Color(0xFF8F8A96),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    background = Color(0xFFFBFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1C1B1F),
    error = Color(0xFFB3261E),
)

@Composable
fun QuakeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content,
    )
}

/** 按震级给出配色，采用中国习惯的"越大越红"。 */
fun magnitudeColor(magnitude: Double): Color = when {
    magnitude >= 6.0 -> Color(0xFFFF1744)
    magnitude >= 5.0 -> Color(0xFFFF5252)
    magnitude >= 4.0 -> Color(0xFFFF8A65)
    magnitude >= 3.0 -> Color(0xFFFFB74D)
    else -> Color(0xFF90CAF9)
}
