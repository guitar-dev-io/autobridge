# Desktop Browser roadmap (multi-browser)

Each phase ends with something checkable. Nothing starts until docs 11–13 are reviewed.
Personal and lab flavors only, arm64-v8a only. The embedded browser stays the default throughout.

## Phase 0 — Review (now)
- Review docs 11–13 against docs 01–10 (not in this repo yet).
- Decide the X server path (Xvfb + frame pump in the rootfs, or a separate GPL companion app).
- Confirm: one active browser at a time by default, Brave first.

## Phase 1 — Shared runtime prototype (LAB only, no browser yet)
- PRoot built from source and packaged as `libproot.so` / `libproot-loader.so`.
- Rootfs download → SHA-256 check → staged extract. `apt-get update` works under PRoot.
- X display + one test client (`xeyes`/`xterm`) shown on the DHU through the rendering bridge.
  Touch reaches it via XTest. Pulse plays a test tone through AutoBridge audio focus.
- **Exit:** a test X client is drawn and controllable on the DHU, and the runtime survives car surface churn.

## Phase 2 — Brave adapter
- `DesktopBrowserAdapter` interface, `BraveAdapter`, managed policy, profile and cache dirs.
- Install / launch / stop / clear cache / reset profile / remove for Brave.
- Device tests T1–T5, T8 from doc 12 for Brave. T6 (Widevine) is run here for **both** vendors' CDMs,
  to confirm or reverse the order.
- **Exit:** Brave browses and plays 720p video on the DHU while parked, stops cleanly on `MOVING`,
  and removing it leaves the runtime intact.

## Phase 3 — Browser Manager UI
- Default browser selection (Embedded / Brave / Chrome), versions, storage per browser,
  diagnostics, Repair. Fallback to the embedded browser on crash loop or missing runtime.
- **Exit:** every operation in doc 13's table works for Brave from the phone UI.

## Phase 4 — Chrome adapter
- `ChromeAdapter`, Google key pinning, ToS acceptance screen, Chrome policy file.
- Device tests T1–T6 for Chrome. T7 (run both, stop one) for the pair.
- **Exit:** Chrome and Brave installed together and switching works. Removing either one leaves the other
  working, and storage shows the runtime counted once.

## Phase 5 — Updates and hardening
- Per-browser update and rollback, separate runtime update, low-memory handling (T9),
  log rotation, backup exclusion.
- Optional LAB experiments: GPU (virgl/Turnip), a third adapter (Debian `chromium`).

## Not planned
- Bundling or mirroring any browser binary or rootfs.
- Anything from VelvetLauncher.
- The safe (Play) flavor, or 32-bit devices.
- Video or interactive browsing while the car is moving.
