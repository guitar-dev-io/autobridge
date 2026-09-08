# Input system

Updated: 2026-09-08

Android Auto does not expose a raw touchscreen stream to a `SurfaceCallback`. AutoBridge therefore treats input as a capability-aware translation layer, not as a promise of arbitrary pointer fidelity.

## Callback path

```text
Android Auto SurfaceCallback
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
```

`MirrorCarScreen` first checks `Feature.TOUCH`. `TouchRouter` checks it again, selects an available backend, and each backend checks it before the final Android operation. A missing/unknown/moving vehicle state therefore blocks input even if an Accessibility service or Shizuku connection exists.

## Coordinate model

`DisplayTransform` is the shared geometry source for mapping and future rendering:

1. Start with the car surface rectangle.
2. Intersect it with vehicle-profile safe insets.
3. Intersect it with the Android Auto visible-area rectangle when the host reports split-screen/pane bounds.
4. Compute `FIT`, `FILL`, `STRETCH`, or `ONE_TO_ONE` rendered bounds.
5. Reject invalid points, points outside the viewport, and FIT/ONE_TO_ONE letterbox bars.
6. Convert oriented logical phone coordinates back to raw default-display pixels for the active rotation.
7. Apply bounded vehicle/profile touch offsets.

`ContentBounds`, `DisplayTransformInfo`, `DebugCoordinates`, and the `CoordinateMapper` compatibility facade keep this logic pure and testable. `TouchRouter` resolves the phone's `Display.DEFAULT_DISPLAY` rather than trusting the Android Auto `CarContext` display. This matters because a car context is not necessarily the phone's physical display.

Supported rotation modes are `AUTO`, `PHONE`, `PORTRAIT`, and `LANDSCAPE` in the geometry model. The current AUTO_MIRROR renderer supports `AUTO` and `PHONE`; per-app/mirror orientation controls may apply the public global rotation setting when the user grants `WRITE_SETTINGS`.

## Backends

| Backend | Operations | Availability | Limitation |
|---|---|---|---|
| Accessibility | tap, long press, swipe/scroll/fling, synthesized pinch, Back/Home/Recents | Bound `AutoBridgeAccessibilityService` plus `Feature.TOUCH` | Requires the user to enable the service; gestures are Android Accessibility gestures, not raw input. |
| Shizuku | `input tap`, bounded `input swipe`, `input keyevent` for Back/Home/Recents | Shizuku permission, user-service binder, `Feature.SHIZUKU`, and `Feature.TOUCH` | The platform `input` CLI has no independent multi-pointer command, so no native pinch capability. |

Shizuku is preferred for operations it advertises. If a backend cannot express a requested capability, `TouchRouter` can fall back to Accessibility. A binder death, disconnect, or remote-call failure clears the Shizuku reference and is logged; stale remote binders are not reused.

`ShizukuInputCommand` builds explicit argument vectors. The service bounds swipe duration and uses a bounded process wait; it does not run arbitrary shell text supplied by a car callback. This is a command-injection and hung-process boundary, not a guarantee that every OEM accepts the command.

## Gestures and system actions

- Tap maps one car coordinate to one phone pixel.
- Scroll/fling synthesize a bounded swipe from the phone display center.
- Long press uses a zero-distance swipe with a bounded duration.
- `onScale` maps the focus point and `PinchGeometry` synthesizes two concurrent Accessibility strokes. Shizuku reports no `PINCH` capability, so Accessibility is the valid fallback.
- Back/Home/Recents use Accessibility global actions or Shizuku key codes 4/3/187.

## Safety and failure behavior

Input returns `false` or shows the existing parked-only feedback when:

- the policy denies `TOUCH`;
- the vehicle is `MOVING` or `UNKNOWN`;
- coordinates are non-finite, outside the visible surface, or in FIT letterbox bars;
- no backend is available;
- the selected backend lacks the requested capability;
- the remote service call fails.

No input path changes `ParkingStateStore` or vehicle speed.

## What this cannot provide

The Android Auto surface API does not provide independent finger coordinates or pointer-down/up streams. Raw multi-touch, arbitrary gesture replay, and perfect app-specific touch semantics are outside this architecture. The synthesized pinch is a best-effort translation of one host scale factor and must be validated against target apps on a device.

Pure coverage is in `CoreRegressionTest` (speed/coordinates, transforms, visible bounds, offsets, rotation, pinch) and `RuntimeBoundaryTest` (command vectors and lifecycle boundaries). DHU/vehicle validation remains separate.
