# Verification — radio-dualscreen-fm (cross-FEAT integration / convergence)

All commands run from the worktree root
`/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/radio-dualscreen-fm`
on branch `radio-dualscreen-fm`. This file records the convergence gate run AFTER all
four FEATs (FEAT-001 domain/shared-state, FEAT-002 phone portrait tuner,
FEAT-003 Android Auto surface, FEAT-004 JVM tests) were committed, to prove the
seams BETWEEN the features hold.

## Commands and outcomes (JVM — executable here)

| Command | Outcome |
| --- | --- |
| `./gradlew testLabDebugUnitTest assembleLabDebug` (primary gate) | BUILD SUCCESSFUL — 111 actionable tasks |
| `./gradlew testLabDebugUnitTest --rerun-tasks` (forced fresh test run) | BUILD SUCCESSFUL in 5m 4s — 74 tasks executed |
| `./gradlew assembleSafeDebug` (NFR1 safe-flavor) | BUILD SUCCESSFUL — safe flavor still builds (all radio code lives in `app/src/main`) |
| `./build-check.sh` (i18n via `scripts/check-i18n.sh`) | OK — locales [en th], 1905 strings each; 60 advisory "still to extract" literals are all pre-existing in unrelated files (no radio file listed), advisory-only, does not fail |

### Aggregate unit-test totals (forced rerun, from `app/build/test-results/testLabDebugUnitTest/`)

**TOTAL: 1119 tests, 0 failures, 0 errors, 0 skipped — across 121 test classes.**

### Radio test classes (per class)

| Test class | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| `dev.autobridge.iptv.RadioBrowserApiTest` (pre-existing — KEPT GREEN) | 7 | 0 | 0 | 0 |
| `dev.autobridge.iptv.RadioStationMatcherTest` (pre-existing — KEPT GREEN) | 13 | 0 | 0 | 0 |
| `dev.autobridge.radio.RadioPlaybackCoordinatorTest` (new JVM) | 9 | 0 | 0 | 0 |
| `dev.autobridge.iptv.RadioPresetsStoreTest` (new JVM) | 9 | 0 | 0 | 0 |
| `dev.autobridge.radio.RadioHistoryMappingTest` (new JVM) | 4 | 0 | 0 | 0 |
| `dev.autobridge.radio.RadioQueueBuildTest` (new JVM) | 4 | 0 | 0 | 0 |

Both pre-existing radio suites (`RadioBrowserApiTest`, `RadioStationMatcherTest`)
remained green through the full integration — confirming no regression from the
four FEATs.

### What the new JVM tests assert

- **Coordinator (switch/play):** switching to an AVAILABLE source flips `active==selected`
  without loading a queue (`playCount == 0`); switching to `HARDWARE_FM` and to `OFFLINE_AUDIO`
  both take the single uniform unavailable branch (only `selectedSource` set, active source +
  audio untouched, `Unavailable` returned); `play(...)` is the one call that reaches the session
  with aligned URIs/titles and a coerced `startIndex`; out-of-range index coerced; blank-URL queue
  rejected; transport commands delegate 1:1.
- **RadioPresetsStore:** save/recall/clear round-trips; slot clamp into 1..6; `HARDWARE_FM` and
  blank-URL rejected with the slot left unchanged; corrupt-JSON and blank-URL slots read back as
  null. Values persist through `core/crypto/SecretPrefs` (NFR2); `SecretPrefs` uses its documented
  in-memory fallback on the JVM (Keystore unavailable) — the expected no-crash path.
- **Favorites/recents mapping:** `Item.toRadioStation()` yields `INTERNET_RADIO`,
  `frequencyKHz==null`, `subtitle==""`; `synthForStore()` builds `IptvSource(M3U, RADIO)` +
  `IptvEntry(LIVE, ALL_CATEGORY_ID, STREAM)`; synthesising an FM/blank-URL station throws
  (FM never persisted).
- **Queue-build:** null `ChannelQueue` → single-item play; non-null → mapped `RadioStation`
  queue with `startIndex` carried through; the `isDead` predicate is honoured (dead channel
  dropped, anchor realigns — proving it is not `{ false }`); a dead anchor is still kept.

## The 5 corrections — confirmed surviving integration

| # | Correction | Evidence in integrated source |
| --- | --- | --- |
| C1 | `around`/`aroundItems` return a **nullable** `ChannelQueue?` | `IptvChannelQueue.kt:32,42,46,73` — all return `ChannelQueue?` |
| C2 | `playPlaylist(uris, startIndex, titles)` takes **parallel** string lists | `MediaPlaybackClient.kt:25` (iface) + `:166` (impl) |
| C3 | `RadioPresetsStore` persists through `core/crypto/SecretPrefs`; no privileged tuner APIs | `RadioPresetsStore.kt:4,50,57,61`; grep for `RADIO_TUNER`/`RadioManager`/`RadioTuner` = none |
| C4 | `HardwareFmProbe.isTunerAccessible(ctx) = false`, read by `HardwareFmSource` | `HardwareFmProbe.kt:24`, `HardwareFmSource.kt:15` |
| C5 | Dead-stream predicate: phone `IptvHealthStore.broken(url) != null`, car `CarIptvCheck::isDead` — never `{ false }` | phone `RadioRepository.kt:184`; car `CarRadioSearchScreen.kt:201` |

## Hardware-FM limitation (FR6 / final verdict)

**FM is NOT ACCESSIBLE.** There is no public or supported Android API that lets this
phone application tune or read a hardware FM tuner — whether that tuner lives in the
phone or (more commonly) in the car head unit. Accordingly, the integrated code:

- Keeps `HardwareFmProbe.isTunerAccessible` returning `false` on every device.
- Never calls `@SystemApi` `RadioManager`/`RadioTuner`, never requests privileged
  permissions, and never touches `MediaRecorder.AudioSource.RADIO_TUNER` (verified by grep — no hits).
- Does NOT claim the phone app can control a head-unit tuner.
- Disables FM controls and shows an accurate unavailable state while **Internet Radio
  remains fully functional**.
- Does not start a second playback engine or a second audio-focus owner — a single
  Media3 session drives both phone and Android Auto.

Full investigation: `.agents/tasks/radio-dualscreen-fm/fm-feasibility.md`.
In-repo summary for users/maintainers: `.agents/tasks/radio-dualscreen-fm/radio-fm-limitation.md`.

## NOT executable here (device / DHU / emulator — out of scope for this JVM gate)

These items from the task's TESTING section require a running device, emulator, or the
Desktop Head Unit (DHU) and were therefore NOT run in this JVM-only convergence step.
They are explicitly reported as not-executable here and remain for a human/CI run with
hardware or an emulator image:

- Internet Radio playback on a physical/emulated phone (manual on-device).
- FM hardware test — not applicable: no accessible tuner; verified unavailable in code and unit tests.
- Background playback.
- Android Auto with the Desktop Head Unit (DHU).
- Phone/car synchronization (shared Media3 session, observed live on-device).
- Presets and Favorites on-device UI interaction.
- Network-disconnection behaviour on-device.
- Dialogs and UI focus on-device.

The architecture, state machine, persistence, queue-building, and source-switching
discipline are all covered at the JVM level by the unit tests above.
