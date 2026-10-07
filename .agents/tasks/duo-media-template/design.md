# Design: Gate AutoBridge's own MediaLibrarySession so its media card does not cover the Duo screen

## Overview

While a Duo screen session is live on the car display, AutoBridge's own
`MediaLibrarySession` (hosted by `app/.../media/MediaPlaybackService`) must stop being the media
source the Android Auto host surfaces as the media card, so the Duo `NavigationTemplate` stays the
visible foreground. When no Duo session is live, the media card must behave exactly as today.

The design has two moving parts and reuses an established seam for the hard one:

1. A **cross-module "Duo active?" signal** published from `:duoscreen` and read from the `:app`
   media layer. This follows the existing `dev.autobridge.power.CarScreenPower` pattern — a
   process-level holder in `:common` with a `@Volatile` field, written on the Duo controller's
   thread and read on the media service's threads — while being deliberately simpler (a directly
   written boolean, no installed policy). No new `:app ↔ :duoscreen` dependency is introduced.
2. A **suppression lever** inside `MediaPlaybackService`: when the signal says Duo is active,
   `onGetSession(...)` returns `null`, so the host is never handed AutoBridge's
   `MediaLibrarySession` and therefore never surfaces its card. `MediaAutoStart` is also gated so
   it does not bind the service purely to be enumerated during Duo. The underlying `ExoPlayer`
   keeps playing, so audio continues while only the card is withheld.

The technology stack is locked by what the modules already use and does not change: Kotlin,
`androidx.media3:*:1.11.1` (`MediaLibraryService`/`MediaLibrarySession`/`MediaController`),
`androidx.car.app:app:1.7.0` + `app-projected:1.7.0`, the `:common` holder-object convention, and
JUnit4 unit tests with `isReturnDefaultValues = true` in `common/src/test`, `duoscreen/src/test`,
and `app/src/test`. No new library and no manifest change are part of this design (NFR1).

## 1. Dependency direction / cross-module signal

### Decision: a new `:common` holder `dev.autobridge.car.DuoSessionState`, in the manner of `CarScreenPower`

The requirement (FR6, Assumption 2) is to publish "Duo active?" from `:duoscreen` and read it from
`:app` through an existing shared seam, not to invent new coupling. `:app` depends on `:duoscreen`
(personal/lab flavors only) and never the reverse; both modules depend on `:common`. The project
already solves exactly this `:duoscreen → :app` direction problem for screen power with
`dev.autobridge.power.CarScreenPower`: a `:common` object that `:duoscreen`'s
`DuoScreenController` writes to (`CarScreenPower.sessionStarted(...)` / `sessionEnded()`) and that
`:app` installs a policy into. I follow that pattern's data flow.

I considered three options and reject two:

- **Reuse `CarScreenPower` itself** by hanging a "Duo active" flag off its existing hooks.
  Rejected: `CarScreenPower` is a *policy installer* owned by `:app`
  (`CarSessionScreenPower.install()` from `AutoBridgeApplication`), scoped to screen-power
  (WakeLock, auto-dim, panel-off). Overloading it would conflate two concerns, and its policy can
  be absent (the whole safe-flavor no-op story), which is the wrong semantics for a flag the media
  layer must always be able to read.
- **A new interface + installed policy** mirroring `CarScreenPowerPolicy` exactly. Rejected as
  over-built: the screen-power seam needs an installed policy because the *behaviour*
  (WakeLock/Shizuku backend) lives in `:app` and cannot be seen by `:duoscreen`. Here the media
  layer only needs to *read a boolean*; there is no `:app`-side behaviour to inject. A plain holder
  with a read accessor is the right size.
- **A new `:common` holder exposing a boolean (chosen).** Minimal, matches the data flow, and keeps
  `:duoscreen` as the sole writer and `:app` media as the sole reader.

### Exact type / API

New file: `common/src/main/java/dev/autobridge/car/DuoSessionState.kt`

