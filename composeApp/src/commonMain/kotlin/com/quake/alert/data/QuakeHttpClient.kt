package com.quake.alert.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.websocket.WebSockets

/**
 * 构造聚合器使用的 HttpClient。
 *
 * 注意：**不显式指定 engine**，由 Ktor 依据各平台 sourceSet 中引入的引擎依赖自动选择
 * （androidMain → OkHttp，iosMain → Darwin，desktopMain → CIO）。
 *
 * 特别说明：`socketTimeoutMillis` 故意留空。全局 socket 超时会作用到 WebSocket 会话上，
 * 导致长连接被无脑掐断；WebSocket 的存活性由 [QuakeAggregator] 内部的心跳看门狗负责。
 */
fun createQuakeHttpClient(
    config: AggregatorConfig = AggregatorConfig(),
    enableLogging: Boolean = true,
): HttpClient = HttpClient {
    install(WebSockets) {
        pingIntervalMillis = config.wsPingIntervalMs
        maxFrameSize = Long.MAX_VALUE
    }

    install(HttpTimeout) {
        requestTimeoutMillis = config.httpTimeoutMs
        connectTimeoutMillis = config.httpTimeoutMs
    }

    if (enableLogging) {
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    config.logger("[Ktor] $message")
                }
            }
        }
    }
}
