# Browser UI polish: fullscreen default, Material-icon migration, slider, dialog audit

The change finishes the half-done migration of the browser's chrome from per-call-site Unicode glyphs to one shared `BrowserIcon` vector set, makes a fresh install launch fullscreen while remembering a later toggle-off, promotes Fullscreen into the hamburger menu next to Desktop mode on both the phone and the car, and confirms the display-scale control and dialog shapes are already right. The critical path was `CarWebRenderer.kt`, which the prior agent had left red: its canvas chrome still read a removed `glyph: String` model field, and six call sites failed to compile. Those are now resolved with a cached `drawIcon(...)` helper, and every chrome glyph the renderer used to draw as text is now a tinted vector. The two surfaces (Canvas on the car, native Views on the phone) route the same role through the same drawable at a size derived from the same `AutoUiSizes` token.

Watch for: nothing blocking. The car fullscreen seeding path (construction default vs. first-start reconciliation) is subtle but correct — verified below (confirmed). The menu/drawer fullscreen entry draws `FULLSCREEN_ENTER` statically and leans on the switch's on/off state to show "entered"; the dynamic enter/exit swap only happens on the car toolbar slot — a deliberate, consistent choice, not a gap (confirmed). On-device visual confirmation of icon rendering on the projection Canvas surface is still owed by the user; it cannot be screenshotted in this environment (possible).

**Verdict**: APPROVED

## High-level view

The icon migration is the bulk of the work and it is complete. `CarWebRenderer.kt` has zero `.glyph` references and zero chrome-glyph `drawText` calls; every remaining `drawText` draws genuine text (page title/host, the "Tabs n/MAX" counter, numeric count badges, the error overlay). The phone View files carry no leftover chrome glyph literals either. One `drawIcon` helper caches the inflated drawable per `resId` and re-applies tint/alpha/bounds per draw, so icons are not re-inflated every frame.

Fullscreen-on-launch is a stored default, not a per-launch force. `BrowserControlsStore.startFullscreen` returns `true` only until the first toggle writes a value, after which the user's choice wins. Both surfaces seed from it exactly once per session — the phone on a null `savedInstanceState`, the car on `firstStart` applied straight through `ChromeVisibility` so seeding doesn't rewrite the preference it just read — and both persist on every `setFullscreen`.

The Fullscreen entry sits directly below Desktop mode on both surfaces (`BrowserMenuSheet` and `BrowserDrawerModel`), both dispatch `TOGGLE_FULLSCREEN`, and both reflect `state.fullscreen`. The old standalone toolbar fullscreen button was removed from the phone and the car layout, with the layout test updated to assert Menu is now the trailing slot.

The display-scale control is a single complete `SeekBar` that reads/writes `BrowserDisplayScaleStore` and applies zoom on release only; no other scale picker exists. The dialog audit found the only exclusive-choice list (User-Agent) is already a radio and no multi-toggle dialog needs checkboxes, so no dialog code changed — documented in `dialog-audit.md`. Scope held: no `src/safe`, `src/projection`, or `src/mirror` changes.

<details>
<summary>Issues (1)</summary>

1. **On-device visual check still owed** — the projection Canvas surface can't be screenshotted here, so centered/proportional icon rendering, the enter/exit toolbar state, and the fresh-install-fullscreen / toggle-off-survives-relaunch behavior need a one-time confirmation on a head unit and phone. Non-blocking; informational.

</details>

<details>
<summary>Details</summary>

### CarWebRenderer glyph-to-vector migration and the drawIcon cache

This was the red-build fix and the largest surface. The renderer previously drew chrome by setting `glyphPaint.textSize` and calling `canvas.drawText(someModel.glyph, ...)`; the prior agent removed the `glyph` field in favor of `icon: BrowserIcon` but left six call sites referencing the gone field. The fix is a single helper:

