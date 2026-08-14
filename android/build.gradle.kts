plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Kotlin SAST（detekt）：根工程声明版本，app 模块实际应用。
    // 待 CI 首跑验证：本机无 Gradle，无法本地跑 `./gradlew detekt`。
    alias(libs.plugins.detekt) apply false
}
