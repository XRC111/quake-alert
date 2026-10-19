<div align="center">

# QuakeAlert

**跨平台地震预警客户端 —— Kotlin Multiplatform + Compose Multiplatform**

Windows / Android / iOS 三端同构，多源 EEW 聚合 + 全屏强提醒。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-4285F4?style=flat-square)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](./LICENSE)

</div>

---

> ## 重要声明（请务必阅读）
>
> **本项目是纯技术演示与工程实践，用于学习 Kotlin Multiplatform / Compose Multiplatform 架构，不构成任何形式的地震预警服务，也不面向公众发布、推送任何预警信息。**
>
> 1. **请通过官方渠道获取地震预警**：中国地震预警网官方小程序、各省地震局渠道、应急管理部门发布。按《中华人民共和国防震减灾法》及多地方法规，地震预警信息由省级地震主管部门统一发布，第三方须经技术评估并签订协议方可向公众播发。
> 2. 本项目不对数据的及时性、准确性、完整性做任何担保，开发者不对因使用本项目造成的任何损失负责。
> 3. 「测试预警」功能仅用于验证**本机**的通知/声音/震动链路，不代表任何真实预警。
> 4. 代码中的「本地估算」（烈度/倒计时）为工程近似值，**不可作为避险依据**。

---

## 简介

QuakeAlert 从 **Wolfx** 接入 5 路数据（CENC / 四川 / 重庆 / 台湾 CWA 四路 EEW WebSocket + 中国地震台网速报目录轮询），统一映射为 `QuakeEvent`，经跨源同震去重后交给规则引擎；达到阈值时触发全屏闪烁弹窗、报警音与震动。

三端共用 `commonMain` 的业务代码，平台差异通过 `expect/actual` 收敛到声音、震动、亮屏、通知与窗口置顶。

## 功能特性

| 能力 | 说明 |
| --- | --- |
| 多源聚合 | 4 路 EEW WebSocket + 1 路速报目录轮询 |
| 同震去重 | 发震时刻 ±2 min、震中距 ≤35 km 合并；`mergedSources` 记录多源冗余 |
| 多报覆盖 | 同一事件后续报就地更新，列表不膨胀 |
| 规则引擎 | 所在地预测烈度 ≥ 阈值触发；无烈度字段时按震级降级判定 |
| 弹窗去重 | 30 分钟窗口内同 ID 抑制；烈度/震级上调 ≥0.5 允许升级复报（可关） |
| 全屏预警 | 红色闪烁背景，返回键/点击外部均不可关闭 |
| 三端提醒 | 报警音 + 震动 + 亮屏 + 系统通知 + 桌面置顶 |
| 事件历史 | 实时列表最近 50 条，历史 Tab 保留最近 500 条 |
| 触发记录 | 每次弹窗自动归档（时间/震级/地点/确认状态） |
| 测试预警 | 一键伪造「烈度 V 度 + 剩余 30 秒」事件走完整链路 |
| 连接自愈 | 指数退避 + 抖动重连，心跳看门狗 90 s 无帧主动断开 |
| 本地估算 | 默认关闭；开启并填写观测点坐标后估算烈度与 S 波倒计时 |

## 快速开始

### 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | **17**（AGP 8.7 要求，更高版本无法跑 Android 构建） |
| Android Studio | Ladybug 或更新（SDK 35 + Build Tools） |
| Xcode | 15+（仅 iOS，需 macOS） |

```bash
git clone https://github.com/XRC111/quake-alert.git
cd quake-alert

# 桌面端（最快，无需 Android SDK / Xcode）
./gradlew :composeApp:run

# Android
./gradlew :composeApp:assembleDebug

# iOS：先生成 XCFramework，再用 Xcode 打开 iosApp
./gradlew :composeApp:assembleComposeAppXCFramework
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

### 桌面端打包

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
./gradlew :composeApp:packageReleaseDmg                          # macOS
./gradlew :composeApp:packageReleaseMsi :composeApp:packageReleaseExe  # Windows
```

产物在 `composeApp/build/compose/binaries/main-release/`。

### 签名

本地：复制 `keystore.properties.example` 为 `keystore.properties`（已 gitignore）。

CI：在仓库 Secrets 中配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，macOS 公证另配三个 `NOTARIZATION_*`。**未配置签名时 release 自动回落 debug 签名**，流水线不会因此失败。

## 技术选型

