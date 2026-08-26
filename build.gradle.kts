plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    // Kotlin 风格门禁（v2.5.0 接入）：./gradlew ktlintCheck / ktlintFormat
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}
