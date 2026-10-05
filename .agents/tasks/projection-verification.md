# Projection fix — verification note

Scope: make the CATEGORY_PROJECTION entry ("AutoBridge Browser",
`dev.autobridge/.projection.ProjectionCarService`) appear in the Android Auto launcher **in
addition to** the two NAVIGATION entries ("AutoBridge" `.car.AutoBridgeCarAppService`, "Duo
Screen" `.duoscreen.DuoScreenCarAppService`). Change is manifest/config only.

Iteration: second iteration — `.agents/tasks/projection-review.json` exists with verdict
`CHANGES_REQUESTED`. This note was revised to correct the scope claims the reviewer flagged
(findings 2 and 3) and to re-record the re-run verification gates.

## The projection fix proper (the two plan files)

These are the only edits that implement the projection fix. They are minimal and config-only:

1. `app/build.gradle.kts` — one line: `res.srcDir("src/projection/res")` wired into the
   `personal`/`lab` source sets (next to the existing `src/projection/java` + manifest wiring) so a
   flavor-scoped resource override resolves. Mirrors how `src/duoscreen/res` was already wired.
   (This file also carries unrelated in-progress changes — see "Scope" below.)
2. `app/src/projection/res/xml/automotive_app_desc.xml` — NEW flavor-scoped override of the main
   car descriptor for `personal`/`lab` only. Declares `template` + `media` (unchanged roles that
   keep both NAVIGATION services and the media card working) PLUS `projection` (the role that makes
   Android Auto's OEMAppProvider surface the CATEGORY_PROJECTION service).

Intended untouched by the projection fix: `app/src/main/res/xml/automotive_app_desc.xml`
(safe/Play flavor keeps `template`+`media` only, no projection role — it ships no projection
service). The `com.google.android.gms.car.application` meta-data pointer in
`app/src/main/AndroidManifest.xml` is unchanged, so the descriptor discovery path is still wired.
No NAVIGATION service removed or altered.

## Scope correction (reviewer findings 2 and 3)

The earlier version of this note claimed "2 files, config-only." That understated the working
tree. Correction: the working tree (branch `projection-route`) contains **50 tracked modified
files and 43 untracked files** of in-progress work beyond the projection fix. In particular, two
files the plan said to leave unchanged ARE modified, by work unrelated to the projection fix:

- `app/src/projection/AndroidManifest.xml` — adds the Duo Screen `CarAppService`
  (`.duoscreen.DuoScreenCarAppService`, NAVIGATION), `DuoScreenSettingsActivity`,
  `DuoScreenSpikeActivity`, and `QUERY_ALL_PACKAGES`. This is **Duo Screen feature work**, not the
  projection descriptor fix. (The projection fix's success criteria do depend on the Duo Screen
  NAVIGATION service existing, since the task requires both NAVIGATION entries to remain, but the
  descriptor fix did not author these entries.)
- `app/src/main/AndroidManifest.xml` — launcher icon swap only
  (`@drawable/ic_autobridge_launcher` → `@mipmap/ic_launcher` for `android:icon` and
  `android:roundIcon`). This is **icon work**, not the projection fix. The car-application
  meta-data pointer is untouched.

These edits appear intentional and belong to the broader Duo Screen / icon effort already in the
tree; they are not reverted here because reverting the Duo Screen service would drop a NAVIGATION
entry the task requires to keep working, and the icon swap is unrelated. The projection fix itself
remains confined to the two files above.

Blocking scope issue (reviewer finding 1): the projection fix cannot be committed, reviewed, or
reverted in isolation while entangled with this larger uncommitted change set, and the task
constraint for this step is "Do NOT commit anything." Resolving this (isolate onto its own
commit/branch vs. ship the whole tree as one unit) is a release decision escalated to the user via
`send_message`; it is not resolved inside this step.

## Flavor-scoped override confirmed in build intermediates

`app/build/intermediates/packaged_res/personalDebug/.../xml/automotive_app_desc.xml` (projection
variant):

```xml
<automotiveApp xmlns:tools="http://schemas.android.com/tools">
    <uses name="template" />
    <uses name="media" />
    <uses name="projection" tools:ignore="InvalidUsesTagAttribute" />
</automotiveApp>
```

`app/build/intermediates/packaged_res/safeDebug/.../xml/automotive_app_desc.xml` (Play variant,
unchanged — no projection role):

```xml
<automotiveApp>
    <uses name="template" />
    <uses name="media" />
</automotiveApp>
```

So `projection` is confined to the sideload flavors; the Play flavor is unaffected.

## Verification steps run and results

### 1. `./gradlew :app:assemblePersonalDebug` — PASS
`BUILD SUCCESSFUL`. APK produced (`app-personal-debug.apk`).

### 2. `./gradlew :app:testPersonalDebugUnitTest` — PASS
`BUILD SUCCESSFUL`. All unit tests pass (regression guard; no test references
`automotive_app_desc`).

### 3. `./gradlew :app:installPersonalDebug` (serial YXEMRCGYAI49S4SS) — PASS
Device reachable (`adb devices` → `YXEMRCGYAI49S4SS device`).
`Installing APK 'app-personal-debug.apk' ... Installed on 1 device. BUILD SUCCESSFUL`.

### 4. Merged on-device manifest — PASS (re-run this iteration)
`adb -s YXEMRCGYAI49S4SS shell dumpsys package dev.autobridge` — relevant lines
(leading whitespace trimmed; filter hashes differ from the prior run because the APK was
reinstalled, content is identical):

```
androidx.car.app.CarAppService:
  6b16c37 dev.autobridge/.duoscreen.DuoScreenCarAppService filter 20e05a4
    Action: "androidx.car.app.CarAppService"
    Category: "androidx.car.app.category.NAVIGATION"
  4bc0f09 dev.autobridge/.car.AutoBridgeCarAppService filter a9d550e
    Action: "androidx.car.app.CarAppService"
    Category: "androidx.car.app.category.NAVIGATION"
...
  7c656d1 dev.autobridge/.projection.ProjectionCarService filter 48cd536
    Action: "android.intent.action.MAIN"
    Category: "com.google.android.gms.car.category.CATEGORY_PROJECTION"
    Category: "com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM"
```

Confirmed: `.projection.ProjectionCarService` carries both CATEGORY_PROJECTION categories AND both
NAVIGATION services remain registered. The fix removed none of them.

### 5. OEMAppProvider head-unit discovery — NOT CONFIRMED ON HEAD UNIT (expected; must be done by the user)
`adb -s YXEMRCGYAI49S4SS logcat -d | grep OEMAppProvider.getApps` returned **no lines** again this
iteration — the current logcat buffer contains no fresh gearhead app-enumeration since install (no
active head-unit / Android Auto projection session re-enumerated apps after the install).

Per the task contract, this note does NOT claim the projection entry appears, because the
OEMAppProvider log does not yet show it. The authoritative on-head-unit confirmation remains for
the user to perform:

1. Connect the phone (serial YXEMRCGYAI49S4SS) to the Android Auto head unit (or reconnect /
   toggle the connection) so gearhead re-enumerates apps.
2. Run:
   ```
   adb logcat -d | grep OEMAppProvider.getApps
   ```
   Expect `dev.autobridge/.projection.ProjectionCarService` (equivalently the "AutoBridge Browser"
   entry) to appear in that app list alongside the other projection apps (e.g. ScreenOnAuto,
   Fermata).
3. On the head unit, confirm "AutoBridge Browser" appears and launches into the split layout (app
   in the main area, Google Maps shrunk to the side panel), and that "AutoBridge" and "Duo Screen"
   still appear and still work.

Contingency (only if "AutoBridge Browser" still does not appear after reconnect): add
`<uses name="service" />` and `<uses name="notification" />` to
`app/src/projection/res/xml/automotive_app_desc.xml` (the two other roles the SDK README lists),
reinstall, and repeat the OEMAppProvider check.

DHU note: DHU cannot reproduce this (DHU 2.0 incompatible with Android Auto 17.7.x per
`docs/DHU.md`), so the final proof is the real phone + head unit as above.

## Summary of gates

- Build (assemblePersonalDebug): PASS
- Unit tests (testPersonalDebugUnitTest): PASS
- Install (installPersonalDebug @ YXEMRCGYAI49S4SS): PASS
- On-device manifest (dumpsys): PASS — projection service + both NAVIGATION services present
- Head-unit OEMAppProvider discovery: PENDING user confirmation (no active session captured; not
  claimed as passing)

No commit was made — this step's constraint is "Do NOT commit anything" (caller handles release).

## Reviewer finding 1 (scope) — RESOLVED by user decision

The projection fix lives in a large uncommitted change set (Duo Screen feature, label change,
compositor crash fix, projection fix, icon swap, version/lint/dependency changes; 50 tracked + 43
untracked files). The user decided (option A) that this is all intentional, continuous work from
the same session and is meant to ship together as one unit — nothing needs to be isolated onto its
own commit/branch. Agreement recorded here per that decision; finding 1 is resolved. Nothing is
committed (step constraint: do not commit anything). No files were reverted. The projection fix
itself is confirmed correct and verified at the build / unit-test / install / dumpsys level; the
head-unit OEMAppProvider confirmation remains the user step documented in section 5.
