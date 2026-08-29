package com.quake.alert.data

import com.quake.alert.model.QuakeSource

enum class ConnectionState {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Error,
}

/** 单个数据源的运行状态，UI 顶部状态条直接消费。 */
data class SourceStatus(
    val source: QuakeSource,
    val state: ConnectionState,
    val lastError: String? = null,
    val lastMessageAt: Long? = null,
    val reconnectAttempt: Int = 0,
) {
    val isHealthy: Boolean get() = state == ConnectionState.Connected
}