```kotlin
package dev.autobridge.car

/**
 * Whether a Duo screen session is live in this process. Written by :duoscreen's controller at
 * session start and cleared only at true teardown; read by :app's media layer to decide whether to
 * surface its own media card.
 *
 * Writer is :duoscreen ONLY. :app and :common must never call sessionStarted()/sessionEnded() — in
 * the safe flavor (:duoscreen absent) the flag then stays permanently at its false default, so the
 * media card behaves exactly as today (NFR4/AC8).
 */
object DuoSessionState {
    @Volatile
    private var active: Boolean = false

    /** True while a Duo session owns the car display (including the keep-alive window). */
    val isActive: Boolean get() = active

    /** Called from DuoScreenController.start(): a session is now live. Idempotent. */
    fun sessionStarted() { active = true }

    /** Called from DuoScreenHost.release(): the session has fully ended. Idempotent. */
    fun sessionEnded() { active = false }
}
```

Package `dev.autobridge.car` is chosen because the signal is about the car session's lifecycle;
`dev.autobridge.power` is `CarScreenPower`'s and is power-specific. `:common` already hosts
`dev.autobridge.power`, `dev.autobridge.logging`, etc., so adding `dev.autobridge.car.*` is
consistent. No change to `:common`'s `build.gradle.kts` is needed (the holder is plain Kotlin).

### Thread-safety approach

The write happens on `DuoScreenController`/`DuoScreenHost`'s calling thread (the car host's main
thread, where `start()` and `release()` run). The reads happen on three threads:

- `MediaPlaybackService.onGetSession(...)` — invoked by media3 on the service's main thread.
- `MediaAutoStart.startSession(...)` — invoked from `BluetoothReceiver.onReceive`, i.e. the
  **broadcast receiver's thread**, not the main looper.
- (unit tests read it directly on the test thread.)

Because reads and writes can be on different threads, the field is a single `@Volatile Boolean`.
The precise guarantee a `@Volatile Boolean` gives is all this needs (NFR2): a write is published
with happens-before visibility to any later read on another thread; a boolean cannot tear; and the
only writes are idempotent plain assignments (`active = true` / `active = false`) with no
read-modify-write, so there is no compound action that would require an atomic or a lock. This is
*not* the same shape as `CarScreenPower.policy` (a volatile *nullable reference* with an installer
and a safe-flavor no-op); `DuoSessionState` is a directly-written volatile *boolean* with no
installer. The thread-safety conclusion is justified by the volatile-boolean guarantee above, not
by equivalence to `CarScreenPower`.

**Writer invariant (safe-flavor correctness).** Only `:duoscreen` may ever write the flag. `:app`
and `:common` must never call `sessionStarted()`/`sessionEnded()`. In the `safe` flavor
`:duoscreen` is absent, so nothing writes it and `isActive` is permanently `false` — the media card
behaves exactly as today (NFR4/AC8). A future `:app` caller would silently break NFR4, so this
invariant is stated on the holder's doc comment and here.

Because `start()` can run more than once across a session's life (the resume path and the
fresh-start path both call it, and `restart()` routes through it), `sessionStarted()` is idempotent
(plain assignment to `true`). `sessionEnded()` is likewise idempotent.

## 2. Suppression lever

### Decision: lever (c) — `MediaPlaybackService.onGetSession()` returns `null` while Duo is active, plus gating `MediaAutoStart` (lever (b)) for the on-connect enumeration

`MediaPlaybackService.onGetSession(controllerInfo)` is the media3 `MediaLibraryService` hook the
host calls to obtain the session for a connecting controller. It already returns
`session.takeIf { allowed }` — i.e. media3 fully supports returning `null` to deny a controller the
session. Returning `null` for the host controller means the host gets no `MediaLibrarySession` from
AutoBridge, so it has no AutoBridge media source to turn into a card. This is the media3-supported
mechanism that actually controls whether the host sees AutoBridge as a source, so it is lever (c)
as described in the task.

