# QuakeAlert · 跨平台地震预警客户端

基于 **Kotlin Multiplatform + Compose Multiplatform** 的实时地震预警客户端，覆盖
**Windows / Android / iOS** 三端。数据源为 **Wolfx 中国地震预警网（CENC）EEW WebSocket 推送**，
达到阈值时触发**全屏强提醒弹窗 + 报警音 + 震动**，并通过 GitHub Actions 在 `macos-latest` 上
一次性产出三端安装包。

> ⚠️ 免责声明：本项目是**技术演示与工程实践**，用于学习 KMP / Compose Multiplatform 架构。
> 地震预警关乎生命安全，实际避险请以**中国地震预警网官方 App（地震预警小程序 / 各省地震局渠道）**
> 与当地应急管理部门的发布为准。本项目不对预警的及时性、准确性与完整性做任何担保。

---

## 1. 功能矩阵

| 能力 | 说明 | 状态 |
| --- | --- | --- |
| 数据源 | **5 路**：CENC / 四川 / 重庆 / 台湾 CWA 四路 EEW WebSocket + 中国地震台网速报目录轮询 | ✅ |
| 跨源同震去重 | 发震时刻 ±2min + 震中距 ≤35km 合并为一条，EEW 多报就地覆盖，`mergedSources` 记录多源冗余 | ✅ |
| 事件历史 | 内存保留最近 500 条，「事件历史」Tab 可回看（实时列表仍为最近 50 条） | ✅ |
| 统一数据模型 | 推送映射为 `QuakeEvent`（EEW 多报就地覆盖） | ✅ |
| 规则引擎 | **所在地预测烈度 ≥ 阈值触发**（API `Intensity` 字段，0~12 度）；无烈度字段时降级按震级判定 | ✅ |
| 倒计时 | **以 API 为准**（`RemainTime` / `ArrivalTime`）；可选本地估算兜底（默认关闭） | ✅ |
| 30 分钟去重 | 同 ID 抑制重复弹窗，支持烈度/震级升级复报（可关） | ✅ |
| 事件列表 | 最近 50 条，时间倒序 | ✅ |
| 触发记录 | 每次弹窗自动归档（时间/震级/地点/确认状态） | ✅ |
| 全屏预警弹窗 | 红色闪烁背景，返回键/点击外部均不可关闭 | ✅ |
| 桌面置顶 | 预警时 `Window(alwaysOnTop = true)` | ✅ |
| 移动端亮屏 | Android `FLAG_TURN_SCREEN_ON` + WakeLock；iOS `idleTimerDisabled` | ✅ |
| 报警音 | 桌面 `Toolkit.beep()` + 合成警笛；Android `MediaPlayer`/`ToneGenerator`；iOS `AVAudioPlayer` | ✅ |
| 系统通知 | Android 全屏通知；iOS `UNUserNotificationCenter` | ✅ |
| 测试预警 | 一键伪造"烈度 V 度 + API 剩余时间"事件走完整链路，验证弹窗/声音/震动 | ✅ |
| 设置面板 | 烈度阈值 / 降级震级阈值 / 去重窗口 / 报警音 / 震动 / 本地估算开关+坐标，实时生效 | ✅ |
| 本地估算 | **默认关闭**；开启并填观测点坐标后，估算所在地烈度与 S 波到达倒计时 | ✅ |
| 连接自愈 | 指数退避 + 抖动重连、心跳看门狗（90s 无帧主动断开） | ✅ |
| CI/CD | `macos-latest` 出 APK/AAB + XCFramework + DMG，Windows Runner 出 Msi/Exe | ✅ |

---

## 2. 技术选型

| 领域 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin | 2.1.20 |
| UI | Compose Multiplatform + Material 3 | 1.8.0 |
| 架构 | MVVM（JetBrains 多平台 `lifecycle-viewmodel-compose`） | 2.8.7 |
| 网络 | Ktor Client（WebSockets / HttpTimeout / Logging） | 3.1.0 |
| 并发 | Coroutines + SharedFlow / StateFlow | 1.10.2 |
| 序列化 | kotlinx-serialization-json（Wolfx 报文手解） | 1.8.1 |
| 时间 | kotlinx-datetime | 0.6.2 |
| 构建 | Gradle Kotlin DSL + Version Catalog | 8.11.1 |
| Android | AGP / compileSdk 35 / minSdk 24 | 8.7.3 |

