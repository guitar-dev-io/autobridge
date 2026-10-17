# Car keyboard latency, round 2 (after 0.4.58-rc9)

## Per-key path, as read (onClick → pixels)

`CarBrowserScreen.onClick` (gated logs only) → `CarWebRenderer.onSurfaceClick` → `runOnMain`
→ `handleKeyboardTap` → `CarSurfaceKeyboard.onTap` (buffer edit) → `requestFrameNow()` → **posted**
`frameRunnable` → `drawFrame`:

- HARDWARE (default): `invalidateChrome()` → next vsync re-records the chrome layer.
- LEGACY_CANVAS: `lockCanvas(null)` + `WebView.draw(canvas)` of the whole page + chrome, every key.

No WebView work per key: the tray holds the text by design and pushes it to the page once, on Go
(`submitText`). That is why the YouTube box still shows its placeholder while typing. No
SharedPreferences writes per character (language toggle only, `apply()`).

## Root causes

1. The keystroke frame was posted, not drawn: it waited behind whatever sat on the main looper
   (Chromium callbacks, the pump's own frame), 0–16 ms typical, more on a busy page.
2. LEGACY: every key re-rendered the whole WebView in software (15–60 ms on YouTube), and the next
   full-rate pump frame could land right before the next key.
3. Small per-key churn: `listOf(...)` cache key on every geometry lookup (3–4 per key), a new
   `Box` per `keyboardArea()`, `getString` for Space/Go every frame, a new `TextPaint` +
   ellipsize for the hint every frame, geometry rebuilt every time one-shot shift flipped back.
4. No pressed-key feedback, so a tap gave no sign until the text changed.
5. Double tap could type 3 characters if the host sent an `onClick` next to the `onScale` (the
   replay always typed 2).

## Changes

- `handleKeyboardTap`: a `Changed` result calls `drawKeyboardNow()` synchronously.
  HARDWARE: `invalidateChrome()` immediately. LEGACY: `lockCanvas(trayRect)` and paint only the
  (opaque) tray; falls back to a full frame if the surface grants a larger dirty rect; then the next
  pump frame is pushed back one frame interval. Close/Commit still draw a whole frame at once.
- Replayed double tap paints once for both keystrokes.
- `CarKeyboardTapFilter`: a double tap types at most two (click+scale → 1+1, scale+echo click → 2+0).
- `CarSurfaceKeyboard`: allocation-free geometry cache with one slot per shift state, cached
  `Box` area, cached labels and ellipsized hint, pressed key lit for 120 ms (`PRESSED_CAP`).
- `drawFrame` split into `paintFrame`/`clipToCard` (no behaviour change).

## Estimated savings per keystroke

- HARDWARE: 0–16 ms typical (one looper hop / up to one frame), more when the page is busy.
- LEGACY: 15–60 ms (no page re-render per key) plus less contention from back-to-back full frames.
- Pressed highlight makes the tap visible on the first frame either way.

## Remaining delay (outside the app)

- `SurfaceCallback` only has `onClick/onScroll/onFling/onScale`; no press/down event exists, so
  there is no earlier input to use. If the host holds `onClick` for its double-tap timeout
  (~300 ms), that hold stays.
- Projection: Android Auto encodes the surface as video and streams it to the head unit
  (roughly 50–150 ms, host/HU dependent).

## Manual DHU checks

1. Open YouTube, tap the search box; the tray opens. Type `เพลง` at normal speed: each key lights
   briefly and appears with no visible lag; the page does not zoom or scroll.
2. Double tap `ง`: exactly `งง`. Repeat 5× and count characters (should be 10).
3. Backspace double tap deletes two; single taps delete one each.
4. Shift one-shot (one capital, then lower), shift twice = caps lock (bar under ⇧), EN/ไทย toggle.
5. Address bar (URL mode, Go) and page field (SEARCH mode, ค้นหา) both commit; ✕ clears; ⌄ and a tap
   above the tray close it and the page reappears in full.
6. Rollback mode: set LEGACY_CANVAS, repeat 1–5; the area above the tray must keep showing the page
   (no black or stale band) while typing.
7. With ViewportDebug on: `kbd frame sinceTapMs` should be ~0–2 ms after `kbd tap`; `kbd doubleTap
   taps=` shows 1 or 2.
