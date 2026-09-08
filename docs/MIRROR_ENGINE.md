# Mirror engine

Updated: 2026-09-08

The current engine is a low-latency, direct-surface proof of concept:

```text
phone MediaProjection consent
        |
        v
ProjectionService (foreground service)
        |
        v
MediaProjection -> MirrorCoordinator
        |
        | one VirtualDisplay, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        v
Android Auto SurfaceCallback surface
        |
        v
DHU or vehicle head unit
```

There is no H.264/WebRTC transport in this path. The phone and car surface are on the same Android device, so an encode/decode hop would add latency without solving the platform constraints.

## Ownership

- `MainActivity` requests user MediaProjection consent and starts/stops `ProjectionService`.
- `ProjectionService` owns the foreground-service session, notification, system projection callback, orientation observer, and optional Shizuku bind lifecycle.
- `MirrorCoordinator` is the process-global owner of the projection-to-surface binding. It is the only component that calls `createVirtualDisplay`, `resize`, or assigns the car `Surface`.
- `MirrorCarScreen` is an Android Auto `NavigationTemplate`/`SurfaceCallback` host. It owns car actions and forwards surface/input callbacks; it does not own capture state.
- `MirrorSurfaceController` translates `SurfaceContainer` callbacks into `SurfaceState`, configures geometry, and delegates the surface to `MirrorCoordinator`.
- `VehicleStateSession` shares one vehicle provider across car screens and closes it after the last lease.

## Engine abstraction

`MirrorEngine` keeps the UI independent from the current renderer:

```text
start(consented MediaProjection)
stop()
attachSurface(surface, width, height, dpi)
detachSurface(surface?)
setScaleMode(mode)
setRotationMode(mode)
```

`AutoMirrorEngine` adapts this contract to `MirrorCoordinator`. Its current supported renderer modes are `FIT` and `AUTO`/`PHONE` rotation. The abstraction is deliberately ready for a future own-content renderer, but it does not claim that renderer exists today.

## Projection lifecycle

1. The user grants screen-capture consent. Android 14+ requires a fresh consent token for a new capture session.
2. `ProjectionService` enters `STARTING`, becomes a media-projection foreground service, obtains a `MediaProjection`, and registers a callback.
3. `MirrorCoordinator.attachProjection` replaces any old projection, resets reconnect tracking, and waits for a valid car surface.
4. Android Auto delivers `onSurfaceAvailable`. The controller records dimensions/DPI/visible bounds and attaches the surface.
5. `MirrorCoordinator` creates one `VirtualDisplay` with `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` when policy, projection, surface validity, and dimensions all allow it.
6. Surface replacement or resize uses the existing virtual display's `resize()` and `surface` assignment. It does not request consent again.
7. Movement, explicit stop, system stop, startup failure, or projection callback releases the display and projection, restores rotation state, unbinds optional input, and returns the service to `IDLE`/`ERROR`.

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

## Geometry boundary

`MirrorSurfaceController` shares the active surface's safe insets, visible area, touch offsets, scale, and rotation with `DisplayTransform`. The actual AUTO_MIRROR output remains OS-owned FIT letterboxing. `DisplayTransform` also has pure geometry for FILL, STRETCH, and ONE_TO_ONE so mapping and a future own-content renderer can share a contract, but selecting those modes currently logs the limitation and keeps the renderer on FIT.

## Diagnostics

`MirrorDiagnostics` records a bounded ring of lifecycle events such as `consent_received`, `projection_attached`, `car_surface_attached`, `virtual_display_created`, `virtual_display_resized`, `car_session_reconnected`, and `projection_stopped`. It measures time between labeled events and current mirroring uptime. Per-frame FPS is not available because AUTO_MIRROR never exposes frames to app code.

`StructuredLog` provides an additional bounded, formatted log for the developer screen. These are lifecycle diagnostics, not proof of frame rate or vehicle compatibility.

## Known limitations

- Actual FILL/STRETCH/crop output requires an ImageReader/Canvas own-content pipeline.
- Screen-off survival requires own-content rendering or an app launched onto a dedicated own-content display; AUTO_MIRROR follows the phone's default display power.
- Direct AUTO_MIRROR is full-display, not target-app-only.
- Protected video surfaces such as Netflix/Widevine content may be intentionally blank in MediaProjection output; this cannot be corrected through a public renderer setting or by removing DRM protections.
- Android Auto host categories, surface sizes, visible-area callbacks, and `CAR_SPEED` availability differ by DHU/OEM.
- `SurfaceProfile.FORD_NEXT_GEN` is a manual, unverified placeholder until measurements come from a real Ford session; see [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md).
