# Verification: Duo media-card gate

Iteration: FIRST (no `review.json` present at start — only `design-review.json`, which the design
already incorporated as findings 1/2/3). Implemented the plan from scratch.

## What was built (maps to plan items 1–7)

1. `common/.../car/DuoSessionState.kt` — new `:common` holder, `@Volatile private var active`,
   `isActive` read accessor, idempotent `sessionStarted()`/`sessionEnded()`. Writer is `:duoscreen`
   only; default `false` ⇒ safe flavor behaves exactly as today (NFR4/AC8). No build.gradle change.
2. `app/.../media/MediaPlaybackService.kt` — added pure `shouldSurfaceSession(duoActive, authorized)
   = !duoActive && authorized`; `onGetSession` reads `DuoSessionState.isActive`, keeps the existing
   `MediaControllerAuthorization.isAllowed(...)` call unchanged, routes through the predicate,
   returns `session.takeIf { surface }`. The one `MEDIA` log line preserves the existing tokens in
   order (`package=`, `uid=`, `allowed=`) and only appends ` duoActive=$duoActive surfaced=$surface`.
3. `app/.../media/MediaAutoStart.kt` — added pure `shouldStartSession(duoActive) = !duoActive`;
   `startSession` returns early (with a `MEDIA` log) when Duo is active, before the controller build.
4. `duoscreen/.../DuoScreenController.kt` — `internal var tearingDown`; `DuoSessionState.sessionStarted()`
   beside BOTH `CarScreenPower.sessionStarted(context)` sites in `start()` (resume path + fresh path);
   `if (tearingDown) DuoSessionState.sessionEnded()` after `CarScreenPower.sessionEnded()` in `stop()`;
   `tearingDown = false` immediately before `stop()` in `restart()`.
   `DuoScreenHost.release()` and `DuoScreenSpikeActivity.stopSession()` each set
   `tearingDown = true` immediately before `stop()`. `detach()`/`onScreenGone()`/`detachSession()`
   untouched, so the keep-alive window stays active.
5. `common/src/test/.../car/DuoSessionStateTest.kt` — default inactive; start/end flip; idempotent;
   `@After` reset. (3 tests)
6. `app/src/test/.../media/MediaSessionDuoGateTest.kt` (3 tests) and
   `MediaAutoStartDuoGateTest.kt` (2 tests) — pure-predicate assertions, no media3 instrumentation.
7. `duoscreen/src/test/.../DuoScreenControllerDuoStateTest.kt` (3 tests) — start/clear, restart
   no-dip ordering, both-teardown-routes clear; `@After` reset.

## Hard constraints (checked by inspection)

- `app/src/main/AndroidManifest.xml` still declares the `android.media.browse.MediaBrowserService`
  intent filter — NOT deleted. No manifest edit in this change (git shows manifest unmodified).
- `app/src/main/res/xml/automotive_app_desc.xml` still has `<uses name="media" />` — NOT deleted.
- Gate is dynamic (runtime `onGetSession` null) — no manifest change (NFR1/AC7).
- Audio kept: the lever withholds only the session from the host; `ExoPlayer`/`CarMediaPlayer` and
  its `AudioAttributes` are untouched, so audio keeps playing during Duo (design §4, AC2).

## Commands run

### Task-specified gated build (plan item 8)

```
cd /Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/duo-media-gate && \
  ./gradlew testPersonalDebugUnitTest assemblePersonalDebug
```

Result: **BUILD SUCCESSFUL in 42s, 88 actionable tasks executed.** Only pre-existing deprecation
warnings (MainActivity, BrowserDefaults, etc.); no errors introduced by this change.

Note: `testPersonalDebugUnitTest` runs the `:app` unit tests only (it is the app-variant test task).
The `:common` and `:duoscreen` library unit tests run under `testDebugUnitTest`, so they were run
explicitly to prove the new library-module tests pass:

```
cd /Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/duo-media-gate && \
  ./gradlew :common:testDebugUnitTest :duoscreen:testDebugUnitTest
```

Result: **BUILD SUCCESSFUL in 3s.**

## New tests — pass/fail (from test-results XML)

| Test class | module/task | tests | failures | errors |
|---|---|---|---|---|
| `dev.autobridge.car.DuoSessionStateTest` | common / testDebugUnitTest | 3 | 0 | 0 |
| `dev.autobridge.media.MediaSessionDuoGateTest` | app / testPersonalDebugUnitTest | 3 | 0 | 0 |
| `dev.autobridge.media.MediaAutoStartDuoGateTest` | app / testPersonalDebugUnitTest | 2 | 0 | 0 |
| `dev.autobridge.duoscreen.DuoScreenControllerDuoStateTest` | duoscreen / testDebugUnitTest | 3 | 0 | 0 |

Total: 11 new tests, all passing. No existing tests regressed (full suites green).

## DHU-only acceptance criteria (not unit-testable, per design §5 / report.md)

Called out, not automated here: AC1 mid-playback-then-Duo (does an already-visible card clear when
Duo starts — the §3 known risk with the lever-(a) fallback), AC1 Duo-then-play, AC2 (audio audible),
AC3–AC5 (normal/browser/auto-start routes unchanged), AC6 (card restored after teardown), AC9
observability (grep `AutoBridgeDuoCtl`/`AutoBridgeDuoHost` start/stop lines bracketing the
`MEDIA ... surfaced=false` line). These depend on the Android Auto host's card behaviour, which is
not observable from a JVM unit test.
