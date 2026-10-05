# Implementation Plan — Browser UI polish (finish Material-icon migration + 6 tasks)

## Context discovered during exploration

- **The build is currently RED.** A prior agent migrated the menu/FAB data models from a
  `glyph: String` field to `icon: BrowserIcon`, but `CarWebRenderer.kt` still reads the removed
  `.glyph` field. `./gradlew :app:compilePersonalDebugKotlin` fails with exactly six
  `Unresolved reference 'glyph'` errors in `CarWebRenderer.kt` at lines **2785, 3002, 3006, 3086,
  3121, 3178**. Fixing these (Task 3) is the critical path; nothing else can be verified until the
  module compiles.
- Build/test commands (run from repo root `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`):
  - Compile only (fast red/green signal): `./gradlew :app:compilePersonalDebugKotlin`
  - Full assemble (required by task): `./gradlew :app:assemblePersonalDebug`
  - Unit tests (required by task): `./gradlew :app:testPersonalDebugUnitTest`
  - `timeout` is NOT available on this macOS zsh; do not wrap gradle in it.
- The icon system: `BrowserIcon` enum (`BrowserIcons.kt`) maps each role to `R.drawable.ic_browser_*`
  (36 entries; 37 `ic_browser_*.xml` files exist — the extra is `ic_browser_lock` vs `ic_car_home`
  reuse; all enum resIds resolve). `BrowserActivity.buildIconDrawable(icon, color)` is the reference
  pattern: `ContextCompat.getDrawable(this, icon.resId)!!.mutate().apply { setTint(color);
  setBounds(0,0,size,size) }`. Phone Views use `ImageView.setImageResource(icon.resId) +
  setColorFilter(tint)`.
- Already migrated by the prior agent (verify, do not redo): `BrowserMenuSheet.kt` (all ImageView,
  incl. `fullscreenToggle` already next to `desktopToggle`), `BrowserDrawer.kt` model
  (`DrawerItem.icon`, `fullscreenToggle()` already placed next to `desktopToggle()`),
  `BrowserSettingsSheet.displayScaleRow()` (complete SeekBar slider).
- Fullscreen state is in-memory only, in two places: `ChromeVisibility.fullscreen` (car, default
  `false`) and `BrowserActivity.fullscreen` (phone, default `false`, set from
  `savedInstanceState` only). There is NO persisted fullscreen preference today — Task 1 must add one.
- Dialogs in `BrowserActivity.kt`: User-Agent already uses `setSingleChoiceItems` (radio). All other
  dialogs are text-entry (home page, custom UA, find) or confirmations (reset/delete/clear/sign-in)
  or navigate-on-tap lists (bookmarks, history, downloads) — none are multi-select or
  exclusive-choice-list candidates. See Task 5.

## AutoUiSizes tokens (for Task 6 proportionality)
`ICON_SMALL_DP=18`, `ICON_MEDIUM_DP=22`, `ICON_LARGE_DP=28`, `TOUCH_TARGET_DP=46`,
`SHEET_ICON_DP=26`, `SHEET_CLOSE_BUTTON_DP=48`, `SHEET_LIST_ICON_DP=44`, `HORIZONTAL_PADDING_DP=12`.
Renderer accessors: `sizes.iconSmall/iconMedium/iconLarge/touchTarget/horizontalPadding/contentGap`,
`sizes.dp(AutoUiSizes.SHEET_ICON_DP)`.

---

# Implementation Plan

