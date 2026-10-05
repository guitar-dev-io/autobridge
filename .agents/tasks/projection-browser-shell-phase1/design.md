# Projection Browser Shell — Phase 1

Requirements and technical design for turning the Android Auto projection browser
(`ProjectionBrowserActivity`) from a bare WebView with text-only quick buttons into something that
feels closer to a real mobile browser: a URL/search bar, a full set of navigation buttons, and a
loading/identity indicator.

Scope is personal/lab flavors only. The file is
`app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt`, wired into the
`personal`/`lab` source sets in `app/build.gradle.kts`. The safe/Play flavor, the main manifest,
and the NAVIGATION car-app route are untouched.

---

## Summary

Add a proper browser control shell around the existing WebView while preserving every behavior that
already works (fullscreen video, media-session audio, ad-block, play queue, last-URL memory,
parked/policy gating, non-http(s) scheme block, timer gate, `setIgnoreConfigChanges(-1)`, no
`onPause` pausing).

Three deliverables:

1. **URL / search bar** — a text control in the action bar that shows the current page URL and,
   when activated, lets the driver type a URL or a search query that is normalized and loaded.
2. **Navigation buttons** — add Forward and Home next to the existing Back and Reload; keep the
   three quick-site buttons.
3. **Loading progress + page identity** — a thin progress indicator driven by
   `onProgressChanged`, with the URL-bar text kept in sync via page/title callbacks.

The headline unknown — how on-screen text entry works on this unofficial car SDK — has been
investigated against the actual `aauto.aar` and is **resolved** (see Investigation). The SDK ships a
host-serviced `SearchController`; that is the chosen input surface.

---

## Functional Requirements

- FR1. The control bar shows the current page's URL as text. It updates when navigation completes,
  when history is updated in-page (SPA navigations), and when a title arrives.
- FR2. Activating the URL control opens the car host's text-entry surface, pre-seeded with the
  current URL, so the driver can type a destination.
- FR3. A submitted entry is normalized: a URL or bare host loads as `https://…`; anything else runs
  as a web search. Normalization reuses the existing, already-tested pure function.
- FR4. Navigation controls: Back (unchanged: exit fullscreen first, else `goBack()`), Reload
  (unchanged), **Forward** (`goForward()`, enabled only when `canGoForward()`), **Home** (loads
  `BrowserDefaults.HOME`). The three quick-site buttons remain.
- FR5. A thin progress indicator is visible while a page loads and hidden at 100%.
- FR6. All text entry and navigation honor parked/feature gating exactly as the rest of the screen
  does (`allowed()` / `enforcePolicy()`): when not allowed, navigation is refused and the gate UI is
  shown instead.
- FR7. The non-http(s) scheme block in `shouldOverrideUrlLoading` stays in force; the URL bar never
  becomes a way to load `javascript:`/`intent:`/etc.

## Non-Functional Requirements

- NFR1. Touch targets ≥ 48dp; `contentDescription` on every actionable control.
- NFR2. No new DI, no Compose, no new third-party dependency. Views are built by hand with the
  file's existing idioms (`Int.dp()`, the `action(label){}` builder, `StructuredLog("PROJECTION", …)`).
- NFR3. The action bar must remain usable on a narrow car display; with Forward + Home + three quick
  sites it should scroll horizontally rather than clip.
- NFR4. The normalization decision ("URL vs search") must be a pure JVM-unit-tested function.
- NFR5. The input surface must degrade safely if the car host does not supply one at runtime.

## Acceptance Criteria

1. The control bar renders a URL/search text control plus buttons Back, Forward, Reload, Home, and
   the three quick-site buttons (YouTube, YT Music, Google), left-to-right, all ≥ 48dp and each with
   a `contentDescription`.
2. On a completed page load (`onPageFinished`), on `doUpdateVisitedHistory`, and on
   `onReceivedTitle`, the URL control's displayed text equals the WebView's current URL (title used
   only as a secondary label, never replacing a correct URL when one exists).
3. Activating the URL control opens the host's search/input surface pre-seeded with the current URL;
   on submit, the typed text is passed through
   `BrowserInputResolver.resolveBrowserInput(input, SearchEngineStore.engine(this))` and the result
   is loaded (subject to criteria 4 and 6).
4. `ContentAddress.https` accepts only `https`; `http`, `javascript:`, `intent:` and all other
   schemes resolve to a search query (verified by `BrowserInputResolverTest`). Concretely:
   submitting a bare host (`youtube.com`) loads `https://youtube.com`; submitting a full `https`
   URL loads it verbatim; submitting free text (`bodyslam live`) loads the engine's search-results
   URL; submitting a non-`https` scheme — plain `http://…` (a product decision to downgrade, not a
   security block), `javascript:…`, or `intent://…` — becomes a search, never a direct load.
   (Already covered by `BrowserInputResolverTest`; this design adds no new resolver.)
5. Forward is disabled/greyed and non-acting when `canGoForward()` is false, and loads the forward
   entry when true. Home loads `BrowserDefaults.HOME`. Back still exits fullscreen before calling
   `goBack()`.
