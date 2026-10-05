# Verification — Browser UI polish

All commands run from the repo root `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`.

## Build

- `./gradlew :app:compilePersonalDebugKotlin` → **BUILD SUCCESSFUL** (the six prior
  `Unresolved reference 'glyph'` errors in `CarWebRenderer.kt` are gone).
- `./gradlew :app:assemblePersonalDebug` → **BUILD SUCCESSFUL**. All referenced
  `ic_browser_*` drawables are packaged (resource merge/package tasks succeeded).

## Tests

- `./gradlew :app:testPersonalDebugUnitTest` → **BUILD SUCCESSFUL**. The prior-agent-modified
  `BrowserViewportTest.kt` (incl. `ChromeVisibilityTest`) stays green; layout/geometry and
  `AutoUiSizes`/drawer-model tests pass (icon draws are not asserted by these, geometry is).

## Lint

- `./gradlew :app:lintSafeDebug` (the CI gate, per `app/build.gradle.kts`) → **BUILD SUCCESSFUL**,
  0 unbaselined errors. The migration from text-glyph `TextView`s to `ImageView`s actually cleared
  a few previously-baselined accessibility findings ("listed in the baseline but not found").
- `./gradlew :app:lintPersonalDebug` reports 6 errors, but **all are pre-existing and outside this
  task's scope**: `src/projection/AndroidManifest.xml` (MissingIntentFilterForMediaSearch,
  QueryAllPackagesPermission), `src/mirror/ProjectionService.kt` (ForegroundServiceType) and
  `src/projection/.../DuoScreenSettingsActivity.kt` (StringFormatMatches). None touch browser UI.
  No new drawable/string lint issues were introduced by this work.

## Canvas-surface grep checks (can't screenshot the projection surface)

- `grep -n "\.glyph" CarWebRenderer.kt` → **no matches** (model field migration complete).
- Grep for chrome glyph `drawText` literals (`"\u2715"`, `"×"`, `"+"`, `"☰"`, `"↻"`, `"‹"`,
  `"›"`, `"⛶"`, `"🔒"`) in `CarWebRenderer.kt` → **no matches**.
- Every remaining `canvas.drawText(...)` in `CarWebRenderer.kt` draws genuine text: page
  titles/subtitles, address text, host, the "Tabs n/MAX" counter, tile/primary count `value`
  badges, and the error-overlay message/hint. No chrome icon is drawn as text.
- Every `BrowserIcon` enum `resId` resolves to an existing drawable: all `ic_browser_*.xml`
  present, and `ic_car_home.xml` (used by `HOME_PAGE`) present. No icon was missing, so none had
  to be added.

## What changed (summary)

- **Task 1 (fullscreen default on):** added `BrowserControlsStore.startFullscreen` /
  `setStartFullscreen` (default `true`, same `autobridge_browser` prefs file). Phone
  `BrowserActivity.onCreate` seeds from the store on cold start (saved-instance restore still wins);
  `setFullscreen` persists the choice. Car `CarWebRenderer.start()` seeds once on `firstStart` via
  `ChromeVisibility.setFullscreen` (no re-write on re-attach); its public `setFullscreen` persists.
- **Task 2 (fullscreen in hamburger next to Desktop):** confirmed already modelled —
  `BrowserDrawerModel` stacks `desktopToggle` then `fullscreenToggle` on both surfaces;
  `fullscreenToggle.on` reflects current state; `TOGGLE_FULLSCREEN` is wired on both.
- **Task 3 (finish Material-icon migration):** added a cached `drawIcon(...)` helper to
  `CarWebRenderer` and replaced every chrome glyph draw (FAB, toolbar back/forward/reload/
  fullscreen/menu, address lock/warning badge, sheet header links + close, sheet address
  lock/clear/go, sheet tiles, primary icon + chevron, toggle icon, tab-switcher close/new-tab/
  per-tab close) with vector draws. Migrated phone `BrowserSheetShell.closeButton`,
  `BrowserSettingsSheet` back/chevron, `MoreActionsSheet` (row icons, back, chevron, zoom chip +
  zoom buttons), and `SendToCarSheet` (search, clear, queue-remove) to `ImageView` + `BrowserIcon`.
  `BrowserActivity` toolbar was already migrated by the prior agent. Brand monograms on
  `BrowserStartPage` and the `◉` radio marker on `SendToCarSheet` left as genuine content.
- **Task 4 (display-scale slider):** verified `BrowserSettingsSheet.displayScaleRow()` is a complete
  `SeekBar` (reads `BrowserDisplayScaleStore.percent`, min/max/step correct, live label on drag,
  writes + applies on stop). No other scale picker exists. Already complete; left as-is.
- **Task 5 (dialog audit):** see `dialog-audit.md`. User-Agent is already a radio; everything else is
  a confirmation, text-entry or navigate-on-tap list. No dialog changes required.
- **Task 6 (proportionality):** each migrated vector is a centered square sized from the same
  `AutoUiSizes` token the old glyph used, so same-role icons read at one size across surfaces.

## Still needs on-device/visual confirmation by the user

The Android Auto projection Canvas surface cannot be screenshotted here. Please confirm on a head
unit / phone that: toolbar + sheet + drawer + tab icons render centered and correctly sized; the
fullscreen enter/exit toolbar icon and the hamburger fullscreen toggle reflect state; a fresh
install starts fullscreen and a toggle-off survives relaunch on both phone and car.
