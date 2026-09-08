# LAB mode

Updated: 2026-09-08

LAB mode is a controlled development surface for emulator/DHU and test-bench work. It is not a mechanism for falsifying a real car's speed or bypassing the parked-only safety restriction.

## Enablement gate

`DevModeEvaluator.isEnabled` returns true only when:

```text
BuildConfig.DEV_MODE == true
AND deviceIsEmulator == true
AND mode == LAB
AND environment != REAL_CAR
```

The Gradle `lab` flavor supplies `AUTOBRIDGE_MODE = "LAB"`. Debug builds set `DEV_MODE = true`; release builds set it to false. The emulator check is independent of the flavor, so a `labDebug` APK installed on a physical phone does not activate the mock provider.

## Environment model

`EnvironmentDetector` automatically identifies common emulator fingerprints as `EMULATOR`; otherwise the safe default is `REAL_CAR`. In LAB, the phone UI can explicitly select `DHU`, `TEST_BENCH`, `EMULATOR`, or reset to `REAL_CAR`.

The selection is a policy/context label, not a speed override. `RuntimeContextStore.setEnvironment` refuses non-real environment changes outside LAB. It also refuses simulated connection/vehicle updates whenever the current environment is `REAL_CAR`.

Changing the LAB environment is blocked while an Android Auto session/surface is active, preventing a live production provider from being silently replaced during a session.

## Mock provider behavior

When the gate is active, `VehicleStateProviderFactory` chooses `MockVehicleStateProvider` instead of `SpeedGate`. The mock starts at `PARKED` and exposes explicit `PARKED`, `MOVING`, and `UNKNOWN` transitions. Every transition still updates `ParkingStateStore` and `RuntimeContextStore`.

`MOVING` invokes the same movement callback used by the production provider: projection is stopped and the car app is exited. `UNKNOWN` blocks parked-only features. The mock does not rewrite the speed value, grant `CAR_SPEED`, or make a real vehicle appear parked.

`VehicleStateSession` registers the car-screen lease before starting a provider, so an initially unsafe simulator state cannot arrive before the screen has a callback. It shares one provider across dashboard/mirror screens and stops it after the final lease closes.

## Emulator/DHU flow

1. Build/install `labDebug` on an Android emulator.
2. Start the Desktop Head Unit and connect it to the emulator using the local Android Auto/DHU setup.
3. Open AutoBridge in the DHU launcher.
4. Use the phone LAB controls to select a non-`REAL_CAR` environment and set `PARKED`.
5. Grant MediaProjection consent on the phone and keep the Android Auto surface open.
6. Verify `ProjectionService` becomes `READY`, `car_surface_attached`/`virtual_display_created` appear, and pixels are visible.
7. Exercise `MOVING`: projection must stop and the car screen must exit.
8. Exercise `UNKNOWN`: mirror, video, browser, touch, and Quick App launch must be denied.
9. Exercise `PARKED`: only then validate touch backends, app launching, and video rendering.

A DHU build that cannot provide a valid `CAR_SPEED` sample can still be used with the emulator-only LAB provider. That proves the application safety transitions and render wiring, not the host's production `CAR_SPEED` integration.

## What LAB does not prove

- It does not prove Ford surface dimensions, DPI, wireless Android Auto behavior, or host template acceptance.
- It does not prove production `CAR_SPEED` cadence or permission delivery.
- It does not grant Accessibility, Shizuku, MediaProjection, `WRITE_SETTINGS`, or MediaSession permissions.
- It does not make a physical debug phone safe to use in a moving vehicle.
- It does not enable target-app fullscreen, screen-off-surviving AUTO_MIRROR, FILL/STRETCH output, or raw multi-touch.

## Evidence to collect

Use the developer screen and structured logs for mode/environment/state, projection state, surface state, reconnect count, input backend, and mirror events. Keep screenshots and Logcat for each scenario. Mark a feature `PARTIAL` until a real device/host run validates the platform-specific part; unit tests only prove the pure decision and mapping logic.
