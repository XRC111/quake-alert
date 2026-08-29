package com.quake.alert

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.quake.alert.ui.QuakeApp
import com.quake.alert.ui.QuakeViewModel

fun main() {
    println("[QuakeAlert] 桌面端启动：报警由 API 下发的所在地烈度决定，倒计时以 API 字段为准")

    application {
        val alwaysOnTop by DesktopWindowState.alwaysOnTop.collectAsState()
        val windowState = rememberWindowState(
            size = DpSize(width = 520.dp, height = 900.dp),
        )

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "QuakeAlert · 地震预警",
            alwaysOnTop = alwaysOnTop,
        ) {
            QuakeApp(viewModel = remember { QuakeViewModel() })
        }
    }
}
