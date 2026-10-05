# Implementation Plan — live per-keystroke mirroring (scope A)

Goal: on the projection route, mirror the current typed buffer into the page's focused
`<input>` on every key that MUTATES the buffer (Text, Space, Backspace), by calling
`sendTextToSearch(typed.toString(), autoSubmit = false)` — including when the buffer becomes
empty, so backspacing to empty clears the page field live. The `CarKey.Go` branch keeps calling
`commitTyped()` (autoSubmit = true) with no behavior change.

Single file: `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt`.

## Verified code facts (read before editing — line numbers confirmed on current file)

- `onKey(key: CarKey)` starts at **line 1346**. Its `when (key)` branches:
  - `CarKey.Text` — appends (`typed.append(...)`), line ~1349-1356. **MUTATES.**
  - `CarKey.Space` — `typed.append(' ')`, line ~1358. **MUTATES.**
  - `CarKey.Backspace` — `if (typed.isNotEmpty()) typed.setLength(typed.length - 1)`, line ~1359. **MUTATES** (no-op only when already empty).
  - `CarKey.Shift` — line ~1360-1363. Toggles `keyboardShift` + `rebuildKeys()`. Does NOT touch `typed`. **No page push.**
  - `CarKey.Symbols` — line ~1365-1369. Toggles layer. Does NOT touch `typed`. **No page push.**
  - `CarKey.Language` — line ~1370-1376. Toggles language layer. Does NOT touch `typed`. **No page push.**
  - `CarKey.Go` — line ~1377: `is CarKey.Go -> return commitTyped()`. Early `return`, so it never
    reaches the trailing `syncKeyboardPreview()`. **Must stay exactly as is.**
- The `when` is immediately followed by a single trailing `syncKeyboardPreview()` at **line ~1379**
  (the last statement of `onKey`), which runs for every non-Go key.
- `syncKeyboardPreview()` at **line 1381** — updates only the local `keyboardPreview` TextView.
  Keep intact.
- `commitTyped()` at **line 1399** — trims buffer, `closeKeyboard()`, returns if empty, else
  `sendTextToSearch(value, autoSubmit = true)` (line ~1406). Keep intact.
- `sendTextToSearch(text, autoSubmit)` at **line 1475**. Line ~1477-1478:
  `val clean = text.trim()` then `if (clean.isEmpty()) return@onUi`. This EARLY-RETURNS on empty,
  so a live empty update would be a no-op and the page field would NOT clear on backspace-to-empty.
  The JS sets `el.value`/`el.textContent` and dispatches `input`; submit JS runs only when
  `autoSubmit` is true.

## Design decisions (chosen approach + rationale)

1. **Where to push:** add the live push inside `onKey`, right where `syncKeyboardPreview()` already
   runs for non-Go keys, gated to the three mutating branches. Rationale: `onKey` already
   distinguishes Go (early return) from everything else, so the trailing region runs exactly for the
   keys we care about plus the three harmless layer toggles. Gating by a local `mutated` flag set in
   the mutating branches is the smallest correct change and avoids pushing on Shift/Symbols/Language
   (which never change `typed`). Chosen over calling `sendTextToSearch` inside each of the three
   branches separately, which would duplicate the call three times.
2. **Full-string resend, no diff:** keep `sendTextToSearch` replacing the whole value; `typed`
   remains the authoritative buffer and we re-send the full string each change (per task constraint).
3. **Empty-buffer clearing:** relax ONLY the live (non-submit) empty path in `sendTextToSearch` so an
   empty string still clears the focused element, while the Go/submit path is untouched. Rationale:
   the current early-return makes backspace-to-empty leave stale text in the page; the task
   explicitly requires the empty live update to clear it.
4. **No debounce:** push on every mutating key. No existing debounce utility is used in this file;
   the task says prefer the simplest correct change. The JS is idempotent (full-value replace).

## Steps