Why this over the alternatives:

- **Lever (a) — don't activate / release the session while Duo is active.** Rejected as the primary
  lever. The session is created once in `onCreate()` and torn down in `onDestroy()`; it is not
  "activated" per playback in a way the gate can cheaply toggle, and releasing/recreating the
  `MediaLibrarySession` mid-process to hide a card is heavy and racy against the ExoPlayer it wraps.
  It also risks silencing audio, which the audio-during-Duo decision forbids by default. (It is
  held in reserve only as the finding-1 fallback below, if a DHU check shows an already-visible card
  will not clear.)
- **Lever (b) alone — gate `MediaAutoStart`.** Necessary but not sufficient. `MediaAutoStart` only
  controls the *early, on-connect* enumeration (off by default). Even with it gated, the host would
  still obtain the session via `onGetSession` the moment AutoBridge plays something, and the card
  would appear. So (b) is applied *in addition to* (c), scoped to its own concern (FR3): while Duo
  is active, `MediaAutoStart.startSession(...)` must not bind the service for the sole purpose of
  enumeration.
- **Lever (c) — `onGetSession` returns null (chosen primary).** It is the single point every host
  controller must pass through to get the session, it is already a nullable-returning hook, and it
  leaves the `ExoPlayer`/`CarMediaPlayer` untouched so audio keeps flowing (part 4). It reliably
  removes AutoBridge as the surfaced source for every *new* connection during Duo and, because the
  gate is re-evaluated on every `onGetSession` call, cleanly restores it when Duo ends — with no
  object to leak, because nothing is created or destroyed by the gate.

### Exact changes

**`MediaPlaybackService`** — add a pure gate predicate and route `onGetSession` through it. The
single source of truth for the decision is the predicate; `onGetSession` computes both inputs,
emits one `MEDIA` log that records the suppression (AC9), and returns through `takeIf`:

```kotlin
/** Pure gate decision: surface the session only when Duo is not active AND the controller is authorized. */
internal fun shouldSurfaceSession(duoActive: Boolean, authorized: Boolean): Boolean =
    !duoActive && authorized

override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
    val duoActive = DuoSessionState.isActive
    val allowed = MediaControllerAuthorization.isAllowed(
        packageName = controllerInfo.packageName,
        uid = controllerInfo.uid,
        ownPackageName = packageName,
        ownUid = applicationInfo.uid,
        systemUid = Process.SYSTEM_UID
    )
    val surface = shouldSurfaceSession(duoActive, allowed)
    StructuredLog.i(
        "MEDIA",
        "MediaSession controller package=${controllerInfo.packageName} uid=${controllerInfo.uid} " +
            "allowed=$allowed duoActive=$duoActive surfaced=$surface"
    )
    return session.takeIf { surface }
}
```

This keeps exactly one log line (not two divergent shapes), preserves the existing authorization
computation and its value in the log for the normal route's baseline parity (AC3/AC4), records the
Duo suppression for AC9 (`duoActive=true surfaced=false`), and matches the unit test's predicate
one-for-one. The player, the composite source, the web poll, and the browse tree are all left
exactly as they are — only the host's access to the session is withheld.

**`MediaAutoStart`** — extract the gate as a pure predicate and apply it at the single entry so the
on-connect bind does not happen during Duo (FR3):

```kotlin
/** Pure gate decision: start the session only when Duo is not active. */
internal fun shouldStartSession(duoActive: Boolean): Boolean = !duoActive

fun startSession(context: Context) {
    if (!shouldStartSession(DuoSessionState.isActive)) {
        StructuredLog.i("MEDIA", "auto-start: Duo session active; not bringing the session up")
        return
    }
    // ... existing controller-build-and-release logic unchanged ...
}
```

