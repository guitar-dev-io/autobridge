# AutoBridge UI Refactor Task + Checklist

## Goal
Refactor the Android Auto AutoBridge Home dashboard to match the approved reference UI as closely as possible while preserving the current Surface/Canvas architecture, navigation behavior, fallback behavior, and DHU compatibility.

## Status (code complete, DHU validation pending)
- All implementation and architecture work is done and verified by build + unit tests:
  `:app:assemblePersonalDebug` succeeds and the full `:app:testPersonalDebugUnitTest` suite is
  **487 tests, 0 failures** (incl. `HomeDashboardLayoutTest`, `HomeDashboardClockTest`).
- A `[x]` below means confirmed by reading the code, the build, or an automated test.
- Items left `[ ]` need a running Desktop Head Unit (install, screenshots, live navigation,
  resize, reconnect, visual comparison) or live regression of each feature — these cannot be
  confirmed from the build host. Sections **M** (feature regression) and **O** (DHU validation)
  are the main remaining work, plus the §19 final report once screenshots exist.

---

# PART 1 — IMPLEMENTATION TASK

## 1. Preserve Existing Architecture
- Keep `CarHomeDashboardScreen` as the main Android Auto Home screen.
- Keep `NavigationTemplate` as the Android Auto host shell.
- Keep `SurfaceCallback` and Canvas-based rendering.
- Keep `MirrorSurfaceOwnership`.
- Keep existing `CarHomeNavigator` destination behavior.
- Keep `GridTemplate` fallback for Car API < 5.
- Do not rewrite the screen using Compose, Fragment, or Activity.
- Do not remove working playback, browser, TV, radio, streaming, or mirror logic.

The reference image is a visual target only.
The existing AutoBridge Surface/Canvas architecture is authoritative.
Adapt the design to the current implementation instead of rewriting the project around the mockup.

---

## 2. New Home Dashboard Structure

Refactor the current Home screen from a simple 3x2 launcher into a media dashboard.

Target hierarchy:

1. AutoBridge Header
2. Continue Watching
3. Quick Access
4. Recently Sent
5. Queue

Do not draw over Android Auto system UI.

Respect:
- `stableArea`
- `visibleArea`
- surface dimensions
- head-unit DPI

---

## 3. Header

Create a compact AutoBridge header.

Display:
- AutoBridge logo
- AutoBridge brand name

Keep the existing Android Auto ActionStrip / More action behavior.

Do not add another Settings button if Settings is already reachable from More.

---

## 4. Continue Watching

Add a full-width Continue Watching card.

Layout example:

```text
[ Thumbnail ]  Japan Travel 4K - Amazing Places
               YouTube
               █████████░░░░    12:34 / 31:20
```

Requirements:
- rounded thumbnail
- play overlay
- title
- source
- progress bar
- current position
- total duration
- entire card clickable
- ellipsize long title
- hide section if no valid resumable item exists

Use existing session/recent/playback state where possible.

Do not use fake production data.

If additional metadata is needed, extend the existing state/store cleanly.

Useful metadata:
- title
- source
- URL/media ID
- thumbnail
- positionMs
- durationMs
- timestamp

---

## 5. Quick Access

Keep the existing 6 main destinations:

- YouTube
- YouTube Music
- Streaming
- TV
- Radio
- Web Browser

Layout:
- 3 columns x 2 rows
- large touch targets
- rounded cards
- icon centered
- label below icon

Visual state:

### Normal
Dark card with subtle border.

### Focused
Bright AutoBridge blue border.
Optional subtle glow if rendering cost stays low.

### Pressed
Slightly darker fill / depressed state.
Use existing short press feedback timing.

Do not change what each menu item opens.

---

## 6. Recently Sent

Add a Recently Sent section.

Home should show a maximum of 2 items.

Each item should contain:

```text
[thumbnail]  Title
             Source • Sent from phone • Relative time        [Play]
```

Header:
`Recently Sent >`

Behavior:
- click item = open/play
- click header = open full Recently Sent/Recent screen

