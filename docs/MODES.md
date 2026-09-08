# AutoBridge modes

Updated: 2026-09-08

AutoBridge has one shared `src/main` implementation and three Gradle product flavors. The mode is a build-time product identity; it is not a second source tree and it is not a runtime switch that can silently weaken safety.

## Build variants

| Variant | BuildConfig value | Intended use | Policy summary |
|---|---|---|---|
| `safe` | `SAFE` | Conservative media/quick-app baseline | Allows `MEDIA` and parked `QUICK_APPS`; does not enable mirroring, browser, video, touch, or developer features. |
| `personal` | `PERSONAL` | Personal parked Android Auto use | Enables the broad feature set, but every parked-only feature still requires authoritative `PARKED`. Media playback can continue when parked-only rendering is unavailable. |
| `lab` | `LAB` | Emulator/DHU and controlled bench development | Enables the broad feature set and explicit LAB controls, while retaining the same parked-only gates. A real-car environment always uses real vehicle state. |

Build examples:

```bash
./gradlew assembleSafeDebug
./gradlew assemblePersonalDebug
./gradlew assembleLabDebug
```

The corresponding unit-test tasks are `testSafeDebugUnitTest`, `testPersonalDebugUnitTest`, and `testLabDebugUnitTest`.

## Shared runtime state

`RuntimeContextStore` exposes a `StateFlow<RuntimeContext>` shared by phone UI, Android Auto screens, and policy callers. A context contains:

- `AutoBridgeMode`: the immutable flavor mode;
- `Environment`: detected `EMULATOR`/`REAL_CAR`, or an explicit LAB selection such as `DHU` or `TEST_BENCH`;
- `VehicleState`: `PARKED`, `MOVING`, or `UNKNOWN`;
- current connection, vehicle profile, app/feature, display, rotation, audio, and session fields.

`DefaultModeProvider` reads `BuildConfig.AUTOBRIDGE_MODE`. `EnvironmentDetector` treats emulator fingerprints as `EMULATOR`; Android does not expose a reliable projected-host identity, so DHU/test-bench selection is explicit in LAB rather than guessed.

## Safety invariant

Mode changes never manufacture a parked state. `FeaturePolicy` is the feature boundary, and the production `SpeedGate` remains authoritative for a real car:

- finite `speed == 0f` becomes `PARKED`;
- any other finite speed, including creeping or reverse movement, becomes `MOVING`;
- null or non-finite speed becomes `UNKNOWN`;
- `UNKNOWN` blocks rendering, touch, video, browser, and app launching;
- a `MOVING` transition stops projection and exits the car app through the existing callback path.

`LAB` is expansive only in feature selection. `LAB + REAL_CAR` does not override `CAR_SPEED`, and the simulator refuses to write state in that environment.

## LAB-only development gate

The emulator vehicle simulator is enabled only when all of these are true:

```text
BuildConfig.DEV_MODE == true
and device is an Android emulator
and flavor mode == LAB
and environment != REAL_CAR
```

Release builds set `DEV_MODE` to false. A debug build on a physical phone therefore remains on the production provider, even if it is connected to a DHU.

## Choosing a mode

Use `safeDebug` when the desired contract is media plus conservative parked quick apps. Use `personalDebug` for the complete personal feature set against a real speed provider. Use `labDebug` on an emulator for controlled state transitions, surface/mirror development, and diagnostics. Do not use LAB controls as a substitute for a valid vehicle integration test.

## Related documents

- [`FEATURE_POLICY.md`](FEATURE_POLICY.md) — exact feature decisions and parked-only set;
- [`LAB_MODE.md`](LAB_MODE.md) — emulator/DHU simulator setup and boundaries;
- [`MIRROR_ENGINE.md`](MIRROR_ENGINE.md) — projection and surface lifecycle;
- [`INPUT_SYSTEM.md`](INPUT_SYSTEM.md) — coordinate mapping and input backends.
