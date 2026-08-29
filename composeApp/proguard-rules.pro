# ---------------------------------------------------------------------------
# QuakeAlert 混淆规则
# ---------------------------------------------------------------------------

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