Use existing `RecentActivityStore`, Send-to-Car state, or other existing state before creating new persistence.

If origin metadata is needed, consider a field similar to:

```text
CAR
PHONE
SHARE_INTENT
AGENT
```

Items from phone/share flow can populate Recently Sent.

If there are no items:
- hide section
- collapse layout naturally

---

## 7. Queue

Add a compact Queue card.

Header:
`Queue 3 >`

Show up to 3 items:

```text
[thumb] Japan Travel 4K          31:20
[thumb] Chill Lofi Mix         1:02:34
[thumb] City Night Drive        28:15
```

Requirements:
- click header = full queue
- click item = play/select item
- no drag-and-drop required on car UI
- do not waste large space when queue is empty

If no queue exists yet, first inspect current playback/session code and reuse existing playlist/queue infrastructure if available.

---

## 8. Responsive Layout

Do not hard-code the mockup resolution.

Create or refactor layout computation based on:
- safe area
- width
- height
- head-unit DPI

Suggested layout model:

```text
HomeDashboardLayout
├── headerBounds
├── continueWatchingBounds
├── quickAccessTitleBounds
├── quickAccessCardBounds[6]
├── recentlySentBounds
├── queueBounds
├── scrollUp
├── scrollDown
├── maxScroll
└── scrollStep
```

Wide DHU:
- Recently Sent and Queue can sit side-by-side.

Narrow DHU:
- stack Recently Sent and Queue vertically.

If content exceeds vertical space:
- keep existing scrolling behavior
- do not reduce button size too aggressively

---

## 9. Renderer Refactor

Prefer a clear renderer structure such as:

```text
HomeDashboardRenderer
HomeDashboardLayout
HomeDashboardTheme
```

Optional reusable renderer pieces:

```text
ContinueWatchingRenderer
QuickAccessRenderer
RecentSentRenderer
QueueRenderer
```

Do not over-engineer if the current codebase favors simpler classes.

Reuse current project components where possible.

---

## 10. Touch Hit Testing

Update Surface click handling for the new dashboard.

Priority:

1. Continue Watching
2. Quick Access
3. Recently Sent header
4. Recently Sent items
5. Queue header
6. Queue items
7. scroll controls

Preserve current surface release behavior before opening another screen.

Do not break:

```text
releaseSurface()
CarHomeNavigator.open(...)
```

Browser/video/mirror screens must still be able to claim their own surface producer.

---

## 11. Icons

Find and add consistent icons.

Required icons:
- AutoBridge logo
- YouTube
- YouTube Music
- Streaming
- TV
- Radio
- Web Browser
- Play
- Queue
- More / Apps
- Chevron Right

Preferred approach:
- official brand assets where appropriate
- Material Symbols / Material Icons for generic actions
- existing project assets where suitable

Suggested generic mappings:
- TV → television
- Radio → radio
- Web → language/public
- Streaming → live_tv/video_library
- Play → play_arrow
- Queue → playlist_play / queue_music
- More → apps
- Chevron → chevron_right

Do not use random icon packs from unknown sources.

Keep the icon family visually consistent.

---

## 12. Icon Asset Loading

Inspect `DashboardArtwork` first.

If it can be extended, reuse it.

If vector drawables need Canvas bitmap conversion, create a reusable cached helper.

Example responsibility:

```text
CarIconAssets.bitmap(
    context,
    drawableRes,
    sizePx
)
```

Requirements:
- cache decoded bitmap
- cache scaled bitmap when sensible
- never decode drawables on every render frame

---

## 13. Theme Tokens

Centralize styling.

Example groups:

```text
background
surface
surfaceElevated
cardBorder
focusedBorder
primaryBlue

textPrimary
textSecondary

cornerRadiusLarge
cornerRadiusMedium

headerTextSize
sectionTextSize
cardTextSize
bodyTextSize

horizontalPadding
verticalPadding
sectionGap
cardGap
```

Use head-unit DPI.

Do not use phone density for the car layout.

