# Projection browser shell — mobile-browser control shell around the car WebView

The projection-route browser (`ProjectionBrowserActivity`) grows from a bare WebView with
text-only quick buttons into a mobile-browser-like shell: a tappable address/search readout wired
to the car host's own `SearchController`, a full Back/Forward/Reload/Home button row kept alongside
the three quick-site shortcuts, a horizontally scrollable action bar, and a thin loading progress
bar. Typed input is normalized by the already-tested pure `BrowserInputResolver` rather than a new
rule. The headline SDK risk (how on-screen text entry works) was resolved in design against the
decompiled `aauto.aar`: the host serves the search box over RPC and throws `IllegalStateException`
on any box call made before a callback is registered, which drives the load-bearing `onCreate`
ordering. The two open MEDIUMs from the design review (M1 progress-visibility ownership, M2 blank
address field on launch) are both addressed in code and annotated.

Watch for: nothing blocking. The two deferred device-time unknowns (does the host *seed* vs
*commit* `startSearch`, and does `getSearchController()` return non-null on the live host) are
correctly degraded to logged no-ops and belong on the DHU, not in this review (confirmed). Scope,
preserved behaviors, and gating all hold on a full read of the file (confirmed).

**Verdict**: APPROVED

## High-level view

The address control is a plain non-editable `TextView` that opens the host search box on tap; this
is the right call because the SDK host owns the IME and no phone keyboard can attach to an app
`EditText` here. The `SearchController` is acquired null-safely with `runCatching { … }.getOrNull()`
and its single reusable `SearchCallback` is registered in `onCreate` before the first
`enforcePolicy()`, which honors the SDK's callback-before-box contract. Every one of the four box
calls (`showSearchBox`/`hideSearchBox`/`startSearch`/`stopSearch`) plus `setSearchCallback` is
wrapped in `runCatching` with a `StructuredLog.w("PROJECTION", …)` on failure, so an
acquired-but-unusable controller degrades to a logged no-op instead of crashing.

The navigation row is Back, Forward, Reload, Home then the three quick sites, wrapped in a
`HorizontalScrollView` so a narrow display scrolls rather than clips. Forward is disabled and dimmed
to 0.4 alpha unless `canGoForward()`, refreshed on every page/progress callback and guarded again at
click. Home and the quick sites route through the existing gated `open()`.

Input normalization is delegated to `BrowserInputResolver.resolveBrowserInput(input,
SearchEngineStore.engine(this))` — the same pure function the phone browser uses, with no
re-implementation — and its JVM unit test covers bare domain, full https URL, multi-word search,
empty/blank, Thai round-trip, and unsafe-scheme-to-search.

M1 is resolved by making `onProgressChanged` the sole writer that *shows* progress and
`enforcePolicy()` the sole writer that *hides* a stranded bar when the gate denies mid-load, both
documented in comments. M2 is resolved by calling `syncAddress()` once at the end of `buildLayout()`
so the field is seeded from `currentUrl()` and never blank on launch. All new navigation entry
points begin with `if (!allowed()) return enforcePolicy()`, and the full preserve list (fullscreen
video, media-session audio with no second focus request and no `onPause` pause, ad-block, play
queue, last-URL memory, non-http(s) block, `setIgnoreConfigChanges(-1)`, timer gate, header/menu
hiding) is intact. Scope is held to the projection source set shared by personal/lab; safe flavor,
main manifest, and the NAVIGATION route are untouched.

<details>
<summary>Issues (0)</summary>

No blocking or non-blocking actionable findings. The two device-time unknowns (host seed-vs-commit
for `startSearch`, live non-null `getSearchController()`) are deferred to the DHU by design and are
not findings.

</details>

<details>
<summary>Details</summary>

### Search surface: callback-before-box ordering and the degrade path

