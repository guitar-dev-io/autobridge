Updated: 2026-09-08

Android Auto does not expose a raw touchscreen stream to a `SurfaceCallback`. AutoBridge therefore treats input as a capability-aware translation layer, not as a promise of arbitrary pointer fidelity. The privileged real-touch implementation is a bounded sink for a raw source if a supported host ever supplies one; it does not manufacture that missing source.

## Callback path

```text
Android Auto SurfaceCallback (available today)
  onClick / onScroll / onFling / onScale
             |
             v
        TouchRouter
             |
             +--> DisplayTransform.mapPoint
             |
             +--> ShizukuInputBackend (preferred when available)
             |
             +--> AccessibilityInputBackend (fallback)

Future/raw host pointer transport (not exposed by current Android Auto API)
  pointerId + down/move/up/cancel
             |
             v
        TouchRouter.rawTouch*
             |
             +--> Shizuku real-touch sink (when explicitly enabled/probed)
```

`MirrorCarScreen` first checks `Feature.TOUCH`. `TouchRouter` checks it again, selects an available backend, and each backend checks it before the final Android operation. A missing/unknown/moving vehicle state therefore blocks input even if an Accessibility service or Shizuku connection exists.

## Coordinate model

`DisplayTransform` is the shared geometry source for mapping and the self-drawn renderer:

1. Start with the car surface rectangle.
2. Intersect it with vehicle-profile safe insets.
3. Intersect it with the Android Auto visible-area rectangle when the host reports split-screen/pane bounds.
4. Compute `FIT`, `FILL`, `STRETCH`, or `ONE_TO_ONE` rendered bounds.
5. Reject invalid points, points outside the viewport, and FIT/ONE_TO_ONE letterbox bars.
6. Convert oriented logical phone coordinates back to raw default-display pixels for the active rotation.
7. Apply bounded vehicle/profile touch offsets.

In AUTO_MIRROR, the OS still applies its own FIT output. In SELF_DRAWN, `RenderPlan` samples this resolved transform once per frame and uses a clipped Canvas matrix, so FILL crop/STRETCH scaling/rotation use the same bounds that `TouchRouter` maps in the other direction.

`ContentBounds`, `DisplayTransformInfo`, `DebugCoordinates`, and the `CoordinateMapper` compatibility facade keep this logic pure and testable. `TouchRouter` resolves the phone's `Display.DEFAULT_DISPLAY` rather than trusting the Android Auto `CarContext` display. This matters because a car context is not necessarily the phone's physical display.

Supported rotation modes are `AUTO`, `PHONE`, `PORTRAIT`, and `LANDSCAPE` in the geometry model. The current AUTO_MIRROR renderer supports `AUTO` and `PHONE`; per-app/mirror orientation controls may apply the public global rotation setting when the user grants `WRITE_SETTINGS`.

## Backends

| Backend | Operations | Availability | Limitation |
|---|---|---|---|
| Accessibility | tap, long press, swipe/scroll/fling, synthesized pinch, Back/Home/Recents | Bound `AutoBridgeAccessibilityService` plus `Feature.TOUCH` | Requires the user to enable the service; gestures are Android Accessibility gestures, not raw input. |
| Shizuku basic | `input tap`, bounded `input swipe`, `input keyevent` for Back/Home/Recents | Shizuku permission, user-service binder, `Feature.SHIZUKU`, and `Feature.TOUCH` | The platform `input` CLI has no independent multi-pointer command, so no native pinch capability. |
| Shizuku real touch | pointer-id/action `MotionEvent` sink through hidden `InputManager.injectInputEvent` | Separate `REAL_TOUCH` capability probe, Shizuku service, `Feature.TOUCH`, and explicit `realTouchEnabled` setting | Current Android Auto callbacks do not supply the raw pointer stream; this sink is not used to convert `onScale` into fake raw input. |
| Shizuku panel power | panel-only power attempt through hidden `SurfaceControl`/`DisplayControl` APIs | Separate display-power capability probe and explicit `screenOffOnAutoDim` setting | OEM/API dependent; failure falls back to public dim and teardown restores panel power. It is not `OWN_CONTENT`. |

Shizuku is preferred for operations it advertises. If a backend cannot express a requested capability, `TouchRouter` can fall back to Accessibility. A binder death, disconnect, or remote-call failure clears the Shizuku reference and is logged; stale remote binders are not reused.

`ShizukuInputCommand` builds explicit argument vectors. The service bounds swipe duration and uses a bounded process wait; it does not run arbitrary shell text supplied by a car callback. This is a command-injection and hung-process boundary, not a guarantee that every OEM accepts the command.

## Gestures and system actions

- Tap maps one car coordinate to one phone pixel.
- Scroll/fling synthesize a bounded swipe from the phone display center.
- Long press uses a zero-distance swipe with a bounded duration.
- `onScale` maps the focus point and `PinchGeometry` synthesizes two concurrent Accessibility strokes. Shizuku reports no `PINCH` capability, so Accessibility is the valid fallback; neither path is a raw pointer stream.
- `TouchRouter.rawTouchDown/rawTouchMove/rawTouchUp/rawTouchCancel` are reserved for a host transport that supplies independent pointer IDs and actions. They map each point through the same geometry and then call only the explicitly enabled/probed real-touch backend.
- Back/Home/Recents use Accessibility global actions or Shizuku key codes 4/3/187.

## Safety and failure behavior

Input returns `false` or shows the existing parked-only feedback when:

- the policy denies `TOUCH`;
- the vehicle is `MOVING` or `UNKNOWN`;
- coordinates are non-finite, outside the visible surface, or in FIT letterbox bars;
- no backend is available;
- the selected backend lacks the requested capability;
- the raw-touch setting is disabled;
- the remote service call fails.

No input path changes `ParkingStateStore` or vehicle speed. Panel-power cleanup is deliberately allowed to run during teardown so a `MOVING`/`UNKNOWN` stop cannot leave the physical panel off.

## What this cannot provide

The Android Auto surface API does not provide independent finger coordinates or pointer-down/up streams. Raw multi-touch, arbitrary gesture replay, and perfect app-specific touch semantics are outside the current host architecture. The synthesized pinch is a best-effort translation of one host scale factor and must be validated against target apps on a device. The Shizuku real-touch sink and AIDL extension do not create the missing pointer stream.

Pure coverage is in `CoreRegressionTest` (speed/coordinates, transforms, visible bounds, offsets, rotation, pinch, and `OWN_CONTENT` availability) and `RuntimeBoundaryTest` (command vectors, default-disabled privileged settings, capability/failure boundaries, raw-pointer validation, and lifecycle boundaries). DHU/vehicle validation remains separate.