Avoid scattered magic numbers.

---

## 14. Rendering Performance

Keep Canvas rendering efficient.

Requirements:
- reuse `Paint`
- cache bitmap/icon resources
- reuse typefaces
- avoid repeated drawable decoding
- minimize allocations in `draw()`
- avoid continuous redraw loops
- only redraw when state changes

Continue using:

1. `lockHardwareCanvas()`
2. fallback to `lockCanvas()`

---

## 15. Required States

Verify UI for:

- Continue Watching exists
- no Continue Watching
- Recently Sent exists
- no Recently Sent
- Queue has items
- Queue empty
- Recent and Queue both absent
- long title
- missing thumbnail
- different DHU resolutions
- different DPI
- resize/reconnect
- Car API < 5 fallback

Use clean fallback artwork when thumbnails are unavailable.

---

## 16. Android Auto Host UI

Never draw over:
- bottom Android Auto dock
- side rail
- host chrome

Preserve the current safe-area behavior.

---

## 17. Older Host Fallback

Do not remove `gridTemplate()`.

For Car API < 5:
- keep the existing native GridTemplate route
- keep all current destinations accessible
- custom dashboard additions are optional there

---

## 18. DHU Testing

After implementation:

- build the project
- fix compilation issues
- launch Desktop Head Unit
- capture screenshots
- test navigation and surface ownership

Test at more than one DHU resolution if available.

---

## 19. Final Delivery

Return a report containing:

- files inspected
- files changed
- classes added
- icon assets added
- source of icon assets
- state/data sources reused
- new state/data added
- DHU test results
- before screenshot
- after screenshot
- known limitations
- remaining issues

Do not finish with analysis only.
Implement, build, run, test, and verify.

---

# PART 2 — IMPLEMENTATION CHECKLIST

## A. Pre-change Inspection

- [x] Locate `CarHomeDashboardScreen`
- [x] Locate `HomeMenuRenderer` (replaced by `HomeDashboardRenderer`)
- [x] Locate `HomeMenuLayout` (replaced by `HomeDashboardLayout`)
- [x] Locate `HomeMenuTheme` (replaced by `HomeDashboardTheme`)
- [x] Locate `HomeMenuItem`
- [x] Locate `DashboardArtwork`
- [x] Locate `RecentActivityStore`
- [x] Locate Send-to-Car state/logic (`BridgeStore`, `RecentActivityStore` origin PHONE/SHARE)
- [x] Locate existing media/session state (`BridgeStore.lastSession`)
- [x] Locate existing queue/playlist implementation (`BrowserPlayQueue`)
- [x] Locate browser screen
- [x] Locate YouTube screen
- [x] Locate YouTube Music screen
- [x] Locate Streaming screen
- [x] Locate TV screen
- [x] Locate Radio screen
- [x] Confirm current `MirrorSurfaceOwnership` flow
- [x] Confirm current `releaseSurface()` flow
- [x] Confirm Car API < 5 fallback still works before editing (`gridTemplate()` retained)

---

## B. Architecture

- [x] Keep `NavigationTemplate`
- [x] Keep `SurfaceCallback`
- [x] Keep Canvas renderer
- [x] Keep GridTemplate fallback
- [x] Create/update `HomeDashboardLayout`
- [x] Create/update `HomeDashboardRenderer`
- [x] Create/update central dashboard theme
- [x] Avoid Compose/Activity rewrite
- [x] Avoid duplicate navigation/state systems

---

## C. Header

- [x] AutoBridge logo rendered
- [x] AutoBridge text rendered (Auto + blue Bridge wordmark)
- [x] Host More/Apps action still works (action strip -> `CarHomeMoreScreen`)
- [x] No duplicate Settings button
- [x] Header respects safe area

---

## D. Continue Watching

