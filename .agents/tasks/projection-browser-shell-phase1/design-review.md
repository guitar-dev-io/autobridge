# Design Review — Projection Browser Shell (Phase 1)

Design under review: `.agents/tasks/projection-browser-shell-phase1/design.md`
Target file: `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt`
Reviewer posture: fresh read, no prior context. Every SDK and helper claim was verified against
source — `aauto.aar` was unzipped and its classes decompiled with `javap -p` and `javap -c -p`;
project helpers and the existing activity were read directly.

## Verdict

CHANGES_REQUESTED — 0 HIGH, 2 MEDIUM, 3 NITs.

The single highest-risk item (the car text-entry mechanism) is **fully backed by concrete aar
evidence** and is not a finding — see Verified Assumptions. The two MEDIUMs are internal
specification gaps the implementer would otherwise have to guess at; neither is an SDK-evidence gap.

---

## Findings

### 1. MEDIUM — `progress` visibility rule contradicts itself between §3 and the gating section

Where: §3 `onProgressChanged` vs. "Parked / policy gating" and AC6.

`onProgressChanged` is specified as:

```kotlin
progress?.visibility = if (newProgress < 100 && allowed()) View.VISIBLE else View.GONE
```

This hides the bar whenever `!allowed()`. Separately, `enforcePolicy()`'s `!permitted` branch also
sets `progress?.visibility = View.GONE`. These agree. But AC6 states the indicator is hidden "at
`onProgressChanged == 100` (and while gating denies the feature)", while FR5 says only "visible
while a page loads and hidden at 100%". The conflict is subtle but real: when a page is loading
(`newProgress < 100`) **and** `allowed()` is true, the bar shows; the moment the gate flips to
denied mid-load, `onProgressChanged` may not fire again (no progress change), so the only thing that
hides the stale bar is the `parkingListener → enforcePolicy()` path. The design relies on that path
but never states that `onProgressChanged`'s own `allowed()` check is **not** sufficient on its own
for the "gate flips during a load" case.

Concrete fix: state explicitly that stale-progress hiding on a mid-load gate change is owned by
`enforcePolicy()` (invoked from `parkingListener`), and that `onProgressChanged`'s `allowed()` guard
only prevents a *new* progress tick from re-showing the bar. Add one line to §3: "If the gate
denies while a load is in flight, `onProgressChanged` may not fire again; `enforcePolicy()` (via
`parkingListener`) is what hides the bar in that case." No code change required — this is a
specification-completeness fix so the implementer does not assume the `onProgressChanged` guard
alone covers it.

### 2. MEDIUM — `urlField` is never populated before the first page load; initial display state unspecified

Where: §1 "View" / §1 "Keeping the field in sync" / `onCreate` ordering.

`syncAddress()` is called only from the WebView callbacks (`onPageStarted`, `onPageFinished`,
`doUpdateVisitedHistory`, `onReceivedTitle`). In `onCreate`, `webView?.loadUrl(...)` runs after the
controller setup, but nothing sets `urlField.text` at build time. Between `buildLayout()` and the
first `onPageStarted`, the `urlField` `TextView` shows empty text (its `text` is never initialized),
so the address control renders blank on launch — and if a launch-while-driving keeps the WebView
`INVISIBLE`, the field stays blank until the gate lifts and a page actually starts. The design's
`currentUrl()` helper (`webView?.url ?: BrowserDefaults.HOME`) is exactly the right seed but is
never invoked at construction.

Concrete fix: call `syncAddress()` once at the end of `buildLayout()` (or immediately after
`setContentView` in `onCreate`) so the field shows `currentUrl()` (HOME on cold start, or the
remembered last URL once `loadUrl` resolves it) from first paint. Add to §1:

```kotlin
// after addView(urlField) wiring, before returning the view tree:
syncAddress()   // seed the display with currentUrl() so the bar is never blank on launch
```

### 3. NIT — AC2 and §3 `onReceivedTitle` disagree on title-vs-URL precedence wording

Where: AC2 ("title used only as a secondary label, never replacing a correct URL when one exists")
vs. §3 `onReceivedTitle` (`if (!view.url.isNullOrEmpty()) syncAddress() else if (!title...) urlField?.text = title`).

The code matches AC2's intent, but AC2 says the displayed text "equals the WebView's current URL" on
`onReceivedTitle`, while the code path can write the *title* when `view.url` is empty. That is a
narrow, correct fallback, but AC2's "equals the current URL" phrasing reads as an absolute. Fix:
reword AC2 to "...equals the current URL when one exists; when the WebView has no URL yet, the title
may be shown as a transient label." Keeps the acceptance test honest.