- [ ] 1. Add a renderer-side cached icon-drawable helper in CarWebRenderer.kt.
      Add a private helper that resolves a `BrowserIcon` to a tinted, centered-square `Drawable`
      drawn onto the canvas, mirroring `BrowserActivity.buildIconDrawable` but cached by
      (resId, tintColor) so it is not re-inflated every frame. Signature suggestion:
      `private fun drawIcon(canvas: Canvas, icon: BrowserIcon, cx: Float, cy: Float, sizePx: Float, color: Int, alpha: Int = 255)`
      — compute a square `setBounds(cx-s/2, cy-s/2, cx+s/2, cy+s/2)`, `mutate().setTint(color)`,
      `setAlpha(alpha)`, `draw(canvas)`. Use `appContext` (already a field) with `ContextCompat.getDrawable`.
      Keep a `HashMap<Int, Drawable>` cache keyed by resId; call `mutate()` once per cached entry and
      re-apply tint/alpha/bounds per draw. Add imports `androidx.core.content.ContextCompat` and
      `android.graphics.drawable.Drawable` if missing.
      Files: app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — helper compiles (still fails on the six
      `.glyph` sites until step 2; that is expected).

- [ ] 2. Replace the six `.glyph` ICON draws in CarWebRenderer.kt with `drawIcon(... item.icon ...)`.
      These are the compile-breaking sites. Each draws an icon from a model that now carries
      `icon: BrowserIcon` instead of `glyph: String`. Replace the `canvas.drawText(... .glyph ...)`
      call (and the preceding `glyphPaint.textSize=` sizing that fed it) with a `drawIcon` call at
      the same center and an equivalent square size, keeping the existing color choice:
      - **L2785 `drawFab`**: `fabAction.glyph` → `drawIcon(canvas, fabAction.icon, box.centerX,
        box.centerY, radius*0.95f, BrowserTheme.dark.onFabContainer, (255*opacity).toInt())`.
      - **L3002 drawSheetHeader ROUND link**: `link.item.glyph` → `drawIcon(canvas, link.item.icon,
        box.centerX, box.centerY, (sizes.iconLarge.coerceAtMost(box.height*0.6f)),
        BrowserTheme.dark.textPrimary)`.
      - **L3006 drawSheetHeader PILL link** (`"${glyph} ${label}"`): draw the icon with `drawIcon`
        at the left of the pill, then draw ONLY the label text (no glyph char) with `drawCentered`
        shifted right by the icon width; keep it a genuine text label. Size icon at
        `(sizes.iconSmall*0.9f).coerceAtMost(box.height*0.5f)`, tint `textPrimary`.
      - **L3086 drawSheetTile**: `row.item.glyph` → `drawIcon(canvas, row.item.icon, tile.centerX,
        blockTop + glyphSize/2f, glyphSize, iconEnabled/iconDisabled)` (keep enabled/disabled color).
        Note the baseline math changes to a center: use `blockTop + glyphSize/2f` as cy.
      - **L3121 drawSheetPrimary icon**: `row.item.glyph` → `drawIcon(canvas, row.item.icon, iconCx,
        box.centerY, sizes.dp(AutoUiSizes.SHEET_ICON_DP)*1.1f (coerced), BrowserTheme.dark.onPrimary)`.
      - **L3178 drawSheetToggle**: `row.item.glyph` → `drawIcon(canvas, row.item.icon, glyphX,
        box.centerY, sizes.iconMedium.coerceAtMost(box.height*0.5f), BrowserTheme.dark.textSecondary)`.
      Keep every text/title/label/subtitle `drawText` untouched. Keep `drawSheetPrimary`'s chevron
      (`\u203A`) for now — it is migrated in step 4.
      Files: app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — compiles clean, zero `Unresolved
      reference 'glyph'` errors.

- [ ] 3. Migrate the CarWebRenderer toolbar + address-bar ICON glyphs to vectors (drawToolbar).
      In `drawToolbar` (~L2800-2860) replace the literal icon chars drawn via `glyphPaint`:
      - Toolbar slots `when(slot.zone)`: `"‹"`→`BrowserIcon.BACK`, `"›"`→`BrowserIcon.FORWARD`,
        `"↻"`→`BrowserIcon.RELOAD`, loading `"×"`→`BrowserIcon.CLOSE`,
        `"⛶"`→`BrowserIcon.FULLSCREEN_ENTER`, `"⤡"`→`BrowserIcon.FULLSCREEN_EXIT`,
        `"☰"`→`BrowserIcon.MENU`. Resolve the BrowserIcon in the `when`, then
        `drawIcon(canvas, icon, slot.bounds.centerX, slot.bounds.centerY, slot.iconSize,
        enabled?iconEnabled:iconDisabled, opacity)`.
      - Address pill secure badge (~L2846): `"🔒"`→`BrowserIcon.LOCK` (tint `secureBadge`), `"!"`
        (insecure)→`BrowserIcon.WARNING` (tint `insecureBadge`), drawn at `badgeX, pill.centerY`,
        size ~`sizes.iconSmall`. Keep the page title/host `drawText` as real text.
      Files: app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — compiles clean.

