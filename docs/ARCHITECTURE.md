# AutoBridge architecture

Updated: 2026-09-08

AutoBridge remains one Android application module with shared code. Product modes are Gradle flavors, while live mode/environment/vehicle state is represented by a shared runtime context and evaluated through `FeaturePolicy`.

## Runtime map

```text
MainActivity + bounded Compose control center
        |
        | user grants MediaProjection consent
        v
ProjectionService (foreground service)
        |
        v
MediaProjection
        |
        v
MirrorCoordinator (process-global projection/surface owner)
        |
        +--> AUTO_MIRROR: VirtualDisplay --> Android Auto Surface
        |
        +--> SELF_DRAWN: VirtualDisplay --> ImageReader --> RenderPlan/Canvas --> Surface
        ^                                      |
        |                                      v
MirrorSurfaceController / SurfaceState       DisplayTransform
        |
        +--> TouchRouter --> Accessibility or Shizuku

VehicleStateSession --> SpeedGate(CAR_SPEED) or emulator LAB mock
        |
        v
ParkingStateStore --> RuntimeContextStore --> FeaturePolicy
```

## Ownership rules

### Phone

`MainActivity` owns phone navigation, consent initiation, bounded Compose embedding, status refresh, settings/profile controls, and explicit restore actions. It does not own the Android Auto surface or directly inject touch.

`ProjectionService` owns the foreground MediaProjection session, notification, system projection callback, orientation observation, and cleanup. It records startup/error state and never treats a failed/expired consent result as reusable.

### Car

`AutoBridgeCarAppService` creates the host-managed car session. `CarDashboardScreen`, `CarAppsScreen`, `CarMediaScreen`, `CarSettingsScreen`, and `MirrorControlScreen` use AndroidX Car App templates. `MirrorCarScreen` owns the `NavigationTemplate` custom surface callback and forwards car actions to shared boundaries.

The car UI never embeds Compose or draws an arbitrary overlay over the live surface. A panel/control screen is a `ListTemplate`; projected phone pixels remain an Android Auto surface concern.

### Shared state and policy

`VehicleStateSession` reference-counts one provider across screens, registers its lease before starting the provider, and stops the provider after the final lease closes. `SpeedGate` is the production provider. `MockVehicleStateProvider` is selected only by the emulator/LAB gate.

`RuntimeContextStore` mirrors vehicle/provider changes as a process-local `StateFlow` and `SharedFlow`. Durable preferences remain in `SettingsStore`, `PerAppProfileStore`, `QuickAppsRepository`, and `SessionRestoreStore`. `FeaturePolicy` is the only shared mode/vehicle feature decision boundary.

## Safety invariant

Only finite exactly-zero speed is `PARKED`. Non-zero finite values are `MOVING`; missing, stale, invalid, or non-finite values are `UNKNOWN`. All parked-only entries and low-level render/input backends fail closed. A movement transition stops the projection and leaves the car app. LAB state changes still flow through `ParkingStateStore`; `LAB + REAL_CAR` cannot use the simulator.

## Mirror lifecycle

The projection and car surface have independent lifetimes:

1. phone consent starts `ProjectionService`;
2. `MirrorCoordinator` retains the projection and waits for a valid car surface;
3. `MirrorSurfaceController` turns `SurfaceContainer` callbacks into an explicit `SurfaceState`;
4. coordinator creates one AUTO_MIRROR virtual display once all policy/surface conditions are true;
5. surface replacement/resizing rebinds the existing display;
6. surface disconnect can leave the projection alive for reconnect recovery;
7. projection stop, movement, system callback, or service teardown releases everything.

`ReconnectTracker` distinguishes first start, unchanged duplicate callbacks, and a real detach/re-attach resume. See [`MIRROR_ENGINE.md`](MIRROR_ENGINE.md).

## Geometry and input