### 4. NIT — `urlField` has `isFocusable = true` with host-owned input; focus semantics unspecified

Where: §1 "View" (`isFocusable = true`, `isClickable = true`).

The `urlField` is a display `TextView` that opens the host search box on tap; it is explicitly not
editable. Marking it `isFocusable = true` is reasonable for rotary/D-pad reachability on the car
display, but the design never says what focus does (there is no `onFocusChange` behavior and no IME
should attach). Fix: add a sentence that focus is for D-pad/rotary traversal only and that the
control responds solely to click (`setOnClickListener`), never to a soft-keyboard focus event — on
this SDK the host owns the IME (confirmed: `CarUiController`'s constructor takes
`com.google.android.gms.car.input.InputManager`), so no phone IME attaches to this `TextView`.

### 5. NIT — "loads it verbatim" overstates `ContentAddress.https` for full https URLs

Where: AC4 ("submitting a full `https` URL loads it verbatim").

`ContentAddress.https` returns `URI(...).toASCIIString()`, which normalizes/ASCII-encodes the URL
rather than echoing the exact input bytes. For the clean URLs the existing test asserts
(`https://m.youtube.com/watch?v=123`) the output is byte-identical, so this is cosmetic, but
"verbatim" is technically wrong for URLs containing non-ASCII or non-normalized components. Fix:
reword to "loads it as the normalized `https` URL (`ContentAddress.https`'s `toASCIIString()`
output), which is byte-identical for already-canonical URLs." No behavior change.

---

## Verified Assumptions

These design claims were checked against source and are **correct**:

- **`aauto.aar` exposes a host-serviced search surface (the headline unknown).** Confirmed by
  decompiling `app/libs/aauto.aar`:
  - `com.google.android.apps.auto.sdk.CarUiController` has
    `public com.google.android.apps.auto.sdk.SearchController getSearchController();` (backing field
    `e` is a `SearchController`). `getStatusBarController()`/`getMenuController()` also present,
    matching the file's existing use.
  - `com.google.android.apps.auto.sdk.SearchController` exposes exactly:
    `showSearchBox()`, `hideSearchBox()`, `setSearchCallback(SearchCallback)`,
    `setSearchHint(CharSequence)`, `setSearchItems(List<SearchItem>)`, `startSearch(String)`,
    `stopSearch()` — all as the design states.
  - `com.google.android.apps.auto.sdk.SearchCallback` is abstract with abstract
    `onSearchTextChanged(String)`, `onSearchSubmitted(String): boolean`,
    `onSearchItemSelected(SearchItem)` and concrete `onSearchStart()`/`onSearchStop()` — matches the
    anonymous-object override set in the design.
  - The backing field is `com.google.android.apps.auto.sdk.q`, a
    `public interface ... extends android.os.IInterface` whose methods all
    `throws android.os.RemoteException` — confirming host-RPC rendering, not an app-drawn view.
- **The callback-before-box runtime precondition (the load-bearing ordering constraint).** Verified
  by `javap -c -p` on `SearchController.class`: `showSearchBox`, `hideSearchBox`,
  `startSearch(String)`, and `stopSearch` each test field `b` (the callback holder) and, when null,
  `new IllegalStateException("No SearchCallback is set"); athrow`. Each catches only
  `RemoteException` (exception table targets a `Log.e` + return). `setSearchCallback` and
  `setSearchHint` have **no** `IllegalStateException` guard and catch only `RemoteException`. This
  matches the design's Investigation and its `onCreate`-ordering fix precisely, including the detail
  that `setSearchHint` is safe to call before a callback is set.
- **`startSearch(String)` only forwards the string over RPC (`q.a(String)`).** Confirmed in
  bytecode. The design's statement that seed-vs-commit is **not** statically determinable from the
  aar is accurate, and its DHU fallback (drop `startSearch`, use `setSearchHint` only) is sound.
- **aar resources corroborate a host-owned styled search box.** `R.txt` contains
  `color search_box_card`, `color search_box_text_primary`,
  `dimen car_app_layout_search_box_small_width`, and `drawable quantum_ic_search_black_24` — all
  present as cited.
- **`CarUiController` constructor takes `com.google.android.gms.car.input.InputManager`.** Confirmed
  — the host owns the IME, so the design's rationale for a non-editable `TextView` (no phone IME
  against an `EditText`) holds.
