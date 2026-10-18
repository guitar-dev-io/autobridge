plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Desktop browser engine: a Debian arm64 runtime under PRoot running Linux Google Chrome or
// Brave against an Xvfb display, with the frames, input and audio bridged to Android. Sideload
// flavors only (see the dependency in :app) - it downloads and executes code, which Play does not
// accept. arm64-v8a only: the browsers it installs exist for arm64 Linux and nothing else here.
android {
    namespace = "dev.autobridge.desktop"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        resourceConfigurations += setOf("en", "th")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
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
    implementation(project(":common"))
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