- [x] Read real resume state (`BridgeStore.lastSession`)
- [x] Thumbnail rendered
- [x] Fallback thumbnail supported (accent gradient + glyph)
- [x] Title rendered
- [x] Long title ellipsized
- [x] Source rendered
- [x] Progress rendered
- [x] Time/duration rendered (`HomeDashboardClock.elapsed`)
- [x] Play overlay rendered
- [x] Entire card clickable (`HomeRegion.CONTINUE`)
- [x] Resume opens correct content (`AutoBridgeSessionManager.resume`)
- [x] Section hides if no resume item (`hasContinueWatching`)
- [x] Layout collapses when hidden (band share handed back)

---

## E. Quick Access

- [x] YouTube card
- [x] YouTube Music card
- [x] Streaming card
- [x] TV card
- [x] Radio card
- [x] Web Browser card
- [x] 3x2 layout
- [x] touch targets large enough (CARD_MIN_HEIGHT floor + scroll)
- [x] normal state
- [x] focused state
- [x] pressed state
- [x] each card opens existing destination (`CarHomeNavigator.open`)
- [x] returning Home preserves focused section (`focusedSection`)

---

## F. Icons

- [x] AutoBridge icon verified (`ic_autobridge_launcher`)
- [x] YouTube icon added/verified (vector glyph, `HomeMenuCard.drawGlyph`)
- [x] YouTube Music icon added/verified (vector glyph)
- [x] Streaming icon added/verified (vector glyph)
- [x] TV icon added/verified (vector glyph)
- [x] Radio icon added/verified (vector glyph)
- [x] Web icon added/verified (globe vector glyph)
- [x] Play icon added (vector play triangle)
- [x] Queue icon added (count pill + chevron in block header)
- [x] More/Apps icon verified (`ic_action_apps`)
- [x] Chevron icon added (drawn as path in `drawBlock`)
- [x] icon sources documented (code-drawn vector glyphs + existing project drawables)
- [x] consistent visual family (one `drawGlyph` stroke style for all)
- [x] no unknown/random icon packs
- [x] bitmaps/vector assets cached (`CarThumbnails`/`ImageLoader`; glyphs are vector draws)

---

## G. Recently Sent

- [x] Inspect existing Send-to-Car data
- [x] Reuse existing recent store where possible (`RecentActivityStore`)
- [x] Add origin metadata only if required (`Origin.PHONE/SHARE`, nullable)
- [x] Show max 2 items on Home (`MAX_RECENTLY_SENT`)
- [x] Thumbnail
- [x] Title
- [x] Source
- [x] relative sent time (`relativeAge`)
- [x] play/open button
- [x] item click works (`HomeRegion.RECENT_ITEM`)
- [x] header click works (`HomeRegion.RECENT_HEADER` -> `CarRecentScreen`)
- [x] empty state collapses (`hasRecentlySent`)
- [x] no duplicated persistence layer

---

## H. Queue

- [x] Reuse existing queue if available (`BrowserPlayQueue`)
- [x] Show queue count (`queueTotal` count pill)
- [x] Show max 3 items (`MAX_QUEUE_ROWS`)
- [x] Thumbnail/fallback artwork
- [x] Title
- [x] Duration (`HomeDashboardClock.duration`, source as fallback)
- [x] item click works (`HomeRegion.QUEUE_ITEM`, consumes on accept)
- [x] header click works (`HomeRegion.QUEUE_HEADER` -> `CarQueueScreen`)
- [x] empty queue handled cleanly (`hasQueue`)
- [x] no drag-and-drop required

---

## I. Hit Testing

- [x] Continue Watching hit region
- [x] Quick Access card hit regions
- [x] Recently Sent header hit region
- [x] Recently Sent item hit regions
- [x] Queue header hit region
- [x] Queue item hit regions
- [x] scroll-up hit region
- [x] scroll-down hit region
- [x] no overlapping hit regions (unit test: `no two regions overlap`)
- [x] press feedback still works (`PRESS_FEEDBACK_MS` flash)

---

## J. Responsive Layout

