import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Kotlin SAST（detekt）：见下方 detekt 块；待 CI 首跑验证。
    alias(libs.plugins.detekt)
}

val apiBaseUrl = providers.gradleProperty("ECHO_API_BASE_URL")
    .orElse("http://10.0.2.2:8000")
    .get()
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && !apiBaseUrl.startsWith("https://")) {
    throw GradleException("Release builds require -PECHO_API_BASE_URL=https://...")
}

// v3.2 §9：Build provenance（APK 内嵌构建来源；CI 无 git 环境时回退 "unknown"）。
val gitCommit: String = providers.gradleProperty("ECHO_GIT_COMMIT").orNull
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            workingDir(rootDir)
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown")
val buildTimestamp: String = providers.gradleProperty("ECHO_BUILD_TIMESTAMP").orNull
    ?: Instant.now().toString().replace(":", ".")

android {
    namespace = "com.yunjue.echo.mind"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yunjue.echo.mind"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("String", "BUILD_VERSION", "\"0.9.0\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    // v0.7.2：Room MigrationTestHelper 需要 schemas 目录作为 androidTest assets
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Kotlin SAST（detekt）：从少数高信号规则起步的基线配置（android/detekt.yml）。
// 待 CI 首跑验证：本机无 Gradle，无法本地执行 `./gradlew detekt` 确认规则集
// 与仓库现状兼容；首次 CI 跑通后可逐步收紧 maxIssues 与规则集。
detekt {
    // 只应用 detekt.yml 中显式声明的规则（不叠加 detekt 全量默认规则集），
    // 从「少数高信号规则」起步，避免一次性引入大量告警。
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("detekt.yml"))
    parallel = true
}

dependencies {
    // ERA 13.5：物理模块（每次一个；actions/security 为零依赖叶子模块）
    implementation(project(":feature:actions"))
    implementation(project(":core:security"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // A3（Batch A）：底部导航/FAB 无障碍图标（Add/Home/Star/TrendingUp/Info，BOM 管理版本）
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // 为主源集和单元测试源集生成 Room 实现（测试源集含 TestConsentDatabase）
    kspTest(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    // SQLCipher 全库加密（T04.3）
    implementation(libs.sqlcipher)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Phase 1.2（跨端契约测试）：纯 JVM 单测使用真实 org.json（android.jar stub 在
    // 非 Robolectric 路径下方法抛异常/返回默认值，无法解析 JSONObject）。org.json:json
    // 是 android.jar 中 org.json 的官方镜像实现，API 兼容；Robolectric 测试类加载时
    // 若命中本依赖的 org.json，行为与 android-all 一致。
    testImplementation("org.json:json:20240303")

    // v0.7.2：Instrumented 测试源集（androidTest）——关闭 CI connected-test 空通过门。
    // MigrationTestHelper 需要 schemas 目录作为 androidTest assets。
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
