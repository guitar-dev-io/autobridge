# Desktop Browser Engine: architecture

Linux Google Chrome and Brave (arm64 desktop builds) run inside a shared Debian runtime on the
phone. They are shown on the phone and on Android Auto, next to the built-in AutoBridge browser,
which is unchanged.

Personal and lab (sideload) flavors only, on arm64 phones only. The safe (Play) flavor does not
compile any of it.

## Layers

```
AutoBridge app process (uid u0_aNNN, untrusted_app sandbox)
│
├── app/src/projection/…/desktop/            UI (needs app internals: design system, FeaturePolicy)
│   ├── DesktopBrowserSettingsActivity       Settings › Desktop Browser Engine
│   ├── DesktopBrowserActivity               phone viewer (SurfaceView + touch + IME)
│   └── DesktopBrowserCarAppService/Screen   car screen, opened inside AutoBridge (service disabled)
│
└── :desktopbrowser module (dev.autobridge.desktop)
    ├── DesktopEngine            facade: state flows, one op queue, one session, idle stop
    ├── install/                 RuntimeSource (pinned image), VerifiedDownloader (resume + SHA-256),
    │                            SafeTarExtractor, RuntimeInstaller, AptRunner, BrowserPackages,
    │                            OpenPgpKeys (key pinning), EngineStore, DesktopEngineInstallService
    ├── runtime/                 RuntimeLayout (paths), ProotCommand, LinuxRuntime (process launch),
    │                            ChildProcessLimit (Android 12+ phantom-process limit)
    ├── browser/                 DesktopBrowserAdapter, ChromeAdapter, BraveAdapter
    ├── session/                 DesktopSession: Xvfb + PulseAudio + browser + bridges
    ├── display/                 X11Connection (XTEST, DAMAGE, keymap), XwdFramebuffer,
    │                            FrameRenderer, DesktopView, DisplayMapping
    ├── input/                   BrowserInput, X11Keyboard, TouchGestureMapper, ControlRail
    └── audio/                   BrowserAudioBridge (PulseAudio PCM → AudioTrack + focus)

Child processes, one PRoot tree each (--kill-on-exit):
    libproot.so … pulseaudio -n -F /etc/autobridge/pulse.pa
    libproot.so … Xvfb :1 -screen 0 WxHx24 -fbdir /tmp/autobridge-fb -nolisten tcp -nolisten local
    libproot.so … /bin/sh -c 'echo $$ > pid; exec google-chrome|brave-browser <flags>'
```

## Data flow

```
                     ┌─────────────── Debian 13 arm64 rootfs (filesDir/desktop-browser/rootfs) ───────────────┐
 car / phone touch   │                                                                                        │
 ──► TouchGestureMapper / car SurfaceCallback                                                                 │
 ──► DisplayMapping (surface px → X px)                                                                       │
 ──► BrowserInput ── XTEST FakeInput ──► Unix socket /tmp/.X11-unix/X1 ──► Xvfb ──► Chrome / Brave (X11 client)│
                                                                             │                                │
 Surface (SurfaceView or Android Auto) ◄── SurfaceFrameRenderer ◄── mmap ── Xvfb_screen0 (XWD, BGRX)            │
                ▲ DAMAGE notify (redraw only on change, ≤30 fps)              │                                │
 AudioTrack ◄── BrowserAudioBridge ◄── Unix socket /tmp/autobridge-pulse/pcm ◄── PulseAudio null sink ◄── browser
                                                                                                              │
                     └────────────────────────────────────────────────────────────────────────────────────────┘
```

All three channels (X display, framebuffer file, PCM socket) are files in this app's private
storage. No TCP port and no abstract socket are opened, because any app on the phone could reach
those.

## Choices and why

