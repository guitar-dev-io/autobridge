# Desktop Browser Engine: test results

Run on 2026-10-18. Every PASS below was observed directly; anything not run is marked **PENDING**
with the reason.

## Environments

| Name | What |
|---|---|
| Container | Docker `debian@sha256:a2ca6537…6850` (the same pinned arm64 trixie-slim image the app installs), on an Apple Silicon Mac |
| Emulator | `Pixel_8_API_35`: Android 15, arm64, **16 KB pages**, 6 GB RAM, 5.8 GB data partition, software GPU, no Android Auto app |
| Phone | Xiaomi 13T Pro (`corot`, 23078PND5G). Connected at the start, **disconnected from adb before any install**, so not used |

## Results

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Gradle build, personal debug | PASS | `assemblePersonalDebug` |
| 2 | Safe flavor still builds without the module | PASS | `compileSafeDebugKotlin`, `testSafeDebugUnitTest` |
| 3 | Existing unit tests | PASS | personal 1144/1144, safe 1144 (1 skipped, pre-existing) |
| 4 | Engine unit tests | PASS | 22 tests, 3 env-gated (below) |
| 5 | Archive integrity / safe extraction | PASS | `..`, symlink escape, set-id, GNU long names, cancel. **Real Debian layer**: 2677 files, 414 dirs, 173 symlinks, 1 hardlink, 100.6 MB |
| 6 | Signing-key pinning | PASS | Google key → `EB4C1BFD…4796`. Brave keyring → its 3 pinned keys, the same 3 published at brave.com/signing-keys. The vendors' keys are not interchangeable |
| 7 | PRoot built from source, 16 KB-aligned | PASS | `llvm-readelf`: LOAD align 0x4000 for both binaries |
| 8 | PRoot runs in the app sandbox at targetSdk 36 | PASS | emulator, from `nativeLibraryDir`: Debian 13.7, glibc 2.41, fake root |
| 9 | Runtime install through the app UI | PASS | download → SHA-256 → extract → configure → apt base packages. "runtime ready" in log, 485 MB |
| 10 | Interrupted install recovery | PASS | emulator `system_server` watchdog killed every app mid-dpkg. Retry: no re-download, `dpkg --configure -a` recovered, then READY |
| 11 | Brave install through the app UI | PASS | 1.97.59 in ~4 min. Program 487 MB, first-run profile 97 MB |
| 12 | Brave startup on the phone | PASS | Brave NTP drawn in the viewer (Shields stats card visible). First DAMAGE 455 ms after launch |
| 13 | X11 rendering (Xvfb → mmap → Surface) | PASS | emulator screenshots. Container framebuffer decoded to PNG |
| 14 | Touch: tap | PASS | tap focused Brave's search box |
| 15 | Keyboard | PASS | IME + hardware keys typed `example.org`. Enter loaded it |
| 16 | Thai text input | PASS (container) | `example.org ทดสอบ` typed by the Kotlin client into Brave: URL shows the correct UTF-8 bytes |
| 17 | Navigation | PASS | typed URL → page. Search → Brave Search results |
| 18 | Scroll (drag) | PASS | one swipe scrolled the results page, with the scrollbar moving |
| 19 | Keyboard does not cover the page | PASS | page re-fits above the IME, bar moves up |
| 20 | Toolbar never covers the page | PASS (after fix) | letterboxed window: page area reserves the bar's size |
| 21 | Stop browser: no processes left | PASS (after fix) | 20 runtime processes → only the app |
| 22 | App killed: no orphans | PASS | after reinstall only the app process remained |
| 23 | Profile kept across restart | PASS | relaunch reused the profile ("Restore pages?" after a kill, as Brave does) |
| 24 | Storage per browser + runtime | PASS | program / profile / cache sizes shown and refreshed |
| 25 | Child-process limit warning | PASS | shown on Android 15, with Developer options + adb command |
| 26 | Chrome: Terms of Service gate | PASS | dialog appears before any download. Cancel starts nothing |
| 27 | Chrome install + startup | PASS (container) / **PENDING (device)** | container: 155.0.8059.84 installs from Google's repo, renders with these flags. On the emulator, the 5.8 GB partition has no room. Accepting Google's terms is the user's step |
| 28 | Brave Shields | PASS (visible) | Shields icon and stats card on the NTP. Blocking counts not measured |
| 29 | Audio | PASS (container) / **PENDING (device)** | PulseAudio export measured at the expected 48 kHz × 4 B/s. The emulator was headless (`-no-audio`) |
| 30 | X11 client: XTEST, keymap, DAMAGE | PASS | integration test against a real Xvfb + Brave |
| 31 | HTML5 video, fullscreen | **PENDING** | not run (emulator software GPU, no audio) |
| 32 | DHU / Android Auto | **PENDING** | needs the phone (the emulator has no Android Auto). Steps below |
| 33 | Regression of the built-in browser | PASS (unit) / **PENDING (DHU)** | built-in browser code untouched, its tests pass |

## Bugs found by testing, fixed

1. apt progress on stdout (`Status-Fd=1`) made maintainer scripts fail with "I/O error". Status now goes to its own file.
2. Stop left Xvfb and PulseAudio running (SIGTERM ignored by PRoot, no SIGKILL escalation on Android, `--kill-on-exit` skipped on SIGKILL). The tree is now read from /proc and killed.
3. The toolbar covered the page in a system-letterboxed window. The page area now reserves the toolbar and IME.
4. Storage figures were stale after installs. They now refresh when an operation ends.
5. Browser installs did not check free space. They do now.
6. Hard links are denied to apps (`tar: can't link … Permission denied`). The extractor already falls back to copying.

## Measurements

| Metric | Value | Where |
|---|---|---|
| APK increase | ~0.3 MB native + code | APK listing |
| Runtime download | 30.2 MB layer + ~110 MB packages | measured |
| Runtime installed | 485 MB (device), 538 MB (container, with apt lists) | measured |
| Brave installed | 487 MB program + 97–116 MB profile | device |
| Chrome installed | +507 MB | container |
| Runtime + Brave on device | 1.2 GB | settings |
| Cold start (tap Open → page) | under 30 s on the loaded emulator. ~2.1 s to first page in the container | screenshots / timing |
| Runtime memory | 1.1 GB summed RSS with Brave on its NTP (RSS double-counts shared pages) | diagnostics |
| Rendering | damage-driven, ≤30 fps cap. Idle ≈ 0.7 fps (keep-alive only) | diagnostics |
| Warm start, CPU %, FPS under video, touch latency | **not measured** | needs the phone |

## Pending on the phone: exact steps

```bash
./gradlew :app:assemblePersonalDebug
```

```bash
adb install -r -t app/build/outputs/apk/personal/debug/app-personal-debug.apk
```

1. Settings › Desktop Browser Engine › Install the runtime, then Brave and/or Chrome.
2. On Android 14+: Developer options › Disable child process restrictions.
3. Choose the browser, then Open on the phone: test tap, scroll, long-press, keyboard (Thai), audio (YouTube), video.
4. DHU: start the head unit server in Android Auto, then:

```bash
scripts/dhu-run.sh personal --no-build
```

5. Open "Desktop Browser" in the car launcher: check tap and pan alignment, keyboard (search template), back and reload, the parked gate (LAB mock MOVING → notice), disconnect/reconnect, and that leaving the screen keeps the browser (10-min idle stop).

```bash
adb logcat -s AutoBridgeDesktop
```