- [ ] 4. Migrate the remaining CarWebRenderer sheet/drawer/tab/address ICON glyphs to vectors.
      Replace these literal icon draws (all currently `canvas.drawText` on `glyphPaint`), leaving
      genuine text (titles, URLs, "Tabs x/y", host) as `drawText`:
      - drawSheetHeader close (~L3018): `"\u2715"`→`BrowserIcon.CLOSE`, tint `textSecondary`,
        center `close.centerX/centerY`.
      - drawSheetAddress badge (~L3035): `"\uD83D\uDD12"`(lock)→`BrowserIcon.LOCK`/`"!"`→`WARNING`.
      - drawSheetAddress clear (~L3050): `"\u2715"`→`BrowserIcon.CLOSE`, tint `textSecondary`.
      - drawSheetAddress go (~L3063): `"\u2315"`→`BrowserIcon.SEARCH`, tint `onPrimary`.
      - drawSheetPrimary chevron (~L3126): `"\u203A"`→`BrowserIcon.CHEVRON_RIGHT`, tint `onPrimary`.
        (Keep the numeric count badge `row.item.value` as text — it is a counter, not an icon.)
      - drawTabSwitcher header close (~L3229): `"\u2715"`→`BrowserIcon.CLOSE`, tint `iconEnabled`.
      - drawTabSwitcher new-tab card (~L3258): `"+"`→`BrowserIcon.ADD`, tint `textPrimary`.
      - drawTabSwitcher per-tab close (~L3291): `"×"`→`BrowserIcon.CLOSE`, tint `textSecondary`.
      Do NOT convert: `drawErrorOverlay` message/hint (real text), tab title/host (real text),
      `"Tabs  n/MAX"` counter (real text).
      Files: app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — compiles clean; `./gradlew
      :app:testPersonalDebugUnitTest` — existing BrowserViewportTest/renderer-model tests pass
      (these are layout/geometry tests and are not affected by draw calls, so they must stay green).

- [ ] 5. Migrate the phone BrowserActivity toolbar chrome buttons from text glyphs to vector icons.
      The toolbar `control(label, desc, action)` helper (~L324) builds a `Button` whose `text` is a
      literal glyph. Convert these chrome buttons to use the `BrowserIcon` vector set so the phone
      toolbar matches the car and the sheets. Change `control` to accept a `BrowserIcon` and set a
      centered drawable instead of text (either switch the lateinit fields to `ImageButton`/
      `ImageView`, or keep `Button` and set a compound/background drawable via `buildIconDrawable`
      centered in the `TOUCH_TARGET_DP` box). Map: back `"‹"`→`BACK`, forward `"›"`→`FORWARD`,
      reload `"↻"`→`RELOAD`, fullscreen `"⛶"`→`FULLSCREEN_ENTER`, menu `"☰"`→`MENU`, home
      `"⏏"`→`APP_HOME`. Update `updateNavigation()` (~L769): stop/reload swaps
      `RELOAD`↔`CLOSE` (was `"↻"`/`"×"`); keep the enabled/alpha logic. Update `setFullscreen`
      (~L823): swap the fullscreen button icon to `FULLSCREEN_EXIT` when enabled, `FULLSCREEN_ENTER`
      when not (in addition to the existing contentDescription swap). Tint with
      `BrowserTheme.iconEnabled`; size glyph at `ICON_MEDIUM_DP` within the `TOUCH_TARGET_DP` box
      (Task 6). The `menuButton`/`handle` TextViews: if they draw an icon glyph, migrate likewise;
      if `handle` is a plain grab-pill, leave it.
      Files: app/src/main/java/dev/autobridge/browser/BrowserActivity.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — compiles clean.