引擎选择：Android → OkHttp，iOS → Darwin，Desktop → CIO。三者均由 Ktor 按 sourceSet 依赖自动装载。

---

## 3. 项目结构

```
earthquake/
├── settings.gradle.kts                # 仓库声明 + 版本目录
├── build.gradle.kts                   # 根工程：只声明插件版本
├── gradle/libs.versions.toml          # 统一版本目录（Version Catalog）
├── keystore.properties.example        # 签名配置模板
└── composeApp/
    ├── build.gradle.kts               # KMP 三端目标 + 签名 + 桌面打包
    ├── proguard-rules.pro
    └── src/
        ├── commonMain/kotlin/com/quake/alert/
        │   ├── model/QuakeEvent.kt            # QuakeEvent / QuakeSource / AlertLevel
        │   ├── data/
        │   │   ├── QuakeAggregator.kt         # ★ 多源聚合器（4×WS + 目录轮询 + 看门狗）
        │   │   ├── EventDeduplicator.kt       # 跨源同震去重（时间/距离容差 + EEW 多报覆盖）
        │   │   ├── QuakeHttpClient.kt         # Ktor 客户端工厂
        │   │   ├── AggregatorConfig / SourceStatus
        │   │   └── source/
        │   │       ├── WolfxEewDecoder.kt     # EEW 解码（多源字段别名 + 心跳/取消帧过滤）
        │   │       └── CencEqListDecoder.kt   # 速报目录解码（HTTP 轮询，isEew=false）
        │   ├── alert/
        │   │   ├── AlertTrigger.kt            # 规则引擎接口 + 配置（烈度优先）+ 历史条目
        │   │   ├── DefaultAlertTrigger.kt     # 烈度阈值 + 30min 去重 + API/估算倒计时
        │   │   └── LocalEstimator.kt          # 可选本地估算（走时差 + Kawasumi 衰减，默认关）
        │   ├── settings/
        │   │   └── AppSettings.kt             # 用户设置（烈度阈值/声音/震动）
        │   ├── platform/
        │   │   └── AlertEffects.kt            # expect：声音/震动/亮屏/通知/置顶
        │   ├── ui/
        │   │   ├── QuakeApp.kt                # 状态条 + 操作行 + 双 Tab 列表
        │   │   ├── SettingsDialog.kt          # 设置面板
        │   │   ├── AlertOverlay.kt            # 全屏闪烁预警弹窗
        │   │   ├── QuakeViewModel.kt          # MVVM 桥接 + 触发历史 + 测试预警
        │   │   └── theme/Theme.kt             # Material 3 深色主题
        │   └── util/                          # 时间/数值格式化（common 无 String.format）
        ├── androidMain/                       # MediaPlayer / Vibrator / WakeLock / 全屏通知
        ├── iosMain/                           # AVAudioPlayer / AudioServices / UNNotification
        ├── desktopMain/                       # Toolkit.beep / SourceDataLine / alwaysOnTop
        └── commonTest/
```

---

## 4. 核心设计

### 4.1 数据流

```
EEW WebSocket ×4（CENC / 四川 / 重庆 / 台湾 CWA）
   │  各自独立协程 + 指数退避重连 + 心跳看门狗（90s 无帧主动断开）
   ▼
QuakeAggregator ──EventDeduplicator──→ history: StateFlow（实时 50 条）
   │                （同震合并）         └─→ fullHistory: StateFlow（历史 500 条）→ 事件历史 Tab
   │                    │
   │                    └─→ events: SharedFlow（增量，一次性消费）
   ▼
AlertTrigger（烈度优先 + 30min 去重，仅 EEW 事件参与）──→ activeAlert ──→ 全屏弹窗
   │                                                          └─→ 触发记录 Tab

速报目录 cenc_eqlist（HTTP 30s 轮询）──→ 同震合并进列表（isEew=false，不触发弹窗）
```

### 4.2 并发与容错

- **独立作用域**：数据链路跑在 `SupervisorJob` 下，与调用方作用域解耦，`stop()` / `close()` 幂等。
- **指数退避 + 抖动**：`base * 2^attempt`，上限 60s，叠加 ±30% 抖动，避免多端同时重连打爆上游。
  仅拦截业务异常，`CancellationException` 原样抛出以保证协程取消语义。
