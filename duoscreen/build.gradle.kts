plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Duo Screen: 2-3 phone apps side by side on the car display, each on its own VirtualDisplay,
// composited into the one Surface the car host hands over, with touch injected back into them.
//
// Its own module rather than a source set of :app for two reasons. The components it declares
// (a second CarAppService, the spike harness) now live in this module's own manifest instead of
// squatting in src/projection/AndroidManifest.xml, which has one manifest slot and was already
// holding the projection route's. And its unit tests run once here rather than once per :app
// flavor that used to add src/duoscreen/test.
//
// :app depends on this from the personal and lab flavors only. What it does — launching arbitrary
// third-party apps onto car-displayed panes through privileged Shizuku ops, and injecting touch
// into them — is far outside anything Android Auto's review would accept, so the safe flavor must
// never see it. A flavor-scoped project dependency is what enforces that now; nothing else needs
// to know.
android {
    namespace = "dev.autobridge.duoscreen"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        // The UI ships English (res/values) and Thai (res/values-th), matching :app.
        resourceConfigurations += setOf("en", "th")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // The pane geometry is pure, but the tests around the GL thread slot and the resize
            // debouncer touch android.jar stubs that throw "not mocked" by default.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // StructuredLog (the diagnostics trail the car screens share) and ShizukuGrant (the one
    // Shizuku permission check, which the touch backend in :app reads too).
    implementation(project(":common"))
    // The car side is an ordinary CarAppService; nothing here needs the unofficial AA SDK.
    implementation("androidx.car.app:app:1.7.0")
    // SharedPreferences.edit {} in DuoScreenStore.
    implementation("androidx.core:core-ktx:1.18.0")
    // IActivityTaskManager/IInputManager are reached through Shizuku's binder path, and both
    // Stub.asInterface methods are hidden-API blocked at targetSdk 36 ("api=blocked" /
    // "max-target-r"), so reflection alone is denied.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    // The android.jar the unit tests link against stubs org.json with methods that throw, and the
    // saved-layout codec is ordinary JSON work worth testing. Not shipped: the platform provides
    // it on a device. Same reason, and same artifact, as :app.
    testImplementation("org.json:json:20250107")
}
