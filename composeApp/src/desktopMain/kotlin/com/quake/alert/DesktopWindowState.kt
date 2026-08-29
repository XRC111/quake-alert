package com.quake.alert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面端窗口置顶状态的桥接层。
 *
 * `AlertEffects.setWindowAlwaysOnTop()` 是同步调用，拿不到 Compose 的重组上下文，
 * 因此这里用一个 StateFlow 中转：desktopMain 的 `main()` 把它绑定到
 * `Window(alwaysOnTop = ...)`，实现"预警时自动置顶、确认后恢复"。
 */
object DesktopWindowState {

    private val _alwaysOnTop = MutableStateFlow(false)
    val alwaysOnTop: StateFlow<Boolean> = _alwaysOnTop.asStateFlow()

    fun setAlwaysOnTop(enabled: Boolean) {
        _alwaysOnTop.value = enabled
    }
}
