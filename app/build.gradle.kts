import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.google.android.gms.oss-licenses-plugin")

// The version lives in version.properties at the repo root so it can be bumped (and read by
// scripts/sync-version.sh and the release workflow) without touching this build script. A single
// build can override either value without editing any file; precedence is Gradle property, then
// environment variable, then the file:
//
//   ./gradlew assembleSafeRelease -PversionName=0.5.0-rc1 -PversionCode=27
//   AUTOBRIDGE_VERSION_NAME=0.5.0-rc1 ./gradlew assembleSafeRelease
val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties()
if (versionPropertiesFile.isFile) {
    versionPropertiesFile.inputStream().use { versionProperties.load(it) }
}

val configuredVersion: (String, String) -> String? = { key, environmentVariable ->
    (providers.gradleProperty(key).orNull
        ?: System.getenv(environmentVariable)
        ?: versionProperties.getProperty(key))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

val autoBridgeVersionName = requireNotNull(configuredVersion("versionName", "AUTOBRIDGE_VERSION_NAME")) {
    "versionName is not set. Add it to version.properties or pass -PversionName=<x.y.z>."
}
val autoBridgeVersionCode = requireNotNull(
    configuredVersion("versionCode", "AUTOBRIDGE_VERSION_CODE")?.toIntOrNull()
) {
    "versionCode is not set, or is not an integer. Add it to version.properties or pass " +
        "-PversionCode=<n>."
}

// Subtitle translation is the only feature here with heavyweight dependencies behind it: ML Kit
// Translate and ONNX Runtime (the Opus-MT/Marian engine). Both are mostly native code and both
// ship a copy per ABI, which is around 17 MB of the packaged APK between them. A build that does
// not want them - a smaller download, a device without Play Services, or an ABI ONNX Runtime has
// no binary for - leaves the whole feature off, which drops both libraries and the three source
// files that use them. It is off by default, so compiling the feature in is the opt-in:
//
//   ./gradlew assembleSafeRelease -Pautobridge.subtitleTranslation=true
//   AUTOBRIDGE_SUBTITLE_TRANSLATION=true ./gradlew assembleSafeRelease
//
// Subtitles themselves are unaffected: the track still decodes and renders, untranslated. The
// default lives in gradle.properties; precedence matches the version above - Gradle property,
// then environment variable, then the file.
val subtitleTranslationEnabled: Boolean = run {
    val raw = (providers.gradleProperty("autobridge.subtitleTranslation").orNull
        ?: System.getenv("AUTOBRIDGE_SUBTITLE_TRANSLATION")
        ?: "false").trim()
    raw.toBooleanStrictOrNull()
        ?: throw GradleException(
            "autobridge.subtitleTranslation must be true or false, not \"$raw\"."
        )
}

// `./gradlew -q :app:printVersion` -> "0.4.12 26". The release workflow and scripts ask Gradle
// rather than re-parsing the properties file, so there is one definition of what the build used.
tasks.register("printVersion") {
    group = "help"
    description = "Prints the versionName and versionCode this build would use."
    doLast { println("$autoBridgeVersionName $autoBridgeVersionCode") }
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
        versionCode = autoBridgeVersionCode
        versionName = autoBridgeVersionName
        // The faults that actually reach a head unit — viewport geometry, scroll bounds, WebView
        // state — only reproduce against a real WebView, so this module needs on-device tests as
        // well as JVM ones.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The UI ships English (default res/values) and Thai (res/values-th). Declaring the set
        // keeps libraries from dragging in other partial translations and lets Android resolve the
        // car/phone UI by the device/app language automatically.
        resourceConfigurations += setOf("en", "th")
        // Read by SubtitleSettings.enabled() so a pref left on by an earlier build cannot ask an
        // engine that is not here, and by the screens that offer the feature.
        buildConfigField("boolean", "SUBTITLE_TRANSLATION", subtitleTranslationEnabled.toString())
        // Arm only. Nothing this app runs on is x86: a phone driving a head unit is arm64, and
        // armeabi-v7a is kept for the older 32-bit ones. The weight is in the translation engines
        // (ML Kit Translate + ONNX Runtime, behind autobridge.subtitleTranslation above), which
        // ship an uncompressed .so per ABI — about 130 MB across four ABIs, 72 MB of it x86 and
        // x86_64 that no target device can load.
        //
        // The one thing this costs is the emulator: an x86_64 image no longer gets
        // libandroidx.graphics.path.so, the only native library a standard build packs (~10 KB per
        // ABI). On-device testing, which is what this project does anyway, is unaffected.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
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
            // R8 on. The point is the deobfuscation file: Play Console asks for one for every
            // bundle, and a minified build writes app/build/outputs/mapping/<variant>/mapping.txt
            // which AGP packs into the .aab, so uploaded crashes and ANRs retrace to real names
            // instead of `a.b.c`. Shrinking the unreached dependency code is the bonus. The edges
            // R8 cannot see - JNI, the Shizuku user service, the Android Auto host instantiating a
            // CarActivity - are pinned in proguard-rules.pro, one rule per reason.
            //
            // Resource shrinking is deliberately not enabled alongside it:
            // play-services-oss-licenses looks its generated res/raw notices up by name through
            // Resources.getIdentifier(), so the Settings "Open-source licenses" screen would come
            // up empty with no build-time warning.
            isMinifyEnabled = true
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
        // Snapshot of the lint errors that already existed when CI started gating on lint, so the
        // `lintSafeDebug` gate fails on anything *new* without first demanding the backlog be
        // cleared. The entries are the intentional experimental-API opt-ins (media3 UnstableApi,
        // car ExperimentalCarApi) and two one-offs; each is a tracked item, not a reason to let
        // the next regression through. The onBackPressed() gesture-nav entries have left it: the
        // four Activities that handled Back themselves now register with the platform dispatcher
        // (see [dev.autobridge.ui.SystemBack]) and suppress the check where the pre-33 override
        // has to stay. Regenerate with
        // `./gradlew lintSafeDebug` after deleting the file to re-snapshot once they are fixed.
        baseline = file("lint-baseline.xml")
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
//
// Duo Screen is gated the same way, for the same reason, but it is a module (:duoscreen) rather
// than a source set: the dependency below is declared on the personal and lab configurations
// only, so the safe flavor never compiles it, never merges its manifest and never ships it. That
// also gives it a manifest of its own — an AndroidSourceSet has exactly one `manifest` slot
// (unlike java/aidl srcDirs, which merge), so as a source set its service and activity had to
// squat in src/projection/AndroidManifest.xml next to the projection route's.
//
// DuoScreenSettingsActivity stays here, in src/projection: it is a phone settings screen built on
// the app's own design system, and belongs with the app's other settings screens rather than in
// a module that otherwise knows nothing about them.
//
// The subtitle translation engines are split the same way, but on the build flag rather than the
// flavor: src/translate holds the two classes that import ML Kit and ONNX Runtime plus the factory
// that names them, src/notranslate holds a factory that passes every line through. Exactly one of
// the two is compiled, so the libraries can leave the build without a single `if` in the subtitle
// stack above them.
android.sourceSets {
    getByName("main") {
        java.srcDir(if (subtitleTranslationEnabled) "src/translate/java" else "src/notranslate/java")
    }
    listOf("personal", "lab").forEach { flavor ->
        getByName(flavor) {
            java.srcDir("src/projection/java")
            manifest.srcFile("src/projection/AndroidManifest.xml")
            res.srcDir("src/projection/res")
        }
    }
}

dependencies {
    implementation(project(":common"))
    // Unofficial Android Auto SDK (CarActivity/CarActivityService), the same archive Fermata Auto
    // ships as fermata/lib/auto/aauto.aar. Not published by Google and carries no license file.
    // sha256 99337c3b591ac9670c12b508da38886aedba61dd494f39f5f166f02580ec584b
    "personalImplementation"(files("libs/aauto.aar"))
    "labImplementation"(files("libs/aauto.aar"))
    // Duo Screen. Sideload flavors only, like every other thing Play would not accept; it brings
    // its own Shizuku and hidden-API dependencies with it.
    "personalImplementation"(project(":duoscreen"))
    "labImplementation"(project(":duoscreen"))
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
    // PlayerView for the phone player: quality / audio-track / subtitle-track / speed menus, the
    // buffered seek bar, buffering spinner and resize modes, instead of re-implementing each one.
    // The car keeps its own template controls (Android Auto only hands over a bare Surface).
    implementation("androidx.media3:media3-ui:1.11.1")
    // Presentation (letterboxing) for the car surface. media3-exoplayer does not depend on the
    // effect module, so setVideoEffects() needs it declared here, at the same version.
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device subtitle translation. ML Kit is the default engine: it ships compact per-language
    // models through Play Services and needs no inference code here. Opus-MT is the optional
    // engine for full-sentence quality and pairs ML Kit has no model for, run as a Marian
    // encoder-decoder through ONNX Runtime. Both keep the subtitle track - the dialogue of
    // whatever is playing - on the device rather than on a translation API's server.
    // Both are behind -Pautobridge.subtitleTranslation; see the flag at the top of this file.
    if (subtitleTranslationEnabled) {
        implementation("com.google.mlkit:translate:17.0.3")
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    }
    // Renders the generated third-party license list in OssLicensesMenuActivity, which the
    // Settings "Open-source licenses" row opens. The oss-licenses-plugin collects the notices from
    // the dependency POMs at build time; this library is the viewer for them.
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")
}
