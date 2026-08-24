import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// 发布签名从 local.properties 读取（该文件被 .gitignore 排除）。
// 贡献者未配置时 release 构建为未签名包，不影响源码编译。
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("PRICLENS_STORE_FILE") != null

android {
    namespace = "com.pricelens"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pricelens"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.3.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProps.getProperty("PRICLENS_STORE_FILE"))
                storePassword = keystoreProps.getProperty("PRICLENS_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("PRICLENS_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("PRICLENS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // Shizuku UserService 需要 BuildConfig.DEBUG
        aidl = true          // IShellService.aidl（Shizuku 命令通道）
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // 网络 / 解析（JSON 用系统 org.json，不引序列化库，控制 APK 体积）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // 图片（§4.4：内存 10% + 磁盘 15MB + 降采样）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Shizuku（免 root/无线调试授权，用于一键开启无障碍服务）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
}