6. While a page is loading the progress indicator is visible; at `onProgressChanged == 100` (and
   while gating denies the feature) it is hidden.
7. When `allowed()` is false, activating the URL control or any navigation button performs no
   navigation and triggers `enforcePolicy()` (gate screen shown), identical to the current `open()`.
8. A unit test on the JVM asserts the normalization rules of criterion 4 (the existing
   `BrowserInputResolverTest`, kept green; one youtube/google/thai case re-asserted as the
   projection bar's contract).
9. The project compiles for the `personal` and `lab` flavors; the safe flavor, main manifest, and
   NAVIGATION route are unchanged.

## Out of Scope (not designed here)

Multi-tab; user-editable bookmarks/quick-sites persistence; a full history list; changing
`QUICK_SITES`; voice input; search suggestions/autocomplete beyond pre-seeding the current URL.

---

## Investigation: how text entry works on this SDK (the UNKNOWN, resolved)

I unzipped `app/libs/aauto.aar` and decompiled its class signatures (`javap -p`). Concrete evidence:

**The SDK exposes a host-serviced search/input surface.** `CarUiController`
(`com.google.android.apps.auto.sdk.CarUiController`), already used in the file via
`statusBarController` and `menuController`, also has:

```
public SearchController getSearchController();
```

`SearchController` (`com.google.android.apps.auto.sdk.SearchController`):

```
public void showSearchBox();
public void hideSearchBox();
public void setSearchCallback(SearchCallback);
public void setSearchHint(CharSequence);
public void setSearchItems(java.util.List<SearchItem>);
public void startSearch(java.lang.String);
public void stopSearch();
```

Its backing field is of type `com.google.android.apps.auto.sdk.q`, an `android.os.IInterface` (RPC
to the car host) — re-verified with `javap -p`: the field is named `a` and its type `q` is
`interface … extends android.os.IInterface` whose methods all `throws android.os.RemoteException`.
This confirms the search box is **rendered and keyboarded by the host**, not drawn as an app view.
The aar resources corroborate
this: `R.txt` defines `color search_box_card`, `search_box_text_primary`,
`car_app_layout_search_box_small_width`, and `drawable quantum_ic_search_black_24` — the host owns a
styled search box.

`SearchCallback` (`com.google.android.apps.auto.sdk.SearchCallback`) is the input channel:

```
public abstract void    onSearchTextChanged(String);
public abstract boolean onSearchSubmitted(String);
public abstract void    onSearchItemSelected(SearchItem);
public void             onSearchStart();
public void             onSearchStop();
```

**Runtime precondition — a callback MUST be set before any box call (verified by `javap -c`, not
just signatures).** Decompiling the bytecode of `SearchController` shows that `showSearchBox()`,
`hideSearchBox()`, `startSearch(String)`, and `stopSearch()` each begin with a guard that throws
`java.lang.IllegalStateException("No SearchCallback is set")` when no callback has been registered;
only `RemoteException` is caught internally, so the `IllegalStateException` propagates to the
caller. By contrast `setSearchCallback(SearchCallback)` has **no** such guard — it only lazily
installs the backing listener and catches `RemoteException` — so it is always safe to call first,
and calling it is what clears the guard for the other methods. `setSearchHint(CharSequence)` also
has no `IllegalStateException` guard. This is the load-bearing ordering constraint for the whole
feature: **`setSearchCallback(...)` must run, in the same breath as controller acquisition and
before any `showSearchBox`/`hideSearchBox`/`startSearch`/`stopSearch`, or the SDK throws.**

`CarUiController`'s constructor takes a `com.google.android.gms.car.input.InputManager`, i.e. the
host owns the IME; there is **no path for a normal phone IME** against a plain `EditText` here, which
is exactly what the file's KDoc anticipated ("text entry on this SDK goes through the car's own input
UI"). `SearchItem`/`SearchItem.Builder`/`SearchItem.Type` exist for suggestions but are optional.

**Conclusion / chosen approach.** True host-serviced text entry **is** achievable through
`carUiController.getSearchController()`. We do **not** need the fallback (editable quick sites + a
search intent). The URL "bar" in the action row is a **display-and-trigger control**: it shows the
current URL and, when tapped, registers a `SearchCallback` once (at startup) and then calls
`showSearchBox()` and pre-seeds the box with the current URL via `startSearch(currentUrl)`. The
driver edits/types in the host's search box with the host keyboard; `onSearchSubmitted(String)`
delivers the final text to us.

This is better than any app-drawn `EditText` because it is the sanctioned, distraction-guideline-
compliant input surface the host already gates while driving.

### Risk and the required fallback

`getSearchController()` is only meaningful on the real projection host, and every box method has a
runtime precondition (callback-must-be-set, see Investigation). Three defensive rules:

- **Controller possibly-absent.** Wrap acquisition in `runCatching { carUiController.searchController }`
  (Kotlin property access of `getSearchController()`). If it is null or throws, the URL control
  degrades to a no-op trigger that logs `StructuredLog.w("PROJECTION", …)` and the driver still has
  the quick-site buttons and Home — navigation is not lost. This satisfies NFR5.
- **Callback-before-box invariant.** `setSearchCallback(...)` is called in the same breath as
  acquisition (in `onCreate`, before the first `enforcePolicy()`), and every
  `showSearchBox`/`hideSearchBox`/`startSearch`/`stopSearch` call is additionally wrapped in
  `runCatching { … }.onFailure { StructuredLog.w(…) }`. This covers the acquired-but-callback-unset
  case (e.g. `setSearchCallback` hit a `RemoteException` and the box methods would then throw
  `IllegalStateException`): the surface degrades to a logged no-op rather than crashing. This is what
  actually makes NFR5 hold on the real host, not just the null-controller case.
- **Host surface independence.** The search box visibility is a host surface, independent of our view
  tree, so the app header stays hidden (`hideAppHeader()` is kept). We call
  `hideSearchBox()`/`stopSearch()` (wrapped) on teardown.

---

## Technical design

### Technology stack (locked)

Kotlin, Android Views built by hand (no Compose), the unofficial Android Auto SDK in
`app/libs/aauto.aar` (`CarActivity`, `CarUiController`, `SearchController`, `SearchCallback`),
`android.webkit.WebView`/`WebChromeClient`/`WebViewClient`, and the existing `dev.autobridge.*`
helpers. Personal/lab flavors only. No new dependencies, no new DI.

### Overview

All work is inside `ProjectionBrowserActivity.kt`. The existing hand-built view tree is a vertical
`LinearLayout` (`column`) of an action `bar` over the WebView, inside a `FrameLayout` (`root`) that
also holds the gate overlay and fullscreen view. Phase 1:

- Wraps the horizontal action `bar` in a `HorizontalScrollView` so it scrolls on a narrow display
  (NFR3), and adds a `urlField`, a Forward button, and a Home button.
- Adds a thin `ProgressBar` (horizontal style) between the bar and the WebView.
- Registers a `SearchController` + `SearchCallback` for text entry, driven by the `urlField`.
- Extends the `WebChromeClient` with `onProgressChanged` and `onReceivedTitle`, and the
  `WebViewClient` with `doUpdateVisitedHistory`, to keep the URL text and progress in sync.

No helper classes are added for normalization — `BrowserInputResolver.resolveBrowserInput` and
`SearchEngineStore` already exist and are reused.

### 1. URL / search bar

**View.** A `TextView` kept in the nullable field `urlField` (see "Fields" below), placed first in
the bar. It is not a variant of the `action(label){}` builder — that builder fixes `minWidth = 48.dp()`
and centres text, which is wrong for an address field — so it is specified explicitly:

```kotlin
urlField = TextView(this).apply {
    isSingleLine = true
    ellipsize = android.text.TextUtils.TruncateAt.END
    setTextColor(Color.WHITE)
    textSize = 14f
    gravity = Gravity.CENTER_VERTICAL
    minWidth = 200.dp()
    minHeight = 48.dp()
    setPadding(16.dp(), 0, 16.dp(), 0)
    contentDescription = "Address and search"
    isClickable = true
    isFocusable = true
    setOnClickListener { openAddressEntry() }
}
```

It is a *display* of the current URL; tapping it opens the host search box. (It is not itself
editable — editing happens in the host surface — which is why a plain `TextView` is correct and an
`EditText` is wrong on this SDK.) The identifier is `urlField` everywhere, including the
`buildLayout` snippet in §2 (`addView(urlField)`).

**Fields (all nullable, assigned in `buildLayout`, nulled in `onDestroy`).** For consistency with the
existing `webView`/`root`/`blocked` fields, every control that a top-level method must reach is a
field — not a `val` local to `buildLayout()`. In particular `progress` is read by both the chrome
client *and* `enforcePolicy()` (a top-level method), so it cannot be a local:

```kotlin
private var urlField: TextView? = null
private var forwardButton: TextView? = null
private var progress: ProgressBar? = null
private var searchController: SearchController? = null   // SDK: com.google.android.apps.auto.sdk.SearchController
// searchCallback is a non-null val (the single reusable SearchCallback instance); see onCreate ordering below.
```

Every call site uses safe calls (`urlField?.…`, `forwardButton?.…`, `progress?.…`). The
`SearchController` box methods are additionally wrapped in `runCatching` wherever they are called,
because they throw `IllegalStateException` until a callback is set (see Investigation / onCreate).

**Opening the input surface.** Add (`searchController` is the field declared under "Fields" above):

```kotlin
private fun openAddressEntry() {
    if (!allowed()) return enforcePolicy()
    val controller = searchController ?: run {
        StructuredLog.w("PROJECTION", "search controller unavailable; address entry disabled")
        return
    }
    // Every box method throws IllegalStateException if no callback is set and only catches
    // RemoteException internally (see Investigation). The callback is registered once in onCreate,
    // but if that registration failed (RemoteException) the controller is non-null-yet-unusable, so
    // guard the whole trigger and degrade to a logged no-op instead of crashing on the first tap.
    runCatching {
        controller.setSearchHint("Search or type a URL")
        controller.showSearchBox()
        controller.startSearch(currentUrl())   // pre-seed with the current URL (see note below)
    }.onFailure { StructuredLog.w("PROJECTION", "search box unavailable: ${it.message}") }
}
```

**Pre-seed semantics — a DHU-runtime assumption, with a fallback.** The bytecode shows
`startSearch(String)` only forwards the string to the host over RPC (`q.a(String)`); whether the
host renders it as *editable seeded text* in the box or *commits it as a submitted query* is host
runtime behavior that cannot be verified statically from the aar. This design assumes it seeds
(editable), which is the useful case, and lists that assumption in Testability's "needs device/DHU"
set. If DHU shows the host commits instead (tapping the field would re-search the current URL
rather than let the driver edit), the fallback is to drop the `startSearch(currentUrl())` line and
open an empty box with `setSearchHint` only — the display `TextView` remains the URL readout
regardless, so the user still sees the current URL. That change is one line and does not alter the
callback/normalization paths.

**`onCreate` ordering (pinned — load-bearing, not merely defensive).** `searchController` is acquired
and its `SearchCallback` registered **immediately after `setContentView(buildLayout())` and BEFORE
the existing `enforcePolicy()` call** in `onCreate` (after `super.onCreate`, where `carUiController`
is valid). This ordering is required, not cosmetic: the existing `onCreate` already calls
`enforcePolicy()` before `loadUrl`, and this design adds `searchController?.hideSearchBox()` to
`enforcePolicy()`'s `!permitted` branch. Because `hideSearchBox()` throws
`IllegalStateException("No SearchCallback is set")` when no callback has been registered (verified
in bytecode, see Investigation), a launch **while driving** (not parked → `allowed()` false → first
`enforcePolicy()` takes the `!permitted` branch) would call `hideSearchBox()` on a non-null host
controller with no callback yet and crash the activity on startup. The `?.` safe-call does not help
because the controller is non-null on the real host. Acquiring + setting the callback first clears
that guard; wrapping the box calls in `runCatching` is the second line of defense if the callback
registration itself failed.

The exact ordering in `onCreate`:

```kotlin
setContentView(buildLayout())
// 1. Acquire the controller and set the callback FIRST — before any enforcePolicy()/box call.
searchController = runCatching { carUiController.searchController }.getOrNull()
searchController?.let { c ->
    runCatching { c.setSearchCallback(searchCallback) }
        .onFailure { StructuredLog.w("PROJECTION", "setSearchCallback failed: ${it.message}") }
}
ParkingStateStore.addListener(parkingListener)
WebMediaHub.register(this, mediaSource)
enforcePolicy()                       // 2. existing call — now safe: callback is already set
webView?.loadUrl(BrowserDefaults.lastUrl(this))
```

The `SearchCallback` is held as a field (`searchCallback`) so acquisition and registration read
cleanly and the same instance is reused:

```kotlin
private val searchCallback = object : SearchCallback() {
    override fun onSearchTextChanged(text: String) { /* no suggestions in phase 1 */ }
    override fun onSearchItemSelected(item: SearchItem) { /* no items offered in phase 1 */ }
    override fun onSearchSubmitted(query: String): Boolean {
        navigateFromInput(query)
        runCatching { searchController?.stopSearch() }
        return true     // we consumed it
    }
}
```

**Invariant (stated for the implementer):** `setSearchCallback(...)` is always called in the same
breath as controller acquisition and before any
`showSearchBox`/`hideSearchBox`/`startSearch`/`stopSearch`; the SDK requires this or it throws
`IllegalStateException`. Every one of those four box calls is additionally wrapped in `runCatching`
so a failed registration degrades to a logged no-op rather than a crash.

**Normalization (the pure function, reused).** `navigateFromInput` is the single call site that
turns typed text into a URL; it must not re-implement the rule:

```kotlin
private fun navigateFromInput(input: String) {
    if (!allowed()) return enforcePolicy()
    val url = BrowserInputResolver.resolveBrowserInput(input, SearchEngineStore.engine(this))
    StructuredLog.i("PROJECTION", "address entry -> $url")
    webView?.loadUrl(url)
}
```

`resolveBrowserInput` already: upgrades a bare host to `https://` via `ContentAddress.https`, routes
every non-`https` scheme to search — `javascript:` and `intent:` as a security measure, plain
`http:` as a product decision to downgrade insecure links rather than load them — and encodes free
text onto the selected engine (`SearchEngineStore.engine(this)`, default YouTube). This is the exact
"typed text → URL vs search URL" normalization the task calls out, and it is already JVM-unit-tested
(`BrowserInputResolverTest`). The task's instruction to "reuse `BrowserDefaults.HOME` search
convention / Google search" is satisfied: `SearchEngine.GOOGLE.searchUrl` is the same
`https://www.google.com/search?q=` convention, and `BrowserDefaults.HOME` is used by the Home button
and the YouTube/Google engines. We do **not** route through `open()`/`ContentAddress.https` directly
for typed input, because that would drop free-text queries (it returns null for non-URLs); `open()`
stays as-is for the quick-site buttons, which always carry URLs.

Note: `loadUrl` is used (not `open()`) because the resolver has already produced a safe absolute
`https`/search URL; the `shouldOverrideUrlLoading` scheme block remains the backstop that refuses
any non-http(s) that somehow reaches navigation.

**Keeping the field in sync.** One helper is the single source for "the current URL", used by both
the pre-seed above and the display sync, so the three previously-divergent fallbacks collapse to one
rule (`webView?.url ?: BrowserDefaults.HOME`):

```kotlin
/** The page's current URL, or HOME when there is none yet. Single source for "current URL". */
private fun currentUrl(): String = webView?.url ?: BrowserDefaults.HOME

/** Writes the live page URL into the display field. */
private fun syncAddress() { urlField?.text = currentUrl() }
```

The page callbacks in §3 call `syncAddress()` (rather than writing `urlField?.text` inline) so every
display write goes through the one helper.

### 2. Navigation buttons

In `buildLayout`, the bar becomes (order: Back, Forward, Reload, Home, quick sites), with `urlField`
first:

```kotlin
addView(urlField)
addView(action("Back") { goBack() })
addView(forwardButton)                          // kept as a field so we can toggle enabled state
addView(action("Reload") { webView?.reload() })
addView(action("Home") { open(BrowserDefaults.HOME) })
QUICK_SITES.forEach { (label, url) -> addView(action(label) { open(url) }) }
```

- **Forward.** `forwardButton = action("Forward") { if (webView?.canGoForward() == true) webView?.goForward() }`.
  Its enabled/greyed state is refreshed by `updateNav()` (below) on every page/progress callback:
  `forwardButton?.isEnabled = webView?.canGoForward() == true` and a dimmed alpha when disabled, so
  it reads as inactive. Enabled-only acting also guards the click.
- **Home.** Reuses `open(BrowserDefaults.HOME)`, which already runs the `allowed()`/`enforcePolicy()`
  gate and `ContentAddress.https`. `BrowserDefaults.HOME` is `https://www.google.com/`.
- **Back.** Unchanged — `goBack()` still exits fullscreen first, else `webView.goBack()`.
- **Reload.** Unchanged.

```kotlin
private fun updateNav() {
    forwardButton?.let {
        val canForward = webView?.canGoForward() == true
        it.isEnabled = canForward
        it.alpha = if (canForward) 1f else 0.4f
    }
}
```

**Horizontal scroll (NFR3).** Wrap the bar in a `HorizontalScrollView`:

```kotlin
val barScroller = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    setBackgroundColor(Color.rgb(24, 24, 24))
    addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
```

`column` adds `barScroller` (fixed 56.dp height) instead of `bar`. The `urlField` keeps a min width
so it does not collapse; the row scrolls when it overflows the display width.

### 3. Loading progress + page identity

**Progress view.** A horizontal `ProgressBar` between the bar and the WebView, assigned to the
`progress` field (declared under §1 "Fields") so `enforcePolicy()` can reach it:

```kotlin
progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    visibility = View.GONE
    contentDescription = "Page loading"
}
```

Added to `column` with height ~`3.dp()`, `MATCH_PARENT` width, between `barScroller` and `web`.

**WebChromeClient extensions** (added to the existing anonymous `WebChromeClient`, keeping
`onPermissionRequest`, `onShowCustomView`, `onHideCustomView` exactly as they are):

```kotlin
override fun onProgressChanged(view: WebView, newProgress: Int) {
    progress?.progress = newProgress.coerceIn(0, 100)
    progress?.visibility = if (newProgress < 100 && allowed()) View.VISIBLE else View.GONE
    updateNav()
}

override fun onReceivedTitle(view: WebView, title: String?) {
    // Title is a secondary signal; the URL is the identity. Keep showing the URL, and only
    // fall back to the title when there is no URL yet.
    if (!view.url.isNullOrEmpty()) syncAddress()
    else if (!title.isNullOrEmpty()) urlField?.text = title
    updateNav()
}
```

**WebViewClient extensions** (added alongside the existing `shouldInterceptRequest`,
`shouldOverrideUrlLoading`, `onPageStarted`, `onPageFinished`, none of which change):

```kotlin
override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
    BrowserDefaults.applyIdentity(this@ProjectionBrowserActivity, view, url) // existing line
    syncAddress()                                                            // added
    updateNav()                                                              // added
}

override fun onPageFinished(view: WebView, url: String) {
    BrowserDefaults.remember(this@ProjectionBrowserActivity, url)           // existing
    audio.installPlayTracking()                                             // existing
    syncAddress()                                                           // added
    updateNav()                                                             // added
}

override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
    syncAddress()             // SPA / in-page navigations that never fire onPageFinished
    updateNav()
}
```

**"Do not fight user edits in progress."** On this SDK the editing happens in the host's search box,
which is a separate surface from our `urlField` `TextView`. Our updates write to the `TextView`
only, never to the open host search box, so page-driven updates cannot clobber what the driver is
typing. There is therefore no edit-in-progress race to guard against — the host box owns the live
text, and we only read its result through `onSearchSubmitted`. (If the fallback no-op path is ever
hit because the controller is absent, there is no live edit at all.)

### Parked / policy gating

All new navigation entry points route through the same gate as `open()`:

- `openAddressEntry()` and `navigateFromInput()` both begin with `if (!allowed()) return
  enforcePolicy()`.
- Home uses `open()`, which already gates.
- Forward/Reload operate on existing in-WebView history and are cheap; they are additionally covered
  by `enforcePolicy()` making the WebView `INVISIBLE` and pausing audio when the gate denies, and by
  `progress` being hidden when `!allowed()`.
- `allowed()` is unchanged: `SafetyEnforcement.gateParked(ParkingStateStore.isParked) &&
  FeaturePolicy.app.isAvailable(Feature.BROWSER)`.
- `enforcePolicy()` gains two null-safe lines inside the existing `!permitted` branch (alongside the
  current `exitFullscreen()` / `audio.userPause()`), so an open search box or a stale progress bar
  cannot linger over the gate screen:

  ```kotlin
  if (!permitted) {
      exitFullscreen()
      audio.userPause()
      runCatching { searchController?.hideSearchBox() }   // added — wrapped: throws IllegalState if
                                                          // no callback is set (see Investigation)
      progress?.visibility = View.GONE                    // added — field, so a top-level method can reach it
  }
  ```

  Because `progress` is a field (not a `buildLayout`-local `val`), this compiles; a `val` local to
  `buildLayout()` would not be visible here. `hideSearchBox()` is wrapped in `runCatching` for the
  same reason as every other box call: it throws `IllegalStateException` when no callback is set.
  The `onCreate` ordering guarantees the callback is set before this branch can ever run (see §1
  "onCreate ordering"), and the `runCatching` is the belt-and-braces fallback for a failed
  registration.

### Preserved behaviors (explicit)

No change to: fullscreen video (`onShowCustomView`/`enterFullscreen`/`exitFullscreen`), media-session
audio (`WebMediaHub`/`WebAudioBridge`, single audio-focus owner, no `onPause` override pausing the
WebView), ad-block (`shouldInterceptRequest`), play queue (`skipToNext`), last-URL memory
(`BrowserDefaults.remember`/`lastUrl`), the non-http(s) scheme block in `shouldOverrideUrlLoading`,
`setIgnoreConfigChanges(-1)`, `WebViewTimerGate.hold/release`, `hideAppHeader()`,
`hideMenuButton()`. `QUICK_SITES` is unchanged.

### Lifecycle / teardown

In `onDestroy`, before destroying the WebView, call `runCatching { searchController?.stopSearch() }`
and `runCatching { searchController?.hideSearchBox() }` (both wrapped — they throw
`IllegalStateException` when no callback is set), then null the new fields (`searchController = null`,
`urlField = null`, `forwardButton = null`, `progress = null`) alongside the existing `webView = null`
/ `root = null`, so no host search box or callback outlives the activity. The `SearchCallback` holds
`this@ProjectionBrowserActivity` only transitively through `webView`/`searchController`, both nulled
in `onDestroy`.

---

## Error handling (per fallible operation)

- **`carUiController.searchController` acquisition** — may be null or throw on hosts/SDK builds that
  do not expose it. Wrapped in `runCatching{…}.getOrNull()`. Recoverable: `searchController` stays
  null, the URL control becomes a logged no-op (`StructuredLog.w`), quick sites + Home still
  navigate. Not fatal; not surfaced to the driver beyond the field simply not opening a box.
- **`setSearchCallback(...)`** — catches `RemoteException` internally but could still fail to install
  the listener on an RPC hiccup. Wrapped in `runCatching{…}.onFailure { StructuredLog.w(…) }` in
  `onCreate`. Recoverable: the controller is then "acquired but unusable"; every box call is itself
  wrapped (next item), so the surface degrades to a logged no-op. Not fatal.
- **`showSearchBox`/`hideSearchBox`/`startSearch`/`stopSearch`** — each throws
  `IllegalStateException("No SearchCallback is set")` when no callback is registered, and only
  catches `RemoteException` internally (verified by `javap -c`). Every call site wraps them in
  `runCatching{…}.onFailure { StructuredLog.w(…) }` (`openAddressEntry`, `onSearchSubmitted`,
  `enforcePolicy`, `onDestroy`). The `onCreate` ordering guarantees a callback is set before any of
  these can run on the happy path; the wrap covers the acquired-but-callback-unset edge. Recoverable;
  logged at warn; the gate screen / teardown still proceeds. Not fatal.
- **`onSearchSubmitted(query)` with empty/garbage text** — delegated to
  `resolveBrowserInput`, which returns an engine search URL for empty input (`engine.searchUrl("")`)
  and never throws. Result is a valid `https` URL; `loadUrl` handles it. No separate handling needed.
- **`resolveBrowserInput` → unsafe scheme** — routed to search by the resolver; `shouldOverrideUrlLoading`
  remains the second line of defense and refuses any non-http(s) that reaches navigation. No throw.
- **`webView` null** (post-destroy callback) — all new code uses safe calls (`webView?.…`,
  `urlField?.…`); a late callback is a no-op.
- **`goForward()` when `!canGoForward()`** — guarded by the click handler and `isEnabled`; WebView
  would otherwise ignore it, but we never call it in that state.
- **Progress callback after gate denies** — `onProgressChanged` checks `allowed()` before showing
  the bar, so a background load cannot flash progress over the gate screen.

Logging: navigations and the unavailable-controller case use `StructuredLog` under the existing
`"PROJECTION"` tag, matching the file's convention (info for navigations, warn for the degraded
path). No new log tags.

