# OBD / Diagnostics ("FORScan-like") plan

Status (2026-11-05): Phase 0–1 written, compiles on safe+personal, `:obd` unit tests pass. NOT yet tried on a real adapter or car. Target vehicle (user, 2026-11-05): Ford Next Gen 2020 — NOT the Honda BR-V this plan first assumed; Honda profile work is dropped, Ford profile first. Also in: BLE transport (auto-detected serial characteristics) + BLE scan, Ford module targets in the scan (community-documented addresses, unverified), foreground ObdService, CSV recording/export, read-only UDS module scan (7E0–7E7, identification DIDs only; no Honda profile yet), last-adapter first. Deferred from the plan: BLE and Wi-Fi transports, CSV logging/export, feeding fuel/trip, car-surface gauges.

## 0. Where we start

- `speed/ObdSpeedSource.kt` is an empty stub (PID 0x0D). `SpeedManager` already has the OBD slot in its fallback order (CAR > GPS > OBD).
- No Bluetooth scanning/socket code exists; the manifest has `BLUETOOTH_CONNECT` only.
- Related features that can consume OBD data later: `trip`, `fuel`, `maintenance`, `mycar`, `speed`, `safety/VehicleStateProvider`.
- Flavors: `safe`, `personal`, `lab`. `scripts/check-protected.sh` guards the safety/bypass files — OBD must not touch them.

## 1. Honest scope: what "like FORScan" can mean

| Tier | Function | Hardware needed | Risk |
|---|---|---|---|
| A. Generic OBD-II | live PIDs, DTC read/clear, freeze frame, readiness, VIN | any ELM327 (BT/BLE/Wi-Fi) | low |
| B. Manufacturer read | module scan (ECU/ABS/SRS/BCM...), manufacturer DTCs, extra PIDs via UDS 0x22/0x19 | good ELM327 clone or STN-based (OBDLink, vLinker) | low |
| C. Service functions | DPF regen, EPB service, battery reset, oil-service reset, bleeding (UDS 0x31 routines) | as B | medium |
| D. Coding / config write | change module options (UDS 0x2E, 0x27 security access) — the real FORScan feature | STN/vLinker-class adapter, stable 12 V | **high: can brick a module** |

Constraints to tell the user up front:
- FORScan is Ford/Mazda-specific, built on years of reverse-engineered Ford module data. There is no universal "coding database". Each brand needs its own profile (module addresses, DIDs, seed-key algorithms).
- The real car is a Ford (Next Gen, 2020 per the user). FORScan's Ford data is the model here, but Ford MS-CAN modules need an adapter with the MS/HS switch (OBDLink EX, vLinker FS), and this project has verified no Ford address or DID yet. Tier D only for profiles verified on that car.
- Cheap ELM327 v2.1 clones fail on UDS/multi-frame and some cannot switch CAN speed — Tier B+ should require a known-good adapter and show an adapter-capability check.
- The test car has no Android Auto (see memory), so everything is tested on the phone; car-screen parts only on DHU.

## 2. Architecture

New Gradle module **`:obd`** (pure Kotlin/JVM-friendly, no Android UI) so the protocol is unit-testable without a car:

```
:obd
  transport/   ObdTransport (interface) — BtClassicSpp, BleGatt, WifiTcp, FakeTransport (tests)
  elm/         ElmSession: AT init, protocol auto-detect (ISO 15765/14230/9141/J1850), timeouts, echo/space/header handling, multi-frame
  obd2/        Mode 01/02/03/04/07/09/0A codecs, PID table + formulas, supported-PID bitmaps
  uds/         UDS client: 0x10 0x11 0x14 0x19 0x22 0x2E 0x27 0x31 0x3E, NRC handling, ISO-TP
  profile/     per-brand/vehicle profiles (JSON assets): module list, DIDs, extra PIDs, DTC text
  dtc/         DTC database (generic P/B/C/U + manufacturer), EN + TH text
  log/         session recorder, CSV export
```

In `:app` (package `dev.autobridge.obd`):
- `ObdService` (foreground, `connectedDevice` type) owns the connection; `ObdRepository` exposes `StateFlow` of connection state, live values, DTCs.
- `ObdSpeedSource` becomes real (reads PID 0x0D from the repository).
- UI = **separate `ObdActivity`** (like `MaintenanceActivity`) reachable from the phone home menu — not buried in an existing screen. It stays a dedicated area because it needs its own permissions, connection state and warnings.
- Car side (phase 5): read-only `ListTemplate`/gauge screen, parked or not — no writes ever from the car surface.