| Concern | Choice | Why / alternatives |
|---|---|---|
| Executing Linux binaries at targetSdk 36 | PRoot (termux/proot, GPL-2.0), built from source, shipped as `libproot.so` + `libproot-loader.so` in `nativeLibraryDir` | Apps targeting API 29+ cannot `exec` writable files. The loader in the read-only library dir maps guest ELFs instead. Verified on the Android 15 emulator in the app's SELinux domain. VelvetLauncher (targetSdk 36) uses the same approach. |
| Root filesystem | Official Debian 13 trixie-slim arm64 layer from Docker Hub, pinned by digest (= SHA-256) | Debian is the vendors' primary target and has a small layer (30 MB). The digest was cross-checked with Debian's own debuerreotype artifacts. GameNative's Wine-oriented image (used by VelvetLauncher) is not used. |
| Browser binaries | Vendors' own signed APT repositories, key fingerprints pinned | Chrome cannot be redistributed and Brave's binary may not be rehosted. Downloading them at the user's request from the vendor is the only legitimate path. apt verifies every Release file and .deb against the pinned key. |
| Display server | Xvfb (X.Org, MIT) from Debian, `-fbdir` | Installed at runtime and licence-clean. Termux:X11 (GPL-3.0) and GameNative/Winlator's X server (GPL-3.0 / LGPL) cannot be linked into this MIT app. |
| Screen size | X screen is a square (≥1600, ≥ the target's long side); the browser window is resized with X ConfigureWindow to exactly the target's shape | Fills phone portrait, phone landscape and every head-unit size without letterboxing; rotation reflows live. A target larger than the screen, or a big scale change (phone → car), restarts the browser with `--restore-last-session` |
| Drawing | Car: the app's `CarSurfaceCanvas` (hardware canvas with a crash canary, software fallback). Phone: an ordinary View drawn in `onDraw` | `lockHardwareCanvas` on a SurfaceView aborted the process on HyperOS 3 / Android 16 (HWUI "no surface") |
| Controls | Floating rail on the left (touch, mouse/trackpad, keyboard, settings), drawn over the page on car and phone | Matches the requested design; the page keeps the whole screen |
| Frame transport | mmap of Xvfb's framebuffer file, DAMAGE-driven, one `copyPixelsFromBuffer` + texture upload per changed frame, R/B swap in a GPU color matrix | No pipes or sockets, and no per-pixel CPU work. **Limitation:** one ~3.7 MB copy per changed frame at 1280×720. Zero-copy would need an X server that renders into AHardwareBuffers (GameNative's `ASurfaceRenderer` does this) and is a follow-up. |
| Input | XTEST over the X socket (Kotlin client). Keyboard: `KeyboardInputBridge` (one per session, on `BrowserInput`'s input thread, so keys and clicks keep their order) | Browsers see a mouse and keyboard, which every site handles. Text goes in as Unicode keysyms bound on demand to spare keycodes from `KeycodePool` (NoSymbol, XF86, F13–F35 rows; Thai pinned when the pool has ≥103, as the guest's 110-keycode pool does; LRU with a rebind quarantine), never xdotool or ASCII-only events. IME composition is typed for real and corrected by diff; long or multi-line commits go through the CLIPBOARD and Ctrl+V, and the previous CLIPBOARD text is owned again once Chrome has fetched the paste. `prepareForTyping` gives the Chrome window X focus (and Ctrl+L for the address bar). `X11Clipboard` + `ClipboardSync` bridge the guest CLIPBOARD and Android's clipboard both ways (XFixes owner notifications). Front ends: the phone viewer's `ChromeKeyInputView` (a real `InputConnection`, Gboard Thai/English), and on the car three keyboards chosen in rail › More › Keyboard: AutoBridge's drawn keyboard (live, key by key), the car's own `SearchTemplate` keyboard (live; submit = Enter, Back takes the text out), or the phone's keyboard through a notification (`DesktopPhoneKeyboardActivity`). Every car route is parked-only through `KeyboardTypingGate` (`FeaturePolicy(enforceParkedOnly = true)` + `ParkingStateStore`, UNKNOWN refused, bypass honoured). The opt-in AutoBridge Chrome car entry has no vehicle-state source of its own: typing there works only while an AutoBridge car screen holds the vehicle-state lease, otherwise it is refused (fail closed). |
| Audio | PulseAudio null sink → `module-simple-protocol-unix` → AudioTrack | Focus is requested only when the page actually makes sound, and is never grabbed back after a permanent loss. |
| One runtime, two browsers | Shared rootfs, Xvfb, Pulse. Separate package, profile, cache, policy and launcher per adapter | apt reference-counts the shared libraries, so removing one browser keeps the other's. |
| Sandbox | `--no-sandbox` (required) | PRoot cannot provide user namespaces or a setuid helper. Renderers are contained only by the Android app sandbox. `--test-type` hides the browser's "unsupported command-line flag" bar, which covered the top of every page and could not be acted on; the missing sandbox is the trade-off stated here. |
| Guest DNS | `GuestNetworkConfig` writes `rootfs/etc/resolv.conf` at install, at every session start and, through `GuestDnsWatcher` (a default-network callback owned by `DesktopEngine` and registered only while a session exists), on every network change | The file holds the active network's IPv4 servers first (loopback and link-local dropped), then `8.8.8.8` and `1.1.1.1` always, at most 3 lines (glibc MAXNS), plus `options timeout:2 attempts:2`. A stale first server then costs about 2 s instead of `DNS_PROBE_FINISHED_BAD_CONFIG`. IPv6 servers are used only when the network has an IPv6 default route and a global address, because the guest shares the app's network. The file is written atomically and only when it changes. Tested on a phone: the running Chrome re-reads the file in both directions, so it needs no restart and no policy. DoH is not enabled: it would not fix a wrong system config, and it adds a third-party dependency. |
| GPU | `--disable-gpu` (SwiftShader) | No GPU driver inside the runtime. virgl/Turnip is a LAB follow-up. |
| Safety | Car screen uses the built-in browser's gate: `SafetyEnforcement.gateParked` + `FeaturePolicy` for `BROWSER` | While not parked, the car shows a notice and input is ignored. Protected safety files are not modified. |

