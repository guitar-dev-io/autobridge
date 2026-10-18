# Desktop Browser Engine: implementation

What was built, where it lives, and how to rebuild and test it. For the design see
[ARCHITECTURE.md](ARCHITECTURE.md); for what was actually verified see
[TEST-RESULTS.md](TEST-RESULTS.md). Research docs: [11](11-multi-browser-architecture.md),
[12](12-chrome-vs-brave-compatibility.md), [13](13-browser-package-management.md).

## Features

| Feature | Where |
|---|---|
| Shared Debian 13 arm64 runtime: download with resume, SHA-256 check, safe atomic extraction, configuration, base packages (Xvfb, PulseAudio, fonts) | `install/RuntimeInstaller.kt`, `VerifiedDownloader.kt`, `SafeTarExtractor.kt` |
| States NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING, READY, RUNNING, FAILED, UPDATE_AVAILABLE | `install/EngineState.kt` |
| Recovery from an interrupted install (staging deleted; dpkg `--configure -a` + `-f install` on retry; no re-download once extracted) | `DesktopEngine.reconcile`, `AptRunner.recover` |
| Google Chrome and Brave adapters: vendor repo, pinned signing-key fingerprints, managed policy, separate profile/cache, launch flags | `browser/DesktopBrowserAdapter.kt` |
| Install / check for updates / update / uninstall (keep or delete profile) / clear cache / reset profile (one backup) / remove runtime | `install/BrowserPackages.kt`, `DesktopEngine.kt` |
| Google Chrome Terms of Service shown and accepted before Chrome is fetched | `DesktopBrowserSettingsActivity.install` |
| Foreground service with real progress notification while installing | `install/DesktopEngineInstallService.kt` |
| X11 display (Xvfb), private Unix socket only | `session/DesktopSession.kt` |
| Frames → any Surface, DAMAGE-driven, ≤30 fps, letterboxed | `display/SurfaceFrameRenderer.kt`, `XwdFramebuffer.kt`, `DisplayMapping.kt` |
| Input: click, right click (long press), drag-select (long press + drag), scroll + fling, pinch zoom, text incl. Thai, keys | `input/*`, `display/X11Connection.kt` |
| Audio → AudioTrack with audio focus | `audio/BrowserAudioBridge.kt` |
| Phone viewer: landscape, keyboard, back, reload, close; back key = browser back | `app/src/projection/.../desktop/DesktopBrowserActivity.kt` |
| Android Auto "Desktop Browser" entry, parked-only, keyboard/back/reload, pan/fling/zoom | `.../desktop/DesktopBrowserCarAppService.kt` |
| Settings › Desktop Browser Engine: browser choice (AutoBridge / Chrome / Brave), runtime and per-browser cards (state, version, download estimate, measured sizes, progress, buttons), diagnostics (runtime, PRoot, glibc, child-process limit, session, X11, browser process, first paint, fps, memory, storage, last error), copy diagnostics, fast/compatible PRoot mode, stop browser | `.../desktop/DesktopBrowserSettingsActivity.kt` |
| Idle stop: a browser with no surface for 10 min is stopped (profile saved) | `DesktopEngine.noteSurfaceDetached` |
| Android 12+ child-process limit detected and explained | `runtime/ChildProcessLimit.kt` |

## Files

Added:

