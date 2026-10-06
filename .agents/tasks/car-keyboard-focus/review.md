# Live-mirror car keyboard typing into the focused page field

The projection route's on-screen car keyboard previously only pushed text into the page when the driver pressed Go. This change makes every buffer-mutating key (text append, Space, Backspace) re-send the full typed buffer into the page's focused element in real time via `sendTextToSearch(..., autoSubmit = false)`, so characters land in the box as they type. Backspace-to-empty now clears the page field live, which required loosening `sendTextToSearch`'s empty-input early-return so it only short-circuits on the submit path. The Go/submit path (`commitTyped()` -> `sendTextToSearch(..., autoSubmit = true)`) is untouched, and layer toggles (Shift, Symbols, Language) do not push because they never mutate the buffer. The change is confined to `ProjectionBrowserActivity.kt`.

Watch for: nothing blocking. The one behavioral subtlety worth noting is the deliberate asymmetry in `sendTextToSearch`'s empty-input handling, which is correct and well-commented (confirmed).

**Verdict**: APPROVED

## High-level view

The design keys off a single local `mutated` flag set only in the three buffer-mutating branches of `onKey()`, with the live push emitted once after `syncKeyboardPreview()`. This keeps the mirror logic in one place and makes the "which keys push" decision explicit and auditable rather than scattered across branches (confirmed).

The authoritative source for the page field is always the full `typed` buffer, re-sent in whole on each keystroke, not a per-character delta — so a dropped intermediate update self-heals on the next key (confirmed).

The empty-input contract on `sendTextToSearch` is now split by intent: a submit on an empty buffer stays a no-op (matching `commitTyped()`, which returns early on empty), while a live empty update is allowed through so `el.value = ""` clears the field. The `navigateFromInput` fallback is separately guarded to `clean.isNotEmpty()`, so an empty live clear that misses a focused field cannot trigger a spurious navigation (confirmed).

<details>
<summary>Issues (0)</summary>

No blocking or actionable concerns. All five gate checks pass.

</details>

<details>
<summary>Details</summary>

### Mutating keys live-mirror; layer toggles do not

`onKey()` declares `var mutated = false` and sets it true in exactly the three branches that change the buffer: `CarKey.Text` (append upper/lower), `CarKey.Space` (append `' '`), and `CarKey.Backspace` (trim one char when non-empty). After the `when`, following the existing `syncKeyboardPreview()`, the single line `if (mutated) sendTextToSearch(typed.toString(), autoSubmit = false)` pushes the full buffer. `Shift`, `Symbols`, and `Language` only flip layout state and call `rebuildKeys()`; none set `mutated`, so they never push — none of them alters `typed` (confirmed). This satisfies check (1).

### Go path unchanged

`CarKey.Go -> return commitTyped()` returns before reaching the live-push line, so Go never double-fires a non-submit mirror. `commitTyped()` is unchanged: it trims the buffer, closes the keyboard, returns early on empty, and submits via `sendTextToSearch(value, autoSubmit = true)` (confirmed). This satisfies check (2).

### Empty-buffer clear via split early-return

The core enabler for backspace-to-empty is the change from `if (clean.isEmpty()) return@onUi` to `if (clean.isEmpty() && autoSubmit) return@onUi`. On the live (non-submit) path an empty string now flows through to the `evaluateJavascript` call that sets `el.value = ""` and dispatches an `input` event, clearing the focused field instead of leaving stale text. The submit path retains its empty no-op, matching `commitTyped()`. The downstream `navigateFromInput` fallback is additionally guarded with `&& clean.isNotEmpty()`, so an empty clear that finds no focused element does not fall back to navigation. Backspace sets `mutated = true` even when the buffer was already empty, so a backspace-to-empty keystroke does reach `sendTextToSearch("", autoSubmit = false)` (confirmed). This satisfies check (3).

### Scope and footprint

The commit touches one source file, `ProjectionBrowserActivity.kt`, plus three task docs under `.agents/tasks/car-keyboard-focus/`. No `EditText`, `InputConnection`, or `SearchTemplate` route was modified; the live-mirror reuses the existing WebView `evaluateJavascript` path already used by the phone remote and by Go, so car-keyboard and phone-remote typing stay on one implementation. The only `sendTextToSearch` change is the two-part empty-handling tweak described above, which is the "tiny same-file tweak" the task explicitly allows. Existing comments and design notes are preserved and new comments explain the non-obvious decisions. No new dependencies (confirmed). This satisfies check (4).

### Verification evidence

The recorded evidence (`verification.md` and the commit message) shows `./gradlew testLabDebugUnitTest assembleLabDebug` ran to `BUILD SUCCESSFUL`, with `:app:compileLabDebugKotlin`, `:app:testLabDebugUnitTest` (including `CarKeyboardLayoutTest`), and `:app:assembleLabDebug` all passing. The lab flavor was chosen because the projection source set is wired into the `personal` and `lab` flavors only. The evidence is candid that the live JS-push path drives a WebView via `evaluateJavascript` and is not JVM-testable, so end-to-end live typing is confirmed by code inspection with a suggested manual DHU check, not by unit tests. That boundary is inherent to the WebView path, not a coverage gap introduced by this change. The build + unit gate is satisfied per recorded evidence; I did not re-run the suites (confirmed). This satisfies check (5).

</details>

<details>
<summary>File map</summary>

- `app/src/projection/java/dev/autobridge/projection/ProjectionBrowserActivity.kt` — `onKey()` now flags mutating keys and emits one `sendTextToSearch(..., autoSubmit=false)` live push; `sendTextToSearch` empty-input early-return narrowed to the submit path and `navigateFromInput` fallback guarded to non-empty.
- `.agents/tasks/car-keyboard-focus/{plan,report,verification}.md` — task docs.

Full diff: `git show 62c28f2 -- '*ProjectionBrowserActivity.kt'`

</details>
