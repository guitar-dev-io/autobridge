# Hardware FM radio: not accessible (FR6.5)

## Statement

The AutoBridge phone app **cannot** tune or play a hardware FM radio tuner, and it does not try to.
This is a final, deliberate decision, not a missing feature:

- There is **no public, supported Android API** that lets an ordinary app tune FM frequencies or
  read FM tuner audio on this target (a phone driving a head unit over Android Auto / Duo Screen /
  projection).
- Where an FM tuner exists it lives either in the phone behind a vendor-private, privileged service,
  or in the car head unit, which Android Auto exposes **no** tuner-control API for. The phone app
  **does not and cannot** control a head-unit tuner.
- The app uses **no privileged or `@SystemApi` interfaces** for FM. It never calls
  `android.hardware.radio.RadioManager` / `RadioTuner`, never requests `ACCESS_BROADCAST_RADIO` or
  any FM permission, and never uses `MediaRecorder.AudioSource.RADIO_TUNER`.

The full feasibility investigation (device locations, platform APIs, evidence) is in
[`fm-feasibility.md`](./fm-feasibility.md) (in this task directory).

## How this is enforced in code

- `dev.autobridge.radio.HardwareFmProbe.isTunerAccessible(context)` is the single, documented,
  honest seam and returns **`false`** on this target. It makes none of the privileged calls above.
  If a future privileged/system build ever gained a legitimate tuner, only this probe would change.
- `HardwareFmSource.isAvailable(context)` delegates to the probe (so it is `false`), and
  `unavailableReason(context)` returns the localized "FM radio is not available on this device."
  string (`R.string.radio_fm_unavailable`, EN + TH).
- The `RadioSourceType.HARDWARE_FM` type is kept so the three-source model is honest and complete,
  but it is never active. `RadioPlaybackCoordinator.switchSource(..., HARDWARE_FM)` takes the uniform
  **unavailable** branch: it records the user's selection, leaves the active source and audio
  untouched, and returns `SwitchResult.Unavailable(reason)` — it never loads a queue.
- FM is **never persisted**. `RadioPresetsStore.save(..)` rejects a `HARDWARE_FM` (or blank-URL)
  station and leaves the slot unchanged, and `RadioRepository.RadioHistory` refuses to synthesise a
  store entry for an FM/blank-URL station (it throws before touching the store). No frequency is
  ever invented for an internet station (`RadioStation.frequencyKHz` is always `null` here).

## What the user sees

- **Phone:** the source selector shows an FM segment that is visibly disabled; selecting it shows the
  accurate unavailable caption while Internet Radio keeps playing. No FM frequency scale is drawn.
- **Android Auto:** FM is a `MessageTemplate` that states "FM radio is not available on this device"
  and never offers a tuning control.
- **Internet Radio remains fully functional** on both surfaces, which is the working radio
  experience the app ships.

## Unit-test coverage of the limitation

- `RadioPlaybackCoordinatorTest` — switching to `HARDWARE_FM` (and to the unwired `OFFLINE_AUDIO`)
  takes the uniform unavailable branch, flips only the selected source, and loads no audio.
- `RadioPresetsStoreTest` — saving a `HARDWARE_FM`/blank-URL station is rejected and leaves the slot
  unchanged.
- `RadioHistoryMappingTest` — synthesising a store entry for an FM/blank-URL station throws, so FM
  is never written to favourites/recents.
