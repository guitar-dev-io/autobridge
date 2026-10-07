# Duo media-card gate — fix report

## Summary

When a Duo screen session is live, AutoBridge no longer surfaces its own
`MediaLibrarySession` to the car host. This stops the AutoBridge media card from
overlaying the Duo screen. When Duo is not active, the media/mirror route is
unchanged. Audio keeps playing throughout Duo — only the session hand-off to the
host is withheld.

Final branch state: `fix/duo-media-card-gate` was rebased onto `main` and `main`
was fast-forwarded to it. The gate commit landed as `21f5288`. Both branches now
point at `01408d1` (gate commit `21f5288` + an upstream v0.4.42 merge pulled in
during finalization + this fix report).

## Suppression lever chosen and why

Lever: **dynamic `onGetSession` gate** — return `null` from
`MediaPlaybackService.onGetSession(...)` while a Duo session is active, instead of
touching the manifest.

Why this lever over the alternatives:

- **No manifest change.** The `MediaBrowserService`/`MediaLibraryService`
  declaration and `<uses name="media"/>` in the Android Auto app descriptor are
  left untouched. A manifest-level removal would be static (all-or-nothing) and
  would disable the media card permanently, including when Duo is not running.
  The requirement is a *conditional* suppression tied to Duo being active, which
  only a runtime decision can satisfy.
- **Narrow blast radius.** Returning `null` from `onGetSession` withholds exactly
  one thing — the session the host would show as a media card. The ExoPlayer /
  `CarMediaPlayer` playback pipeline is not involved, so audio is unaffected.
- **Reversible and self-healing.** The gate is a pure function of the live
  `DuoSessionState.isActive` flag, so the card returns automatically the moment
  Duo tears down; no state to reset, no manifest to restore.

Decision function:

```kotlin
// MediaPlaybackService
internal fun shouldSurfaceSession(duoActive: Boolean, authorized: Boolean): Boolean =
    !duoActive && authorized
```

The authorization term is preserved from the existing controller-authorization
check, so the Duo gate is layered additively on top of today's behavior (the card
is surfaced only when the controller is both authorized **and** Duo is inactive).

A second, complementary lever guards the on-connect path:

```kotlin
// MediaAutoStart
internal fun shouldStartSession(duoActive: Boolean): Boolean = !duoActive
```

`MediaAutoStart.startSession` skips the enumeration bind while Duo is active, so a
session that would otherwise be auto-started on connect is not brought up during
Duo.

## Cross-module "Duo active?" signal mechanism

Signal: a process-wide holder in `:common`, written by `:duoscreen`, read by
`:app`. This mirrors the existing `CarScreenPower` seam already used across the
same modules.

- **Holder:** `common/src/main/java/dev/autobridge/car/DuoSessionState.kt` — an
  `object` with a single `@Volatile private var active: Boolean = false`,
  exposing `isActive` (read), `sessionStarted()` and `sessionEnded()` (write).
  `@Volatile` gives the cross-thread visibility needed because the writer
  (Duo controller thread) and reader (media binder thread) differ.
- **Writer is `:duoscreen` only.** `:app` and `:common` never call the mutators.
  In the safe flavor where `:duoscreen` is absent, the flag stays at its `false`
  default forever, so the media card behaves exactly as before (satisfies the
  NFR4/AC8 "safe flavor unchanged" constraint).
- **Reader is `:app`'s media layer** (`MediaPlaybackService.onGetSession` and
  `MediaAutoStart.startSession`), which reads `DuoSessionState.isActive`.

Lifecycle writes, in `DuoScreenController`:

- `sessionStarted()` is published at **both** `start()` entry sites (the car
  route and the on-phone harness path).
- `sessionEnded()` is cleared in `stop()` **only on a real teardown**, guarded by
  the controller's internal `tearingDown` flag:
  ```kotlin
  if (tearingDown) DuoSessionState.sessionEnded()
  ```
  `restart()`'s internal `stop()` leaves `tearingDown` false, so the flag stays
  true across a live rebuild and the gate does not flicker open mid-session. The
  two true-teardown callers — `DuoScreenHost.release()` and
  `DuoScreenSpikeActivity.stopSession()` — set `tearingDown = true` before calling
  `stop()`, which also closes the harness leak.

## Audio-during-Duo behavior decision

**Audio keeps playing during Duo.** The gate withholds only the *session hand-off*
to the car host (the thing that renders the media card); it does not touch the
playback engine. `ExoPlayer` / `CarMediaPlayer` are left running, so music that
is playing through AutoBridge continues to play while a Duo session owns the car
screen. The only user-visible effect is that the media card is not drawn over the
Duo screen. When Duo ends, the session is surfaced again and the card returns,
with playback uninterrupted the whole time.

## Files changed

Source (`:common`, `:app`, `:duoscreen`):