| 领域 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1.20 |
| UI | Miuix 0.9.2（MIUI / HyperOS 风格） |
| 架构 | MVVM（JetBrains 多平台 ViewModel） |
| 网络 | Ktor Client 3.1.0（Android→OkHttp / iOS→Darwin / Desktop→CIO） |
| 并发 | Coroutines + StateFlow / SharedFlow |
| 序列化 | kotlinx-serialization-json |
| 构建 | Gradle 8.11.1 + Version Catalog |

## 架构

```
EEW WebSocket ×4（CENC / 四川 / 重庆 / 台湾 CWA）
   │  独立协程 + 指数退避重连 + 心跳看门狗
   ▼
QuakeAggregator ──EventDeduplicator──→ history（实时 50 条）
   │                （同震合并）         └→ fullHistory（历史 500 条）
   │                    │
   │                    └→ events（增量，一次性消费）
   ▼
AlertTrigger（烈度优先 + 30 min 去重，仅 EEW 参与）──→ 全屏弹窗
   │                                                    └→ 触发记录
速报目录（HTTP 30 s 轮询）──→ 同震合并进列表（isEew=false，不弹窗）
```

### 目录结构

```
composeApp/src/
├── commonMain/kotlin/com/quake/alert/
│   ├── model/          # QuakeEvent / QuakeSource / AlertLevel
│   ├── data/           # 聚合器、去重、解码器
│   ├── alert/          # 规则引擎、本地估算
│   ├── settings/       # 用户设置
│   ├── platform/       # expect：声音/震动/亮屏/通知/置顶
│   └── ui/             # 主界面、设置面板、全屏弹窗
├── androidMain/        # MediaPlayer / Vibrator / WakeLock / 通知
├── iosMain/            # AVAudioPlayer / UNNotification
└── desktopMain/        # Toolkit.beep / 置顶窗口
```

### 报警判定

烈度取值优先级：

1. 本地估算烈度（开启估算且填写坐标时）
2. API 下发的 `MaxIntensity`（保守上界）
3. 都没有 → 按震级降级（默认 M3.0，`reason` 标注"降级"）

### 倒计时

**只信 API**：`remainTimeSec` → `arrivalTime` − 当前时间 → 都没有则显示"未知"。客户端不做 P/S 波走时估算（可选本地估算除外，默认关闭）。

## 数据源

| 源 | 端点 | 状态 |
| --- | --- | --- |
| 中国地震台网 EEW | `cenc_eew` | 已接入 |
| 四川省地震局 | `sc_eew` | 已接入 |
| 重庆市地震局 | `cq_eew` | 已接入 |
| 台湾 CWA | `cwa_eew` | 已接入 |
| 中国地震台网速报目录 | `cenc_eqlist` | 已接入（不弹窗） |
| 福建省地震局 | `fj_eew` | 停更，未接入 |

> 实测要点：中国区源**均不含到达时间字段**，倒计时默认"未知"；`sc/cq` 把震级拼写为 `Magunitude`、CWA 的 `MaxIntensity` 是字符串，解码器已做多别名兼容。
>
> 本项目只接入省级/国家级官方源，不接入任何第三方自建预警系统。

## 设置

| 设置项 | 说明 |
| --- | --- |
| 烈度阈值 | 触发报警的最低预测烈度 |
| 降级震级阈值 | 无烈度字段时的兜底阈值 |
| 去重窗口 | 默认 30 分钟 |
| 报警音 / 震动 | 独立开关 |
| 本地估算 + 坐标 | 默认关闭 |

修改即时生效（StateFlow 热更新），当前为内存态，重启恢复默认。

## CI / CD

| Job | Runner | 产物 |
| --- | --- | --- |
| `build` | `macos-latest` | Android APK + AAB、iOS XCFramework、DMG |
| `build-windows` | `windows-latest` | Msi + Exe |

推送 main / 打 `v*` tag / PR / 手动触发；打 tag 自动创建 Release 并挂载产物。

## 已知限制

- **后台保活**：聚合器随 ViewModel 生命周期运行，App 被回收后无法拉流，生产方案需前台服务
- **设置持久化**：内存态，重启恢复默认
- **倒计时**：中国区源无到达时间字段，未开启估算时显示"未知"
- **本地估算精度**：Kawasumi 衰减式未考虑场地放大/方向性，仅供参考
- **多语言**：当前硬编码中文

## License

MIT（见 [LICENSE](./LICENSE)）

## 致谢

数据源：[Wolfx](https://wolfx.jp)（CENC / 四川 / 重庆 / CWA EEW 推送）。
UI 组件库：[Miuix](https://github.com/compose-miuix-ui/miuix)。

二次分发请保留数据源致谢与免责声明。
