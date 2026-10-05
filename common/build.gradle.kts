plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The handful of things both :app and :duoscreen need. Deliberately tiny: anything that belongs
// to one of them stays there, so this never becomes the drawer every module reaches into.
android {
    namespace = "dev.autobridge.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // StructuredLog mirrors to android.util.Log and stamps entries with
            // SystemClock.elapsedRealtime(), both android.jar stubs that throw "not mocked" by
            // default — which made the log's own behaviour the one thing its tests could not
            // touch. Same reason, and same setting, as :app.
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
    // api, not implementation: ShizukuGrant is about Shizuku's own state and both consumers talk
    // to the Shizuku API directly as well.
    api("dev.rikka.shizuku:api:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
