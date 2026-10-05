# Verification — live per-keystroke mirroring (scope A)

## Commands run (from repo root)

```
./gradlew testLabDebugUnitTest assembleLabDebug
```

Ran the lab flavor per the README because the projection source set (which contains
`ProjectionBrowserActivity.kt`) is wired into the `personal` and `lab` flavors only; the lab flavor
includes the projection/DHU bits.

## Result

PASS. `BUILD SUCCESSFUL in 12s`.

Key tasks observed:
- `:app:compileLabDebugKotlin` — compiled clean (the edited file).
- `:app:testLabDebugUnitTest` — unit tests passed (includes the pure keyboard layout test
  `CarKeyboardLayoutTest`, the only JVM-exercisable unit relevant to the keyboard).
- `:app:assembleLabDebug` — APK packaged.

No test failures, no compile errors.

## Scope of the automated gate

The automated gate is **compile + unit tests**. The `onKey -> sendTextToSearch(autoSubmit = false)`
live-push path drives a WebView via `evaluateJavascript`, which is not exercisable on the JVM, so
end-to-end live typing cannot be asserted by unit tests. Full end-to-end live-typing can only be
verified on the DHU (`scripts/dhu-run.sh`) with a debug build, which cannot run on the JVM.

The live behavior is therefore confirmed by **code inspection** of the path:
- `onKey` sets a local `mutated = true` only in the three buffer-mutating branches
  (`CarKey.Text`, `CarKey.Space`, `CarKey.Backspace`).
- After the existing `syncKeyboardPreview()`, `if (mutated) sendTextToSearch(typed.toString(),
  autoSubmit = false)` pushes the full authoritative buffer into the page's focused element on every
  mutating key, including backspace-to-empty (sends `""`).
- `CarKey.Go -> return commitTyped()` is unchanged, so the Go/submit path (`autoSubmit = true`) is
  untouched. Layer toggles (`Shift`, `Symbols`, `Language`) do not set `mutated`, so they never push.
- `sendTextToSearch` now early-returns on empty input only when `autoSubmit` is true (`if
  (clean.isEmpty() && autoSubmit) return@onUi`), so an empty live update reaches the JS and clears
  the focused field (`el.value = ""` + `input` event). The `navigateFromInput` fallback is guarded
  with `clean.isNotEmpty()` so an empty live clear that lands on no focused field does not trigger a
  spurious navigation.

## Suggested manual DHU check (not automatable here)

Debug build sets `DHU_TEST_MODE=true` per `app/build.gradle.kts`. Via `scripts/dhu-run.sh`: open the
Google homepage on the car surface, tap the address pill to open the app's own keyboard, type a few
characters and confirm each lands in the page's search box in real time, backspace to empty and
confirm the page box clears, then press Go and confirm it submits/searches exactly as before.
