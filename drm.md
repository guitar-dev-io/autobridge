You are working on my existing Android project called **AutoBridge**.

Goal:
Investigate and improve the current WebView/video implementation so that DRM-protected web video can use the Android System WebView / Chromium / Widevine L3 stack correctly, similar in architectural concept to the open-source project:

https://github.com/kododake/AABrowser

IMPORTANT:

* DO NOT start modifying code immediately.
* First inspect the existing AutoBridge project completely.
* Preserve the current architecture and existing features as much as possible.
* Do not implement DRM cracking, DRM key extraction, license interception, Widevine bypass, or content decryption.
* Widevine must be handled only through Android's normal WebView / MediaDrm stack.
* Do not bypass Android Auto driving/safety restrictions.
* Video functionality should remain intended for parked/safe usage.
* Do not blindly copy AABrowser code. Study its architecture and adapt only what is appropriate.

## Phase 1 — Inspect first

Before changing anything, inspect and report:

1. Current Android project structure.
2. Current Android Auto integration.
3. Current Activities used for the browser/video UI.
4. Current WebView initialization.
5. Current WebViewClient.
6. Current WebChromeClient.
7. Fullscreen video handling.
8. WebView hardware acceleration configuration.
9. AndroidManifest.xml.
10. automotive_app_desc.xml or other car app metadata.
11. Gradle dependencies related to:

* androidx.webkit
* androidx.car.app
* Media3 / ExoPlayer
* WebView

12. Current handling of:

* `onShowCustomView`
* `onHideCustomView`
* fullscreen HTML5 video
* orientation
* audio focus
* Activity lifecycle

13. Whether the project currently uses screen mirroring / capture for the browser display or renders the WebView directly.
14. Whether any SurfaceView / TextureView handling may interfere with protected video surfaces.
15. Current Android minSdk / targetSdk.

Do not change anything yet.

Produce a short architecture map such as:

AutoBridge
├── Android Auto entry
├── Main/Browser Activity
├── WebView
│    ├── WebViewClient
│    └── WebChromeClient
└── FullscreenVideoContainer

Also identify the likely reason DRM video currently:

* does not start,
* shows black video,
* has audio but no image,
* fails fullscreen,
* or reports DRM unsupported.

## Phase 2 — Study AABrowser

Review the implementation approach used by:

`kododake/AABrowser`

Focus specifically on:

* AndroidManifest configuration
* hardware acceleration
* Android Auto entry configuration
* WebView setup
* WebChromeClient
* fullscreen custom view
* `onShowCustomView`
* `onHideCustomView`
* fullscreen video container
* lifecycle handling
* orientation handling
* Android System WebView usage
* Widevine L3 behavior

Search its source for terms such as:

```text
WebView
WebChromeClient
onShowCustomView
onHideCustomView
CustomViewCallback
hardwareAccelerated
CAR_LAUNCHER
automotive_app_desc
androidx.webkit
androidx.car.app
fullscreen
MediaDrm
Widevine
```

Important observation to verify:

AABrowser should NOT be manually decrypting Widevine content.

Expected architecture should be roughly:

```text
Website
   ↓
HTML5 video / EME
   ↓
Android System WebView / Chromium
   ↓
Android MediaDrm
   ↓
Widevine L3
   ↓
Video Surface
```

Explain what AABrowser actually does differently from AutoBridge.

Do not assume. Verify from source.

## Phase 3 — Compare with AutoBridge

Create a comparison:

```text
AABrowser                     AutoBridge
------------------------------------------------
hardwareAccelerated           ?
WebChromeClient               ?
onShowCustomView              ?
Fullscreen container          ?
System WebView                ?
Widevine through WebView      ?
Direct rendering              ?
Screen capture/mirroring      ?
Lifecycle handling            ?
```

Identify:

### Must change

Things necessary for correct WebView/fullscreen video behavior.

### Should change

Improvements for stability.

### Do not change

Existing AutoBridge functionality unrelated to the problem.

## Phase 4 — Proposed implementation

Only after the investigation, propose the smallest safe implementation.

Preferred architecture:

