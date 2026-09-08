# DHU and device scenarios

Updated: 2026-09-08

JVM tests prove pure policy, resolver, transform, and state logic. They do not prove Android Auto host behavior, actual surface pixels, MediaProjection consent, speed callbacks, media routing, or input injection. Run these scenarios with a DHU/emulator first and a real head unit only after the safety path is understood.

## Setup

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

Enable Android Auto developer mode/unknown sources as required by the local DHU. Use `labDebug` on an emulator for the mock provider; a physical phone connected to a DHU does not activate the emulator-only simulator.

## LAB state and mirror

- Open AutoBridge from the DHU launcher.
- Select a non-`REAL_CAR` LAB environment and set mock state to `PARKED`.
- Grant MediaProjection consent on the phone.
- Confirm `ProjectionService` becomes `READY`, the surface has non-zero dimensions/DPI, and diagnostics show `car_surface_attached` and `virtual_display_created`.
- Confirm visible phone pixels in the surface.
- Set mock state to `MOVING`: projection stops and the car app exits.
- Set mock state to `UNKNOWN`: mirror/video/browser/touch/Quick Apps remain blocked.
- Return to `PARKED`: explicitly restart/restore only through the documented user flow.

## Production speed gate

With `personalDebug` or a release-like build on a supported host:

- request/grant `CAR_SPEED`;
- confirm a successful finite zero sample becomes `PARKED`;
- confirm a non-zero positive or negative sample becomes `MOVING`;
- stop updates for more than three seconds and confirm `UNKNOWN`;
- confirm no app-side override can turn an unavailable sample into `PARKED`.

A DHU configuration that returns unavailable `CarValue` data cannot prove the production gate. Record that as a host limitation, not as a reason to weaken the policy.

## Surface/reconnect

- Start mirror with an active projection and surface.
- Re-enter the car screen or disconnect/reconnect Android Auto.
- Confirm the original projection is reused when still alive, no new consent dialog appears, and the reconnect event/counter changes once per real detach/re-attach.
- Send duplicate surface callbacks and resize/replacement callbacks; confirm no duplicate virtual display or reconnect inflation.
- Stop projection from the phone, notification, and car control panel; confirm cleanup and rotation restoration.

## Input

- With `PARKED` and Accessibility enabled, test tap, scroll/fling, long press, pinch, Back, Home, and Recents.
- With Shizuku granted, bind the user service and test tap/swipe/keyevent; kill/disconnect the service and confirm the backend becomes unavailable and Accessibility can be selected where supported.
- Tap visible letterbox bars and outside reported visible bounds; no phone gesture should be dispatched.
- Repeat while `MOVING`/`UNKNOWN`; all input must be denied.

## Apps and media

- Add/disable/remove Quick Apps and verify both phone/car lists use the same persisted records.
- Launch an app while parked; verify profile rotation is restored after stop/failure and autoMirror never bypasses capture consent.
- Play progressive, local, HLS, and DASH sources; test MediaSession play/pause/next/previous and audio continuity after video policy is denied.
- Verify an unknown MediaSession controller is rejected/logged and legitimate own/system/Android Auto controllers remain usable.
- Test WEB/AUDIO/VIDEO classification, local picker permission, favorites, last-source restore, and position restore.

## Diagnostics and screen power

- Verify the developer screen refreshes structured logs, mirror events, reconnect count, current transform/rotation, and latency/uptime values.
- Confirm the event ring can be cleared without resetting active uptime.
- With the current AUTO_MIRROR pipeline, turning the phone screen off may pause/blacken the car image. Do not record this as a regression or claim screen-off survival; own-content rendering is deferred.

## Evidence record

For every host run save APK flavor/build type, phone/Android version, Android Auto/DHU version, host connection type, surface width/height/DPI, speed callback status/value, Logcat, and screenshots. A scenario is `PARTIAL` until its platform-specific observation is recorded.