- [x] Uses `stableArea`
- [x] Uses `visibleArea`
- [x] Uses surface dimensions
- [x] Uses head-unit DPI
- [x] wide layout tested (unit test: side-by-side blocks)
- [x] narrow layout tested (unit test: stacked blocks)
- [x] recent/queue side-by-side when possible
- [x] recent/queue stack when necessary
- [x] vertical scroll works (unit test + DHU live: layout flips `scroll=true` when content exceeds band)
- [ ] no clipping (needs DHU visual confirmation — screenshot blocked)
- [x] no content under Android Auto bottom bar (safe area clamp; live `visibleArea Rect(24,24-776,388)` drives layout; final visual confirm pending screenshot)

---

## K. Performance

- [x] Paint instances reused (all Paints/Paths/Typefaces built once in renderer)
- [x] Typeface reused
- [x] bitmaps cached (`CarThumbnails`/`ImageLoader`, gradient shaders keyed+cached)
- [x] no repeated drawable decoding
- [x] minimal allocation in render loop
- [x] no unnecessary continuous redraw (renders only on state change)
- [x] hardware Canvas used first (`CarSurfaceCanvas`)
- [x] Canvas fallback retained (`lockCanvas` fallback in `CarSurfaceCanvas`)

---

## L. Surface Ownership

- [x] Home claims surface correctly (`MirrorSurfaceOwnership.claim`)
- [x] Home releases surface before Browser (`handOverSurface`/`releaseSurface`)
- [x] Home releases surface before Video (`handOverSurface`/`releaseSurface`)
- [x] Home releases surface before Mirror (`handOverSurface`/`releaseSurface`)
- [x] child screen can attach its producer (release happens only on accepted handover)
- [x] Back returns surface ownership to Home (`onStart` re-claims + re-renders)
- [x] reconnect does not leak surface (DHU live: force-stop+relaunch logs a single `claim CarHomeDashboardScreen (was none)`, no stale/double-claim/orphan anomaly)
- [x] screen switching does not crash (DHU live: launch → open Browser → BACK → reconnect cycle, `AndroidRuntime:E` for `dev.autobridge` empty)

---

## M. Existing Features Regression

<!-- Live DHU run exercised Home → Browser open → BACK → reconnect with no crash and
     correct surface handover. The per-destination content checks below still need
     interactive/visual confirmation on the DHU (each app actually loading its content). -->

- [ ] YouTube works (not individually driven this run)
- [ ] YouTube Music works (not individually driven this run)
- [ ] Streaming works (not individually driven this run)
- [ ] TV works (not individually driven this run)
- [ ] Radio works (not individually driven this run)
- [x] Web Browser works (live: opened google.com, surface handover + BACK reclaim logged, no crash)
- [ ] Mirror works (not individually driven this run)
- [ ] Quick Launch/Apps works (needs interactive confirm; More→`CarHomeMoreScreen` route present)
- [ ] Settings accessible (needs interactive confirm; reachable via More action)
- [x] Back navigation works (live: BACK returns to Home, surface re-claimed, re-render logged)
- [ ] Resume last session still works (needs a seeded session; current live state had none)
- [ ] Vehicle safety logic still works (needs driving-state toggle on DHU)

---

## N. Car API Compatibility

- [x] Car API >= 5 custom dashboard path present (`drawsMenu` gate)
- [x] Car API < 5 GridTemplate fallback path present (`gridTemplate()`)
- [x] host action strip valid (non-empty single icon action)
- [x] surface callbacks valid (`SurfaceCallback` implemented)
- [x] rotary fallback remains accessible (More -> `CarHomeMoreScreen` route to all sections)
  <!-- Live confirmation of each on a real host still pending DHU validation (section O). -->


---

## O. DHU Validation