```text
AutoBridgeActivity
       │
       ├── BrowserView / WebView
       │       │
       │       ├── WebViewClient
       │       └── WebChromeClient
       │                  │
       │                  └── onShowCustomView()
       │
       └── FullscreenVideoContainer
                  ↑
                  │
          Chromium video View
```

The normal web playback path should remain:

```text
Web page
 → EME
 → Chromium
 → Android MediaDrm
 → Widevine L3
```

Do NOT implement:

```text
custom Widevine decryption
license key extraction
CDM modification
DRM bypass
screen-capture workaround for protected video
```

## WebView configuration review

Check whether AutoBridge needs settings similar to:

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
webView.settings.mediaPlaybackRequiresUserGesture = false
```

But do NOT blindly add settings.

Explain whether each one is actually required.

Also check:

```kotlin
WebView.setWebContentsDebuggingEnabled(...)
```

Debug mode should only be enabled for debug builds.

Check the installed WebView provider using APIs where appropriate.

For diagnostics, it would be useful to display:

```text
WebView package
WebView version
Android version
Widevine availability
Widevine security level if legally/queryable through normal APIs
```

Do not alter DRM provisioning.

## Fullscreen implementation

If AutoBridge lacks correct fullscreen handling, implement a reusable component such as:

```text
FullscreenVideoController
```

Responsibilities:

```text
enterFullscreen(view, callback)
exitFullscreen()
restoreSystemUi()
handleBackPress()
handleLifecycle()
```

The WebChromeClient should route:

```text
onShowCustomView()
        ↓
FullscreenVideoController.enterFullscreen()

onHideCustomView()
        ↓
FullscreenVideoController.exitFullscreen()
```

Avoid destroying/recreating the WebView unnecessarily when entering fullscreen.

## Protected surface issue

Determine whether AutoBridge currently uses any of these:

```text
MediaProjection
ImageReader
PixelCopy
screen capture
virtual display
TextureView mirror
scrcpy-style capture
```

in the video display path.

If yes, explain whether that path can cause DRM-protected video to appear black.

Do not try to defeat secure/protected surfaces.

Instead prefer direct rendering where technically possible.

## Diagnostics

Add a developer-only DRM/WebView diagnostics screen or section if it fits the current architecture.

Suggested output:

```text
Android: 16
WebView provider: com.google.android.webview
WebView version: xxx
Hardware acceleration: ON
JavaScript: ON
DOM Storage: ON
Fullscreen custom view: supported
Widevine CDM: available / unavailable
Widevine level: L1 / L2 / L3 / unknown
```

If Widevine level cannot safely/reliably be queried, display `unknown` rather than using hacks.

## Logging

Use structured debug logs such as:

```text
[AutoBridge/WebView]
[AutoBridge/Fullscreen]
[AutoBridge/DRM]
```

Log only technical state.

Never log:

* DRM keys
* license responses
* authentication tokens
* cookies
* user credentials

## Safety / Android Auto behavior

Do not introduce code whose purpose is to disable or bypass:

```text
driving state restrictions
speed lock
UX restrictions
parked-only restrictions
Android Auto safety checks
```

The goal is only to make the WebView/video architecture technically correct.

## Final result expected

After inspection, provide:

1. Current AutoBridge architecture.
2. Relevant AABrowser architecture.
3. Differences between them.
4. Root cause hypothesis for DRM/fullscreen problems.
5. Exact files that need modification.
6. Minimal implementation plan.
7. Risks/regressions.
8. Then implement the changes.
9. Show a diff/summary of every modified file.
10. Provide manual test cases.

Testing should include:

```text
TEST 1
Normal non-DRM HTML5 video

TEST 2
Fullscreen non-DRM video

TEST 3
Widevine L3 test content that we are authorized to access

TEST 4
Enter/exit fullscreen repeatedly

TEST 5
Background → foreground

TEST 6
Android Auto disconnect/reconnect

TEST 7
WebView process recreation

TEST 8
Rotate/display size change if applicable
```

Most important rule:

**Inspect first. Do not rewrite AutoBridge blindly. Make the smallest architecture-correct change based on actual existing code.**
