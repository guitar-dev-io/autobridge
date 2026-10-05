# Car keyboard focus bug — root-cause investigation

Bug (Thai, paraphrased): on the CAR screen, typing on the on-screen keyboard into the Google
homepage search box does nothing. The typed Thai characters (`ออออออ`) show only in the IME's
composing/candidate strip at the top-left and never land in the Google input.

This is a READ-ONLY investigation. No code was changed.

---

## 1. Summary answer (the root cause)

There are **two different car text-entry mechanisms**, and the symptom describes the one that has
**no editor at all** behind the Google box.

- **Projection route** (`src/projection/.../ProjectionBrowserActivity.kt`) draws AutoBridge's **own**
  on-screen keyboard. It never attaches a system IME to the page. Keys accumulate into an in-memory
  `StringBuilder` and are injected into the page only once, on **Go**, via `evaluateJavascript`.
- **Template route** (`src/main/.../car/CarBrowserScreen` + `CarBrowserSearchScreen` using
  `SearchTemplate`) relies on the Android Auto **host** to draw the keyboard/IME against the host's
  own search field — never against the WebView.

**In no code path does the system IME ever bind to the WebView's focused `<input>`.** The WebView on
the car surface is only ever drawn/mirrored and driven by synthetic touches and JS injection; it is
never given window focus with a real `InputConnection`. So when the user has a *system* IME up (the
candidate strip in the report is a system IME, e.g. on a DHU/host that pops its own keyboard) and
taps the Google box, the IME has **no editable target that owns an `InputConnection`**. Composition
text therefore accumulates in the IME's own composing region and is never committed anywhere — the
page's `<input>` never receives it.

This is consistent with the brief's finding that `InputConnection` / `onCreateInputConnection` /
`commitText` / `setComposingText` return **zero matches** across `app/src/main` — the app never
provides an editor for an IME to commit into on the car surface.

