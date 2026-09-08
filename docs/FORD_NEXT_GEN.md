# Ford Next Gen profile

Updated: 2026-09-08

`SurfaceProfile.FORD_NEXT_GEN` is an extension point, not a claim that AutoBridge has been tuned on Ford hardware. Android Auto does not expose a reliable API for the projected app to identify the OEM head unit, so profile selection is manual and persisted.

## Current values

| Profile | Fallback DPI | Hardware flag |
|---|---:|---|
| `DEFAULT` | 160 | validated as the generic software/default profile |
| `FORD_NEXT_GEN` | 160 | `hardwareValidated = false` |

The Ford value currently equals the default because no measured Ford session has supplied a different value. `fallbackDpi` is used only when the `SurfaceContainer` reports an invalid/zero DPI; it does not override a valid host-reported value.

The phone UI can switch the active profile. `SettingsStore` persists it, and the UI must continue to display the unverified label until real data is recorded. Do not infer a Ford resolution from marketing material or invent DPI values.

## Measurement procedure

Run the generic DHU checklist first, then repeat on each Ford/SYNC/head-unit combination:

1. Record phone model, Android version, Android Auto version, Ford model/year, SYNC/head-unit version, and wired/wireless connection.
2. Install the same `personalDebug` or release candidate APK without changing the source between runs.
3. Open AutoBridge from the host launcher while fully parked.
4. Grant `CAR_SPEED` and confirm a valid zero-speed sample; do not use a render test to claim speed compatibility.
5. Grant MediaProjection consent and record the complete `AutoBridgeCarScreen` surface dimensions and DPI.
6. Record `onVisibleAreaChanged` bounds, split-screen behavior, rotation behavior, input callback delivery, and whether `VirtualDisplay` creation succeeds.
7. Repeat after disconnect/reconnect and after a surface resize if the host supports it.
8. Save Logcat and screenshots with the hardware metadata.

Useful log/event evidence includes:

```text
AutoBridgeCarScreen: Surface WIDTHxHEIGHT dpi=DPI
AutoBridgeMirror: Creating car virtual display ...
car_surface_attached
virtual_display_created
car_session_reconnected
```

## Updating the profile

Only after repeatable measurements:

1. update the specific field in `SurfaceProfile.FORD_NEXT_GEN`;
2. document the Ford/SYNC hardware and sample values here;
3. set `hardwareValidated = true` only for the values actually measured;
4. add or update a pure regression assertion so placeholder values cannot silently be presented as validated;
5. rerun all flavored tests and build tasks.

If multiple Ford generations disagree, add separate explicit profiles rather than making one broad label silently cover them.

## Current status and blockers

Implemented: manual profile selection, persisted profile state, fallback-DPI mechanism, unverified UI label, and test guard. Not implemented: automatic Ford detection, Ford-specific geometry values, or a claim of production compatibility. A DHU surface is useful for protocol/template validation but is not evidence of Ford behavior; see [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md).
