import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.XCFramework
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// ---------------------------------------------------------------------------
// 签名配置：优先读环境变量（CI 通过 GitHub Secrets 注入），其次读本地 keystore.properties
// 两者都缺失时，release 构建自动回落到 debug 签名，保证 CI 不会因缺证书而失败。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
fun secret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }

kotlin {
    // Android -------------------------------------------------------------
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Desktop (JVM) -------------------------------------------------------
    jvm("desktop")

    // iOS -----------------------------------------------------------------
    // 注意：miuix 0.9.x 仅发布 iosArm64 + iosSimulatorArm64（无 iosX64 构件），
    // 故移除 iosX64（Intel 模拟器）；Apple Silicon 模拟器走 iosSimulatorArm64。
    // Kotlin 2.4 起 iosX64 已降为 Tier 3 支持。
    // 显式 XCFramework("ComposeApp") 才会生成 assembleComposeAppXCFramework 任务
    val xcf = XCFramework("ComposeApp")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            xcf.add(this)
        }
    }

    // 统一 JDK 17 toolchain，避免 CI 上 JDK 版本漂移
    jvmToolchain(17)

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Compose
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // UI 组件库：Miuix（MIUI / HyperOS 风格，基于 Compose Multiplatform）
            implementation(libs.miuix.ui)

            // MVVM
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            // 并发 / 序列化 / 时间
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // 网络
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            // Ktor Android 引擎
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            // Ktor iOS 引擎（基于 NSURLSession）
            implementation(libs.ktor.client.darwin)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Ktor Desktop 引擎（CIO 对 WebSocket 支持最稳）
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "com.quake.alert"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.quake.alert"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        resources.srcDirs("src/commonMain/resources", "src/androidMain/resources")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        val storePath = secret("KEYSTORE_FILE")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.quake.alert.MainKt"

        nativeDistributions {
            // 注意：Windows 的 Exe 安装包（jpackage --type exe）在裸 CI runner 上
            // 反复 jpackage exit 1（工具 stderr 不落 workflow 日志，根因待查）。
            // 暂只保留 Dmg + Msi（MSI 为 jpackage 最成熟格式），Exe 待定位后恢复。
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "QuakeAlert"
            packageVersion = "1.0.0"
            // 打包元数据保持纯 ASCII：jpackage 在 Windows 上处理非 ASCII
            // 字符（中文/©）会触发 Charset 解码错误（Input length = 1）
            description = "Cross-platform earthquake alert client (Wolfx CENC + USGS multi-source)"
            copyright = "(c) 2026 QuakeAlert. Data courtesy of Wolfx / USGS."
            vendor = "QuakeAlert"

            windows {
                menu = true
                perUserInstall = true
                shortcut = true
                dirChooser = true
            }
            macOS {
                bundleID = "com.quake.alert.desktop"
                dockName = "QuakeAlert"
                // 如需 macOS 公证，在 CI 中注入以下环境变量：
                // NOTARIZATION_APPLE_ID / NOTARIZATION_PASSWORD / NOTARIZATION_TEAM_ID
                val teamId = secret("NOTARIZATION_TEAM_ID")
                if (teamId != null) {
                    signing {
                        sign.set(true)
                        identity.set(secret("NOTARIZATION_IDENTITY") ?: "Developer ID Application")
                    }
                    notarization {
                        appleID.set(secret("NOTARIZATION_APPLE_ID") ?: "")
                        password.set(secret("NOTARIZATION_PASSWORD") ?: "")
                        teamID.set(teamId)
                    }
                }
            }
        }

        // ProGuard 7.7.0 对 Kotlin 2.4 字节码中的 ktor 合成类（SocketBase$attachFor$1）
        // 与 com.jetbrains.JBR 会误报未解析引用并中止打包；用自定义规则忽略这类警告。
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

// ---------------------------------------------------------------------------
// ProGuard 输出 jar 签名清理：
// ProGuard 重写带签名的依赖 jar 后，META-INF 中的 *.SF/*.DSA/*.RSA 等签名
// 条目失效，jpackage 打包 MSI/Exe 时校验失败（exit 1）。在 proguardReleaseJars
// 之后把这些签名条目剔除（与 opendeep/多家项目的成熟做法一致）。
// ---------------------------------------------------------------------------
abstract class StripInvalidJarSignaturesTask : DefaultTask() {
    @get:InputDirectory
    abstract val jarDirectory: DirectoryProperty

    @TaskAction
    fun stripSignatures() {
        val dir = jarDirectory.get().asFile
        if (!dir.exists()) return
        dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", true) }
            .forEach { jarFile ->
                val temp = File.createTempFile("strip-${jarFile.name}", ".jar", jarFile.parentFile)
                try {
                    ZipFile(jarFile).use { zip ->
                        ZipOutputStream(temp.outputStream().buffered()).use { zout ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val name = entry.name
                                val isSignature = name.startsWith("META-INF/SIG-") ||
                                    name.endsWith(".SF") || name.endsWith(".DSA") ||
                                    name.endsWith(".RSA") || name.endsWith(".EC")
                                if (isSignature) continue
                                zout.putNextEntry(ZipEntry(name))
                                if (!entry.isDirectory) zip.getInputStream(entry).copyTo(zout)
                                zout.closeEntry()
                            }
                        }
                    }
                    if (temp.length() >= 0 && !jarFile.delete()) {
                        throw GradleException("无法删除旧 jar: $jarFile")
                    }
                    if (!temp.renameTo(jarFile)) {
                        throw GradleException("无法替换 jar: $jarFile")
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
    }
}

tasks.register<StripInvalidJarSignaturesTask>("stripReleaseJarSignatures") {
    jarDirectory.set(layout.buildDirectory.dir("compose/tmp/main-release/proguard"))
}

// proguardReleaseJars 由 Compose 插件延迟注册，须等所有项目配置完成后再 hook
gradle.projectsEvaluated {
    tasks.named("proguardReleaseJars") {
        finalizedBy("stripReleaseJarSignatures")
    }
}