`DisplayTransform` resolves viewport, safe insets, host visible bounds, rendered bounds, rotation, and touch offsets. `TouchRouter` maps car callbacks through this model and resolves the phone's default display. AUTO_MIRROR keeps OS-owned FIT output; SELF_DRAWN uses the same resolved geometry in `RenderPlan` for actual FIT/FILL/STRETCH/ONE_TO_ONE Canvas output. See [`INPUT_SYSTEM.md`](INPUT_SYSTEM.md) and [`MIRROR_ENGINE.md`](MIRROR_ENGINE.md).

## Browser layout contract

Both browser presentations use an **overlay** model: the page fills the whole viewport and chrome is
composited on top of it. Only three things may re-measure the page — the car surface changing size,
the host reporting a materially different stable area, and a device configuration change. Toolbar
show/hide, fullscreen, drawer and tab switcher change opacity and nothing else.

```text
car surface                              phone window
+------------------------------+         +------------------------------+
| toolbar overlay (fade)       |         | chromeBar overlay (status    |
| drawer / tab overlay (fade)  |         |   bar inset applied here)    |
| WebView page (full viewport) |         | WebView page (full viewport, |
+------------------------------+         |   IME inset applied here)    |
                                         +------------------------------+
```

- `BrowserViewport` deliberately has no chrome-height parameter; page size cannot depend on chrome.
- `AutoUiSizes` is the only source of chrome sizing. Values are authored in dp and resolved through
  the *panel's own* density (`SurfaceContainer.getDpi() / 160`), never through its pixel width.
  Icon visual size and touch target are separate fields.
- `BrowserChromeLayout` produces the rectangles that drawing and hit testing both read, so a control
  cannot be drawn in one place and tapped in another.
- `ChromeVisibility` is a clock-injected state machine; transitions are opacity only.
- `CarBrowserRuntime` owns one `CarWebRenderer` per car session, so pushing a screen never recreates
  the WebView. It is released by `AutoBridgeSession.onDestroy`, never by a screen being popped.
- `ViewportDebug` traces every geometry event with the same fields, so an unexpected reflow can be
  attributed rather than guessed at.

## Feature layers

- `apps`: installed apps, Quick Apps, profiles, Smart Mode, launch/rotation cleanup;
- `media`: Media3 player/session, source resolver, controller authorization;
- `browser`: shared browser logic for both presentations — page identity/navigation
  (`BrowserDefaults`), User-Agent, viewport/chrome geometry, sizing tokens, tabs, downloads,
  and the car surface renderer;
- `entertainment`: HTTPS WebView, local picker, audio/video surface, content-kind policy;
- `display`: orientation, surface profile, diagnostics, screen-off/per-app boundaries;
- `safety`: CAR_SPEED, LAB mock, movement/timeout behavior;
- `core`: models, state, policy, logs, tolerant persistence codecs.

## Why direct AUTO_MIRROR remains

The phone and Android Auto surface are on the same device, so direct AUTO_MIRROR avoids an unnecessary encoder/transport/decoder chain. SELF_DRAWN is available when app-controlled geometry/FPS is worth the copy/draw cost. The trade-offs remain explicit: AUTO_MIRROR owns final composition and FIT behavior; SELF_DRAWN still depends on the default display as its capture source and cannot promise screen-off survival.

## Platform/distribution boundary

The app uses a `NavigationTemplate` custom surface because the official Android Auto model does not provide a general arbitrary screen-mirroring category. This is suitable for development/personal testing only until a compliant category and distribution path are established.

## Related documents

- [`MODES.md`](MODES.md) and [`FEATURE_POLICY.md`](FEATURE_POLICY.md)
- [`MIRROR_ENGINE.md`](MIRROR_ENGINE.md) and [`INPUT_SYSTEM.md`](INPUT_SYSTEM.md)
- [`APP_PROFILES.md`](APP_PROFILES.md) and [`LAB_MODE.md`](LAB_MODE.md)
- [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md) and [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md)
