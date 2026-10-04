import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.google.android.gms.oss-licenses-plugin")

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
        versionCode = 26
        versionName = "0.4.12"
        // The faults that actually reach a head unit — viewport geometry, scroll bounds, WebView
        // state — only reproduce against a real WebView, so this module needs on-device tests as
        // well as JVM ones.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The UI ships English (default res/values) and Thai (res/values-th). Declaring the set
        // keeps libraries from dragging in other partial translations and lets Android resolve the
        // car/phone UI by the device/app language automatically.
        resourceConfigurations += setOf("en", "th")
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

    testOptions {
        unitTests {
            // StructuredLog mirrors to android.util.Log and stamps entries with
            // SystemClock.elapsedRealtime(). Both are android.jar stubs that throw "not mocked" by
            // default, which made the log's own behaviour the one thing its tests could not touch.
            // Returning defaults lets those paths run; every assertion still comes from this
            // project's own state, never from a stubbed return value.
            isReturnDefaultValues = true
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

    lint {
        // A string that exists in res/values but not in every res/values-<tag> renders in English
        // for that locale, which reads as a bug rather than a missing translation. Promoted to an
        // error so it cannot ride along in a release; scripts/check-i18n.sh reports the same gap
        // with the key names, without needing the SDK.
        error += "MissingTranslation"
        // The inverse — a translation for a key the default locale dropped — is dead weight and
        // the usual sign of a rename that only landed in one file.
        error += "ExtraTranslation"
        // Hardcoded UI text. Only reaches XML layouts; the Kotlin side is scripts/check-i18n.sh.
        warning += "HardcodedText"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Projection route (split screen beside the navigation app) for the sideloaded flavors only. It
// depends on the unofficial Android Auto SDK, which Play does not accept, so the safe flavor never
// sees this source set or the archive.
android.sourceSets {
    listOf("personal", "lab").forEach { flavor ->
        getByName(flavor) {
            java.srcDir("src/projection/java")
            manifest.srcFile("src/projection/AndroidManifest.xml")
        }
    }
}

dependencies {
    // Unofficial Android Auto SDK (CarActivity/CarActivityService), the same archive Fermata Auto
    // ships as fermata/lib/auto/aauto.aar. Not published by Google and carries no license file.
    // sha256 99337c3b591ac9670c12b508da38886aedba61dd494f39f5f166f02580ec584b
    "personalImplementation"(files("libs/aauto.aar"))
    "labImplementation"(files("libs/aauto.aar"))
    testImplementation("junit:junit:4.13.2")
    // The subtitle pipeline launches its translation on an injected CoroutineScope; the test
    // drives that scope with a test dispatcher so a line's result lands synchronously and the
    // ordering guarantees can be asserted without sleeping.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
    // Declared rather than inherited: SharedPreferences.edit {} and the permission/compat helpers
    // are used directly across media, IPTV, and settings, so the version those calls resolve
    // against should not be whatever media3 or car-app happens to drag in this release.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    // WebViewCompat.getCurrentWebViewPackage() for the DRM/WebView diagnostics screen.
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Custom Tabs for provider sign-in pages that block embedded WebView login; shares Chrome's
    // cookie jar so an already-signed-in Chrome session skips the credential prompt entirely.
    implementation("androidx.browser:browser:1.8.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Presentation (letterboxing) for the car surface. media3-exoplayer does not depend on the
    // effect module, so setVideoEffects() needs it declared here, at the same version.
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device subtitle translation. ML Kit is the default engine: it ships compact per-language
    // models through Play Services and needs no inference code here. Opus-MT is the optional
    // engine for full-sentence quality and pairs ML Kit has no model for, run as a Marian
    // encoder-decoder through ONNX Runtime. Both keep the subtitle track - the dialogue of
    // whatever is playing - on the device rather than on a translation API's server.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Renders the generated third-party license list in OssLicensesMenuActivity, which the
    // Settings "Open-source licenses" row opens. The oss-licenses-plugin collects the notices from
    // the dependency POMs at build time; this library is the viewer for them.
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")
}
