Updated: 2026-09-08

JVM tests prove pure policy, resolver, transform, and state logic. They do not prove Android Auto host behavior, actual surface pixels, MediaProjection consent, speed callbacks, media routing, or input injection. Run these scenarios with a DHU/emulator first and a real head unit only after the safety path is understood.

## Setup

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

Enable Android Auto developer mode/unknown sources as required by the local DHU. Use `labDebug` on an emulator for the mock provider; a physical phone connected to a DHU does not activate the emulator-only simulator.

## LAB state and mirror

- Open AutoBridge from the DHU launcher.
- Select a non-`REAL_CAR` LAB environment and set mock state to `PARKED`.
- Grant MediaProjection consent on the phone.
- Confirm `ProjectionService` becomes `READY`, the surface has non-zero dimensions/DPI, and diagnostics show `car_surface_attached` and `virtual_display_created`.
- Confirm visible phone pixels in the surface.
- Set mock state to `MOVING`: projection stops and the car app exits.
- Set mock state to `UNKNOWN`: mirror/video/browser/touch/Quick Apps remain blocked.
- Return to `PARKED`: explicitly restart/restore only through the documented user flow.

## Self-drawn renderer matrix

Repeat the LAB mirror flow with `SELF_DRAWN` selected before requesting consent:

- verify diagnostics record `self_drawn_capture_created` and `self_drawn_surface_attached`;
- verify visible pixels for FIT, FILL, STRETCH, and ONE_TO_ONE, including safe insets and visible-area clipping;
- verify touch lands on the same phone content that is displayed, especially FILL crop edges and rotation;
- record frame counters, measured FPS, last latency, CPU/battery impact, and any ImageReader color/order issue;
- resize/reconnect the car surface and confirm stale generations do not draw into the replacement;
- test protected/DRM content separately and record blank output as a platform boundary, not a bypass target.

SELF_DRAWN is not by itself evidence of screen-off survival: its source is still the phone default display. On a device that passes the separate panel-only capability probe, test panel-off behavior independently. `OWN_CONTENT` is expected to be rejected/fallback because a dedicated app-display implementation does not exist.

With `personalDebug` or a release-like build on a supported host:

- request/grant `CAR_SPEED`;
- confirm a successful finite zero sample becomes `PARKED`;
- confirm a non-zero positive or negative sample becomes `MOVING`;
- stop updates for more than three seconds and confirm `UNKNOWN`;
- confirm no app-side override can turn an unavailable sample into `PARKED`.

A DHU configuration that returns unavailable `CarValue` data cannot prove the production gate. Record that as a host limitation, not as a reason to weaken the policy.

## Surface/reconnect

- Start mirror with an active projection and surface.
- Re-enter the car screen or disconnect/reconnect Android Auto.
- Confirm the original projection is reused when still alive, no new consent dialog appears, and the reconnect event/counter changes once per real detach/re-attach.
- Send duplicate surface callbacks and resize/replacement callbacks; confirm no duplicate virtual display or reconnect inflation.
- Stop projection from the phone, notification, and car control panel; confirm cleanup and rotation restoration.

## Input

- With `PARKED` and Accessibility enabled, test tap, scroll/fling, long press, pinch, Back, Home, and Recents.
- With Shizuku granted, bind the user service and test tap/swipe/keyevent; kill/disconnect the service and confirm the backend becomes unavailable and Accessibility can be selected where supported.
- If the service reports panel-power or `REAL_TOUCH` capability, record the capability probe and device/API details. The current Android Auto surface callbacks do not provide raw pointer IDs/actions, so do not convert `onScale` into a claimed raw-touch test; the raw router/sink remains a separate host-transport boundary.
- Tap visible letterbox bars and outside reported visible bounds; no phone gesture should be dispatched.
- Repeat while `MOVING`/`UNKNOWN`; all input must be denied.

## Apps and media

- Add/disable/remove Quick Apps and verify both phone/car lists use the same persisted records.
- Launch an app while parked; verify profile rotation is restored after stop/failure and autoMirror never bypasses capture consent.
- Play progressive, local, HLS, and DASH sources; test MediaSession play/pause/next/previous and audio continuity after video policy is denied.
- Verify an unknown MediaSession controller is rejected/logged and legitimate own/system/Android Auto controllers remain usable.
- Test WEB/AUDIO/VIDEO classification, local picker permission, favorites, last-source restore, and position restore.

## Diagnostics and screen power