- **心跳看门狗**：90s 内没收到任何帧（含心跳）即判定为"TCP 还连着但服务端已哑火"，主动 `close()` 重连。
- **socket 超时刻意不设**：全局 `socketTimeoutMillis` 会作用到 WebSocket 会话上导致长连接被掐断，
  它的存活性交给看门狗负责。

### 4.3 去重策略（两处，各管一段）

| 位置 | 作用 | 行为 |
| --- | --- | --- |
| `Aggregator.onEvent` 列表更新 | EEW 多报去重 | 同 ID 就地覆盖而非追加，列表不膨胀 |
| `DefaultAlertTrigger.history` | **弹窗去重** | 30 分钟窗口内同 ID 抑制弹窗；烈度/震级上调 ≥ 0.5 时允许升级复报（设置面板可关，关闭后严格 30 分钟一次） |

### 4.4 报警判定：烈度优先 + 降级

报警与否由**所在地预测烈度**决定，烈度取值优先级：

```
1. 本地估算烈度（用户开启「本地估算」且填写观测点坐标时，最贴合"所在地烈度"）
2. API 下发的烈度（QuakeEvent.intensity，即 MaxIntensity 最大预测烈度，保守上界）
3. 都没有 → 降级按 magnitude >= fallbackMagnitudeThreshold（默认 M3.0）判定（reason 标注"降级"）
```

`SettingsStore`（StateFlow）+ `AlertTrigger.rules` 热更新：滑动阈值滑杆的瞬间，规则引擎的
判定阈值就已改变，无需重启。**当前设置为内存态**，跨启动持久化需接入平台键值存储（README §10 已注明接入点）。

### 4.5 可选本地估算（默认关闭）

背景：实测确认中国区 CENC / sc（成都）两个源均不含到达时间字段，社区实现
（ClassIsland 插件等）都是拿用户坐标自算。本项目遵循"以 API 为准"原则，因此估算**默认关闭**：

- 关闭（默认）：倒计时只信 API 字段，无则显示"未知"；
- 开启且填写观测点坐标后：
  - 本地烈度估算：Kawasumi 简化衰减式 `I = 1.5·M − 3.4·log₁₀(R) + 4.6`，clamp 0~12 度，**参与报警判定**；
  - S 波倒计时估算：走时差 `R × (1/3.5 − 1/6.0) − 已流逝`，仅在 API 无到达时间字段时兜底。

实现位于 `alert/LocalEstimator.kt`（纯函数，可单测）。⚠️ 均为工程近似，替代不了官方烈度速报。

### 4.5 测试预警

`QuakeViewModel.simulateAlert()` 伪造一条"烈度 V 度 + API 剩余 30 秒"的事件直接喂给规则引擎——
不污染事件列表，但会走完整链路：全屏弹窗 + 声音 + 震动 + 通知 + 触发记录归档。用于验证三端报警链路。

### 4.6 expect / actual 边界

```kotlin
// commonMain
expect class AlertEffects() {
    fun playAlarm(); fun stopAlarm(); fun vibrate()
    fun wakeScreen(); fun setWindowAlwaysOnTop(enabled: Boolean)
    fun showNotification(title: String, text: String); fun cancelNotification()
    fun dispose()
}
```

| 能力 | Android | iOS | Desktop |
| --- | --- | --- | --- |
| 报警音 | `MediaPlayer`（`res/raw/alarm.wav`）→ 回落 `ToneGenerator` 循环 | `AVAudioPlayer`（bundle `alarm.wav`）→ 回落 `AudioServicesPlaySystemSound(1005)` | `Toolkit.beep()` + `SourceDataLine` 合成 880/1320Hz 警笛，支持 `resources/alarm.wav` |
| 震动 | `Vibrator` / `VibratorManager` 波形 | `kSystemSoundID_Vibrate` | 三声短促蜂鸣替代 |
| 亮屏 | `FLAG_TURN_SCREEN_ON` + `FLAG_SHOW_WHEN_LOCKED` + WakeLock | `UIApplication.idleTimerDisabled` | 无息屏概念，改为置顶 |
| 通知 | `NotificationCompat` + FullScreenIntent | `UNUserNotificationCenter` | 预留（可接 `SystemTray`） |
| 置顶 | 无对应能力（用亮屏 + 全屏通知替代） | 无对应能力 | `DesktopWindowState` → `Window(alwaysOnTop)` |

> 所有 actual 方法都用 `runCatching` 兜底并**永不抛异常**——预警链路上一次崩溃，比没声音严重得多。

### 4.7 倒计时：以 API 为准