```kotlin
private fun drawIcon(canvas, icon, cx, cy, sizePx, color, alpha = 255) {
    val drawable = iconCache.getOrPut(icon.resId) {
        ContextCompat.getDrawable(appContext, icon.resId)!!.mutate()
    }
    val half = sizePx / 2f
    drawable.setBounds((cx-half).roundToInt(), (cy-half).roundToInt(), (cx+half).roundToInt(), (cy+half).roundToInt())
    drawable.setTint(color); drawable.alpha = alpha.coerceIn(0,255); drawable.draw(canvas)
}
```

Caching by `resId` and mutating once per entry avoids re-inflating a vector on every frame — the right call for a surface that redraws continuously. The center-on-`cy` geometry replaces the old baseline-offset text math, which is what the proportionality pass needed: a vector centered in its box reads at a predictable size, where a glyph's visual weight varied by font.

`grep "\.glyph"` on the file returns nothing, and a scan for the chrome glyph literals (`✕ × + ☰ ↻ ‹ › ⛶ ⤡ 🔒`) in `drawText` calls returns nothing. The toolbar slot resolves BACK/FORWARD/RELOAD, swaps RELOAD↔CLOSE while loading, and swaps FULLSCREEN_ENTER↔FULLSCREEN_EXIT by `isFullscreen` before handing a single `drawIcon`. The address badge draws LOCK or WARNING by scheme. Every surviving `drawText` is genuine content: the address label, sheet titles, the per-tab title/host, the "Tabs n/MAX" counter, the numeric `row.item.value` count badge, and the error-overlay message/hint.

### Fullscreen as a stored default, seeded once per surface

`BrowserControlsStore` gains `startFullscreen` (default `true`) and `setStartFullscreen`, in the existing `autobridge_browser` prefs file. The default only holds until the first write, so this is a default, not a forced-every-launch behavior — the exact distinction the task called out.

The phone cold-start branch reads it only when there is no saved instance:

```kotlin
setFullscreen(savedInstanceState?.getBoolean("fullscreen") ?: BrowserControlsStore.startFullscreen(this))
```

so a rotation/process-restore keeps what was on screen while a fresh launch honors the stored default. `setFullscreen` persists the choice on every call.

The car path is the subtle one and it holds up. The field initializer constructs `ChromeVisibility().apply { setFullscreen(0L, true) }` with a hardcoded `true`, but that is a pre-surface construction placeholder — no draw happens before `start()`. Inside `start()`, `firstStart = webView == null` gates the real seeding, which is applied through `visibility.setFullscreen(...)` directly (not the public `setFullscreen`) so reading the stored value doesn't immediately rewrite it. Because the seed is gated on `firstStart`, a surface re-attach or resize cannot stomp a choice the user made mid-session. The public `CarWebRenderer.setFullscreen` persists via `setStartFullscreen`. One asymmetry worth noting without alarm: the public car `setFullscreen` writes the preference even when the state is unchanged (the persist runs before the `visibility.fullscreen == enabled` early-return) — a redundant write, not a correctness problem, and the author flagged it in the comment.

### Fullscreen entry adjacent to Desktop on both surfaces

`BrowserMenuSheet` adds `desktopToggle(current)` then `fullscreenToggle(current)`; `BrowserDrawerModel` builds `listOf(desktopToggle(state), fullscreenToggle(state))`. Both dispatch `DrawerAction.TOGGLE_FULLSCREEN` and set `on = state.fullscreen`, and `BrowserActivity` now wires `TOGGLE_FULLSCREEN -> setFullscreen(!fullscreen)` (previously a no-op in the ignored-actions arm). The standalone toolbar fullscreen button is gone from the phone `buildChrome`, and `BrowserChromeLayoutTest` was updated to assert the address pill's right edge is now bounded by the MENU slot rather than the removed FULLSCREEN slot.

The menu/drawer rows draw `FULLSCREEN_ENTER` statically and rely on the switch's on/off visual to convey state, while the enter/exit icon swap is dynamic only on the car toolbar slot. That split is coherent: a switch row already encodes state through the switch, so a static leading icon is correct there; a bare toolbar button has no switch, so it swaps the glyph.

