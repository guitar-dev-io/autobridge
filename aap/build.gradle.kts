plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Direct Android Auto projection: the phone side of the Android Auto wire protocol, spoken by
// AutoBridge itself instead of by Google's Android Auto app. Protocol layer only - framing, the
// handful of protobuf messages it needs (hand-encoded, no protobuf runtime), the TLS session and
// the session state machine. No Android types live here: the USB/Wi-Fi link is the AapTransport
// interface and TLS is javax.net.ssl, so everything is covered by JVM unit tests against a
// scripted head unit. The USB transport, the encoder, the virtual display and the screens are in
// :app (dev.autobridge.aapdirect, sideload flavors only).
android {
    namespace = "dev.autobridge.aap"
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