倒计时**只信 API 字段**，客户端不做任何 P/S 波走时之类的自行估算：

```
1. event.remainTimeSec（API 下发的剩余秒数）→ 直接使用
2. event.arrivalTime（API 下发的预计到达时刻）→ 减去当前时间
3. 两者都没有 → null，UI 显示"未知"
```

相应地，`QuakeEvent` 增加了 `intensity` / `arrivalTime` / `remainTimeSec` 三个字段，
`WolfxEewDecoder` 以宽容读取方式解析这些字段的多种命名变体。

---

## 5. 环境搭建

### 5.1 前置依赖

| 组件 | 版本要求 | 备注 |
| --- | --- | --- |
| JDK | **17**（必须） | AGP 8.7 不支持更高版本跑 Android 构建；Gradle 会自动按 `jvmToolchain(17)` 解析 |
| Android Studio | Ladybug 或更新 | 需要 Android SDK 35 + Build Tools |
| Xcode | 15+ | 仅 iOS 构建需要，且只能在 macOS 上跑 |
| Kotlin Multiplatform 插件 | 随 AS 安装 | 或 `KDoctor` 校验环境 |

环境自检（推荐）：

```bash
brew install kdoctor      # macOS
kdoctor                   # 会逐项检查 JDK / Android SDK / Xcode / CocoaPods
```

### 5.2 快速开始

```bash
git clone <your-repo-url> earthquake
cd earthquake

# 桌面端（最快，无需 Android SDK / Xcode）
./gradlew :composeApp:run

# Android
./gradlew :composeApp:assembleDebug
# 或直接用 Android Studio 打开工程，选择 composeApp 配置运行

# iOS：先生成 framework，再用 Xcode 打开 iosApp 工程
./gradlew :composeApp:assembleComposeAppXCFramework
```

Windows 上把 `./gradlew` 换成 `gradlew.bat`。

### 5.3 桌面端打包

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS   # 当前系统对应的格式
./gradlew :composeApp:packageReleaseDmg                        # 仅 DMG（需 macOS）
./gradlew :composeApp:packageReleaseMsi :composeApp:packageReleaseExe  # 仅 Windows
```

产物位于 `composeApp/build/compose/binaries/main-release/`。

### 5.4 桌面端打包产物说明

桌面端不需要注入观测点坐标——报警阈值由 API 下发的所在地烈度决定，倒计时以 API 字段为准，
客户端不再自行估算，因此也不再依赖任何定位能力。

---

## 6. 签名与 Secrets

本地构建：复制 `keystore.properties.example` 为 `keystore.properties` 并填入值（已 gitignore）。

CI 构建：在仓库 **Settings → Secrets and variables → Actions** 中添加：

| Secret | 类型 | 说明 |
| --- | --- | --- |
| `KEYSTORE_BASE64` | 必选（出 release 包时） | `base64 -i quake.jks \| pbcopy` 的内容 |
| `KEYSTORE_PASSWORD` | 必选 | keystore 密码 |
| `KEY_ALIAS` | 必选 | 密钥别名 |
| `KEY_PASSWORD` | 必选 | 密钥密码 |
| `NOTARIZATION_TEAM_ID` | 可选 | macOS 公证 Team ID |
| `NOTARIZATION_APPLE_ID` | 可选 | Apple ID |
| `NOTARIZATION_PASSWORD` | 可选 | App 专用密码 |
| `NOTARIZATION_IDENTITY` | 可选 | 签名证书身份 |

生成 keystore：

```bash
keytool -genkeypair -v \
  -keystore quake.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias quake