The SDK contract — every box method throws `IllegalStateException("No SearchCallback is set")`
until `setSearchCallback` has run, and only `RemoteException` is caught internally — was verified in
the design review against `javap -c` bytecode, and the implementation honors it exactly (confirmed).
`onCreate` acquires the controller and registers the callback immediately after
`setContentView(buildLayout())` and before the first `enforcePolicy()`:

```kotlin
searchController = runCatching { carUiController.searchController }.getOrNull()
searchController?.let { controller ->
    runCatching { controller.setSearchCallback(searchCallback) }
        .onFailure { StructuredLog.w("PROJECTION", "setSearchCallback failed: ${it.message}") }
}
ParkingStateStore.addListener(parkingListener)
WebMediaHub.register(this, mediaSource)
enforcePolicy()
```

This matters because `enforcePolicy()`'s `!permitted` branch calls `hideSearchBox()`, and a launch
while driving takes that branch on the first call. Acquiring and registering first clears the SDK
guard; the `?.` safe-call would not help since the controller is non-null on the real host. The
registration itself can still fail with `RemoteException`, which is why `openAddressEntry()`,
`enforcePolicy()`, the `onSearchSubmitted` `stopSearch`, and `onDestroy` each wrap their box calls
in `runCatching` with a logged failure — the acquired-but-unusable case degrades to a no-op and the
driver keeps the quick-site buttons and Home. This is the whole of NFR5 on the live host, not just
the null-controller case (confirmed).

The address control is a display `TextView`, not an `EditText`, because `CarUiController`'s
constructor takes a `com.google.android.gms.car.input.InputManager` — the host owns the IME, so no
app-drawn editable field can take keyboard input here. `isFocusable = true` is for D-pad/rotary
reachability; the control acts only on click.

### Progress-visibility ownership (M1)

The two writers are now cleanly split and both annotated. `onProgressChanged` is the sole writer
that *shows* the bar (`visible when newProgress < 100 && allowed()`) and that hides it at 100.
`enforcePolicy()`'s `!permitted` branch is the sole other writer and only ever hides. The subtle
mid-load case the design review flagged — the gate flips to denied while a load is in flight,
`onProgressChanged` may not fire again, so its own `allowed()` guard cannot retract the bar — is
handled by `parkingListener → enforcePolicy()` hiding the stranded bar, and the code comment says
exactly that:

```kotlin
// If the gate flips mid-load, onProgressChanged may not fire again (no progress tick), so
// enforcePolicy() (via parkingListener) is what hides a stranded bar in that case; the allowed()
// guard here only stops a new tick from re-showing it.
```

### Address seeding (M2)

`syncAddress()` is called once at the end of `buildLayout()`, after the view tree is assembled and
`urlField` is assigned, so the field renders `currentUrl()` (`webView?.url ?: BrowserDefaults.HOME`)
from first paint rather than empty. `currentUrl()` and `syncAddress()` are the single source for
"current URL" and the display write, used by the pre-seed in `openAddressEntry()` and by all four
page callbacks, so the previously-divergent fallbacks collapse to one rule (confirmed).

### Address sync without clobbering the host box

`onPageStarted`, `onPageFinished`, and `doUpdateVisitedHistory` all call `syncAddress()` +
`updateNav()`; `onReceivedTitle` writes the URL when one exists and only falls back to the title
when `view.url` is empty. Every display write targets the app's `TextView`, never the host search
box — the host owns the live edit text and the app only reads the result through
`onSearchSubmitted` — so a page update cannot overwrite what the driver is typing.

### Input normalization reuse

`navigateFromInput` is the single call site turning typed text into a URL, and it calls the shared
pure function rather than re-deriving the rule:

```kotlin
val url = BrowserInputResolver.resolveBrowserInput(input, SearchEngineStore.engine(this))
```