`startSession` is called from `BluetoothReceiver.onReceive`, so this read of `DuoSessionState.isActive`
happens on the **broadcast receiver's thread**; the `@Volatile` read covers it (see §1
thread-safety). Gating `startSession` rather than the receiver keeps both callers (the receiver and
any future direct caller) covered and keeps the check adjacent to the behaviour it guards.

**`DuoScreenController.start()`** — publish the "active" signal next to the existing
`CarScreenPower.sessionStarted(context)` calls. There are exactly two `sessionStarted` sites and
**both live inside `start()`**: the resume early-return path (`if (resume(...)) { CarScreenPower.sessionStarted(context); return true }`)
and the fresh-start path (after the panes are opened). `resume()` itself does **not** call
`sessionStarted` — the caller `start()` does — so the implementer edits `start()` in two places and
leaves `resume()` untouched. Add `DuoSessionState.sessionStarted()` beside each
`CarScreenPower.sessionStarted(context)`.

**`DuoScreenHost.release()`** — clear the signal. This is the single true-teardown entry point:
both the explicit "end it" action and the 120 s keep-alive `expire` runnable route through
`release()`, which calls `controller.stop()`. Add `DuoSessionState.sessionEnded()` in `release()`,
alongside (or immediately after) `active.stop()`.

**Why the clear goes in `DuoScreenHost.release()` and NOT in `DuoScreenController.stop()`.** `stop()`
is *not* only teardown: the private `restart()` (reached from `applyStoredSettings()` →
`restart()`, i.e. a live phone-side settings change) calls `stop()` then `start()` *while a Duo
session is genuinely live*. If the clear sat in `stop()`, a live rebuild would flip the flag
`true → false → true` on the host main thread, and a media `onGetSession` on another thread landing
in that window could momentarily see `false` and surface the card during a live Duo session. Placing
the clear in `release()` instead means:

- `restart()` path: `stop()` (no clear) → `start()` (sets `true`). The flag never dips; it stays
  `true` across the whole live rebuild. No race.
- real teardown: `release()` → `controller.stop()` (no clear) → `DuoSessionState.sessionEnded()`
  (clears). The flag goes `false` exactly once, when the session is actually over.

This also makes the keep-alive window count as active (Assumption 3, FR5): `onScreenGone()` →
`detach()` touches neither `CarScreenPower` nor `DuoSessionState`, so the signal stays `active`
through the 120 s window, and only `release()` (explicit end or `expire`) clears it — exactly
mirroring how `CarScreenPower` keeps the screen awake through `detach()`. Note this deliberately
diverges from where `CarScreenPower.sessionEnded()` sits (`controller.stop()`): the Duo-active flag
must not be cleared on the `restart()` path, whereas the screen-power restore on a `restart()` is
immediately re-established by the following `start()`'s `sessionStarted` and is not read
cross-thread the same way. The teardown entry points still net the same end state (flag `false` once
the session is gone).

No manifest or `automotive_app_desc.xml` change is made (NFR1, AC7).

## 3. Lifecycle

