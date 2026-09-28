Updated: 2026-09-08

This roadmap describes the current shared implementation. `DONE` means the code path exists and has local compile/unit coverage where pure logic is testable. `PARTIAL` means a platform or host-dependent portion remains. `TODO` means the work is intentionally deferred; it is not silently represented as complete.

## Completed in shared code

### Core and variants — DONE

- Single `:app` module with shared `src/main` code.
- `safe`, `personal`, and `lab` product flavors with `BuildConfig.AUTOBRIDGE_MODE`.
- Runtime models, `RuntimeContextStore` StateFlow/SharedFlow, structured logs, vehicle profiles, and reference-counted vehicle-state leases.
- Central `FeaturePolicy` for mode, environment, and `PARKED` decisions.

### Mirror and Android Auto — DONE locally / PARTIAL on hardware

- `CarAppService`, dashboard, `NavigationTemplate`, `ListTemplate` control screens, and custom surface callback.
- MediaProjection foreground service and direct `MediaProjection -> VirtualDisplay(AUTO_MIRROR) -> Surface` path.
- One virtual display per consented projection; surface resize/rebind uses `resize()`/`setSurface()`.
- Explicit surface lifecycle, safe/visible geometry, orientation state, reconnect tracking, and lifecycle diagnostics.

### Input — DONE locally / PARTIAL on devices

- Shared FIT/FILL/STRETCH/ONE_TO_ONE geometry model used by both self-drawn output and input mapping.
- Rotation, visible-area, safe-inset, touch-offset, normalized/debug coordinate support.
- Accessibility tap/swipe/long-press/pinch/system actions.
- Capability-aware Shizuku user-service backend for bounded tap/swipe/keyevent commands, with binder-death cleanup.
- Optional Shizuku capability probes and sinks for panel-only display power and real pointer-id/action `MotionEvent` injection.
- Explicit documentation of Android Auto's raw-pointer/multi-touch limits; the current host still supplies gestures rather than a raw pointer stream.

### Apps and profiles — DONE locally / PARTIAL on devices

- Installed-app discovery, Quick Apps source of truth, legacy-favorite migration, sorting/enabled filtering.
- Per-app persisted scale/resolution/FPS/touch/audio/screen-on/rotation/fullscreen preferences.
- Smart Mode with reasons, parked-only launcher, public force-landscape permission, and rotation restoration.
- Explicit session restore/forget; no automatic replay of MediaProjection consent.

### Media and entertainment — DONE locally / PARTIAL on devices

- Media3 progressive, local, HLS, and DASH source routing.
- MediaSession client/service, playlist next/previous, controller allowlist, and audio/video policy split.
- Explicit WEB/AUDIO/VIDEO content routing, HTTPS WebView boundary, local document picker, favorites, and position restore.

### Developer/LAB/diagnostics — DONE locally

- Emulator-only LAB simulator, explicit environment selection, and no real-car simulation override.
- Reconnect outcome tracking, structured logs, mirror event ring, latency/uptime diagnostics, live LAB/developer refresh.
- ScreenOnAuto-inspired optional prevent-sleep/auto-dim, opt-in panel-only screen-off, stop-on-disconnect, auto-launch-last-app, and consent-preserving auto-start settings.
- Bounded Compose phone control-center slice without replacing the existing Activity/Car App architecture.

### ScreenOnAuto-inspired automation — DONE locally / PARTIAL on devices

- Persisted mirror settings for prevent-sleep, timed auto-dim, opt-in panel-only screen-off, stop-on-disconnect, last-app auto-launch, and consented auto-open.
- Public WakeLock-based screen power controller plus a separate Shizuku hidden display-power attempt; every stop/unbind path restores panel power.
- Shizuku hidden `InputManager` real-touch sink with bounded pointer state; the current Android Auto host does not provide the raw source needed to drive it end to end.
- Opt-in self-drawn ImageReader/Canvas renderer with immutable RenderPlan, FILL/STRETCH/crop/ONE_TO_ONE output, FPS throttling, and captured/dropped/rendered/latency diagnostics.
- Shizuku permission onboarding for the optional privileged backend; Accessibility remains the no-root fallback.

## Intentional partials and blockers

### Self-drawn renderer — DONE locally / PARTIAL on devices

`SELF_DRAWN` is an opt-in renderer selected from Mirror Settings. It captures the consented default display into an `ImageReader`, hands off at most the newest pending frame to a render worker, and draws an immutable `RenderPlan` to the Android Auto surface. `DisplayTransform` is shared with touch mapping, so FIT bars, FILL crop, STRETCH, ONE_TO_ONE, safe/visible bounds, and rotation use the same geometry. Diagnostics report captured, dropped, rendered, measured FPS, and last frame latency.

The direct `AUTO_MIRROR` path remains the default and is not changed by this mode. Device validation is still required for ImageReader color/order, CPU cost, exact frame rate, DRM/protected surfaces, surface reconnects, and OEM host behavior.

### Own-content and privileged display — PARTIAL/BLOCKED

`OWN_CONTENT` remains a capability marker for a dedicated app-owned VirtualDisplay and is intentionally unavailable. Settings restore it to `AUTO_MIRROR`, and runtime mode changes are rejected rather than creating a misleading screen-off promise.

Separately, AutoBridge now contains an opt-in panel-only power backend in the Shizuku user service. It probes hidden `SurfaceControl`/`DisplayControl` APIs, reports unavailable when the device/OEM does not expose a compatible token/setter, falls back to public dimming, and restores panel power on teardown. This is a device-dependent attempt, not a public Android guarantee and not an implementation of `OWN_CONTENT`.

A real-touch injection sink is also present behind a separate capability and setting. It accepts bounded raw pointer IDs/actions, but Android Auto's current `SurfaceCallback` contract does not provide those raw events; `onScale` remains the synthetic Accessibility fallback. No end-to-end raw multi-touch claim is made.

### Host and vehicle validation — BLOCKED/TODO

- DHU/OEM must provide a successful `CAR_SPEED` value for production parking validation.
- Ford surface dimensions/DPI/visible-area behavior require a real Ford session.
- Wireless Android Auto, media-button/controller identity, Accessibility, Shizuku binding, panel-only power, raw touch landing, and reconnect behavior need device runs.
- Current `FORD_NEXT_GEN` values remain unverified placeholders.

### Distribution — TODO

The NavigationTemplate custom surface remains a development/personal POC. Android Auto category and distribution rules must be resolved before any store-facing product claim.

## Suggested next order

1. Complete repeatable DHU and physical-host matrix for both AUTO_MIRROR and SELF_DRAWN, including valid `CAR_SPEED` and reconnect.
2. Record SelfDrawn frame counters/latency, ImageReader color correctness, CPU/battery impact, and exact host surface behavior on representative phones.
3. Validate the Shizuku panel-only backend on explicitly supported phone/OEM/Android combinations, including failure and teardown restoration.
4. Determine whether a supported host can supply raw pointer IDs/actions; otherwise retain the synthetic Accessibility pinch boundary.
5. Record Ford/SYNC surface measurements and only then tune `FORD_NEXT_GEN`.
6. Revisit dedicated OWN_CONTENT display only for a specifically supported device/OEM contract; do not generalize it from a DHU run.
7. Revisit distribution/category compliance separately from the personal POC.

See [`docs/MODES.md`](docs/MODES.md), [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md), and [`docs/DHU_SCENARIOS.md`](docs/DHU_SCENARIOS.md) for operational detail.