## Android Auto

- No Android Auto entry of its own. AutoBridge is the single Android Auto app the project lists:
  `DesktopBrowserCarScreen` is pushed inside AutoBridge's own session (a NAVIGATION app, so it has
  the drawing surface) when the car's Web card is opened and a desktop browser is chosen *and*
  installed; otherwise the Web card opens the built-in browser exactly as before, which is also
  the fallback whenever the engine is unavailable. Car › More › "Browser engine" switches between
  them (the same `EngineChoice` as the phone setting). The surface callback is shared with
  AutoBridge's other surface screens through `MirrorSurfaceOwnership`, and Home returns to AutoBridge.
- `DesktopBrowserCarAppService` (the earlier standalone "Desktop Browser" entry) is kept but declared
  `android:enabled="false"`; `scripts/check-car-entries.py` fails CI if it, Multi-app's or Bridge
  Web's service is listed by default again. For a standalone test:
  `adb shell pm enable dev.autobridge/dev.autobridge.projection.desktop.DesktopBrowserCarAppService`.
- `NavigationTemplate` with an action strip (keyboard, back, reload) and `Action.PAN`, which is
  what makes the host deliver `onScroll`/`onFling`/`onScale`.
- Text, parked only: the keyboard chosen in rail › More › Keyboard (AutoBridge's drawn keyboard, the
  host's `CarBrowserSearchScreen`, or the phone's keyboard via a notification) types into the
  focused field live; see the Input row above. While the drawn keyboard is open, pans and flings
  are dropped and a host double-tap `onScale` on the tray is replayed as key taps
  (`KeyboardDoubleTap`), so the page does not zoom while typing.
- The page takes the whole car surface 1:1 (scale = dpi/160, clamped 1–2). The host's stable area
  is ignored when it only reserves the auto-hidden action strip; it is used when a Coolwalk
  widget covers part of the screen.
- Portrait head units that give an app only a narrow strip (DHU portrait config: 182×1830) are a
  host layout limit.
- Surface churn (another screen pushed, DHU reconnect) only detaches the renderer; the browser
  keeps running. Ten minutes without any surface stops it, after it saves its profile.

## Known blockers and limits

- **Android 12+ child-process limit.** Android kills app child processes past a device-wide limit
  of 32, and a browser needs 15–25. Settings detects the limit and offers Developer options
  (Android 14+) or the adb command. AutoBridge never changes it itself.
- No hardware video decode or GPU inside the runtime, so 1080p VP9/AV1 is likely too heavy.
- Widevine (Netflix etc.) inside PRoot: not tested. DRM is not worked around.
- Google may show a CAPTCHA to unfamiliar traffic. AutoBridge never interacts with CAPTCHAs.