## Input validation (per external input)

- **Typed address/search text** (from `onSearchSubmitted`): optional (empty allowed → search),
  type `String`, no length cap imposed here (the host search box bounds it; `URLEncoder` handles any
  content including Thai/specials — already tested). Behavior on "not a URL": becomes a search.
  Behavior on unsafe scheme: becomes a search. Validation owned by `BrowserInputResolver` /
  `ContentAddress.https`.
- **Quick-site / Home URLs** (compile-time constants): already `https` literals; still pass through
  `open()`→`ContentAddress.https`.
- **URLs from WebView callbacks** (`onPageStarted/Finished`, `doUpdateVisitedHistory`,
  `onReceivedTitle`): display-only into the `TextView`; not re-navigated, so no validation needed
  beyond null/empty checks.

## Invariants and ownership

- **"Only http(s) ever loads"** — owned by `WebViewClient.shouldOverrideUrlLoading` (unchanged) as
  the enforcement layer, with `BrowserInputResolver`/`ContentAddress.https` as the normalization
  layer that makes unsafe input never reach navigation. Keeping both means a bug in either still
  fails safe.
- **"Navigation only when allowed"** — owned by `allowed()`/`enforcePolicy()`, consulted at every
  new entry point. Single source of truth, unchanged.
- **"One audio-focus owner"** — owned by `WebAudioBridge`/Chromium; this change adds no audio path.
- **"URL field reflects the live page"** — owned by the WebView callbacks writing `urlField`; the
  host search box owns in-flight edits, so there is no dual-writer conflict.