- [x] Project builds successfully (`:app:assemblePersonalDebug` BUILD SUCCESSFUL)
- [x] App installs successfully (`adb install -r` → `Success`, v0.4.12 vc26 on `YXEMRCGYAI49S4SS`)
- [x] DHU connects (`AutoBridge: car.connected`, `session.initialized connected=true`)
- [x] AutoBridge Home opens (`CarHome: surface 800x400 dpi=160` → dashboard layout logged)
- [ ] screenshot captured before changes (BLOCKED: capture needs a terminal with macOS Screen Recording permission — run `scripts/dhu-shot.sh` from Terminal.app/iTerm)
- [ ] screenshot captured after changes (BLOCKED: same — `scripts/dhu-shot.sh after_home`)
- [ ] visual hierarchy matches reference (needs screenshot)
- [x] Continue Watching tested (live empty-state path: `hero=false` → section collapsed; populated path covered by `HomeDashboardLayoutTest`)
- [x] six Quick Access cards tested (live: `layout card=192x96` grid computed; nav+surface claim fire on open)
- [x] Recently Sent tested (live empty-state: `recent=0` → collapsed; populated path unit-tested)
- [x] Queue tested (live empty-state: `queue=0` → collapsed; populated path unit-tested)
- [x] scroll tested (live: layout flips `scroll=true` when content exceeds band)
- [ ] screen resize tested (partial — live `visibleArea` recompute 197x98→192x96 observed; multi-resolution DHU resize still needs visual check)
- [x] disconnect/reconnect tested (live: clean re-claim, no leak/crash)
- [x] app does not crash (live: no `AndroidRuntime:E` for `dev.autobridge` across all exercised paths)

---

## P. Final Review

- [ ] Compare actual DHU screenshot with reference
- [ ] Check card spacing (on DHU)
- [ ] Check corner radii (on DHU)
- [ ] Check icon size consistency (on DHU)
- [ ] Check typography hierarchy (on DHU)
- [ ] Check focused blue border (on DHU)
- [ ] Check pressed feedback (on DHU)
- [ ] Check long Thai text (on DHU)
- [ ] Check long English text (on DHU)
- [x] Check no fake placeholder production content (all sections read real stores)
- [x] Remove debug-only temporary code (only intentional `StructuredLog` production logging remains)
- [x] Final build successful (`:app:assemblePersonalDebug`; 487 unit tests pass)
- [x] Final report completed (see §19 report below; screenshots still outstanding — see Known Limitations)

---

# §19 FINAL DELIVERY REPORT

_Generated after an on-device DHU validation run on 2026-10-03. Build: `dev.autobridge`
v0.4.12 (versionCode 26), flavor `personalDebug`. Device: `YXEMRCGYAI49S4SS` (Xiaomi corot)
projecting to Desktop Head Unit (DHU 2.0, surface 800x400 @ dpi 160)._

## Build & test status
- `:app:assemblePersonalDebug` → **BUILD SUCCESSFUL**.
- `:app:testPersonalDebugUnitTest` → **487 tests, 0 failures, 0 errors** (counted from
  `app/build/test-results/testPersonalDebugUnitTest/*.xml`), incl. `HomeDashboardLayoutTest`
  and `HomeDashboardClockTest`.
- APK installed on device: `adb install -r -d …/app-personal-debug.apk` → `Success`.

