# Verification — radio-dualscreen-fm (FEAT-004 testing phase)

All commands run from the worktree root
`/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/radio-dualscreen-fm`.

## Commands and outcomes (JVM — executable here)

| Command | Outcome |
| --- | --- |
| `./gradlew :app:testLabDebugUnitTest` | BUILD SUCCESSFUL — all radio unit tests pass |
| `./gradlew testLabDebugUnitTest assembleLabDebug` | BUILD SUCCESSFUL (primary gate) |
| `./gradlew assembleSafeDebug` | BUILD SUCCESSFUL (NFR1 safe-flavor builds) |
| `./build-check.sh` | OK — locales [en th], 1905 strings each (no new strings added) |

### Unit-test results (per class, from `app/build/test-results/testLabDebugUnitTest/`)

| Test class | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| `dev.autobridge.radio.RadioPlaybackCoordinatorTest` | 9 | 0 | 0 | 0 |
| `dev.autobridge.iptv.RadioPresetsStoreTest` | 9 | 0 | 0 | 0 |
| `dev.autobridge.radio.RadioHistoryMappingTest` | 4 | 0 | 0 | 0 |
| `dev.autobridge.radio.RadioQueueBuildTest` | 4 | 0 | 0 | 0 |
| `dev.autobridge.iptv.RadioBrowserApiTest` (pre-existing, kept green) | 7 | 0 | 0 | 0 |
| `dev.autobridge.iptv.RadioStationMatcherTest` (pre-existing, kept green) | 13 | 0 | 0 | 0 |

### What the new tests assert

- **Coordinator (switch/play):** switch to an AVAILABLE source flips active==selected without
  loading a queue (`playCount == 0`); switch to `HARDWARE_FM` and to `OFFLINE_AUDIO` both take the
  single uniform unavailable branch (only `selectedSource` set, active source + audio untouched,
  `Unavailable` returned); `play(...)` is the one call that reaches the session with aligned
  URIs/titles and a coerced `startIndex`; out-of-range index coerced; blank-URL queue rejected;
  transport commands delegate 1:1.
- **RadioPresetsStore:** save/recall/clear round-trips; slot clamp into 1..6; `HARDWARE_FM` and
  blank-URL rejected with the slot left unchanged; corrupt-JSON and blank-URL slots read back as
  null. Exercised through a self-contained in-memory `SharedPreferences`; `SecretPrefs` keeps values
  in its documented in-memory fallback because the Keystore is unavailable on the JVM (the expected
  no-crash path).
- **Favorites/recents mapping:** `Item.toRadioStation()` yields `INTERNET_RADIO`, `frequencyKHz==null`,
  `subtitle==""`; `synthForStore()` builds `IptvSource(M3U, RADIO)` + `IptvEntry(LIVE,
  ALL_CATEGORY_ID, STREAM)`; synthesising an FM/blank-URL station throws (FM never persisted).
- **Queue-build:** null `ChannelQueue` → single-item play; non-null → mapped `RadioStation` queue
  with `startIndex` carried through; the `isDead` predicate is honoured (a dead channel is dropped
  and the anchor realigns, proving it is not `{ false }`); a dead anchor is still kept.

## Test seam introduced (behaviour-preserving)

- Extracted a narrow interface `dev.autobridge.media.RadioTransportClient` (the exact transport the
  coordinator uses: `currentQueueUris`, `playPlaylist`, `pause/resume/next/previous/stop`).
  `MediaPlaybackClient` now implements it (signatures unchanged), and
  `RadioPlaybackCoordinator`'s constructor takes the interface. `RadioRepository` still passes a
  `MediaPlaybackClient`, so runtime behaviour is identical; only the test can now fake the transport.
- `RadioRepository.RadioHistory.synthForStore` changed from `private` to `internal`
  (`@VisibleForTesting`) so the mapping test can assert the built (source, entry) pair. No behaviour
  change.

## Hardware-FM limitation (FR6.5)

Documented in-repo at `.agents/tasks/radio-dualscreen-fm/radio-fm-limitation.md` (summary) with the
full investigation in `.agents/tasks/radio-dualscreen-fm/fm-feasibility.md`. In short: **FM is NOT ACCESSIBLE** — no
public/supported Android API lets this phone app tune or read a hardware FM tuner; FM controls are
disabled with an accurate unavailable state; Internet Radio remains fully functional; no privileged
or `@SystemApi` interfaces are used; the phone app does not and cannot control a head-unit tuner.

## NOT executable here (device/DHU/emulator — explicitly out of scope for FEAT-004)

The following items from the task's TESTING section require a running device/emulator/DHU and were
NOT run in this JVM-only step. They are reported as not-executable here:

- Internet Radio playback on a phone (manual on-device).
- FM hardware test (not applicable — no accessible tuner; verified unavailable in code/tests).
- Background playback.
- Android Auto with the Desktop Head Unit (DHU).
- Phone/car synchronization (shared session, observed live on-device).
- Presets and Favorites on-device UI.
- Network-disconnection behaviour.
- Dialogs and UI focus on-device.

The architecture is covered at the JVM level by the unit tests above; the on-device checks remain for
a human/CI run with hardware or an emulator image.