## 3. Phases

### Phase 0 — Transport + ELM session (foundation)
- Permissions: `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT`, legacy BT perms ≤ API 30, `ACCESS_FINE_LOCATION` only if BLE scan needs it; Wi-Fi adapter via TCP 192.168.0.10:35000.
- Pair/pick adapter screen, remember last adapter, auto-reconnect.
- `ElmSession`: `ATZ ATE0 ATL0 ATS0 ATH1 ATSP0`, adapter identification (ELM327 v1.5/2.1, STN, vLinker), capability report.
- `FakeTransport` + recorded ELM transcripts for unit tests.
- Done when: connects to a real adapter, prints supported protocol + VIN on the phone.

### Phase 1 — Generic OBD-II (Tier A)
- Mode 01 live data with a PID picker, update-rate scheduler (fast PIDs more often), gauges + graphs.
- Mode 03/07/0A read DTCs, Mode 02 freeze frame, Mode 04 clear DTCs (confirm dialog, warns about readiness reset), Mode 01 PID 01 readiness/MIL, Mode 09 VIN/CAL ID.
- DTC database EN/TH (bundled asset, source: open DTC lists; license check required before bundling).
- Wire `ObdSpeedSource`; optionally feed `fuel`/`trip` (fuel rate, MAF-based consumption) and `mycar`.
- Session logging + CSV export.
- Done when: matches a reference app (Torque/Car Scanner) on the BR-V for the same PIDs/DTCs.

### Phase 2 — Module scan + manufacturer read (Tier B)
- UDS/KWP discovery across module addresses, read DIDs (0x22), DTC by status mask (0x19), `ECU info` page (part number, SW/HW version).
- Profile format (JSON): module name, request/response CAN IDs, DID list with scaling, DTC text. Start with Honda profile (for the BR-V) and one more brand to prove the format is generic.
- Guard rails: read-only; rate limiting; 0x3E tester-present; stop on repeated NRC 0x78/0x22.

### Phase 3 — Service functions (Tier C)
- Whitelisted routines per profile (oil reset, battery registration, EPB, DPF where applicable). Each requires: ignition state check, battery voltage ≥ threshold, explicit confirm, full log.

### Phase 4 — Coding / write (Tier D) — gated hard
- Only for profiles marked `verified: true` with a documented rollback.
- Mandatory flow: connect → read module identity → **backup current config to file** → show diff → typed confirmation → voltage/ignition check → write (0x2E) → read-back verify → on mismatch offer restore.
- Seed-key (0x27) algorithms live in the profile layer, never invented — if unknown, feature is simply unavailable.
- Build gating: Tier C/D compiled into `personal`/`lab` only; `safe` flavor ships Tier A/B read-only (same pattern as the existing Multi-app/Duo Screen split). Parked/engine-off precondition is checked in the OBD layer itself, independent of `FeaturePolicy`.
- Do not modify `safety/*` or the bypass files; reuse `VehicleStateProvider` read-only.

### Phase 5 — Car screen + polish
- Read-only live gauges and DTC list on the Android Auto surface (DHU-testable using `FakeTransport`).
- Alerts (coolant temp, battery voltage, new DTC) via the existing notification path.

## 4. Risks and decisions

- **Bricking**: only Phase 4 can do it; hence backup-first, verified-profile-only, and no generic "write any DID" UI (maybe a lab-only expert console, off by default).
- **Adapter quality**: detect clones and refuse Tier B+ with a clear message instead of failing mid-write.
- **Legal/ethics**: coding that disables emissions/safety systems is out of scope; no mileage/odometer functions.
- **Data licensing**: DTC text and brand profiles must come from sources we may redistribute, or be user-importable files.
- **Testing**: protocol layer unit-tested via transcripts; phone UI manual with a real ELM327; car UI on DHU; writes only on a bench ECU or a car the owner agrees to risk.

## 5. Questions for the user (needed before Phase 2+)

1. Which vehicle(s) matter most — BR-V only, or others (Ford/Mazda/Toyota...)? This decides the first profile.
2. Which adapter do you own (ELM327 BT clone, vLinker, OBDLink, Wi-Fi)?
3. Is Tier D (coding) wanted at all, or stop at Tier A–C?
4. OK to keep it as its own `ObdActivity` + `:obd` module (recommended) versus a menu inside the existing screens?