- [ ] 1. Relax the empty-string handling in `sendTextToSearch` so an empty LIVE (non-submit) update
      still clears the focused element, without changing the Go/submit path.
      Currently line ~1478 is `if (clean.isEmpty()) return@onUi`, which drops empty updates entirely.
      Change so that: when `autoSubmit` is false, an empty `clean` proceeds and the JS writes the
      empty value (`el.value = ""` / `el.textContent = ""` + `input` event) and does NOT submit;
      when `autoSubmit` is true, keep the existing early-return-on-empty behavior (Go on an empty
      buffer must stay a no-op, matching `commitTyped()`'s own `if (value.isEmpty()) return`).
      Concretely, guard the early return with the submit flag, e.g.
      `if (clean.isEmpty() && autoSubmit) return@onUi`. `JSONObject.quote("")` yields `""`, and the
      existing JS already handles empty assignment correctly (`el.value = ""`), so no JS-template
      change is needed. The `NO_FOCUS` fallback that calls `navigateFromInput(clean)` must not fire a
      navigation for an empty live clear — confirm during edit that an empty live update which hits a
      non-editable element does not trigger a spurious navigation; if `navigateFromInput` would act on
      empty, keep it from navigating on an empty non-submit clear (it should be a no-op there).
      Files: app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` compiles clean (projection source set is
      wired into the `personal` and `lab` flavors only — see app/build.gradle.kts lines 252-257 — so
      `safe` cannot compile this file).

- [ ] 2. In `onKey`, after updating the buffer and the local preview, push the current buffer into
      the focused page element live for mutating keys only.
      Add a local `var mutated = false` at the top of `onKey`; set `mutated = true` in the
      `CarKey.Text`, `CarKey.Space`, and `CarKey.Backspace` branches (leave `Shift`, `Symbols`,
      `Language` as-is; leave `CarKey.Go -> return commitTyped()` exactly as-is). After the existing
      trailing `syncKeyboardPreview()` call (line ~1379), add:
      `if (mutated) sendTextToSearch(typed.toString(), autoSubmit = false)`.
      This runs after the preview update, pushes the full current buffer live on every text append,
      space, and backspace (including backspace-to-empty, which sends `""`), and never fires on the
      layer-toggle keys or on Go. Preserve all existing comments in the function.
      Files: app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt
      Verify: `./gradlew :app:compilePersonalDebugKotlin` compiles clean.

- [ ] 3. Run the full build + existing JVM unit tests for the affected flavor to confirm nothing
      regressed. The only JVM-testable unit relevant here is the pure keyboard layout
      (`app/src/test/.../CarKeyboardLayoutTest.kt`); the onKey→WebView live-push path is not
      JVM-exercisable and is validated manually on the DHU.
      Files: (none — verification only)
      Verify: `./gradlew :app:assemblePersonalDebug` and `./gradlew :app:testPersonalDebugUnitTest`
      both succeed. Then, manual DHU check (`scripts/dhu-run.sh`, debug build has
      `DHU_TEST_MODE=true` per app/build.gradle.kts): open the Google homepage on the car surface,
      tap the address pill to open the app's own keyboard, type a few characters and confirm each one
      appears in the page's search box in real time, backspace to empty and confirm the page box
      clears, then press Go and confirm it submits/searches exactly as before.

## Gaps / assumptions

- Assumed the three layer-toggle keys (`Shift`, `Symbols`, `Language`) never mutate `typed`
  (confirmed by reading their branches) so no live push is needed there; if a future refactor makes
  one of them edit the buffer, add it to the `mutated` set.
- Assumed `navigateFromInput("")` on the `NO_FOCUS` fallback for an empty live clear should be a
  no-op (we do not want backspace-to-empty to trigger a navigation when nothing is focused). Step 1
  calls out confirming this at edit time; if `navigateFromInput` acts on empty input, it must be
  guarded so an empty non-submit clear never navigates.
- No design document was provided; the decisions above are mine, grounded in the cited lines, and
  stay within scope A (no EditText/InputConnection, no template-route change, no Go-behavior change).
