plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.yunjue.echo.mind.qa"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
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
        unitTests.all {
            it.useJUnit()
        }
    }
}

detekt {
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("detekt.yml"))
    parallel = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ports"))
    implementation(project(":feature:observation"))
    implementation(project(":feature:presence"))
    implementation(project(":feature:journey"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:intelligence"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