- Verify the developer screen refreshes structured logs, mirror events, reconnect count, current transform/rotation, and lifecycle/frame diagnostics. In SELF_DRAWN, check captured/dropped/rendered counts, measured FPS, and last latency.
- Confirm the event ring can be cleared without resetting active uptime.
- Without the privileged setting, turning the phone screen off may pause/blacken the car image because AUTO_MIRROR and SELF_DRAWN capture the default display; record this as the expected unsupported/default behavior.
- On a device whose Shizuku service reports display-power capability, enable `Panel off on auto-dim` explicitly, wait for the configured timeout, and verify whether the physical panel turns off without locking/sleeping the device while the car surface remains usable. Record Android/OEM/API results; a failure must fall back to public dimming.
- Stop the projection, trigger `MOVING`/`UNKNOWN`, disconnect the Shizuku service, and confirm panel power is restored and any active real-touch stream is canceled before teardown.
- `OWN_CONTENT` remains unavailable regardless of the panel-power result; do not generalize a supported-device observation into a dedicated app-display claim.

## Evidence record

For every host run save APK flavor/build type, phone/Android version, Android Auto/DHU version, host connection type, surface width/height/DPI, speed callback status/value, Logcat, and screenshots. A scenario is `PARTIAL` until its platform-specific observation is recorded.

## Phone display controls (2026-09-10; pending device verification)

1. Start a permitted mirror while parked; enable Prevent Sleep and a 15-second
   auto-dim delay. After idle, verify phone dimming and continuous car pixels.
2. Enable Panel off on auto-dim with Shizuku available. Use Dim phone now from
   the car control panel. Wait longer than the phone system screen timeout;
   verify the car image continues and car gestures do not wake the phone panel.
3. Use Restore phone screen. Verify the panel returns and the idle timer restarts.
4. Repeat without a panel-power backend: the result should be dimming, not a
   false panel-off success. Turn both idle settings off, manually dim and restore,
   then verify no AutoBridge screen wake lock remains using dumpsys power.
5. Stop mirroring/disconnect after panel-off. Verify restoration and wake-lock
   release. If restoration fails, reconnect Shizuku and use Restore phone screen;
   verify the UI does not claim success until the backend accepts restoration.
6. With no active mirror or an unknown/moving vehicle state, verify Dim phone now
   is rejected; restoration must remain available.

## Browser toolbar inspired by the supplied Fermata reference (pending host verification)

- Open Browser while parked on portrait and landscape surfaces. Verify the dark toolbar has back, forward, reload, address, save bookmark, and menu controls with no speed overlay covering the page. Toolbar drawing and touch zones scale together within the host's stable area.
- Enter a URL or query through the address field. Follow a link, then use back/forward. Back with no page history stays in the browser; the host Back action exits the screen.
- Save the current page with Menu → More → Bookmark. Open Menu → Bookmarks, select it, and verify it opens in the same browser with history retained. Save more than four pages and verify bookmark pagination.
- Open the menu and check the sheet itself: the header names the page, the address row reports it, Back is dimmed with no history behind it, and the desktop-site switch shows the state it toggles and leaves the sheet open when tapped. Menu → More and its Back return to the same sheet without it blinking out.
- Use Menu → Start Page, the desktop switch, and Fullscreen. The fullscreen handle restores the toolbar without reloading. Check touch coordinates after a surface resize and fullscreen transition.
- On a short stable area (the 800x320 a DHU session reports), verify the sheet scrolls to its footer and that the header's `✕ Close` stays put while it does.
- Check the phone remote's loading indicator while a page loads and after completion.
- Verify ordinary web content and video separately. This remains an off-screen WebView drawn to a software Canvas; matching toolbar appearance does not establish Fermata-equivalent video playback or hardware-composited/DRM support.

## Shared phone / Android Auto browser (pending device verification)

- On the phone, choose OPEN BROWSER without capture consent. Verify the compact dark toolbar has back/forward/reload, editable URL, bookmark and menu controls, and the keyboard does not cover the focused address.
- Use the same URL/search, bookmark set, and Desktop preference from either display. A newly created browser starts at the last completed HTTPS page; an already open browser retains its own history.
- From the phone menu (More → Send to car / Get from car), send the current page to Android Auto, or receive the active car browser page. With no car session, verify a connection message rather than a crash. These are explicit URL transfers, not live page/scroll/video synchronization.
- Rotate the phone, background/resume, and toggle fullscreen. Verify page history is restored and the fullscreen handle restores the toolbar. Check the blocked view when browser policy denies use.
- Start Mirror separately and open the phone browser to see the same phone pixels on the car. Opening the native car browser stops projection as before.

## Compact Home inspired by the supplied Fermata Xtream image

