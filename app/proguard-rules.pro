# R8 configuration for the release build (isMinifyEnabled in app/build.gradle.kts).
#
# Why the release build is minified at all: Play Console wants a deobfuscation file for every
# bundle. R8 writes one (app/build/outputs/mapping/<variant>/mapping.txt) and AGP packs it into
# the .aab, so an uploaded crash or ANR comes back with real class and method names instead of
# `a.b.c`. Shrinking also drops the dependency code no screen reaches.
#
# Everything below is one of two things: a place where the app is reached by *name* at runtime
# rather than by a reference R8 can follow, or a library that ships no rules of its own. Keep it
# to those. A blanket `-keep class dev.autobridge.**` would turn the mapping file back into a
# formality and keep every dead screen in the archive.
#
# Rules that already arrive with a dependency are deliberately absent: androidx.car.app and
# app-projected, media3, ML Kit, Shizuku, HiddenApiBypass and the unofficial Android Auto SDK
# (app/libs/aauto.aar, which keeps com.google.android.gms.car.**) each ship a consumer
# proguard.txt that AGP merges in. Android's own manifest keep rules cover every Activity,
# Service, Receiver and Provider declared in a manifest, which is why BrowserActivity
# (named as a string by DuoScreenSelfPane.ACTIVITY), DuoScreenSettingsActivity (named as a
# string by MainActivity) and OssLicensesMenuActivity (reached by Class.forName) need nothing
# here.

# -- Readable stack traces ----------------------------------------------------------------------
# Without these the mapping file can rename methods back but not point at a line, so a retraced
# frame says `BrowserActivity.onCreate(Unknown Source)`. Source file names are collapsed to one
# placeholder, which costs nothing to retrace and leaks no paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -- ONNX Runtime (optional Opus-MT subtitle engine) --------------------------------------------
# onnxruntime-android is the one dependency here with no consumer rules, and its native library
# resolves these classes, their fields and their constructors from JNI — edges no shrinker can
# see. Renaming or removing any of them fails at the first OrtEnvironment call, i.e. only once a
# user turns subtitle translation on, which is exactly the kind of fault that would ship.
# Compiled out entirely by -Pautobridge.subtitleTranslation=false; harmless then.
-keep class ai.onnxruntime.** { *; }

# -- Shizuku user service -----------------------------------------------------------------------
# ShizukuInputBackend hands Shizuku a ComponentName built from ShizukuTouchService's class name,
# and Shizuku loads and constructs that class inside its own shell-UID process. The class name in
# the APK therefore has to stay the one the string resolves to.
-keep class dev.autobridge.input.ShizukuTouchService { *; }
# Both ends of the binder live in this APK and are renamed consistently, but the generated Stub
# and Proxy are only ever reached through the interface, so pin the AIDL surface rather than
# depend on R8's view of it.
-keep interface dev.autobridge.input.IShizukuTouchService { *; }
-keep class dev.autobridge.input.IShizukuTouchService$* { *; }

# -- Projection route (personal and lab flavors only) -------------------------------------------
# ProjectionCarService returns ProjectionBrowserActivity's Class to the Android Auto host, which
# instantiates it itself. R8 sees the class literal but not the no-arg constructor the host calls,
# and the SDK's own rules cover com.google.android.gms.car.** only — not the
# com.google.android.apps.auto.sdk.* types this subclasses. Absent from the safe flavor, which
# never compiles src/projection.
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class * extends com.google.android.apps.auto.sdk.CarActivity { <init>(...); }
-keep class * extends com.google.android.apps.auto.sdk.CarActivityService { <init>(...); }
