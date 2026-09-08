# AutoBridge roadmap and status

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
- Explicit surface lifecycle, safe insets, visible bounds, orientation state, reconnect tracking, and lifecycle diagnostics.
- Reconnect reuse logic avoids fresh consent when the projection remains alive.

### Input — DONE locally / PARTIAL on devices

- Shared FIT/FILL/STRETCH/ONE_TO_ONE geometry model for mapping and future rendering.
- Rotation, visible-area, safe-inset, touch-offset, normalized/debug coordinate support.
- Accessibility tap/swipe/long-press/pinch/system actions.
- Capability-aware Shizuku tap/swipe/keyevent backend with binder-death cleanup and bounded commands.
- Explicit documentation of Android Auto's raw-pointer/multi-touch limits.

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
- ScreenOnAuto-inspired optional prevent-sleep/auto-dim, stop-on-disconnect, auto-launch-last-app, and consent-preserving auto-start settings.
- Bounded Compose phone control-center slice without replacing the existing Activity/Car App architecture.

### ScreenOnAuto-inspired automation — DONE locally / PARTIAL on devices

- Persisted mirror settings for prevent-sleep, timed auto-dim, stop-on-disconnect, last-app auto-launch, and consented auto-open.
- Public WakeLock-based screen power controller with projection cleanup and car-input activity reset.
- Shizuku permission onboarding for the existing optional privileged input backend.
- Screen-off panel control, real pointer injection, self-drawn rendering, and silent capture consent remain intentionally deferred.

## Intentional partials and blockers

### Own-content display pipeline — PARTIAL

The current renderer is direct AUTO_MIRROR. Actual FILL/STRETCH/crop/ONE_TO_ONE output, per-frame FPS, target-app-only rendering, screen-off-surviving output, and robust own-content per-app display require an ImageReader/Canvas or dedicated own-content VirtualDisplay architecture. That is a renderer change, not a setting toggle.

### Host and vehicle validation — BLOCKED/TODO

- DHU/OEM must provide a successful `CAR_SPEED` value for production parking validation.
- Ford surface dimensions/DPI/visible-area behavior require a real Ford session.
- Wireless Android Auto, media-button/controller identity, touch injection, Shizuku binding, and reconnect behavior need device runs.
- Current `FORD_NEXT_GEN` values remain unverified placeholders.

### Distribution — TODO

The NavigationTemplate custom surface remains a development/personal POC. Android Auto category and distribution rules must be resolved before any store-facing product claim.

## Suggested next order

1. Complete repeatable DHU and physical-host matrix, including valid `CAR_SPEED` and reconnect.
2. Record Ford/SYNC surface measurements and only then tune `FORD_NEXT_GEN`.
3. Decide whether the latency/CPU trade-off of an own-content renderer justifies FILL/STRETCH, screen-off, per-app display, and real FPS.
4. Add device/instrumentation coverage for MediaSession controllers, Shizuku, Accessibility, and surface lifecycle.
5. Revisit distribution/category compliance separately from the personal POC.

See [`docs/MODES.md`](docs/MODES.md), [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md), and [`docs/DHU_SCENARIOS.md`](docs/DHU_SCENARIOS.md) for operational detail.