## Files changed (dashboard refactor — current working tree)
Modified:
- `app/src/main/java/dev/autobridge/car/CarHomeDashboardScreen.kt`
- `app/src/main/java/dev/autobridge/car/HomeMenuCard.kt`
- `app/src/main/java/dev/autobridge/bridge/AutoBridgeSessionManager.kt`
- `app/src/main/java/dev/autobridge/bridge/BridgeStore.kt`
- `app/src/main/java/dev/autobridge/browser/BrowserPlayQueue.kt`
- `app/src/main/java/dev/autobridge/core/state/RecentActivityStore.kt`
- (plus README, build.gradle.kts, strings, ImageLoader, PhoneHomeLayout, library/* support)

Added:
- `app/src/main/java/dev/autobridge/car/HomeDashboardLayout.kt`
- `app/src/main/java/dev/autobridge/car/HomeDashboardRenderer.kt`
- `app/src/main/java/dev/autobridge/car/HomeDashboardContent.kt`
- `app/src/main/java/dev/autobridge/car/HomeDashboardTheme.kt`
- `app/src/main/java/dev/autobridge/car/CarQueueScreen.kt`
- `app/src/main/java/dev/autobridge/car/CarThumbnails.kt`
- `app/src/test/java/dev/autobridge/car/HomeDashboardLayoutTest.kt`
- `app/src/test/java/dev/autobridge/car/HomeDashboardClockTest.kt`

Deleted (replaced by the Dashboard equivalents):
- `app/src/main/java/dev/autobridge/car/HomeMenuLayout.kt`
- `app/src/main/java/dev/autobridge/car/HomeMenuRenderer.kt`
- `app/src/main/java/dev/autobridge/car/HomeMenuTheme.kt`

> NOTE: these are **uncommitted working-tree changes** at report time (git HEAD is
> `42e2b29 Add the car Home UI refactor task + checklist`). Commit before shipping.

## State / data sources reused (no new persistence layer)
- Continue Watching ← `BridgeStore.lastSession`, resume via `AutoBridgeSessionManager.resume`.
- Recently Sent ← `RecentActivityStore` (origin `PHONE`/`SHARE`).
- Queue ← `BrowserPlayQueue`.
- Thumbnails ← `CarThumbnails` / `ImageLoader` (cached).

## Icon assets
Code-drawn vector glyphs (one shared `drawGlyph` stroke style) plus existing project
drawables (`ic_autobridge_launcher`, `ic_action_apps`). No third-party icon packs.

## DHU test results (live, via adb + logcat — see `docs/dhu-shots/validation-log.txt`)
PASS, confirmed from device logs:
- Dashboard renders from the real surface: `CarHome: surface 800x400 dpi=160`.
- Responsive layout from safe area: recompute `card=197x98` → `card=192x96` after
  `visible area Rect(24,24-776,388)`; `scroll` flips to `true` when content exceeds the band.
- Empty-state collapse live: `hero=false recent=0 queue=0` (Continue Watching / Recently Sent
  / Queue all hidden with no data).
- Surface ownership: single `MirrorSurface: claim CarHomeDashboardScreen (was none)` on launch
  and again on BACK — no double-claim / stale / orphan.
- Web Browser destination opened (google.com) with correct handover; BACK reclaims Home.
- Reconnect (force-stop + relaunch) re-claims cleanly, no leak.
- **No crash**: `AndroidRuntime:E` filtered to `dev.autobridge` is empty across launch →
  browser → BACK → reconnect.

## Before / after screenshots
**NOT CAPTURED — blocked.** Capturing the DHU window needs macOS **Screen Recording**
permission, which the automated/sandboxed shell used for this run does not hold (CoreGraphics
window list returns empty owner/title; a full-screen grab only captures the foreground IDE).
This is the exact constraint documented in `scripts/dhu-shot.sh`.

To produce them, run from a real **Terminal.app / iTerm** (grant Screen Recording once):
```sh
scripts/dhu-shot.sh home-before      # with old build, or current Home
scripts/dhu-shot.sh home-after       # new dashboard
scripts/dhu-test.sh                  # full drive: shots per screen + logcat
```
Reference target for the comparison: the "Home Dashboard" panel in
`ChatGPT Image 15 ก.ย. 2569 19_41_15.png`.

## Known limitations / remaining issues
- No DHU screenshots yet → §O before/after, §P pixel comparison (spacing, corner radii,
  icon size, typography, focused blue border, pressed feedback, long Thai/English text),
  and §J "no clipping" remain unverified. These need the screenshot step above.
- §M per-destination regression (YouTube, YT Music, Streaming, TV, Radio, Mirror, Quick
  Launch, Settings, Resume, vehicle-safety) was **not** individually driven/visually confirmed
  this run; only Home, Web Browser handover, BACK, reconnect and no-crash were exercised live.
  Run `scripts/dhu-test.sh` + manual pass to close these.
- Multi-resolution resize (§O "screen resize") only partially covered (one surface geometry
  observed live); test a second DHU resolution for full coverage.
- Dashboard changes are uncommitted — commit before release.