---

## Testability

- **Unit (JVM, no device).** The normalization contract is the pure
  `BrowserInputResolver.resolveBrowserInput` + `looksLikeUrl`, already covered by
  `app/src/test/java/dev/autobridge/browser/BrowserInputResolverTest.kt`. This design adds **no new
  resolver**, so that suite is the Phase-1 unit coverage; keep it green. If a projection-specific
  assertion is wanted, add one case to that existing test asserting the projection bar's contract
  (bare host → https, free text → engine search, unsafe scheme → search). Because the projection
  activity itself lives in `src/projection/java` (compiled only into personal/lab) and depends on
  the SDK and `WebView`, it is **not** unit-testable on the JVM; the resolver is deliberately the
  piece that is pure and tested, which is why typed-input handling delegates to it rather than
  branching inline.
- **Not unit-testable (needs device/DHU, integration).** `SearchController` wiring, host search box,
  progress visibility, Forward enablement, and the view tree all require a running WebView and the
  car host; these are verified on-device/DHU, consistent with the module's existing
  `androidTest`/manual-DHU posture noted in `app/build.gradle.kts`. Two host-runtime behaviors in
  particular must be confirmed on DHU because they are not statically verifiable from the aar:
  (a) `getSearchController()` returns non-null on the personal/lab target host; and
  (b) `startSearch(currentUrl())` renders the string as *editable seeded text* rather than
  *committing it as a search* — if the host commits, apply the empty-box fallback from §1
  ("Pre-seed semantics"). The callback-before-box ordering is enforced statically by the `onCreate`
  structure and does not need DHU, but its crash symptom (launch-while-driving) is worth a smoke
  check on DHU.
