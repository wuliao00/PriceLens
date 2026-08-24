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