- Open Home and verify Browser, Mirror, YouTube, Media, Apps, More in that order. Tiles use original monochrome artwork with dark rounded backgrounds and thin outlines. Titles remain host-rendered beneath the artwork; the image border is not a border around the entire host tile.
- Verify More opens Agent, Recent, Driving, and Settings, with Back returning to Home. Verify the Settings speed-permission action still works.
- Test portrait/landscape and Car API below/above 8: request SMALL grid items on API 8+, use ICON images on older hosts, and use legacy headers below API 7. Android Auto controls columns, full tile borders, header height, text size and spacing; a fixed 3-by-2 layout is not promised.
- Verify the YouTube shortcut opens https://m.youtube.com in the browser. This shortcut does not add video-rendering capabilities.
- No device was connected during implementation; on-car visual and input checks remain pending.

Reference: https://github.com/malebuffy/Fermata-Xtream (visual reference only; no upstream code/assets copied). Grid sizing: https://developer.android.com/reference/androidx/car/app/model/GridTemplate.Builder#setItemSize(int).

## DHU run 2026-09-29 (Xiaomi 13T Pro, Android 16, DHU 2.0 mac-arm64)

Transport: AOA over direct USB (`desktop-head-unit -u <serial>`). ADB port forwarding was not
needed. Protocol 1.7, TLS negotiated, `AutoBridgeCarAppService` bound and a 800x400 virtual display
created.

### Verified working

| Area | Result |
|---|---|
| Car app binds and creates its session | `AutoBridgeCarSession: onCreateScreen` on every connect |
| Home dashboard | Renders the shared `HomeSection` tiles on the head unit |
| Grid pagination | The host reported a grid content limit of 12 in one session and 6 in another; the tile list adapted both times, and "More" led to the remaining sections |
| `CarHomeMoreScreen` | Media Center, Manage bookmarks, Agent, Recent, Driving, Settings all listed |
| Media Center | TabTemplate renders Music / Video / Streaming after the two fixes below |
| Browser on the car surface | `CarWebRenderer` drew Google and YouTube Music, including the drawer, at 800x400 |

### Bugs found on the head unit and fixed

1. **Media Center crashed the car app.** `TabTemplate.Builder.setHeaderAction(Action.BACK)` throws
   `IllegalArgumentException: Missing required action types: APP_ICON`. Only `APP_ICON` is allowed
   there. Fixed; the host draws its own back affordance.
2. **Media Center crashed again, one layer down.** With the header fixed, `Tab.Builder` without an
   icon throws `IllegalStateException: A icon must be set for the tab`. Each tab now carries the
   same `DashboardArtwork` icon its dashboard tile uses.
3. **Two tiles labelled "Settings" on the last dashboard page.** The trailing tile fell back to the
   label "Settings" when there was nothing more to page to, while still opening the overflow list —
   right next to the real Settings tile. It is now always "More".

### Observed, not a defect

- "Resume last session" was enabled in this install (it defaults to off). On connect it pushes the
  last screen — here the browser — on top of Home, so the dashboard is visible for a moment and
  then covered. Dashboard taps during that window do nothing, which reads as an unresponsive grid.
- Reinstalling the APK while the projection session is live leaves the car app in an ANR
  ("AutoBridge ไม่ตอบสนอง"). A force-stop and relaunch recovers it. Install with the app stopped.
- Tab icons render as plain light squares at tab size: `DashboardArtwork`'s compact form draws a
  dark card with a white glyph, and the host tints the whole bitmap. Cosmetic only.

### Not verified in this run

- Folders / Playlists / Gallery on the car: the phone has not granted media permission.
- Mirror.

## DHU run 2026-09-29b — IPTV end to end (same phone, same DHU)

The gap left by the previous run was the whole IPTV path, which needs a real playlist. Two public
ones were used, both added through the app's own "+ M3U" dialog rather than by writing preferences:

| Source | URL | Content |
|---|---|---|
| Thai Free TV | `https://iptv-org.github.io/iptv/countries/th.m3u` | 78 live channels, 13 groups |
| Thai Radio | `https://de1.api.radio-browser.info/m3u/stations/bycountry/thailand` | 134 stations, no groups |

No Xtream portal was tested: that needs a paid account. The Xtream URL/credential forms stay covered
by `IptvParsingTest`.

### Verified on the head unit

| Step | Result |
|---|---|
| TV → source → categories | 78 entries • 13 categories, counts matching the file exactly |
| Category → entries | Rows render, Thai script included |
| Play a live channel | 3HD played full-screen video on the car surface |
| Radio → source → All | 134 stations listed |
| Play a station | Audio played, Now Playing showed title and Pause/Next |
| Favorites | A channel starred on the phone appeared under "Channels", next to the web bookmarks, and played |
| Recently played | Populated from both surfaces |