### Display-scale slider and dialog audit

`BrowserSettingsSheet.displayScaleRow()` is one `SeekBar`: `max` is the step count over the store's MIN/MAX, `progress` seeds from the stored percent, `onProgressChanged` updates a live label, and the store write + zoom apply happen in `onStopTrackingTouch` only — deliberately on release so the page isn't re-zoomed mid-drag under the finger. No other display-scale picker exists (the car's `ZOOM_IN/ZOOM_OUT` are WebView zoom, a separate concern, correctly left alone).

The dialog audit (`dialog-audit.md`) classifies every `AlertDialog`: User-Agent is already `setSingleChoiceItems` (radio) and left alone; home-page/custom-UA/find are text entry; reset/delete/clear/sign-in are confirmations; bookmarks/history/downloads are navigate-or-informational lists. Independent on/off options already live as `Switch` rows in the settings sheet (the checkbox equivalent), so no dialog needed converting. The `◉` selection marker in `SendToCarSheet` is kept as genuine single-selection content while that sheet's SEARCH and CLOSE chrome icons were migrated to vectors — the right line between content and chrome.

### Verification evidence and scope

`verification.md` records `:app:assemblePersonalDebug` and `:app:testPersonalDebugUnitTest` both BUILD SUCCESSFUL, the CI lint gate `:app:lintSafeDebug` clean, and the six `:app:lintPersonalDebug` errors as pre-existing and outside browser scope (`src/projection`, `src/mirror`). `BrowserViewportTest.kt` was updated to match the new drawer model (desktop + fullscreen toggles, asserting the fullscreen row stacks below desktop and dispatches `TOGGLE_FULLSCREEN`) and the toolbar layout change, and is reported green. `git diff --name-only` shows no changes under `src/safe`, `src/projection`, or `src/mirror`, so the Play flavor and projection descriptor are untouched. `ic_car_home` (used by `HOME_PAGE`) and all 37 `ic_browser_*.xml` drawables exist, so every `BrowserIcon.resId` resolves.

Not independently re-run here (per instruction): the gradle assemble and unit-test commands — the recorded results were read instead. Not verifiable in this environment: on-device rendering of the Canvas chrome.

</details>

<details>
<summary>File map</summary>

- `BrowserIcons.kt` (new) — `BrowserIcon` enum, one Material vector per role, shared by both surfaces.
- `app/src/main/res/drawable/ic_browser_*.xml` (new, 37) — the vector set.
- `CarWebRenderer.kt` — `drawIcon` cache helper; all chrome glyph draws replaced with vectors; `FloatingButtonAction` icon migration consumed; car fullscreen seeding on `firstStart` + persist.
- `BrowserControlsStore.kt` — `startFullscreen`/`setStartFullscreen` (default true); `FloatingButtonAction.glyph` → `.icon`.
- `BrowserActivity.kt` — cold-start fullscreen seeding; `setFullscreen` persists; toolbar fullscreen button removed; `TOGGLE_FULLSCREEN` wired; toolbar buttons → vector icons.
- `BrowserMenuSheet.kt` / `BrowserDrawer.kt` — fullscreen toggle stacked below desktop on both surfaces.
- `BrowserSettingsSheet.kt` — display-scale `SeekBar` (verified complete); back/chevron → vectors.
- `BrowserSheetShell.kt`, `MoreActionsSheet.kt`, `SendToCarSheet.kt`, `BrowserChromeLayout.kt`, `BrowserDisplayScaleStore.kt` — remaining View glyphs → `ImageView` + `BrowserIcon`.
- `BrowserViewportTest.kt` — updated for the new drawer toggles and toolbar layout; reported green.
- `.agents/tasks/browser-ui-polish/{plan,verification,dialog-audit}.md` — planning and evidence.
- `README.md`, `quick-install.md` — docs, unrelated to the UI behavior.

Full diff: `git diff HEAD` in `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`.

</details>
