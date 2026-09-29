import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("keystore/release.properties")
val releaseSigningProperties = Properties()
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use { releaseSigningProperties.load(it) }
}
val hasReleaseSigning = releaseSigningProperties.getProperty("storeFile") != null

android {
    namespace = "dev.autobridge"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }

    defaultConfig {
        applicationId = "dev.autobridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.7"
        // The faults that actually reach a head unit — viewport geometry, scroll bounds, WebView
        // state — only reproduce against a real WebView, so this module needs on-device tests as
        // well as JVM ones.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // DHU-only test mode uses the deterministic parked mock; never enabled in release.
            buildConfigField("boolean", "DEV_MODE", "true")
            buildConfigField("boolean", "DHU_TEST_MODE", "true")
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_MODE", "true")
            // Unlocked-all-safety build: release mirrors the debug parked-mock behavior so both
            // variants behave identically for personal/DHU use.
            buildConfigField("boolean", "DHU_TEST_MODE", "true")
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
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // The android.jar the unit tests link against stubs org.json with methods that throw. The
    // SponsorBlock response parser is ordinary JSON work and is worth testing, so the tests get a
    // real implementation of the same API. Not shipped: the platform provides it on a device.
    testImplementation("org.json:json:20250107")
    // Compose BOM pinned to the last release line compatible with AGP 8.13.2 / compileSdk 36.
    // Compose 1.12+ (BOM 2026.06.01) requires compileSdk 37 and AGP 9.1.0+, so it is not used here.
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    // WebViewCompat.getCurrentWebViewPackage() for the DRM/WebView diagnostics screen.
    implementation("androidx.webkit:webkit:1.16.0")
    // Custom Tabs for provider sign-in pages that block embedded WebView login; shares Chrome's
    // cookie jar so an already-signed-in Chrome session skips the credential prompt entirely.
    implementation("androidx.browser:browser:1.8.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    // Presentation (letterboxing) for the car surface. media3-exoplayer does not depend on the
    // effect module, so setVideoEffects() needs it declared here, at the same version.
    implementation("androidx.media3:media3-effect:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
