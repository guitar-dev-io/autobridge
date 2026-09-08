# Ford Next Gen test checklist

Updated: 2026-09-08

Run the emulator/DHU scenarios before a physical Ford session. The Ford profile is deliberately unverified until measurements are recorded; see [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md).

## DHU baseline

- AutoBridge appears in the Android Auto development launcher.
- `AutoBridgeCarScreen` receives a non-zero surface width/height/DPI and visible-area callbacks.
- The host accepts the NavigationTemplate/action strips/control screens.
- `CAR_SPEED` permission can be requested; a successful zero sample reaches `PARKED`.
- Screen-capture consent produces one projection session.
- Phone pixels appear only when policy permits.
- A non-zero or stale/unknown speed blocks the parked-only paths and stops an active projection on movement.

## Ford session record

Record all of the following:

```text
phone model / Android version
Android Auto version
Ford model/year and SYNC/head-unit version
wired or wireless connection
surface width x height and reported DPI
visible-area bounds / split-screen behavior
reported vehicle-speed status/value and update cadence
portrait/landscape and rotation behavior
onClick/onScroll/onFling/onScale delivery
virtual-display creation/resize/rebind results
```

Useful log/event labels include:

```text
AutoBridgeCarScreen: Surface WIDTHxHEIGHT dpi=DPI
AutoBridgeMirror: Creating car virtual display ...
projection_attached
car_surface_attached
virtual_display_created
virtual_display_resized
car_session_reconnected
```

## Recommended order

1. Test with the vehicle fully parked and the parking brake applied.
2. Connect Android Auto normally and open AutoBridge.
3. Request `CAR_SPEED`; wait for a valid zero sample instead of assuming the gear state.
4. Start capture on the phone and approve fresh system consent.
5. Verify image-only output first; enable touch only after the surface/safety path is stable.
6. Change speed only after the stop-on-movement behavior is observed.
7. Disconnect/reconnect and verify reuse without a second consent dialog.
8. Repeat with wired/wireless modes if both are supported by the vehicle.

## Regression checks

- Starting a new consented projection must not be stopped by an old projection callback.
- A projection/surface replacement must resize/rebind rather than create a second display for the same consent.
- `+0.001` and `-0.001` m/s are `MOVING`.
- Missing/stale/non-success speed is `UNKNOWN`.
- Duplicate speed subscriptions and queued callbacks from a closed lease must not restore `PARKED`.
- Touch in letterbox bars or outside visible bounds must be rejected.
- Unknown MediaSession controllers must not receive the app session.
- Rotation and any temporary force-landscape setting must be restored after every stop/failure path.

## Ford profile update rule

`SurfaceProfile.FORD_NEXT_GEN` currently has the same fallback DPI as `DEFAULT` and `hardwareValidated = false`. Do not change those values based on a single observation or a DHU result. After repeatable Ford measurements, update the profile, document the hardware/SYNC generation, add/adjust a regression guard, and rerun all flavored builds/tests.

No physical Ford session is currently sufficient to claim a stable Ford-specific profile. The remaining validation is hardware/host dependent, not a missing JVM test.