```
settings.gradle.kts                                   include(":desktopbrowser")
desktopbrowser/build.gradle.kts
desktopbrowser/proot/{build-proot.sh, README.md, talloc-cross-answers.txt}
desktopbrowser/src/main/AndroidManifest.xml
desktopbrowser/src/main/jniLibs/arm64-v8a/{libproot.so, libproot-loader.so}   (built by build-proot.sh)
desktopbrowser/src/main/assets/desktop/pulse.pa
desktopbrowser/src/main/res/values{,-th}/strings.xml
desktopbrowser/src/main/java/dev/autobridge/desktop/**                          (engine, see ARCHITECTURE.md)
desktopbrowser/src/test/java/dev/autobridge/desktop/{EngineUnitTest, SafeTarExtractorTest, X11ConnectionIntegrationTest}.kt
desktopbrowser/src/test/resources/keys/{google-linux-signing-key.pub, brave-browser-archive-keyring.gpg}
app/src/projection/java/dev/autobridge/projection/desktop/{DesktopBrowserSettingsActivity, DesktopBrowserActivity, DesktopBrowserCarAppService}.kt
app/src/projection/res/values{,-th}/strings_desktop.xml
docs/desktop-browser/{IMPLEMENTATION, ARCHITECTURE, TEST-RESULTS}.md
```

Modified (only these hunks):

```
app/build.gradle.kts                    personal/lab dependency on :desktopbrowser
app/src/projection/AndroidManifest.xml  settings + viewer activities, car app service
app/src/main/java/dev/autobridge/MainActivity.kt   Settings row, resolved by class name (absent in safe)
app/src/main/res/values{,-th}/strings.xml          two strings for that row
```

The built-in browser, the safety/bypass files (`scripts/check-protected.sh`) and every other
feature are untouched.

## Installation sources (all fetched on the phone, on request)

| What | URL | Integrity |
|---|---|---|
| Debian 13.7 rootfs layer | `https://registry-1.docker.io/v2/arm64v8/debian/blobs/sha256:bbeda6b4…21ba` (anonymous token from auth.docker.io) | SHA-256 = digest, pinned; manifest digest `a2ca6537…6850` cross-checked with debuerreotype artifacts commit `cf1f4a45` |
| Debian packages | `deb.debian.org` (from the image's own sources) | apt + Debian archive keyring in the image |
| Google Chrome | `https://dl.google.com/linux/chrome/deb/ stable main` (arm64) | key `EB4C 1BFD 4F04 2F6D DDCC EC91 7721 F63B D38B 4796` pinned (google.com/linuxrepositories) |
| Brave | `https://brave-browser-apt-release.s3.brave.com/ stable main` (arm64) | keys `DBF1A116…0257`, `47D32A74…CD96`, `B2A3DCA3…DCA0` pinned (same as brave.com/signing-keys "Linux Release") |

## Sizes

| | Size | Source |
|---|---|---|
| APK increase (personal) | ~0.3 MB native (`libproot.so` 268 KB + loader 17 KB) + engine/UI code | measured from the APK |
| Runtime download | 30.2 MB layer + ~110 MB base packages | layer measured; packages from apt's own output |
| Runtime installed | 538 MB | measured, arm64 container from the same image |
| Chrome 155 | +507 MB installed (136 MB .deb + deps) | measured, container |
| Brave 1.97 | +465 MB installed (143 MB .deb) | measured, container |
| Both browsers + runtime | 1.51 GB | measured, container |

## Rebuild and test

```bash
./gradlew :desktopbrowser:testDebugUnitTest
```

```bash
./gradlew :app:assemblePersonalDebug
```

```bash
./gradlew :app:compileSafeDebugKotlin
```

```bash
adb install -r -t app/build/outputs/apk/personal/debug/app-personal-debug.apk
```

Rebuild PRoot from source (only when upgrading it):

```bash
desktopbrowser/proot/build-proot.sh
```

Integration tests against a real Xvfb + browser (arm64 Debian container, see TEST-RESULTS.md):

```bash
DESKTOP_ROOTFS_TAR=/path/to/layer.tar.gz X11_TEST_PORT=6001 ./gradlew :desktopbrowser:testDebugUnitTest
```

On the phone: Settings › Desktop Browser Engine › Install runtime › Install (Chrome or Brave) ›
Open. On the car: pick Chrome or Brave as the browser, then open "Desktop Browser" in Android
Auto's launcher (sideload apps must be enabled in Android Auto's developer settings). Live log:

```bash
adb logcat -s AutoBridgeDesktop
```
