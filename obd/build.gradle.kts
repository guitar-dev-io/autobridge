plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// OBD-II over an ELM327-style adapter: the protocol layer only. No Android UI and no Bluetooth
// types live here - the adapter connection is the ObdTransport interface, so everything in this
// module is exercised by JVM unit tests against scripted adapter transcripts instead of a car.
// The Bluetooth transport, the connection owner and the screens are in :app (dev.autobridge.obd).
android {
    namespace = "dev.autobridge.obd"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