- A design where typed-input logic lived inside the activity would be hard to test; delegating to the
  already-pure resolver keeps the testable surface clean, which is the right factoring.

## Files touched

- `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt` — all view-tree,
  callback, and `SearchController` changes above. Imports added:
  `com.google.android.apps.auto.sdk.{SearchController, SearchCallback, SearchItem}`,
  `android.widget.{HorizontalScrollView, ProgressBar}`, `android.graphics.Bitmap`,
  `dev.autobridge.browser.{BrowserInputResolver, SearchEngineStore}`.
- No change to `app/build.gradle.kts` (projection source set and the aar are already wired to
  personal/lab; the SDK classes used are in the already-linked aar).
- No new source or test files required (resolver + its JVM test already exist).

## Assumptions

- The projection host exposes `getSearchController()` at runtime on the personal/lab target devices
  (strongly indicated by the aar's IInterface-backed `SearchController` and `search_box_*`
  resources). The null-safe acquisition + logged fallback covers the case where it does not, so this
  assumption is not load-bearing for correctness.
- `SearchEngineStore` default (YouTube) is acceptable for the car bar; it is the app-wide default and
  shared with the phone address bar and "Send to car", so behavior is consistent across surfaces.
- `startSearch(currentUrl())` seeds the host box with editable text (rather than committing it as a
  search). Verified only on DHU; if false, the §1 empty-box fallback applies. Not load-bearing for
  correctness — only for the pre-seed convenience.

