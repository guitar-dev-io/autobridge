# Phase 1 Verification — Projection Browser Shell

Iteration: FIRST (no `phase1-review.json` present; implemented from `design.md` + `design-review.md`).

Target file: `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt`
(personal/lab flavors only). Resolver test: `app/src/test/java/dev/autobridge/browser/BrowserInputResolverTest.kt`.

## SDK facts re-confirmed (not re-litigated — verified against the aar)

Unzipped `app/libs/aauto.aar` → `classes.jar` and ran `javap -p` on the relevant classes:

- `com.google.android.apps.auto.sdk.CarUiController` has `public SearchController getSearchController();`
- `SearchController` exposes exactly `hideSearchBox()`, `setSearchCallback(SearchCallback)`,
  `setSearchHint(CharSequence)`, `setSearchItems(List<SearchItem>)`, `showSearchBox()`,
  `startSearch(String)`, `stopSearch()`.
- `SearchCallback` is abstract with `onSearchItemSelected(SearchItem)`, `onSearchSubmitted(String): boolean`,
  `onSearchTextChanged(String)` abstract, plus concrete `onSearchStart()/onSearchStop()`.

These match the design. The callback-before-box IllegalStateException contract is taken from the
design's `javap -c` evidence and honored by code ordering + runCatching (below).

## Commands run and results

### 1. `./gradlew :app:assemblePersonalDebug`
Result: **BUILD SUCCESSFUL**. (After two local compile fixes during development: a
`ProgressBar.progress` setter resolution inside the anonymous `WebChromeClient` was disambiguated
by binding the field to a local `val bar = this@ProjectionBrowserActivity.progress` first. Final
build is green.)

### 2. `./gradlew :app:testPersonalDebugUnitTest`
Result: **BUILD SUCCESSFUL**. Includes `dev.autobridge.browser.BrowserInputResolverTest`
(run in isolation with `--tests` and as part of the full suite — both green). New cases added:
`emptyOrBlankInputBecomesAnEmptySearchNeverAUrl` and
`projectionBarContractBareDomainFullUrlAndMultiWordSearch` (bare domain → https, full https URL
preserved, multi-word phrase → engine search, empty/blank → engine search URL).

### 3. `./gradlew :app:installPersonalDebug` (serial YXEMRCGYAI49S4SS)
`adb devices` → `YXEMRCGYAI49S4SS  device`.
Ran with `ANDROID_SERIAL=YXEMRCGYAI49S4SS`.
Result: **BUILD SUCCESSFUL — Installed on 1 device** (`app-personal-debug.apk`).

### Extra: `./gradlew :app:compileLabDebugKotlin`
Result: **BUILD SUCCESSFUL** (the projection source set is shared with the `lab` flavor; confirms
both target flavors compile).

## What is verified vs. what still needs the DHU / car host

Verified here: the project compiles for personal + lab, the full unit suite passes (incl. the
resolver), the APK installs on the phone, and the activity/view-tree compiles and constructs
(hand-built Views, no Compose).

**On-DHU confirmation is REQUIRED** for the following — they cannot be driven from this environment
because they depend on the live car host and its IME/search surface:

1. The host search box accepting typed text via the car keyboard (tap the address field → box opens
   → type → submit navigates). `startSearch(currentUrl())` is assumed to seed *editable* text; if
   the host instead *commits* it as a search, apply the design's one-line fallback (drop
   `startSearch`, keep `setSearchHint` only).
2. Forward enable/disable and Home navigation behavior on the car display.
3. The progress bar actually showing during a load and hiding at 100%.
4. The address field staying in sync with the live page (onPageStarted/Finished/
   doUpdateVisitedHistory/onReceivedTitle).
5. Smoke-check: launch **while driving** (gate denied) does not crash — this exercises the
   callback-before-box ordering.

The car keyboard runtime path is NOT claimed to work beyond what the SDK evidence + wiring show.
