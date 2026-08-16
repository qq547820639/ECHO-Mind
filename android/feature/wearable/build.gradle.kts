plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.yunjue.echo.mind.wearable"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

detekt {
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("detekt.yml"))
    parallel = true
}

dependencies {
    // 架构冻结例外（ERA 33）：唯一新增产品边界 module。
    // 只依赖 :core:model + :core:ports（ECHO_WRIST_CONTRACT §Architecture）。
    // 禁止：:app / Room / MemoryRepository / JourneyRepository / AiProviderManager / Xiaomi SDK。
    implementation(project(":core:model"))
    implementation(project(":core:ports"))
    implementation(libs.kotlinx.coroutines.core)

    // 生产使用 android.jar 内置 org.json；纯 JVM 单测使用同 API 的 org.json:json。
    testImplementation(libs.json.org.lib)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
