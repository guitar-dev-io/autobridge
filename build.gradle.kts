buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // oss-licenses-plugin ships as a classic classpath artifact on Google's Maven rather than a
        // Gradle plugin-portal marker, so it is applied via the legacy buildscript path.
        classpath("com.google.android.gms:oss-licenses-plugin:0.10.6")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