---

## Responses to design review

Review: `.agents/tasks/projection-browser-shell-phase1/design-review.md`
(verdict CHANGES_REQUESTED — 1 HIGH, 1 MEDIUM, 2 NITs). Every finding is **addressed** below; none
backlogged, none ignored. All changes stay within the original Phase-1 requirements (no scope added).
The review's SDK evidence was independently re-verified for this revision by decompiling the
`SearchController` bytecode (`javap -c -p` on `com/google/android/apps/auto/sdk/SearchController.class`):
`showSearchBox`, `hideSearchBox`, `startSearch(String)`, and `stopSearch` each throw
`IllegalStateException("No SearchCallback is set")` and catch only `RemoteException`, while
`setSearchCallback` and `setSearchHint` have no such guard — exactly as the review states.

- **Finding 1 (HIGH) — `onCreate` ordering lets `enforcePolicy()` call `hideSearchBox()` before a
  `SearchCallback` is set, throwing `IllegalStateException` on a launch-while-driving.** ADDRESSED.
  §1 "onCreate ordering (pinned)" is rewritten to make the ordering load-bearing, not cosmetic:
  `searchController` is acquired and `setSearchCallback(searchCallback)` is called **immediately
  after `setContentView(buildLayout())` and before the existing `enforcePolicy()`** call. The
  `SearchCallback` is now a reusable field (`searchCallback`). The invariant is stated explicitly in
  the design: the callback is set in the same breath as acquisition and before any
  show/hide/start/stop box call, which the SDK requires or it throws. Additionally, every box call
  (`hideSearchBox` in `enforcePolicy`, `show/startSearch` in `openAddressEntry`, `stopSearch` in the
  callback and `onDestroy`) is wrapped in `runCatching{…}.onFailure { StructuredLog.w(…) }` as the
  belt-and-braces fallback for a failed registration. The error-handling section gains a dedicated
  entry for the four guarded box methods.

