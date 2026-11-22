pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack for exactly one library and its SPAKE2 dependency: libadb-android, which Duo
        // Screen's ADB backend uses to pair with and connect to this phone's own Wireless
        // debugging. Nothing else may resolve from here (exclusive content), so a typo or a
        // look-alike group elsewhere in the build cannot be served by JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeModule("com.github.MuntashirAkon", "libadb-android")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}

rootProject.name = "AutoBridge"
include(":app")
include(":common")
include(":duoscreen")
include(":whisper")
include(":desktopbrowser")
include(":obd")
include(":aap")
