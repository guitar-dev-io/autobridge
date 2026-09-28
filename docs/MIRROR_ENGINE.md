Updated: 2026-09-08

The default engine is a low-latency, direct-surface proof of concept. An opt-in self-drawn engine is now available when output geometry or frame diagnostics matter:

```text
phone MediaProjection consent
        |
        v
ProjectionService (foreground service)
        |
        +--> AUTO_MIRROR: VirtualDisplay(AUTO_MIRROR) --> Android Auto Surface
        |
        +--> SELF_DRAWN: VirtualDisplay(AUTO_MIRROR) --> ImageReader
                                      |
                                      v
                           RenderPlan + Canvas worker
                                      |
                                      v
                             Android Auto Surface
```

There is no H.264/WebRTC transport in either path. The phone and car surface are on the same Android device, so an encode/decode hop would add latency without solving the platform constraints.

## Ownership

- `MainActivity` requests user MediaProjection consent and starts/stops `ProjectionService`.
- `ProjectionService` owns the foreground-service session, notification, system projection callback, orientation observer, and optional Shizuku bind lifecycle.
- `MirrorCoordinator` is the process-global owner of the projection-to-surface binding. It is the only component that calls `createVirtualDisplay`, `resize`, or assigns the car `Surface`.
- `MirrorCarScreen` is an Android Auto `NavigationTemplate`/`SurfaceCallback` host. It owns car actions and forwards surface/input callbacks; it does not own capture state.
- `MirrorSurfaceController` translates `SurfaceContainer` callbacks into `SurfaceState`, configures geometry, and delegates the surface to `MirrorCoordinator`.
- `VehicleStateSession` shares one vehicle provider across car screens and closes it after the last lease.

Screen power is deliberately separate from mirror ownership. `ScreenPowerController` uses public WakeLock/dim behavior by default and may ask the optional Shizuku user service for panel-only power-off after the user enables it. The source renderer is not changed into `OWN_CONTENT` by that setting.

## Engine abstraction

`MirrorEngine` keeps the UI independent from the renderer:

```text
start(consented MediaProjection + Context)
stop()
attachSurface(surface, width, height, dpi)
detachSurface(surface?)
setScaleMode(mode)
setRotationMode(mode)
```

`AutoMirrorEngine` adapts the existing direct path and supports FIT with `AUTO`/`PHONE` rotation. `SelfDrawnMirrorEngine` owns the capture `ImageReader`, bounded pending-frame handoff, bitmap copy, output Canvas, surface generation, and frame counters. It supports all four scale modes and all geometry rotation modes. `OWN_CONTENT` is a separate capability marker, not a hidden fallback: the dedicated app-display launch path is currently unavailable.

The optional real-touch backend is also separate from rendering. `ShizukuRealTouchController` can inject a bounded raw pointer stream through hidden `InputManager` APIs when a supported privileged service and host transport exist. Current Android Auto callbacks do not provide that stream, so ordinary `onScale` remains synthetic Accessibility input.

## Projection lifecycle

1. The user grants screen-capture consent. Android 14+ requires a fresh consent token for a new capture session.
2. `ProjectionService` enters `STARTING`, becomes a media-projection foreground service, obtains a `MediaProjection`, and registers a callback.
3. `MirrorCoordinator.attachProjection` replaces any old projection, resets reconnect tracking, and waits for a valid car surface.
4. `MirrorSurfaceController` records dimensions/DPI/visible bounds and attaches the car surface.
5. `MirrorCoordinator` selects the persisted pipeline. AUTO_MIRROR binds one OS-owned virtual display directly; SELF_DRAWN creates a capture virtual display into an `ImageReader` and waits for the renderer to own the car surface. An unavailable OWN_CONTENT selection fails closed.
6. AUTO_MIRROR surface replacement/resizing uses the existing display's `resize()`/`setSurface()`. SELF_DRAWN increments a surface generation and the render worker refuses stale generations.
7. Projection stop, movement/UNKNOWN, system callback, startup failure, or service teardown releases the display/reader/worker, restores rotation state, cancels active real touch, restores panel power, unbinds optional input, and returns the service to `IDLE`/`ERROR`.

A single projection does not silently create multiple virtual displays. A new consent flow is required after the projection itself has ended.

## Surface lifecycle and reconnects

`SurfaceState` makes `UNAVAILABLE`, `AVAILABLE`, `CHANGED`, and `DESTROYED` explicit and increments a generation on callback changes. `MirrorCoordinator` treats duplicate callbacks and replacement surfaces idempotently.

`ReconnectTracker` records:

- first attached surface after `attachProjection`: `FRESH_START`;
- duplicate callback with no surface change: `UNCHANGED`;
- a new surface after a detach while the projection is alive: `RESUMED`, incrementing the reconnect counter.

The projection therefore outlives a car `Screen`/surface disconnect when Android keeps the MediaProjection alive. A fresh projection resets the tracker. This is structurally implemented; reconnect behavior still needs DHU/vehicle confirmation on the target host.

## Safety boundary

`MirrorCoordinator` calls `FeaturePolicy` before rendering. `SpeedGate`/`MockVehicleStateProvider` update the shared vehicle state, and a `MOVING` callback stops the projection and leaves the car app. `UNKNOWN` remains blocked. LAB does not alter this rule on a real car.

The panel-off and real-touch settings do not bypass this policy. Normal remote operations are gated by `FeaturePolicy`; teardown restoration is intentionally handled separately so movement, unknown state, binder death, or service destruction cannot leave privileged state active.

## Geometry boundary

`MirrorSurfaceController` shares the active surface's safe insets, visible area, touch offsets, scale, and rotation with `DisplayTransform`. AUTO_MIRROR keeps the OS-owned FIT transform. SELF_DRAWN samples the same state once per frame into an immutable `RenderPlan`; FIT/ONE_TO_ONE letterbox handling, FILL crop, STRETCH axis scaling, rotation, and touch mapping therefore share one geometry source. Changing pipeline while a projection is active is rejected because each producer has different ownership. SELF_DRAWN scale changes are applied on the next frame through a new immutable `RenderPlan`; stop/re-consent is not required for a scale change.

## Diagnostics

`MirrorDiagnostics` records a bounded lifecycle ring for both pipelines. SELF_DRAWN additionally records captured, dropped, rendered, measured FPS, and last frame latency; AUTO_MIRROR remains intentionally unmeasured per-frame because the OS owns its producer. These counters are diagnostics, not a promise of a fixed FPS or pixel correctness on every host.

`StructuredLog` provides an additional bounded, formatted log for the developer screen. These are lifecycle diagnostics, not proof of frame rate or vehicle compatibility.

## Known limitations

- Actual SELF_DRAWN pixels require device validation for ImageReader color/order, copy cost, CPU/battery impact, surface lock behavior, reconnects, and exact measured FPS.
- SELF_DRAWN still captures the default physical display. The opt-in panel-only backend may preserve composition on supported devices, but it uses hidden OEM/API-dependent display-control methods and does not guarantee screen-off survival.
- Direct AUTO_MIRROR is full-display, not target-app-only. A dedicated `OWN_CONTENT` display is currently unavailable and is not silently substituted.
- Protected video surfaces such as Netflix/Widevine content may be intentionally blank in MediaProjection output; this cannot be corrected through a public renderer setting or by removing DRM protections.
- Android Auto host categories, surface sizes, visible-area callbacks, and `CAR_SPEED` availability differ by DHU/OEM.
- `SurfaceProfile.FORD_NEXT_GEN` is a manual, unverified placeholder until measurements come from a real Ford session; see [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md).