Reading `BrowserInputResolver` confirms the reuse is real: `resolveBrowserInput` is
`ContentAddress.https(trimmed) ?: engine.searchUrl(trimmed)` with an empty-input short-circuit, and
`ContentAddress.https` upgrades a bare host to `https://` and returns null for any non-`https` scheme
so `http:`/`javascript:`/`intent:` all fall through to search (confirmed). The resolver test covers
the four required cases plus more: `aBareHostnameIsUpgradedToHttps`,
`aFullUrlIsSentExactlyAsTypedWhicheverEngineIsSelected`,
`projectionBarContractBareDomainFullUrlAndMultiWordSearch` (multi-word), and
`emptyOrBlankInputBecomesAnEmptySearchNeverAUrl` (empty/blank → safe search URL, asserted non-empty
and non-bare-host). `unsafeSchemesAreTreatedAsSearchesNotNavigation` and the Thai round-trip are
bonus coverage. `loadUrl` is used rather than `open()` because the resolver already produced a safe
absolute URL, and the `shouldOverrideUrlLoading` scheme block remains the backstop.

Not tested here, by design: the live host keyboard path (tap → box → type → submit navigates), the
seed-vs-commit behavior of `startSearch(currentUrl())`, and the launch-while-driving smoke check.
These depend on the real car host/IME and are explicitly deferred to the DHU in the verification
note; rejecting for lack of live-DHU confirmation is out of scope for this review.

### Gating on every new entry point

`openAddressEntry()` and `navigateFromInput()` both open with `if (!allowed()) return
enforcePolicy()`; Home and the quick sites go through `open()`, which gates the same way; Forward
and Reload operate on in-WebView history and are additionally covered by `enforcePolicy()` making
the WebView `INVISIBLE` and hiding progress when denied. `allowed()` is the unchanged
`SafetyEnforcement.gateParked(…) && FeaturePolicy.app.isAvailable(Feature.BROWSER)` (confirmed).

### Preserved behaviors and lifecycle

The regression list is intact on a full read: fullscreen video
(`onShowCustomView`/`enterFullscreen`/`exitFullscreen`), media-session audio via
`WebMediaHub`/`WebAudioBridge` with audio focus left to Chromium (no second request) and no
`onPause` override pausing the WebView, ad-block (`shouldInterceptRequest → BrowserAdBlock.intercept`),
play queue (`skipToNext → BrowserPlayQueue.takeNext`), last-URL memory
(`BrowserDefaults.remember`/`lastUrl`), the non-http(s) scheme block in `shouldOverrideUrlLoading`,
`setIgnoreConfigChanges(-1)`, `WebViewTimerGate.hold/release`, `hideAppHeader()`, `hideMenuButton()`,
and unchanged `QUICK_SITES` (confirmed). `onDestroy` tears down the host search surface (wrapped
`stopSearch`/`hideSearchBox`) and nulls every new nullable field (`searchController`, `urlField`,
`forwardButton`, `progress`) alongside the existing `webView`/`root`. Touch targets are 48dp with
`contentDescription` on the address field ("Address and search"), the progress bar ("Page loading"),
and every `action()` button (its label). File idioms (`Int.dp()`, the `action(label){}` builder,
`StructuredLog("PROJECTION", …)`) are matched.

### Scope

All changes live in the projection source set shared by personal/lab plus the resolver unit test in
`src/test`. The safe/Play flavor, main manifest, and NAVIGATION car-app route are untouched. No
multi-tab, bookmarks, or history-list appears in the diff (confirmed).

</details>

<details>
<summary>File map</summary>

- `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt` — new projection
  browser activity with the address/search control, Forward/Home buttons, horizontally scrollable
  bar, progress indicator, host `SearchController` wiring, and the M1/M2 fixes.
- `app/src/test/java/dev/autobridge/browser/BrowserInputResolverTest.kt` — JVM unit test for the
  reused resolver (bare domain, full https URL, multi-word search, empty/blank, Thai round-trip,
  unsafe-scheme-to-search, `looksLikeUrl`).

Full diff: `git diff main -- app/src/projection/.../ProjectionBrowserActivity.kt
app/src/test/.../BrowserInputResolverTest.kt`.

</details>
