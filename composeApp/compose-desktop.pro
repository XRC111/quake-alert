# Compose Desktop release ProGuard 附加规则
#
# 背景：Compose Multiplatform 桌面 release 打包默认启用 ProGuard 7.7.0。
# 对 Kotlin 2.4 字节码，ProGuard 7.7.0 会误报以下未解析引用并在
# proguardReleaseJars 阶段以 exit 1 中止：
#   - io.ktor.network.sockets.SocketBase$attachFor$1（inline/atomicfu 合成类，
#     找不到 enclosing method attachFor）→ 属于 program class member 未解析；
#   - com.jetbrains.JBR 对 java.lang.invoke.MethodHandle 的方法引用
#     （JBR 仅存在于 JetBrains Runtime，运行时并不使用）。
# 这两类均为已知误报，不影响产物运行，故忽略警告使其继续。

-ignorewarnings

# 可选集成库缺失类提示（未使用，直接忽略）
-dontwarn com.jetbrains.JBR
-dontwarn com.jogamp.**
-dontwarn org.eclipse.swt.**
-dontwarn javafx.**
-dontwarn org.apache.commons.compress.harmony.pack200.**