Answer to question 1 (what receives the keystrokes): **(d) nothing with a real editor.** On the
projection route the only "editor" is a `StringBuilder` fed by the app's own key buttons (not an IME
at all), and on the template/host route the IME is bound to the host's search surface, not to the
WebView. When a *system* IME is showing over the self-drawn WebView (the report's scenario), there
is no focused editable `View` with an `InputConnection`, so the IME strands composition in its
candidate strip.

---

## 2. Full keystroke-to-WebView data flow

### A. Projection route — AutoBridge's own keyboard (`src/projection`)

1. User taps the address pill. `addressPill()` sets `setOnClickListener { noteInteraction(); openKeyboard() }`
   — `ProjectionBrowserActivity.kt:664`. Note the explicit comment at lines ~658-664: "The app's own
   keyboard, not the host's search box … No soft keyboard attaches — the host owns the IME on this SDK."
2. `openKeyboard()` (`ProjectionBrowserActivity.kt:1170`) builds a `LinearLayout` key panel over the
   page. Critically it sets `descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS` and the
   comment at ~lines 1201-1208 states: "no key is focusable … the commit path types into
   `document.activeElement`." It resets the buffer with `typed.setLength(0)`.
3. The key grid is built from `CarKeyboardLayouts.rows(...)` (`CarKeyboardLayout.kt`) via
   `rebuildKeys()` (`ProjectionBrowserActivity.kt:1272`) and `keyButton()` (line 1296).
4. Each key tap calls `onKey(key)` (`ProjectionBrowserActivity.kt:1347`):
   - `CarKey.Text` → `typed.append(...)` (line 1350)
   - `CarKey.Space`/`Backspace`/`Shift`/`Symbols`/`Language` → edit buffer / toggle layers
   - `CarKey.Go` → `commitTyped()` (line 1376)
   - After every non-Go key: `syncKeyboardPreview()` updates only a local `TextView` preview
     (line 1379, 1381-1392). **Nothing is sent to the page here.**
5. `commitTyped()` (`ProjectionBrowserActivity.kt:1400`): trims the buffer, `closeKeyboard()`, then
   `sendTextToSearch(value, autoSubmit = true)` — only now does any text reach the page, and only
   the whole string at once.
6. `sendTextToSearch(...)` (`ProjectionBrowserActivity.kt:1475`) runs JS against the WebView:
   finds `document.activeElement`; if editable, sets `.value`/`.textContent`, dispatches an `input`
   event, and (autoSubmit) submits the form or dispatches Enter. If `activeElement` is **not**
   editable it returns `NO_FOCUS` and the callback falls back to `navigateFromInput(clean)`
   (treat as a search/address load).

So on this route, the system IME is never involved. If a host nonetheless shows a system IME over
this surface, it has no bound editor and composition strands exactly as reported.

### B. Template route — host `SearchTemplate` (`src/main`)

1. `CarBrowserSearchScreen` (`CarBrowserSearchScreen.kt`) builds a `SearchTemplate` with
   `setShowKeyboardByDefault(true)`. The host draws the keyboard and calls `onSearchTextChanged` /
   `onSearchSubmitted`. The result is returned to `CarBrowserScreen` via `setResult(...)`.
2. This typed text targets the **host's** search field, not the WebView. On submit it is resolved to
   a URL/search and loaded. There is no per-character path into a focused page `<input>` here either.
   (`CarBrowserSettingsScreen.kt` around line 508 uses the same `setShowKeyboardByDefault(true)`
   pattern for a settings input — same host-IME mechanism.)

### C. Phone/remote injection path (shared tail)

- Mobile Remote text → `AutoBridgeSessionManager.sendKeyboard()` (`AutoBridgeSessionManager.kt:~410`)
  → for the local browser engine → `TextInjectionController.send(..., Target.BROWSER_SEARCH, submit)`
  (`TextInjectionController.kt:48-58`) → `CarScreenController.activeBrowser.sendTextToSearch(text, autoSubmit)`.
- `CarScreenController.activeBrowser` is implemented by whichever surface is live:
  `ProjectionBrowserActivity` registers itself in `onCreate` (`CarScreenController.activeBrowser = this`),
  and `CarWebRenderer.submitText(...)` (`CarWebRenderer.kt:~1264`) is the template-route equivalent
  JS injector. Both end in the same JS `document.activeElement` write.

---

## 3. Why text stays in the IME composing area

The IME shows a composing/candidate strip because a system IME is up, but it has **nowhere to
commit**:

- The car surface never gives any `View` an `InputConnection` for the page. Confirmed by the brief's
  zero-match grep for `onCreateInputConnection`/`commitText`/`setComposingText` and by the explicit
  design comments:
  - `ProjectionBrowserActivity.kt:~1201-1208` — keys are not focusable; commit goes to
    `document.activeElement` via JS, so there is no editor view by design.
  - `ProjectionBrowserActivity.kt:~658-664` and the address pill (`isFocusable = true` "for
    D-pad/rotary traversal only … No soft keyboard attaches — the host owns the IME on this SDK").
  - `CarKeyboardLayout.kt` class doc: "Android attaches no IME to the projected car display. A
    `<input>` inside the page can be focused and still raise nothing."
- Even on the projection keyboard's *own* path, interim text is NOT pushed to the page per keystroke.
  `onKey` only updates the local preview; the page is written **only** in `commitTyped()` on Go
  (`ProjectionBrowserActivity.kt:1376, 1400-1405`). So any live composition the user sees before Go
  is not in the page.

Both candidate causes from the brief are confirmed and compounding: (a) no focused editable view owns
an `InputConnection` on the car surface, and (b) the JS-injection path fires only on commit/Go, never
per keystroke.

---

## 4. How the WORKING path differs (phone omnibox contrast)

The phone browser (`src/main/.../browser/BrowserActivity.kt`) works because it uses a **real
`EditText`** omnibox that owns a genuine `InputConnection`:

- `address: EditText` field (`BrowserActivity.kt:84`), built in `buildAddressField()` (line 406).
- On focus it explicitly shows the system IME:
  `InputMethodManager.showSoftInput(this, SHOW_IMPLICIT)` (`BrowserActivity.kt:439-440`), and
  `focusAddressBar()` does `address.requestFocus()` + `showSoftInput(address, ...)` (lines 963-968).
- `setOnEditorActionListener` handles IME `IME_ACTION_GO`/Enter to navigate (lines 465-468).

Because a real focused `EditText` owns the `InputConnection`, the IME commits normally and Thai
composition resolves into the field. The car surfaces have no equivalent focused editor bound to the
IME, which is precisely the difference that produces the bug.

---

## 5. Recommended fix

**Primary recommendation — give the car WebView surface a real focused `EditText` that owns an
`InputConnection`, and mirror it into the page on every change (projection route).**

Rationale: this is the only mechanism that lets a *system* IME (the one the user is actually seeing,
per the report) commit text, including multi-stage/composing scripts like Thai. It also makes the
in-page Google box behave like the phone omnibox that already works.

Concrete changes in `src/projection/.../ProjectionBrowserActivity.kt`:

1. In `openKeyboard()` (line 1170), replace the non-focusable `keyboardPreview` `TextView` with a
   real `EditText` that:
   - is focusable in touch mode, calls `requestFocus()`, and triggers
     `InputMethodManager.showSoftInput(...)` (mirror the pattern at `BrowserActivity.kt:439-440,
     963-968);
   - keeps `descendantFocusability` permissive enough for the EditText to hold focus (the current
     `FOCUS_BLOCK_DESCENDANTS` at ~line 1206 must be relaxed for this view).
2. Add a `TextWatcher`/`doAfterTextChanged` on that EditText that calls `sendTextToSearch(current,
   autoSubmit = false)` on each change, so the focused page `<input>` is updated live rather than
   only on Go. Keep the app's own key grid as an optional fallback for hosts that still show no IME.
3. On Go / `IME_ACTION_GO`, call the existing `commitTyped()` / `sendTextToSearch(value,
   autoSubmit = true)` to submit.

Note: `sendTextToSearch` currently *replaces* `el.value` wholesale (`ProjectionBrowserActivity.kt:
1489-1495` and the mirror in `CarWebRenderer.submitText`). For per-keystroke mirroring this is fine
because the EditText holds the authoritative buffer and re-sends the full string each change; keep
the full-value replace rather than trying to diff.

**Secondary (template route): prefer the host `SearchTemplate`/`SearchController` for in-page typing
where available.** On the template route, route a tap on a focused page `<input>` to open the host
search surface (`CarBrowserSearchScreen` already exists) and feed its result into
`CarWebRenderer.submitText(...)`. This leans on the host-provided IME instead of trying to bind one
to the self-drawn WebView.

---

## 6. Alternative approaches and tradeoffs

- **Keep the app's own keyboard but push composing text per keystroke** (change `onKey` at
  `ProjectionBrowserActivity.kt:1347` so every `CarKey.Text`/`Space`/`Backspace` also calls
  `sendTextToSearch(typed.toString(), autoSubmit=false)`).
  - Pro: smallest change; no `InputConnection` work; keeps working on IME-less hosts (DHU).
  - Con: does NOT fix the reported symptom when a *system* IME is the thing showing the candidate
    strip — that IME still has no editor to commit into. It only helps users of AutoBridge's own
    key grid. Best as a complement, not the fix.

- **Implement a WebView `InputConnection` bridge** (override `onCreateInputConnection` on a WebView
  subclass / offscreen editor and forward `commitText`/`setComposingText` into the page via JS).
  - Pro: makes a system IME commit correctly, including composition.
  - Con: significantly more complex and brittle across WebView/IME versions; the project has
    deliberately avoided `InputConnection` so far (zero matches). The focused-`EditText` approach
    achieves the same user outcome with far less risk.

- **Host `SearchTemplate` only, drop the self-drawn keyboard.**
  - Pro: simplest, uses the platform IME.
  - Con: the codebase explicitly notes the DHU and some hosts do not back the search box with a
    keyboard (`CarKeyboardLayout.kt` doc; `ProjectionBrowserActivity.kt:~658-664`), which is exactly
    why the own-keyboard exists. Removing it regresses those hosts.

---

## 7. Build / verification notes (for the eventual fix, not this step)

- Android Gradle project; flavors `safe`/`personal`/`lab` × `debug`/`release`
  (`app/build.gradle.kts:126-145`). Projection sources live under `src/projection` wired via a
  source set (`app/build.gradle.kts:253-255`).
- JVM unit tests exist for the pure keyboard layout: `app/src/test/.../CarKeyboardLayoutTest.kt`.
  A fix should add JVM-testable seams where possible and run `./gradlew test` for the affected flavor.
- End-to-end keyboard→WebView behavior needs the DHU (`scripts/dhu-run.sh`) with a debug build
  (`DHU_TEST_MODE=true`, `app/build.gradle.kts` debug buildType) since the IME/WebView focus wiring
  cannot be exercised on the JVM.

### Key evidence index (file:line)
- `CarKeyboardLayout.kt` — class doc: no IME on projected display; own keyboard rationale.
- `ProjectionBrowserActivity.kt:664` — address pill opens app's own keyboard, not host IME.
- `ProjectionBrowserActivity.kt:1170-1254` — `openKeyboard()`; keys non-focusable; `FOCUS_BLOCK_DESCENDANTS`.
- `ProjectionBrowserActivity.kt:1347-1379` — `onKey()`; buffer-only, preview-only until Go.
- `ProjectionBrowserActivity.kt:1400-1405` — `commitTyped()`; sends to page only on Go.
- `ProjectionBrowserActivity.kt:1475-1500` — `sendTextToSearch()`; JS write to `document.activeElement`.
- `CarWebRenderer.kt:~1264-1300` — `submitText()`; template-route JS injector (same mechanism).
- `TextInjectionController.kt:48-58` — delegates to `activeBrowser.sendTextToSearch`.
- `AutoBridgeSessionManager.kt:~410` — `sendKeyboard()` routing.
- `CarBrowserSearchScreen.kt` — `SearchTemplate` host-IME path.
- `BrowserActivity.kt:84, 406, 439-440, 465-468, 963-968` — working phone omnibox with real
  `EditText` + `InputConnection` + `showSoftInput`.
