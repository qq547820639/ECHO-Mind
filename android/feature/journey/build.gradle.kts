plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.yunjue.echo.mind.journey"
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
    implementation(project(":core:visual"))
    implementation(project(":feature:presence"))
    implementation(project(":feature:intelligence"))
    implementation(project(":feature:memory"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(project(":feature:presencevisual"))
}