- `common/src/main/java/dev/autobridge/car/DuoSessionState.kt` — new holder
  (volatile boolean seam; `isActive` / `sessionStarted()` / `sessionEnded()`).
- `app/src/main/java/dev/autobridge/media/MediaPlaybackService.kt` — new
  `shouldSurfaceSession(duoActive, authorized)`; `onGetSession` reads
  `DuoSessionState.isActive`, returns `null` while Duo active; log line extended
  additively with `duoActive`/`surfaced`.
- `app/src/main/java/dev/autobridge/media/MediaAutoStart.kt` — new
  `shouldStartSession(duoActive)`; `startSession` skips the on-connect bind while
  Duo active.
- `duoscreen/src/main/java/dev/autobridge/duoscreen/DuoScreenController.kt` —
  publish `sessionStarted()` at both `start()` sites; clear `sessionEnded()` in
  `stop()` guarded by `tearingDown`.
- `duoscreen/src/main/java/dev/autobridge/duoscreen/DuoScreenHost.kt` — set
  `tearingDown = true` before `stop()` on real teardown (`release()`).
- `duoscreen/src/main/java/dev/autobridge/duoscreen/phone/DuoScreenSpikeActivity.kt`
  — set `tearingDown = true` before `stop()` on real teardown (`stopSession()`).

Tests (11 new, all passing):

- `common/src/test/java/dev/autobridge/car/DuoSessionStateTest.kt` (3)
- `app/src/test/java/dev/autobridge/media/MediaSessionDuoGateTest.kt` (3)
- `app/src/test/java/dev/autobridge/media/MediaAutoStartDuoGateTest.kt` (2)
- `duoscreen/src/test/java/dev/autobridge/duoscreen/DuoScreenControllerDuoStateTest.kt` (3)

Docs:

- `.agents/tasks/duo-media-template/design.md`
- `.agents/tasks/duo-media-template/verification.md`

## Build/test results after rebase

Rebased `fix/duo-media-card-gate` onto `main` (merge-base was `a02bf6d` /
v0.4.23; main had advanced to `22ca2a8` / past v0.4.41). The rebase applied
cleanly with **no conflicts** — the only gate-touched file that main had also
modified was `DuoScreenController.kt`, and git merged the additive gate lines into
a non-overlapping region automatically. Gate code was re-verified present in all
source files after the rebase.

Re-ran on the rebased state, from the worktree:

```
cd /Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/duo-media-gate
./gradlew testPersonalDebugUnitTest assemblePersonalDebug
```

Result: **BUILD SUCCESSFUL in 28s**, 88 actionable tasks. Only pre-existing
deprecation warnings (`startActivityForResult`, `onActivityResult`,
`REQUESTED_WITH_HEADER_ALLOW_LIST`, `setActionStrip`, and an existing
"Condition is always false" in `ControlScreen.kt`) — none introduced by this
change. All unit tests passed.

After the build, `main` was fast-forwarded to the rebased branch:

```
git -C /Users/anuwat.t/Documents/ChatGPT/AutoBridge merge --ff-only fix/duo-media-card-gate
# Updating 22ca2a8..21f5288  Fast-forward
```

`main` and `fix/duo-media-card-gate` both resolve to `01408d1` (the gate commit
`21f5288`, an upstream v0.4.42 merge `93eb309` that was pulled in during
finalization, and this fix report `01408d1`). A final
`./gradlew testPersonalDebugUnitTest assemblePersonalDebug` on this merged tip
was re-run: **BUILD SUCCESSFUL**, all tests pass. The worktree at
`.worktrees/duo-media-gate` is left in place for the orchestrator to remove.

(Note: the fast-forward was executed from the primary worktree because `main` is
checked out there; a `checkout main` inside the Duo worktree would be refused by
git since the same branch cannot be checked out in two worktrees. The result is
identical to the `checkout <primary>` + `merge --ff-only` sequence — `main` now
points at the rebased tip. Unrelated uncommitted edits in the primary worktree
(`.vscode/*`, `scripts/dhu-run.sh`) were untouched because the fix branch does not
touch those files.)

## DHU verification steps the user must run

Run these on the Desktop Head Unit (DHU) with a device/emulator running the
`personalDebug` build:

1. **Duo active — card must be suppressed.**
   - Start a Duo screen session in AutoBridge (bring the Duo screen up on the car
     display).
   - Play music through AutoBridge.
   - **Confirm: NO AutoBridge media card overlays the Duo screen.** The Duo screen
     stays fully visible, and the music keeps playing (audio is not interrupted).

2. **Duo ended — card must return.**
   - End the Duo screen session.
   - Play music through AutoBridge.
   - **Confirm: the AutoBridge media card RETURNS** and is shown by the host as
     usual.

Optional log check: in `StructuredLog` under tag `MEDIA`, confirm the
`MediaSession controller ... duoActive=true surfaced=false` line appears while Duo
is active, and `duoActive=false surfaced=true` once Duo ends.