- [ ] 6. Migrate the remaining phone-View text-glyph icons in sheet shells and start page.
      - BrowserSheetShell.closeButton (~L63) draws `"✕"` in a `TextView`; replace with an
        `ImageView`/compound drawable using `BrowserIcon.CLOSE` tinted `textSecondary`, keeping the
        48dp circular `SHEET_CLOSE_BUTTON_DP` target and contentDescription. This makes every sheet
        header close consistent with the migrated menu/settings sheets.
      - BrowserSettingsSheet header back `"‹"` (~L150) and navRow chevron `"›"` (~L~392): these are
        in-sheet affordances — migrate back arrow to `BrowserIcon.BACK` and the navRow chevron to
        `BrowserIcon.CHEVRON_RIGHT` via ImageView, tinted to match (`textPrimary`/`textSecondary`),
        so the sheets use the one icon language. (The segmented-control `"✓"` tick is genuine
        selection text, not chrome — leave it; see Task 5 rationale on checkmarks.)
      - BrowserStartPage shortcut `glyph` (the `StartPageShortcut.glyph`, e.g. "G"/"▶"/"f"): these
        are brand monograms / genuine content on branded tiles, NOT chrome icons. LEAVE them as text
        per the task ("Keep emoji that are genuine content, not chrome"). Document this decision in
        the row item. Only migrate a start-page glyph to a vector if it is actually a chrome control
        (there are none here).
      Files: app/src/main/java/dev/autobridge/browser/BrowserSheetShell.kt,
      app/src/main/java/dev/autobridge/browser/BrowserSettingsSheet.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` — compiles clean.

- [ ] 7. Task 1 — persist and default the fullscreen preference to ON for a fresh install.
      Add a persisted fullscreen flag to `BrowserControlsStore` (same prefs file
      `autobridge_browser`): `KEY_START_FULLSCREEN = "controls_start_fullscreen"`,
      `fun startFullscreen(context) = prefs.getBoolean(KEY, true)` (default **true** = fresh install
      starts fullscreen) and `fun setStartFullscreen(context, Boolean)`. Then:
      - **Phone** `BrowserActivity.onCreate` (~L308): where it currently does
        `setFullscreen(savedInstanceState?.getBoolean("fullscreen") == true)`, change the cold-start
        branch so that when `savedInstanceState == null` it uses
        `BrowserControlsStore.startFullscreen(this)` as the initial value (so a fresh launch is
        fullscreen) while a config-change restore still honors the saved instance value. In
        `setFullscreen(enabled)` (~L823), persist the user's choice:
        `BrowserControlsStore.setStartFullscreen(this, enabled)` so toggling off is remembered.
      - **Car** `CarWebRenderer`: in `start()` (~L771, right where it calls
        `visibility.onInteraction(...)`/`applyControlSettings()`), seed fullscreen on first start
        only (guard with an existing "first start" signal — e.g. only when the page has not yet been
        loaded / `pendingStartUrl`/initial generation) using
        `BrowserControlsStore.startFullscreen(appContext)`; call `setFullscreen(true)` if stored on.
        In the public `setFullscreen(enabled)` (~L681), persist via
        `BrowserControlsStore.setStartFullscreen(appContext, enabled)`. Guard against forcing
        fullscreen on every surface re-attach/resize (only seed once per session, not on every
        `start()`), matching the existing "only load on first start" comment.
      Rationale (record in code): fullscreen default is a stored preference, not a per-launch force,
      so the user's toggle-off persists; default true satisfies "ปรับ default ของ browser ให้
      fullscreen".
      Files: app/src/main/java/dev/autobridge/browser/BrowserControlsStore.kt,
      app/src/main/java/dev/autobridge/browser/BrowserActivity.kt,
      app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — add/confirm no test regresses;
      `./gradlew :app:assemblePersonalDebug` succeeds. Manual-reasoning check (document in code):
      fresh prefs → `startFullscreen` returns true → launch hides chrome; after `setFullscreen(false)`
      the stored value is false → next launch not fullscreen.

- [ ] 8. Task 2 — confirm the Fullscreen menu entry sits next to Desktop on BOTH surfaces.
      This is largely done; verify and tighten. `BrowserMenuSheet.render()` already adds
      `desktopToggle(current)` then `fullscreenToggle(current)` (phone). `BrowserDrawer.kt`
      `BrowserDrawerModel` already builds `listOf(desktopToggle(state), fullscreenToggle(state))` as
      the primary-sheet toggles (car). Confirm: (a) `fullscreenToggle` label reflects state — it uses
      `R.string.drawer_fullscreen` ("Fullscreen") with `on = state.fullscreen`; the switch state
      already reflects current fullscreen, which satisfies "label reflecting current state". If a
      distinct enter/exit label is wanted, it is optional — the switch already shows on/off. (b) The
      `BrowserMenuState.fullscreen` field is populated for both surfaces (phone passes it from
      `BrowserActivity.fullscreen`; car from `visibility.fullscreen` — confirm the state builders set
      it). (c) `DrawerAction.TOGGLE_FULLSCREEN` is wired to `toggleFullscreen()` in both
      `CarWebRenderer.handleDrawerAction` (~L1848) and `BrowserActivity` menu dispatch. (d) The FAB
      `FloatingButtonAction.FULLSCREEN` binding may remain (task permits leaving it). No toolbar
      redundancy to remove on the car (fullscreen slot already moved to the drawer per
      `BrowserChromeLayoutTest`); on the phone the toolbar fullscreen button stays (migrated in
      step 5) — acceptable per task.
      Files: (verify only) app/src/main/java/dev/autobridge/browser/BrowserMenuSheet.kt,
      BrowserDrawer.kt, CarWebRenderer.kt, BrowserActivity.kt
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — BrowserDrawerModelTest / layout tests
      still pass (they assert fullscreen is a drawer toggle, not a toolbar slot).

- [ ] 9. Task 4 — verify the display-scale slider end-to-end; remove any non-slider picker.
      `BrowserSettingsSheet.displayScaleRow()` is already a complete `SeekBar`: reads
      `BrowserDisplayScaleStore.percent`, `max = (MAX-MIN)/STEP`, live label on
      `onProgressChanged`, writes + applies on `onStopTrackingTouch` only. Confirm there is NO other
      display-scale UI: grep `BrowserActivity.kt` and the car surface for any zoom/scale chip,
      stepper, or dialog bound to `BrowserDisplayScaleStore` (the car has `DrawerAction.ZOOM_IN/
      ZOOM_OUT` which are WebView zoom, a different concern — leave them). If a discrete/dialog
      display-scale picker exists anywhere, replace it with a pointer to the settings slider. If
      none exists (expected), state "already complete, slider is the only display-scale control" in
      the code review notes.
      Files: (verify only; edit only if a stray picker is found)
      app/src/main/java/dev/autobridge/browser/BrowserSettingsSheet.kt, BrowserActivity.kt
      Verify: `./gradlew :app:assemblePersonalDebug` succeeds.

- [ ] 10. Task 5 — audit dialogs; convert to radio/checkbox only where it fits.
      Reviewed `BrowserActivity.kt` AlertDialogs:
      - **User-Agent** (~L1423): already `setSingleChoiceItems` (radio). LEAVE.
      - **Home page** (~L1333), **Custom UA** (~L1453), **Find-in-page** (~L1531): text-entry
        (`setView` with EditText). NOT choice lists. LEAVE.
      - **Reset permissions / Delete site data / Clear browsing data / Sign-in** (~L1362/1377/1554/
        1637): confirmation dialogs (message + positive/negative). LEAVE.
      - **Bookmarks / History** (~L1591/1611): navigate-on-tap `setAdapter` lists (each row is an
        action, not an exclusive selection). LEAVE.
      - **Downloads** (~L1578): informational `setItems` list (rows do nothing; neutral button
        clears). NOT a choice list. LEAVE.
      Conclusion: the only exclusive-choice list is User-Agent and it is already a radio list; there
      are no multi-independent-toggle dialogs that should become checkboxes (those live in the
      Settings sheet as `Switch` rows already). **No dialog changes required.** Record this audit
      result (which dialogs were reviewed, the classification, and why each stays) in the code
      review notes / a short comment. The sheet's segmented controls already use a "✓" selected
      marker which is the in-house equivalent of a radio for single-choice — leave as-is.
      Files: (audit only — no edits expected)
      app/src/main/java/dev/autobridge/browser/BrowserActivity.kt
      Verify: n/a (no code change); covered by the final full build.

- [ ] 11. Task 6 — proportionality pass across all migrated icons.
      After steps 2-6, confirm every migrated icon is a centered square sized from an AutoUiSizes
      token and that same-role icons read at one size across surfaces:
      - Car: toolbar slots use `slot.iconSize`; sheet tiles `SHEET_ICON_DP`; sheet toggle/primary
        `iconMedium`/`SHEET_ICON_DP`; drawer round link `iconLarge`; address badges `iconSmall`.
        Ensure each `drawIcon` center matches the old glyph center (the old code offset the text
        baseline by `*0.34f`/`*0.36f`; a vector is centered on cy, so pass the box center, not the
        baseline). Audit for any size that was derived from `glyphPaint.textSize` and keep the same
        dp so nothing shrinks or grows.
      - Phone: toolbar buttons draw at `ICON_MEDIUM_DP` inside a `TOUCH_TARGET_DP` box; sheet icons
        at `SHEET_ICON_DP`; close buttons centered in `SHEET_CLOSE_BUTTON_DP`. Confirm
        `AutoUiSizesTest.iconVisualSizeIsSmallerThanItsTouchTarget` style invariants are respected
        (icon < touch target).
      Files: (review/tune) CarWebRenderer.kt, BrowserActivity.kt, BrowserSheetShell.kt,
      BrowserSettingsSheet.kt
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — `AutoUiSizesTest` and layout tests pass.

- [ ] 12. Final full verification.
      Run the two task-mandated commands from the repo root and confirm both succeed with the
      migration complete and no `.glyph` references remaining (`grep -rn "\.glyph" CarWebRenderer.kt`
      returns nothing for icon draws). Confirm the modified `BrowserViewportTest.kt` is green.
      Files: none (verification).
      Verify: `./gradlew :app:assemblePersonalDebug` — BUILD SUCCESSFUL;
      `./gradlew :app:testPersonalDebugUnitTest` — all unit tests pass.

## Notes / assumptions
- Scope is browser-UI only. Do NOT touch `src/projection`, `src/safe`, or the Play flavor behavior
  except a shared `ic_browser_*` drawable or `BrowserIcon` enum entry if one proves missing.
- No missing drawables were found: every BrowserIcon used by the migration
  (BACK, FORWARD, RELOAD, CLOSE, FULLSCREEN_ENTER/EXIT, MENU, APP_HOME, LOCK, WARNING, SEARCH, ADD,
  CHEVRON_RIGHT, DESKTOP_MODE, CAR, etc.) already resolves to an existing `ic_browser_*.xml`. If a
  needed icon turns out missing during implementation, add a 24dp/24x24-viewport white-fill vector
  following the existing `ic_browser_*.xml` format and register it in `BrowserIcon`.
- Do NOT add new tests unless needed to keep an existing test compiling. `BrowserViewportTest.kt`
  was modified by the prior agent and must stay green.
- The six tasks are tightly coupled through shared files (CarWebRenderer.kt, BrowserActivity.kt,
  BrowserIcon enum, AutoUiSizes). Order is dependency-driven: the renderer must compile (steps 1-4)
  before anything else can be verified; the phone/sheet migrations (5-6) and the five feature items
  (7-11) can then proceed; step 12 is the final gate.
