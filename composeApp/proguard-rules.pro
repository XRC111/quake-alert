# ---------------------------------------------------------------------------
# QuakeAlert 混淆规则
# ---------------------------------------------------------------------------

# androidx.startup：Initializer 经 manifest meta-data 类名字符串反射实例化，
# R8 无法追踪反射，混淆/裁剪后 InitializationProvider 启动即
# ClassNotFoundException（打开闪退）。官方规则是整包 keep。
-keep class androidx.startup.** { *; }
# EmojiCompatInitializer / ProfileInstallerInitializer 也走 startup 链
-keep class androidx.emoji2.** { *; }
-keep class androidx.profileinstaller.** { *; }
# androidx.core.CoreComponentFactory（manifest 引用，保险）
-keep class androidx.core.app.CoreComponentFactory { *; }

# Ktor：保留 kotlinx.serialization 生成的序列化器与 ServiceLoader 入口
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class kotlinx.serialization.** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.quake.alert.**$$serializer { *; }
-keepclassmembers class com.quake.alert.** {
    *** Companion;
}
-keepclasseswithmembers class com.quake.alert.** {
    <init>(...);
}

# kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# kotlinx-datetime
-dontwarn java.time.**

# OkHttp / CIO
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