### Bugs found and fixed

4. **Every IPTV channel opened a dead player.** `PlayerActivity.render()` runs before the media
   session controller has connected, and `MediaPlaybackClient.play()` silently drops a command
   issued with no controller. `render()` still set `started = true`, so the real start from
   `onConnected` was skipped and the screen sat at 00:00 forever. `startPlayback()` now waits for
   the controller, and a dropped command is logged instead of vanishing.
5. **A failed stream looked identical to one still loading.** `onPlayerError` showed a notice that
   the 500 ms `render()` tick immediately painted over with the (black, empty) video view. The
   failure is now held in state, and the play button retries it — `resume()` alone cannot recover
   from an error, because ExoPlayer needs a fresh `prepare()`.
6. **A live channel was drawn as a 27-second clip.** Live HLS reports the length of its sliding
   window, so `duration <= 0` is not what makes a stream live; 3HD showed a scrub bar and no LIVE
   badge. The badge now follows `Player.isCurrentMediaItemLive`.
7. **A Radio playlist was filtered down to almost nothing.** `loadPlaylist` ran an audio-word
   heuristic over any source filed under Radio. Real station names — "88 nice peak", "90.5 Delight",
   "97qfm" — match none of it. The heuristic now applies only to an Xtream portal playlist, which
   genuinely mixes TV and radio in one URL.
8. **"1 sources", "1 entries", "1 categories".** Counts are now pluralised.

### Observed, not a defect

- Roughly a third of the free channels fail with `ERROR_CODE_IO_BAD_HTTP_STATUS` (ALTV, Amarin TV).
  These are dead or geo-restricted public streams, not a client fault — the app now says so and
  offers a retry.
- `keycode back` does nothing while the DHU is in touch mode; the on-screen back arrow pops
  normally. A DHU input-mode artifact.

### The parked gate does not engage, by construction

With `restrict all` and `speed 60` applied, video kept playing full-screen on the head unit. That is
what this tree is currently written to do:

- `FeaturePolicy.decide()` copies the runtime context to `vehicleState = PARKED` before evaluating.
- `RuntimeContextStore` and `MockVehicleStateProvider` publish `PARKED` unconditionally; the real
  `SpeedGate` / `VehicleStateProviderFactory` wiring is commented out.
- `build.gradle.kts` sets `DHU_TEST_MODE = true` for release as well as debug.

So no gate can fire regardless of what the head unit reports. The five failing unit tests
(`AppProfileRegressionTest` ×3, `CoreRegressionTest` ×2) are the assertions for exactly this
behaviour and have been failing since before these changes. Restoring the gate is a deliberate
decision, not something to patch in passing.

## Driving a real drag (page scroll) on the head unit

`CarWebRenderer.scrollBy` / `fling` are reached only through `SurfaceCallback.onScroll` /
`onFling`, which the Android Auto host raises from a genuine touch drag. Producing one is harder
than it looks, and two obvious routes are dead ends — both tested, not assumed:

- **DHU has no drag command.** Its `help` lists exactly one touch command, `tap`. There is no
  `swipe`, `move`, `drag` or `touch down/up`, so no scripted DHU input can scroll a page.
- **`adb shell input -d <display>` does not reach the template host.** The host's displays
  (`CarAppService`, `TemplateCarFragment`, `GhostActivityDisplay:…TemplateNavigationService`) accept
  the command silently and nothing happens — no dispatch log, no app reaction. The custom surface is
  not on those displays' input channels; it is a `Surface` the host hands the app, and touch arrives
  by IPC, not by display input.

What does work is a **real mouse drag inside the DHU window**, which DHU turns into touch
down/move/up on the wire. Two things make that reliable:

- DHU is an SDL app, so the Accessibility API cannot enumerate its windows: `System Events` reports
  `count of windows = 0` and `set frontmost` fails. Find and capture it through CoreGraphics
  instead — `CGWindowListCopyWindowInfo` gives its window id and bounds, and
  `screencapture -l <id>` captures it even when it is occluded.
- DHU launched detached (`nohup`, or piped from a `tail` fifo) renders and answers `screenshot`, but
  has **no on-screen window**, so no mouse input can reach it. It must be launched from a GUI
  terminal session.
- With Stage Manager enabled, activating any other app moves DHU off-stage and the drag lands on
  whatever replaced it. Confirm DHU is on-screen via the CoreGraphics list immediately before each
  gesture; a capture that shows another app's content means the gesture was not delivered.
