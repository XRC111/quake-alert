package com.quake.alert

import androidx.compose.ui.window.ComposeUIViewController
import com.quake.alert.ui.QuakeApp
import platform.UIKit.UIViewController

/**
 * iOS 入口：在 Xcode 工程中 `ComposeViewMainViewController()` 或直接
 * 通过生成的 ComposeApp.framework 调用本函数，把返回值塞进 SwiftUI 的
 * `UIViewControllerRepresentable` 即可。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    QuakeApp()
}
