# 11 — Multi-browser architecture

Status: **research and plan only**. Nothing here is implemented, and no runtime has been downloaded.
Checked against the official package indexes on 2026-10-18.

## Decision

One shared Linux runtime. Chrome and Brave are two **adapters** on top of it, and neither is
special-cased below the Browser Manager.

- They need the same things: glibc ≥ 2.25, GTK 3, NSS, X11/xcb, ALSA, libgbm, libvulkan
  (see [12](12-chrome-vs-brave-compatibility.md)). One Debian arm64 rootfs satisfies both.
- Installing the runtime twice would cost roughly 400–500 MB of duplicated libraries, two X servers,
  and two audio bridges competing for the car surface and audio focus.
- apt already reference-counts shared dependencies, so removing one browser leaves the other's
  libraries in place.

**Build Brave first, then Chrome.** The reasons are in
[12 § Which browser first](12-chrome-vs-brave-compatibility.md#which-browser-first).

## Layers

```
AutoBridge (personal / lab flavors only, arm64-v8a only)
└── Desktop Browser Manager                       Kotlin, app process
    ├── BrowserRegistry       installed adapters, default browser, per-browser state
    ├── PackageManager        apt inside the rootfs, one lock, see doc 13
    ├── SessionController     start / stop / switch, one session per browser
    └── Diagnostics
    │
    ├── Shared Linux Runtime                       installed once
    │   ├── RootFS            Debian 13 (trixie) arm64, minimal
    │   ├── PRoot             shipped as libproot.so + libproot-loader.so (nativeLibraryDir)
    │   ├── X11 display       one server, one display per active car session (:1)
    │   ├── Audio bridge      PulseAudio in the rootfs → AutoBridge AudioTrack + existing audio focus
    │   ├── Input bridge      car touch / rotary / keyboard → X events (XTest)
    │   └── Rendering bridge  X framebuffer → Android Auto Surface
    │
    ├── BraveAdapter          brave-browser (arm64), /opt/brave.com/brave
    └── ChromeAdapter         google-chrome-stable (arm64), /opt/google/chrome
```

The rendering, input and audio bridges know about **one X display and one top-level window**. They
never see which browser is running. Everything browser-specific lives in an adapter.

## The adapter contract

Sketch only. Names follow the existing `dev.autobridge.browser` package.

```kotlin
interface DesktopBrowserAdapter {
    val id: DesktopBrowserId            // BRAVE, CHROME
    val displayName: String
    val aptPackage: String              // "brave-browser", "google-chrome-stable"
    val aptSource: AptSource            // repo URL, suite, signing-key fingerprint
    val executable: String              // path inside the rootfs
    val policyDir: String               // managed policy JSON directory
    val wmClass: String                 // X11 WM_CLASS, so the bridge can find the window

    fun launchArgs(session: SessionSpec): List<String>   // common + browser-specific flags
    fun managedPolicy(session: SessionSpec): JsonObject  // first run, password manager, extras off
    fun profileLayout(home: String): ProfileLayout       // profile dir, cache dir, crash dir
    fun parseVersion(dpkgVersion: String): BrowserVersion
}
```

`SessionSpec` holds what the bridges decided: display number, width/height/DPI of the car
surface, GPU mode, sandbox mode, and whether the session is car or phone. Adding a third browser
(Debian's `chromium`, Edge, Vivaldi) means adding one adapter and nothing else.

## Independent state per browser

| Item | Brave | Chrome | Where it is kept |
|---|---|---|---|
| Install state | NOT_INSTALLED / INSTALLING / INSTALLED / UPDATING / BROKEN | same | Android-side `BrowserRegistry` (DataStore), reconciled with `dpkg-query` on start |
| Executable | `/usr/bin/brave-browser-stable` → `/opt/brave.com/brave/brave-browser` | `/usr/bin/google-chrome-stable` → `/opt/google/chrome/google-chrome` | adapter constant, checked on start |
| Version | `dpkg-query -W -f '${Version}' brave-browser` | same for `google-chrome-stable` | read on demand, cached |
| User profile | `~/.autobridge/brave/profile` | `~/.autobridge/chrome/profile` | `--user-data-dir` |
| Cache | `~/.autobridge/brave/cache` | `~/.autobridge/chrome/cache` | `--disk-cache-dir` (outside the profile, so clearing it never touches logins) |
| Launch arguments | common set + Brave extras | common set + Chrome extras | adapter, overridable from LAB |
| Update | `apt-get install --only-upgrade brave-browser` | same for Chrome | doc 13 |
| Uninstall | `apt-get purge brave-browser` + remove repo | same for Chrome | doc 13 |

`~` is `/home/autobridge` inside the rootfs. Profiles stay inside the rootfs, so a whole-runtime
backup or removal covers them, and they are never under Android shared storage.

## Process model: stopping one browser leaves the rest running

Each long-lived piece is its own process tree:

```
AutoBridge app process
 ├── X server  (DISPLAY=:1)              lives as long as the runtime is "warm"
 ├── pulseaudio                           same
 ├── proot #1 → brave-browser …           one proot tracer per browser session
 └── proot #2 → google-chrome …           only if the user really runs both
```

- **Stop one browser:** send SIGTERM to that browser's main process (Chromium flushes the profile
  and exits), wait up to 5 s, then SIGKILL that proot tracer. The X server, PulseAudio and the
  other browser's proot are separate processes and are not touched.
- **Stop the runtime:** stop every browser first, then PulseAudio, then X.
- Two browsers can share one X display, but only one window is shown on the car surface. **Default
  policy: one active browser at a time.** Switching stops the current one and starts the other.
  Running both is a LAB option, because two Chromium trees on a phone easily need 1.5 GB+ of RAM.
- Separate `--user-data-dir`s mean the two never fight over Chromium's `SingletonLock`. After a
  crash, the manager removes a stale `SingletonLock` before relaunching that browser only.

The "stop one, keep the other" claim is a design property of PRoot (one tracer per tree). It still
needs a check on the phone: see test T7 in [12](12-chrome-vs-brave-compatibility.md#on-device-test-plan).

## Android Auto rendering bridge

The bridge is browser-agnostic by construction:

1. The X display is sized to the car surface (`width × height`, DPI from `CarDisplayScaling`).
2. The browser starts with `--ozone-platform=x11`, `--start-fullscreen` (or `--kiosk` for car
   mode) and `--window-size` equal to the display, so there is one full-size window.
3. The bridge finds that window by `WM_CLASS` (the only thing the adapter supplies) and pumps
   frames into the Android Auto `Surface`, through the same `SurfaceContainer` lifetime handling
   that `MirrorSurfaceController` and `CarHardwareWebWindow` already use (surface churn → stop
   pumping, keep the browser alive).
4. Input goes the other way through XTest. The existing `CarSurfaceKeyboard` can feed text.

Which X server feeds the frames (an Xvfb in the rootfs with a frame pump, or an Android-side
server like Termux:X11's) is a runtime decision that does not change the adapters. Note:
Termux:X11 is GPL-3.0 and AutoBridge is MIT. Linking it into the APK would change the licence of
the distributed app. Use it only as a separate companion app, or use the X.org Xvfb inside the
rootfs.

## The embedded browser stays

`BrowserActivity` / `CarWebRenderer` (the WebView browser) remain the default and the fallback. The
desktop browser is an extra engine:

- **Browser engine** setting: `Embedded (default)` · `Brave desktop` · `Chrome desktop`. Only installed
  engines are offered.
- If a desktop engine fails to start (runtime missing, OOM, crash loop of 3 in 60 s), the car
  falls back to the embedded browser with the same URL and shows why.
- `SendToCarSheet` and other "open in car browser" paths go to whichever engine is the default.

## Safety and policy

- Desktop browser sessions map to the existing `BROWSER` and `VIDEO` features in
  [FEATURE_POLICY.md](../FEATURE_POLICY.md). Both are already parked-only. On `MOVING` or `UNKNOWN`,
  the car surface leaves the desktop browser exactly as it leaves the embedded one. The frame pump
  stops, and the browser is paused (window hidden, media paused via a media key over XTest), not
  killed, so the parked session can resume.
- Only the **personal** and **lab** flavors carry this. The **safe** (Play) flavor never compiles
  it: downloading and running executable code is not acceptable there.
- arm64-v8a only. On armeabi-v7a devices the Desktop Browser section is hidden and the embedded
  browser is the only engine.
- No binaries or rootfs are bundled in the APK beyond PRoot itself. Everything else is downloaded
  only when the user asks, from the official sources in [13](13-browser-package-management.md).
- Nothing is taken from VelvetLauncher.

## Open questions for review

1. Which X server path did docs 01–10 settle on? This doc assumes either works. The
   adapters do not care.
2. Is "one active browser at a time" acceptable as the default, with "both" as LAB only?
3. Should the desktop engine ever be the default for **phone** browsing, or is it car-only?