base64 -i quake.jks | pbcopy    # 粘贴到 KEYSTORE_BASE64
```

**未配置签名时 release 构建会自动回落到 debug 签名**，保证流水线不会因为缺证书而直接失败。

---

## 7. CI / CD

`.github/workflows/build.yml` 的两个 job：

| Job | Runner | 产物 |
| --- | --- | --- |
| `build` | `macos-latest` | Android APK + AAB、iOS XCFramework、桌面 DMG |
| `build-windows` | `windows-latest` | 桌面 Msi + Exe |

触发条件：`push` 到 `main`/`master`/`develop`、打 `v*` tag、PR、手动 `workflow_dispatch`
（可勾选是否编译 iOS）。打 tag 时自动创建 GitHub Release 并挂载三端产物。

产物下载位置：Actions 运行页底部的 Artifacts（`android` / `ios-xcframework` / `desktop-macos` / `desktop-windows`）。

---

## 8. 调参入口

| 想改什么 | 改哪里 |
| --- | --- |
| 重连策略 / 列表长度 / 数据源地址 | `AggregatorConfig`（`data/QuakeAggregator.kt`） |
| 烈度阈值 / 降级震级阈值 / 去重窗口 / 本地估算（运行时） | App 内「设置」面板（`ui/SettingsDialog.kt`） |
| 烈度阈值 / 降级震级阈值 / 去重窗口（默认值） | `AlertRuleConfig`（`alert/AlertTrigger.kt`）+ `AppSettings` |
| 本地估算模型（走时差 / 烈度衰减式） | `alert/LocalEstimator.kt` |
| 烈度 / 到达时间字段解析 | `data/source/WolfxEewDecoder.kt`（`Intensity` / `ArrivalTime` / `RemainTime` 别名表） |
| 主题配色 | `ui/theme/Theme.kt` |
| 报警音 | 放入 `androidMain/res/raw/alarm.wav`、`desktopMain/resources/alarm.wav`、iOS bundle `alarm.wav`；缺失时自动回落合成音 |
| 设置持久化 | `settings/AppSettings.kt` 顶部注释中的平台键值存储接入点 |

---

## 9. 数据源致谢

- **Wolfx 地震预警**（[wolfx.jp](https://wolfx.jp)）— 提供中国地震预警网（CENC）的 EEW WebSocket 推送
  `wss://ws-api.wolfx.jp/cenc_eew`。感谢 Wolfx 团队面向开发者社区开放实时数据。

同时在早期版本中验证过 **USGS Earthquake Hazards Program**（earthquake.usgs.gov）的 GeoJSON feed
聚合能力；当前版本为聚焦单源，已移除 USGS 轮询与解码代码。

请在二次分发时保留本节的致谢声明。

---

## 9.5 数据源实测记录（2026-08-29）

本机实测（Node WebSocket 直连 + `GET https://api.wolfx.jp/cenc_eew.json`）：

**① WebSocket 连接与心跳**：`wss://ws-api.wolfx.jp/cenc_eew` 连接正常，周期性收到小写 `type` 心跳帧：

```json
{"type":"heartbeat","ver":22,"id":"2778613","timestamp":1788008050926}
```

**② 真实 CENC EEW 事件报文**（新疆阿克苏地区温宿县 M4.0，第 3 报）：

```json
{"ID":"b4dmzcqoricyy","EventID":"202608290607.0001","ReportTime":"2026-08-29 06:07:28",
 "ReportNum":3,"OriginTime":"2026-08-29 06:07:28","HypoCenter":"新疆阿克苏地区温宿县",
 "Latitude":42.2,"Longitude":80.502,"Magnitude":4.0,"Depth":null,"MaxIntensity":5.1}
```

**字段与解码器对照**：

| 报文字段 | 值 | 解码器映射 | 状态 |
| --- | --- | --- | --- |
| `ID` / `EventID` | `b4dmzcqoricyy` / `202608290607.0001` | 事件 id | ✅ |
| `HypoCenter` | 新疆阿克苏地区温宿县 | `placeName` | ✅（本轮实测后补充的别名） |
| `ReportNum` | 3 | `updateSerial`（第几报） | ✅ |
| `OriginTime` | 2026-08-29 06:07:28 | `originTime` | ✅ |
| `Latitude` / `Longitude` | 42.2 / 80.502 | 坐标 | ✅ |
| `Magnitude` | 4.0 | `magnitude` | ✅ |
| `Depth` | null | `depthKm`（null 兼容） | ✅ |
| `MaxIntensity` | 5.1 | `intensity`（烈度判定） | ✅ |
| `ArrivalTime` / `RemainTime` | — | `arrivalTime` / `remainTimeSec` | ⚠️ 本次报文**未携带** |

**③ 六个源实测清单（48h 活跃度，已全部验证）**：