- **Finding 2 (MEDIUM) — `openAddressEntry()` can throw `IllegalStateException` from
  `showSearchBox()`/`startSearch()` on an acquired-but-callback-unset controller.** ADDRESSED.
  §1 "Opening the input surface" now wraps the `setSearchHint`/`showSearchBox`/`startSearch` body in
  `runCatching { … }.onFailure { StructuredLog.w("PROJECTION", "search box unavailable: …") }`, so a
  non-null-yet-unusable controller degrades to a logged no-op instead of crashing on the first tap.
  NFR5 ("degrade safely") now holds for the acquired-but-unusable case, not just the null case; the
  "Risk and the required fallback" subsection spells this out as its second defensive rule.

- **Finding 3 (NIT) — ambiguous pre-seed semantics of `startSearch(currentUrl())`.** ADDRESSED.
  §1 "Opening the input surface" now marks "editable seed vs. committed search" as a host-runtime
  behavior that is not statically verifiable, and it is added to the Testability "needs device/DHU"
  list (item b) and to Assumptions. A one-line fallback is specified: if DHU shows the host commits
  the string, drop the `startSearch(currentUrl())` call and open an empty box with `setSearchHint`
  only; the display `TextView` remains the URL readout regardless.

- **Finding 4 (NIT) — "even `http:`" phrasing understates that http→search is a product decision,
  not a security rejection.** ADDRESSED. Acceptance Criteria 4 is reworded to
  "`ContentAddress.https` accepts only `https`; `http`, `javascript:`, `intent:` and all other
  schemes resolve to a search query (verified by `BrowserInputResolverTest`)," and the §1
  Normalization paragraph now distinguishes `javascript:`/`intent:` (security) from plain `http:`
  (a product decision to downgrade insecure links).

The review's verified assumptions (SDK `SearchController`/`SearchCallback`/`getSearchController`
signatures and the `q`/`IInterface`/`RemoteException` backing, `BrowserInputResolver` /
`SearchEngineStore` / `ContentAddress.https` behavior, preserved behaviors, parked gating,
accessibility, no scope creep) were re-confirmed and are unchanged. The two not-statically-verifiable
items — the host returning a non-null `getSearchController()`, and `startSearch` seeding vs.
committing — are both covered by the null/`runCatching` fallbacks and listed as DHU checks.

### Prior review round (folded in, retained for history)

An earlier review round (1 MEDIUM `progress`-field + 4 NITs) was already resolved in a previous
revision and remains in effect: `progress`/`urlField`/`forwardButton`/`searchController` are nullable
fields assigned in `buildLayout` and nulled in `onDestroy`; `urlField` is a fully-specified
single-line `TextView` (not an `action()` variant); the `SearchController` backing field is correctly
described as `q`/`IInterface`; and a single `currentUrl()` helper is the one source for the current
URL. Those changes are unchanged by this round.
