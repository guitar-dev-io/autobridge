# Fresh GL thread per Duo Screen compositor session (pass 2)

The Android Auto host can deliver the car Surface more than once per Screen. `DuoScreenCompositor` used to call `start()` again on a single `HandlerThread` field, which threw `IllegalThreadStateException`. The fix adds `DuoScreenThreadSlot`, which creates a new thread on every `start()` and shuts down the old one (quitSafely, 1 s join, warn if still alive). Since pass 1, the coder answered the one blocking finding with comments only: `DuoScreenController.stop()` now has a KDoc explaining why VirtualDisplays do not survive a surface swap, and `DuoScreenScreen.onSurfaceDestroyed` points to it. No code changed.

Watch for: the main-thread wait inside `stopBlocking` has no timeout, so a wedged GL thread can still hang teardown (confirmed structure, likely low frequency). The restart sequence has not run on DHU or a head unit (confirmed).

**Verdict**: APPROVED

## High-level view

The crash fix itself is unchanged from pass 1 and meets the requirements. A finished `Thread` is never restarted. A second `onSurfaceAvailable` with no destroy in between runs `controller.stop()` first, which tears down the old session. A failed EGL bind cleans up its own thread. Screen `onDestroy` and `onSurfaceDestroyed` both reach `compositor.stopBlocking()`, which releases the EGL surface, context, and display before it quits and joins the thread.

The new KDoc on `stop()` covers what the requirement asked for. Each pane's display renders into a SurfaceTexture owned by the compositor's EGL context, and that context is destroyed with the old host Surface. As a result, every surface swap is a full restart: pane apps are launched again and lose in-activity state, while the layout is kept through `saveLayout`. That closes the blocking finding.

Scope is still tight. The change touches the compositor, the new slot class, its test, and two comment blocks. The `android:label="Duo Screen"` on `DuoScreenCarAppService` in `app/src/projection/AndroidManifest.xml` is preserved. The coder's final message was not passed to this step. Build artifacts written after the last source edit corroborate the verification: the controller and screen were edited at 20:49, the `personalDebug` APK and classes were rebuilt at 20:50, and all 64 unit-test result files were written at 20:50:02 with no failures or errors, including `DuoScreenThreadSlotTest` at 4/4.

<details>
<summary>Issues (2)</summary>

1. **Join timeout does not bound teardown** (non-blocking, carried from pass 1) — `stopBlocking` → `runOnGlThread` → `latch.await()` blocks the main thread with no timeout before the timed `join`. Use `latch.await(timeout)` on the release path so the 1 s budget covers all of teardown.
2. **Restart path untested end to end** (non-blocking, carried from pass 1) — run available→destroyed→available and available→available once on DHU to confirm that EGL re-init and pane relaunch work on a real car surface.

</details>

<details><summary>Details</summary>

### Unbounded wait ahead of the timed join

```kotlin
fun stopBlocking() {
    ...
    runOnGlThread(threadHandler) { ...; releaseEgl(); true }   // latch.await() — no timeout, main thread
    glThread.stop()                                            // join(1_000) only reached afterwards
}
```

The 1 s join only protects against a looper that fails to quit after the release work is done. It does not cover a GL thread stuck mid-frame, for example in `eglSwapBuffers` against a surface the host is destroying. On the car side this runs inside `onSurfaceDestroyed`, so a stall there would block the host callback (confirmed structure; whether the stall happens in practice is unverified).

### Test coverage

`DuoScreenThreadSlotTest` covers fresh-thread-on-restart, start→start shutdown, nothing alive after repeated starts, and a double `stop`. Not tested: the real `HandlerThread` and EGL re-init, controller restart ordering with layout save and restore across differing bounds, and the join-timeout branch.

</details>

<details>
<summary>File map</summary>

- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenThreadSlot.kt` — new: holds one worker thread and creates a fresh one on each `start()`.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenCompositor.kt` — GL thread now comes from the slot; `stopBlocking` shuts it down with a 1 s join.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenController.kt` — KDoc on `stop()` documents why a surface swap is a full restart (pass 2).
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenScreen.kt` — comment on `onSurfaceDestroyed` that points to the controller KDoc (pass 2).
- `app/src/duoscreen/test/dev/autobridge/duoscreen/DuoScreenThreadSlotTest.kt` — new: JVM lifecycle tests for the slot.
- `app/src/projection/AndroidManifest.xml` — `android:label="Duo Screen"` preserved.

The duoscreen sources are untracked, so `git diff` does not show them. Read the files directly.

</details>
