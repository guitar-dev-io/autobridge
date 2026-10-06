# Verification — Gboard-style car keyboard

Visual/size-only restyle of the on-screen (projection) keyboard in
`app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt`.
No layout data, language logic, or committing behavior was changed. This is the first
iteration (no `review.json` was present).

## What each key category now renders as

All caps are drawn by `roundedRipple(fill, 12f)` — a `RippleDrawable` over a rounded-rect
`GradientDrawable` whose corner radius is `12f * density` (was `10f`). Fill and ink come from the
new `keyCapFill(key)` / `keyCapInk(key)` helpers (next to `keyButton`), which derive only from
`scheme` (`BrowserTheme.dark`) M3 roles — no hardcoded hex.

| Key category | Fill (`keyCapFill`) | Fill color source | Ink (`keyCapInk`) | Text size | Weight |
|---|---|---|---|---|---|
| Letters/numbers (`CarKey.Text`) | light raised cap | `scheme.surfaceContainerHighest` (`#33353A`) | `scheme.textPrimary` (`#E2E2E9`) | 22f (was 19f) | BOLD |
| Space (`CarKey.Space`) | light raised cap | `scheme.surfaceContainerHighest` | `scheme.textPrimary` | 17f (was 15f) | BOLD |
| Modifiers: Shift, Symbols, Language, Backspace | darker/greyer cap | `scheme.surfaceContainerHigh` (`#282A2F`) | `scheme.textPrimary` | 17f | BOLD |
| Go (enter) | accent cap | `scheme.fabContainer` (= primaryContainer) | `scheme.onFabContainer` (= onPrimaryContainer) | 17f | BOLD |

Letter caps (`surfaceContainerHighest`) sit visibly above the tray's `surfaceContainerLow`
background; modifier caps (`surfaceContainerHigh`) read as a distinct, darker tier; Go keeps its
blue-equivalent accent fill — Gboard's three-tier coloring. All caps are bolded via
`setTypeface(typeface, Typeface.BOLD)`, centered (`gravity = CENTER`), rounded at 12f.

## Sizing & spacing

- `KEY_HEIGHT`: 48 → **56** dp (Gboard-like key height for arm's-length taps). Each row in
  `rebuildKeys()` is `KEY_HEIGHT.dp()` tall.
- `KEY_GAP`: 3 → **4** dp. Used for per-key `marginStart`/`marginEnd` and row `topMargin`
  (unchanged call sites), giving each cap a clear 4dp gutter.
- Panel tray padding: was uniform `KEY_GAP.dp()`; now `setPadding(8, 6, 8, 8)` dp
  (left/top/right/bottom) so the caps sit inset on a toolbar-like tray. Tray background stays
  `scheme.surfaceContainerLow`.
- Panel still added as `MATCH_PARENT × WRAP_CONTENT`, `Gravity.BOTTOM`. Fit check: 4 key rows ×
  56dp = 224dp + 3 inter-row top-margins × 4dp = 12dp + preview row 52dp + preview bottom-margin
  6dp + tray top/bottom padding 14dp ≈ **308dp**, comfortably under a typical head-unit surface
  height, so the panel still fits over the page.

## Preview row & close control

- **Preview** (`keyboardPreview`): now has a rounded filled field background — a plain
  (non-ripple) `GradientDrawable`, radius `12f * density`, color `scheme.surfaceContainerHigh` —
  so typed text reads like Gboard's text area. Non-clickable, so a plain rounded fill rather than
  a ripple (per plan item 5). Padding re-set to `(16, 0, 16, 0)` dp after the background (a
  drawable background resets padding). `marginEnd = KEY_GAP.dp()` separates it from the ✕ control;
  the preview row's LayoutParams gets `bottomMargin = 6.dp()` to separate it from the key rows.
  `isSingleLine`, `ellipsize = START`, `gravity = CENTER_VERTICAL` unchanged. `syncKeyboardPreview`
  text branches and its `textSecondary` (placeholder) / `textPrimary` (typed) colors unchanged.
- **Close ✕ control**: unchanged size (`TOUCH_TARGET.dp()` square = 52dp), `gravity = CENTER`,
  `contentDescription = "Close keyboard"`, `setOnClickListener { closeKeyboard() }`. Background
  swapped from `circleRipple()` to `roundedRipple(scheme.surfaceContainerHigh, 12f)` so it reads as
  a modifier-style cap on the tray; ink stays `scheme.textSecondary`.

## Behavior wiring confirmed unchanged (read back from final code)

- `openKeyboard(seed)` guards and sequence (`allowed()/enforcePolicy()`, `keyboardPanel != null`
  early return, `keyboardLanguage`, `keyboardShift=false`, `keyboardSymbols=false`,
  `typed.setLength(0)`, `typed.append(seed)`, panel assignment, `parent.addView`,
  `blocked?.bringToFront()`, `fab?.visibility = INVISIBLE`, `revealChrome()`, `rebuildKeys()`,
  `syncKeyboardPreview()`) — all present and unmodified.
- Panel `isClickable = true` and `descendantFocusability = FOCUS_BLOCK_DESCENDANTS` — unchanged.
- `rebuildKeys()`: still calls `CarKeyboardLayouts.rows(...)` and `CarKeyboardLayouts.weight(key)`,
  still adds `keyButton(key)` as the row child. Only margin/height *values* flow from the bumped
  companion constants.
- `keyButton()`: `text = keyLabel(key)`, `contentDescription = keyDescription(key)`,
  `isClickable = true`, `isFocusable = false`, `setOnClickListener { onKey(key) }` — unchanged.
  No key is focusable.
- `onKey`, `commitTyped()`, `closeKeyboard()`, `keyLabel`, `keyDescription`,
  `syncKeyboardPreview` text/visibility logic — not touched.
- `CarKeyboardLayout.kt` (layout data) — not touched; confirmed by the passing layout test below.

## Gradle commands run (from workspace root)

Note: the projection source set is flavor-scoped to `personal`/`lab` (not `safe`), so the
projection file is compiled via the `personal` variant.

```
./gradlew :app:compilePersonalDebugKotlin :app:testPersonalDebugUnitTest --tests "dev.autobridge.browser.CarKeyboardLayoutTest"
```

Result: **BUILD SUCCESSFUL** in 3s. `:app:compilePersonalDebugKotlin` compiled the changed file
cleanly; `CarKeyboardLayoutTest` executed and passed (layout data regression guard green).

## Note on visual confirmation

This is a visual change that cannot be screenshotted headlessly; there is no visual-regression
harness for this surface. Final pixel confirmation would require a head unit / Desktop Head Unit,
which is out of scope for the automated verification. The rendering above was confirmed by reading
back the final `openKeyboard()`, `rebuildKeys()`, `keyButton()`, `keyCapFill()`, and `keyCapInk()`
code.