| 截图源 | 正确端点 | HTTP | 最新 ReportTime | 状态 |
| --- | --- | --- | --- | --- |
| 中国地震台网 地震预警 | `cenc_eew.json` | 200 | 2026-08-29 06:07 | ✅ 已接入 |
| 四川省地震局 地震预警 | `sc_eew.json` | 200 | 2026-08-28 13:13 | ✅ 已接入 |
| 重庆市地震局 地震预警 | `cq_eew.json` | 200 | 2026-08-28 13:13 | ✅ 已接入 |
| CWA 地震预警 | `cwa_eew.json` | 200 | 2026-08-28 10:21 | ✅ 已接入 |
| 中国地震台网 地震信息 | `cenc_eqlist.json` | 200 | 2026-08-29 06:36 | ✅ 已接入（速报目录） |
| 福建省地震局 地震预警 | `fj_eew.json` | 200 | 2026-05-14 | ❌ 停更 3 个月（未接入） |

**同震去重示例**：2026-08-28 四川内江隆昌地震，`sc_eew` 报 M5.2、`cq_eew` 报 M4.6——发震时刻同为 13:13、震中距 < 1km，由 [EventDeduplicator] 合并为一条（`mergedSources` 记录两个源）。

**④ 三个必须知道的现实**：

1. **烈度字段语义**：CENC 下发的是 `MaxIntensity`（**最大预测烈度**，如 5.1 度），并非"逐地点烈度表"。
   规则引擎的烈度取值优先级：本地估算（开启时）> API MaxIntensity > 震级降级。
2. **到达时间字段：中国区源都不含**。实测对照：
   - `cenc_eew` / `sc_eew` / `cq_eew` / `cwa_eew`：均无 `ArrivalTime` / `RemainTime` / `S_P_Time`
     （sc/fj 等源把震级拼写为 `Magunitude`，cwa 的 `MaxIntensity` 为字符串，均已兼容）
   - 只有 `jma_eew`（日本气象厅源）报文带"主要動到達予測時刻"（在区域列表 `WarnArea` 中）
   按"以 API 为准、不做自行估算"的原则，中国区事件倒计时默认显示"未知"。
3. **第三方预警资质（2026-07-22 事件）**：成都高新减灾研究所冒用"中国地震预警网"名义
   在 2026-08-24 长宁地震时违规播发自建系统生成的"7.7 级"假预警（官方实为 5.4 级），
   此前四川省地震局已于 **2026-07-22 终止**其播发授权，中国地震局 8-26 公开回应要求第三方须签约播发。
   **本项目接入的全是省级/国家级官方源（CENC / 四川 / 重庆 / 台湾 CWA），不接入任何第三方自建预警。**

**⑤ 社区实现均为自算**：调研 ClassIsland 地震预警插件
   （[denglihong2007/EarthquakeWarningForClassIsLand](https://github.com/denglihong2007/EarthquakeWarningForClassIsLand)，
   数据源为 `sc_eew.json` 每秒轮询）源码确认——其倒计时与本地烈度均由
   `HuaniaEarthQuakeCalculator.GetCountDownSeconds(depth, distance)` / `GetIntensity(magnitude, distance)`
   自行估算（用户配置经纬度 → 震中距 → 模型换算），而非 API 字段。
   本项目以"可选本地估算"（默认关闭）方式提供同等能力，尊重"以 API 为准"的默认行为。

---

## 10. 已知限制与后续方向

- **后台保活**：Android 端 `QuakeAggregator` 随 ViewModel 生命周期运行，App 被系统回收后无法拉流。
  生产方案需要前台服务（`ForegroundService` + `startForeground`）常驻。
- **设置持久化**：当前为内存态，重启即恢复默认；接入点见 `settings/AppSettings.kt`。
- **倒计时默认"未知"**：中国区 CENC 源无到达时间字段，未开启本地估算时倒计时显示"未知"；
  开启估算（需填观测点坐标）后由走时差模型补上，但属于近似值。
- **本地估算精度**：Kawasumi 衰减式未考虑场地放大/方向性，仅供预警参考。
- **烈度字段依赖上游**：CENC EEW 报文是否携带 `Intensity` / `ArrivalTime` / `RemainTime` 取决于上游；
  未携带时自动降级为震级判定。上线前请用真实报文核对字段名（`WolfxEewDecoder` 已做多别名宽容读取）。
- **多语言**：目前硬编码中文，可接入 Compose Resources 的 `strings.xml` 做 i18n。
- **测试**：`commonTest` 已覆盖 `WolfxEewDecoder`（真实报文/别名/畸形/烈度与到达时间）、
  `DefaultAlertTrigger`（烈度优先/降级/去重/升级复报/API 倒计时/本地估算集成）、`LocalEstimator`；
  运行：`./gradlew :composeApp:testDebugUnitTest`。