- **Duo starts while media is already active (mid-playback-then-Duo).** `DuoScreenController.start()`
  sets `DuoSessionState` active. Any *new* host connection and `MediaAutoStart` are gated
  immediately, and any *new* playback goes through a gated `onGetSession`. **Whether the host
  re-queries `onGetSession` for an already-connected controller when Duo entry changes the active
  source is UNVERIFIED.** `report.md` is explicit that host media-card behaviour "is not
  controllable or documented as suppressible by a car app" and "must be confirmed on the device" —
  whether/when the host re-queries a live controller is exactly what the investigation refused to
  assert. Therefore:
  - The gate is **guaranteed only for the common ordering**: Duo is entered first, *then* the user
    plays audio. There the new playback's `onGetSession` is genuinely gated and no card appears
    (satisfying FR1's "read at the moment AutoBridge would otherwise present/activate its own media
    source" and AC1 for that ordering).
  - The **mid-playback-then-Duo case is a DHU observation**, tracked as a known risk in the test
    plan (§5), not an assertion: does a card already on screen at the instant Duo starts clear?
  - **Fallback if the DHU shows the card persists:** either accept it as a documented limitation
    (the common ordering is covered, and in practice Duo is usually entered before audio is
    chosen), or revisit lever (a) — a *targeted* release/re-add of the `MediaLibrarySession` driven
    by the Duo-active transition — which is heavier and risks a brief audio gap, and so is held in
    reserve rather than designed in now. The implementer must not assume the gate alone closes the
    mid-playback case until the DHU confirms it.
- **Duo ends (teardown).** `DuoScreenHost.release()` sets the flag `false`. The next `onGetSession`
  (next playback or next host query) returns the real session again, restoring the card with no app
  restart (FR5, AC6). Nothing was created or destroyed by the gate, so there is nothing to leak: the
  holder is a single boolean, `MediaPlaybackService` kept its one session alive throughout, and
  `MediaAutoStart` simply resumes binding on the next car connect.
- **Keep-alive window.** `onScreenGone()` → `detach()` does not touch `DuoSessionState`, so the
  signal stays `active` through the 120 s window (Assumption 3). Only `release()` clears it.
- **Live settings rebuild.** `applyStoredSettings()` → `restart()` → `stop()`/`start()` keeps the
  flag `true` throughout (clear is not in `stop()`); the following `start()` re-asserts `true`. No
  transient dip, no card flash during a live Duo session.
- **Flavor safety.** In the `safe` flavor `:duoscreen` is absent, so nothing ever calls
  `sessionStarted()`; `DuoSessionState.isActive` is permanently `false` and both media call sites
  behave exactly as today (NFR4, AC8). The holder lives in `:common`, which the `safe` flavor
  compiles, so the `:app` references resolve in every flavor.

## 4. Audio-during-Duo behaviour

**Decision: keep audio audible, suppress only the card** — the requirements default, and it is
achievable here with no tradeoff. The chosen lever withholds the *session* from the host
(`onGetSession` → null); it does not touch the `ExoPlayer`/`CarMediaPlayer` that produces sound.
Audio in media3 is produced by the player, not by the host holding a session token. With no session
handed over, the host shows no card, but `ExoPlayer` continues to render audio through its
configured `AudioAttributes` (`USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`, `handleAudioFocus=true`), which
are set in `onCreate()` and unaffected by the gate. So the keep-audio-without-card tradeoff the
requirements warned might be forced by the platform does **not** arise: card and audio are already
decoupled because the player is independent of the session hand-off. This satisfies AC1 (no card)
and AC2 (audio still audible) together.

One nuance worth stating: steering-wheel transport controls and the card's on-screen controls route
through the host's `MediaController`, which during Duo has no AutoBridge session to bind to, so
those controls are unavailable while Duo is active. That is the intended consequence of hiding the
card (there is no card to carry controls) and does not affect audibility. Control returns with the
card when Duo ends.

## 5. Test plan

All three test source sets use JUnit4 and `isReturnDefaultValues = true`, so pure-logic and
holder-level assertions run on the JVM. The gate decomposes into unit-testable seams; the
cross-process card behaviour (AC1 mid-playback, AC3–AC6) remains an on-DHU integration check, noted
explicitly.

### `common` — `DuoSessionState` (new `common/src/test/.../car/DuoSessionStateTest.kt`)

`:common` already has a `src/test` (e.g. `logging/StructuredLogSinkTest`), so this adds a sibling
plain-JUnit4 test (the holder is pure Kotlin, no Android framework needed). Asserts:

- Default `isActive` is `false` (NFR4, AC8 default).
- `sessionStarted()` flips `isActive` to `true`; `sessionEnded()` flips it back to `false` (AC9).
- Repeated `sessionStarted()` / `sessionEnded()` are idempotent (the resume + fresh paths both call
  `start`, and `restart()` routes through `start` again).
- Reset the flag in an `@After` so test order cannot leak the process-global singleton state.

### `app` — the two media call sites

`app/src/test` has no media tests yet; this adds focused ones, following `CarScreenPowerTest`'s
style of asserting a pure decision rather than instrumenting media3.

- **`MediaAutoStartDuoGateTest`** (new). `startSession` is hard to unit test directly because it
  builds a real `MediaController`, so the gate decision is extracted into the package-visible pure
  predicate `shouldStartSession(duoActive) = !duoActive`. The test asserts
  `shouldStartSession(true) == false` and `shouldStartSession(false) == true`, covering FR3 and AC5
  (gated during Duo; unchanged otherwise) without constructing a controller.
- **`MediaSessionDuoGateTest`** (new). Asserts the extracted `shouldSurfaceSession(duoActive,
  authorized) = !duoActive && authorized` used by `onGetSession`:
  - Duo active ⇒ `false` regardless of `authorized` (FR2, AC1 Duo-then-play ordering).
  - Duo inactive + authorized ⇒ `true` (FR4, AC3/AC4 at the gate level).
  - Duo inactive + unauthorized ⇒ `false` (existing authorization behaviour preserved).
  This isolates the gate from media3's service machinery while asserting the exact `null`-vs-session
  decision and the single-log shape chosen in §2.

### `duoscreen` — the publish/clear points

- **`DuoScreenControllerDuoStateTest`** (new, in
  `duoscreen/src/test/.../DuoScreenControllerDuoStateTest.kt`). Driving a full
  `DuoScreenController.start()` requires Shizuku/VirtualDisplay stubs the unit environment cannot
  provide (which is why `DuoScreenController` has no existing lifecycle unit test), so this asserts
  the invariant the gate depends on by exercising `DuoSessionState` from the `:duoscreen` side:
  `sessionStarted()` makes `isActive` `true`, `sessionEnded()` makes it `false`, mirroring the
  start-sets / release-clears ordering. It encodes Assumption 3 as an executable statement — that
  only the teardown clear (what `DuoScreenHost.release()` calls) returns the flag to `false`, and an
  intermediate `start()` re-call leaves it `true` — so a regression that moved the clear back into
  `stop()` (reintroducing the `restart()` dip of finding 4) would show up as a failing ordering
  assertion. Reset in `@After`.

### Integration (on-DHU, not unit) — called out, not automated

The following depend on the Android Auto host's card behaviour, which `report.md` established is not
observable from a JVM unit test, so they are verified on the DHU, not asserted in unit tests:

- AC1 **mid-playback-then-Duo**: does an already-visible card clear when Duo starts? This is the
  finding-1 known risk; if it does not clear, apply the §3 fallback.
- AC1 Duo-then-play, AC2 (audio audible), AC6 (card restored after teardown), AC3–AC5 (normal and
  browser routes unchanged).

**AC9 observability — exact tags/lines to grep on the DHU trail.** The media side emits the new
`MEDIA` line from `onGetSession` (`... duoActive=true surfaced=false` on suppression). The Duo-side
transition is **not** on the `MEDIA` tag: `DuoScreenController` logs `"Session started"` /
`"Session stopped"` under tag `AutoBridgeDuoCtl`, and `DuoScreenHost` logs session create/release
under `AutoBridgeDuoHost`. So AC9 is read as the `AutoBridgeDuoCtl`/`AutoBridgeDuoHost` start/stop
lines bracketing the `MEDIA` `surfaced=false` line — grep those three tags together, not `MEDIA`
alone.

## Requirements coverage

- FR1/FR2 — `onGetSession` returns null while `DuoSessionState.isActive`, evaluated per host query;
  guaranteed for the Duo-then-play ordering (mid-playback case is the §3 DHU risk).
- FR3 — `MediaAutoStart.startSession` gated on the same flag via `shouldStartSession`.
- FR4/AC3/AC4 — gate is a no-op when `isActive` is false; `takeIf { allowed }` path and its log
  preserved.
- FR5/AC6 — flag cleared in `DuoScreenHost.release()`; next `onGetSession` restores the session, no
  restart.
- FR6/NFR2 — `:common` `DuoSessionState` with `@Volatile Boolean`, written by `:duoscreen`, read by
  `:app`; no new cross-module dependency; thread-safety from the volatile-boolean guarantee across
  all three read sites.
- NFR1/AC7 — no manifest or `automotive_app_desc.xml` change; the gate is pure runtime.
- NFR3 — only AutoBridge's own session is touched; no pane-app session is observed.
- NFR4/AC8 — default `false`, writer is `:duoscreen` only, `:duoscreen` absent in `safe` ⇒ today's
  behaviour.
- AC2 / part 4 — player untouched; audio continues while the card is withheld.
- AC9 — Duo start/stop (`AutoBridgeDuoCtl`/`AutoBridgeDuoHost`) and the suppression (`MEDIA`
  `surfaced=false`) are observable on the `StructuredLog` trail.

## Responses to design-review findings

- **Finding 1 (HIGH) — "host re-queries sources on Duo entry" stated as fact.** Addressed. §3 now
  labels host re-query of an already-connected controller as UNVERIFIED, scopes the gate's guarantee
  to the Duo-then-play ordering, lists the mid-playback case as a DHU-tracked known risk in §5, and
  states the explicit fallback (accept as documented limitation, or revisit a targeted lever (a)).
- **Finding 2 (MEDIUM) — two divergent `onGetSession` shapes.** Addressed. §2 adopts the §5 predicate
  shape as the single source of truth: `onGetSession` computes `duoActive` and `allowed`, calls
  `shouldSurfaceSession`, emits one `MEDIA` log including `allowed`/`duoActive`/`surfaced`, and
  returns `session.takeIf { surface }`. The early-return shape is dropped.
- **Finding 3 (MEDIUM) — false `CarScreenPower.policy` equivalence + missing writer invariant.**
  Addressed. §1 replaces the equivalence with the explicit volatile-boolean guarantee
  (happens-before visibility, booleans cannot tear, idempotent plain assignments ⇒ no atomic/lock),
  and adds the writer invariant (`:duoscreen` only; `:app`/`:common` never write) both in prose and
  on the holder's doc comment.
- **Finding 4 (MEDIUM) — clear in shared `stop()` causes a `true→false→true` dip on `restart()`.**
  Addressed via the review's Option A: the clear is moved out of `DuoScreenController.stop()` and
  into `DuoScreenHost.release()` (the single true-teardown entry for both explicit end and
  `expire`). `restart()`'s internal `stop()`/`start()` no longer dips the flag; §2 and §3 state this
  explicitly and the contradiction is resolved.
- **Finding 5 (NIT) — elided `MediaControllerAuthorization.isAllowed(...)` args.** Addressed. §2
  shows the full argument list (`packageName`, `uid`, `ownPackageName = packageName`,
  `ownUid = applicationInfo.uid`, `systemUid = Process.SYSTEM_UID`).
- **Finding 6 (NIT) — name that `resume()` is called inside `start()`.** Addressed. §2 states both
  `sessionStarted` sites live inside `start()`, that `resume()` does not itself call it, and that the
  implementer edits `start()` in two places and leaves `resume()` untouched.
- **Finding 7 (NIT) — AC9 Duo-side log tag.** Addressed. §5 names `AutoBridgeDuoCtl`
  (`"Session started"`/`"Session stopped"`) and `AutoBridgeDuoHost` as the Duo-side tags bracketing
  the `MEDIA` `surfaced=false` line.
- **Finding 8 (NIT) — `MediaAutoStart` receiver-thread read.** Addressed. §1 and §2 note the
  `startSession` read happens on the `BroadcastReceiver` thread and that the `@Volatile` read covers
  it, completing the thread story across all three read sites.