- **Normalization is a pure, JVM-tested function and is reused, not re-implemented.**
  `BrowserInputResolver.resolveBrowserInput(input, engine)` is pure (`src/main/java/.../BrowserInputResolver.kt`):
  empty → `engine.searchUrl("")`; otherwise `ContentAddress.https(trimmed) ?: engine.searchUrl(trimmed)`.
  `ContentAddress.https` upgrades a bare host to `https://`, and returns null for any non-`https`
  scheme (so `http:`, `javascript:`, `intent:` all fall through to search). `SearchEngineStore.engine`
  defaults to `SearchEngine.YOUTUBE`. `BrowserInputResolverTest.kt` asserts exactly these cases,
  including the `http://insecure.example`/`javascript:`/`intent://` → Google-search case and Thai
  round-tripping. The design adds no new resolver — correct.
- **Parked gating via `SafetyEnforcement.gateParked` is honored.** `allowed()` in the current file
  is `SafetyEnforcement.gateParked(ParkingStateStore.isParked) && FeaturePolicy.app.isAvailable(Feature.BROWSER)`.
  `SafetyEnforcement.gateParked` exists with that signature. The design routes `openAddressEntry()`
  and `navigateFromInput()` through `if (!allowed()) return enforcePolicy()`, and Home via `open()`
  which already gates. Verified.
- **Preserved behaviors are genuinely preserved.** The design's "Preserved behaviors" list matches
  what the current activity does: `shouldOverrideUrlLoading` non-http(s) block (returns true for any
  scheme that is not `https`/`http`), fullscreen video (`onShowCustomView`/`enterFullscreen`/
  `exitFullscreen`), media-session audio (`WebMediaHub`/`WebAudioBridge`, no `onPause` override),
  ad-block (`shouldInterceptRequest` → `BrowserAdBlock.intercept`), play queue (`skipToNext` →
  `BrowserPlayQueue.takeNext`), last-URL memory (`BrowserDefaults.remember`/`lastUrl`),
  `setIgnoreConfigChanges(-1)`, `WebViewTimerGate.hold/release`, `hideAppHeader()`,
  `hideMenuButton()`, unchanged `QUICK_SITES`. The new `onPageStarted`/`onPageFinished` overrides
  keep the existing `applyIdentity`/`remember`/`installPlayTracking` lines and only add
  `syncAddress()`/`updateNav()`.
- **`BrowserDefaults.HOME` is `https://www.google.com/`** (confirmed in source), matching §2 Home,
  AC5, and the Google quick-site.
- **Helpers referenced exist with the cited behavior:** `BrowserDefaults.applyIdentity/remember/
  lastUrl/configureDebugTools`, `StructuredLog.i/w` under tag convention, `ParkingStateStore`,
  `FeaturePolicy.app.isAvailable(Feature.BROWSER)`, `CarActivity.getCarUiController()`.
- **Out-of-scope items are not designed in.** No multi-tab, no bookmarks/quick-site persistence, no
  history list, no `QUICK_SITES` change, no voice input, no suggestions. The "Out of Scope" section
  is explicit and the body honors it. No scope creep found.
- **Accessibility addressed.** NFR1/AC1: `urlField` and every `action()` button get `minHeight =
  48.dp()` (and `minWidth` ≥ 48dp), and each has a `contentDescription` (`urlField` =
  "Address and search", `progress` = "Page loading", buttons use their label). The existing
  `action()` builder already enforces 48dp + contentDescription. Verified against the current
  `action()` source.
- **Normalization pure-function + JVM test requirement (NFR4/AC8)** is satisfied by the existing
  `BrowserInputResolverTest` with no device dependency.

## Unverified / Wrong Assumptions

- **Host renders `startSearch(currentUrl())` as editable seeded text (not a committed search).**
  Correctly flagged by the design as not statically verifiable (bytecode shows only an RPC
  forward). This is NOT a finding — the design lists it as a DHU check and specifies a concrete
  one-line fallback. Carried here only as the known device-time unknown.
- **`getSearchController()` returns non-null on the personal/lab target host at runtime.** Cannot be
  verified from the aar (depends on the live host). Correctly flagged by the design and covered by
  `runCatching { carUiController.searchController }.getOrNull()` with a logged no-op fallback (NFR5).
  Not a finding.
- No design claim about the SDK or project helpers was found to be **wrong**. Every concrete,
  statically-checkable assertion (class names, method signatures, bytecode guards, resource names,
  helper behavior, HOME constant, preserved behaviors) checked out.
