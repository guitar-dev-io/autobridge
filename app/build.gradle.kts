plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.autobridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.autobridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("safe") {
            dimension = "mode"
            buildConfigField("String", "AUTOBRIDGE_MODE", "\"SAFE\"")
        }
        create("personal") {
            dimension = "mode"
            buildConfigField("String", "AUTOBRIDGE_MODE", "\"PERSONAL\"")
        }
        create("lab") {
            dimension = "mode"
            buildConfigField("String", "AUTOBRIDGE_MODE", "\"LAB\"")
        }
    }

    buildTypes {
        debug {
            // Test-only mode is still guarded at runtime by DevMode's emulator check.
            buildConfigField("boolean", "DEV_MODE", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_MODE", "false")
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

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
