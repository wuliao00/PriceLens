# OkHttp
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
# Jsoup（1.18 引用可选的 jspecify 注解，运行时不加载）
-dontwarn org.jspecify.annotations.**
-keep class org.jsoup.** { *; }
# Coil
-dontwarn coil.**
# Shizuku（跨进程 binder，禁止混淆）
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# --- Shizuku UserService：AIDL 接口和 Stub 实现是跨进程 binder 边界 ---
# 不保留会导致 release 构建 R8 混淆后，Shizuku 拿到的是乱码类名，
# UserService 连接失败 → 设置页 Shizuku 状态显示"未服务"、一键授权静默失败
-keep class com.pricelens.util.IShellService { *; }
-keep class com.pricelens.util.IShellService$Stub { *; }
-keep class com.pricelens.util.IShellService$Stub$Proxy { *; }
-keep class com.pricelens.util.ShizukuShellService { *; }

# AccessibilityService：系统按完整类名绑定 binder，不能混淆
-keep class com.pricelens.accessibility.PriceMonitorService { *; }
# AccessibilityEvent 用到的节点回收扩展函数
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keep @androidx.annotation.Keep class *
