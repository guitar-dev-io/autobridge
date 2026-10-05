# Implementation Plan — Make the CATEGORY_PROJECTION entry ("AutoBridge Browser") appear in Android Auto

## Root cause

Android Auto's app discovery (gearhead `GH.OEMAppProvider.getApps`) does not list
`dev.autobridge/.projection.ProjectionCarService` even though the service is correctly registered.
`adb shell dumpsys package dev.autobridge` confirms the service carries action
`android.intent.action.MAIN` with categories `...CATEGORY_PROJECTION` and
`...CATEGORY_PROJECTION_OEM` (verified live on YXEMRCGYAI49S4SS), yet the service is never bound
and never appears in the OEMAppProvider list that includes ScreenOnAuto and Fermata.

The deciding factor is the **app-level car descriptor**, not the service's intent-filter. The merged
manifest declares a single pointer to one descriptor:

- `app/src/main/AndroidManifest.xml:` `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />` (in the always-on main manifest, inside `<application>`).
- `app/src/main/res/xml/automotive_app_desc.xml:1-9` declares only:
  ```xml
  <automotiveApp>
      <uses name="template" />
      <uses name="media" />
  </automotiveApp>
  ```

That descriptor is how Android Auto decides which car-app ROLES the package offers. `template`
advertises the Car-App-Library NAVIGATION route (`AutoBridgeCarAppService`,
`DuoScreenCarAppService`) and `media` advertises the media source (`MediaPlaybackService`). Neither
value advertises a **projection** role, so OEMAppProvider classifies the package as a templated +
media app and never surfaces its CATEGORY_PROJECTION service — regardless of the service's own
intent-filter.

The projection service is provided by the unofficial Android Auto SDK in `app/libs/aauto.aar`
(package `com.google.android.apps.auto.sdk`, class `CarActivityService`, confirmed by unzipping the
aar: `classes.jar` contains `com/google/android/apps/auto/sdk/CarActivityService.class`;
`ProjectionCarService.kt` extends it). The canonical manifest wiring for THIS SDK family declares
`projection` in the descriptor. The upstream SDK README documents the required
`res/xml/automotive_app_desc.xml` as:

```xml
<automotiveApp xmlns:tools="http://schemas.android.com/tools">
    <uses name="service" tools:ignore="InvalidUsesTagAttribute" />
    <uses name="projection" tools:ignore="InvalidUsesTagAttribute" />
    <uses name="notification" />
</automotiveApp>
```

Source: [Ronelg/aauto-sdk-1 README](https://github.com/Ronelg/aauto-sdk-1) and
[elmesmoudi/aauto-sdk](https://github.com/elmesmoudi/aauto-sdk) (same `martoreto` aauto-sdk lineage
as the archive Fermata ships). Content was rephrased for compliance with licensing restrictions.

**Comparison to the working apps:** ScreenOnAuto and Fermata expose their CATEGORY_PROJECTION
`CarService` from a descriptor that declares `projection` (that is what the SDK they share
mandates). We expose the same kind of service but our descriptor omits `projection` — so we have
the service but lack the descriptor role that makes gearhead surface it. That is the thing they
have that we lack.

Why this is not merely a "two services in one package" shadowing problem: the NAVIGATION services
and the projection service are discovered through different gearhead providers (androidx car-app
discovery vs. OEMAppProvider). The log evidence shows our NAVIGATION service IS bound while the
projection service is absent from OEMAppProvider's own list — consistent with the descriptor never
advertising the projection role, not with one service suppressing the other. Adding the role is
additive and does not remove or alter the NAVIGATION route.

NEEDS VERIFICATION DURING IMPLEMENTATION: the gearhead discovery behavior cannot be reproduced from
source alone. The fix is confirmed against the SDK's documented contract and the live
`dumpsys`/`logcat` evidence, but the final proof is the on-device OEMAppProvider check in step 4.
Default assumption until then: the user sees a real discovery gap we must close, not an absent one.

## Chosen fix (and why, over the alternatives)

Add `<uses name="projection" />` to the car descriptor **for the projection flavors only**, by
providing a flavor-scoped `res/xml/automotive_app_desc.xml` that overrides the main one in the
`personal`/`lab` builds. The override keeps `template` and `media` (so both NAVIGATION services and
the media card keep working) and adds `projection`.

Candidates weighed:

1. **Edit the always-on `app/src/main/res/xml/automotive_app_desc.xml` to add `projection`.**
   Rejected: the main descriptor is compiled into the `safe` (Play) flavor too, which has NO
   projection service and IS distributed through Google Play. Declaring a `projection` role with no
   backing projection service in a Play build is exactly the kind of mismatch Play review flags,
   and the project rules keep projection/Play strictly separated (`app/build.gradle.kts` comments;
   `src/projection/AndroidManifest.xml` header). This would violate "keep it minimal / do not
   affect the Play flavor."

2. **Flavor-scoped descriptor override (CHOSEN).** A same-named resource in a flavor source set
   overrides the `main` one for that flavor only. The projection source set is already the right
   home for projection-only wiring, and the gradle file already merges `src/projection` and
   `src/duoscreen` resources into `personal`/`lab`. This confines `projection` to the exact flavors
   that ship the service, leaves `safe` untouched, and keeps `template`+`media` so NAVIGATION and
   media are unaffected.

3. **Add a `<meta-data>` to the projection service.** Rejected: the SDK's contract is the
   descriptor `uses`, not a per-service meta-data; the service intent-filter is already correct per
   `dumpsys`. No reference app adds a service meta-data for this.

4. **Remove/relocate a NAVIGATION declaration to stop it shadowing projection.** Rejected: forbidden
   by the task (NAVIGATION must stay) and unsupported by the evidence — the NAVIGATION service binds
   fine; the projection service is missing from a different provider's list, which the descriptor
   role explains.

Note on `service`/`notification`: the SDK README also lists `<uses name="service" />` and
`<uses name="notification" />`. `projection` is the one that governs OEMAppProvider listing and is
the single required addition for this bug. The plan keeps the change minimal (add `projection`
only, alongside the existing `template`+`media`) and flags the other two as optional-if-needed
during the on-device check in step 4.

## Files to edit

- CREATE `app/src/projection/res/xml/automotive_app_desc.xml` (new flavor-scoped override).
- MODIFY `app/build.gradle.kts` — wire `src/projection/res` into the `personal`/`lab` source sets
  so the override is picked up (the projection source set currently wires only `java`, not `res`).
- Leave `app/src/main/res/xml/automotive_app_desc.xml` unchanged (safe/Play flavor keeps
  `template`+`media` only).
- Leave `app/src/main/AndroidManifest.xml` and `app/src/projection/AndroidManifest.xml` unchanged
  (service declarations and NAVIGATION services already correct).

---

## Steps

- [ ] 1. Wire the projection flavor's `res` dir so a flavor-scoped resource override is possible.
      In `app/build.gradle.kts`, inside the `android.sourceSets { listOf("personal", "lab").forEach { flavor -> getByName(flavor) { ... } } }` block, add `res.srcDir("src/projection/res")` next to the existing `src/projection/java` wiring. This mirrors how `src/duoscreen/res` is already wired.
      Files: `app/build.gradle.kts`
      Before:
      ```kotlin
      getByName(flavor) {
          java.srcDir("src/projection/java")
          manifest.srcFile("src/projection/AndroidManifest.xml")
          java.srcDir("src/duoscreen/java")
          res.srcDir("src/duoscreen/res")
      }
      ```
      After:
      ```kotlin
      getByName(flavor) {
          java.srcDir("src/projection/java")
          manifest.srcFile("src/projection/AndroidManifest.xml")
          res.srcDir("src/projection/res")
          java.srcDir("src/duoscreen/java")
          res.srcDir("src/duoscreen/res")
      }
      ```
      Verify: `./gradlew :app:assemblePersonalDebug` configures and builds without a source-set error (full build asserted in step 3; this step is a prerequisite for the resource to resolve).

- [ ] 2. Create the flavor-scoped car descriptor that adds the projection role, keeping template and media.
      This file overrides `app/src/main/res/xml/automotive_app_desc.xml` for the `personal`/`lab` flavors only. The `safe` flavor continues to use the main descriptor unchanged.
      Files: `app/src/projection/res/xml/automotive_app_desc.xml` (new)
      Contents:
      ```xml
      <?xml version="1.0" encoding="utf-8"?>
      <!-- Projection-flavor override of the main car descriptor (personal/lab only; wired via
           src/projection/res in app/build.gradle.kts). Adds the "projection" role so Android Auto's
           OEMAppProvider surfaces dev.autobridge/.projection.ProjectionCarService (the
           CATEGORY_PROJECTION entry "AutoBridge Browser"). The unofficial AA SDK in app/libs/aauto.aar
           requires this role for its CarActivityService to be listed. template + media are kept so the
           NAVIGATION services (AutoBridgeCarAppService, DuoScreenCarAppService) and the media card
           (MediaPlaybackService) keep working exactly as before. The safe (Play) flavor keeps the
           main descriptor with no projection role. -->
      <automotiveApp xmlns:tools="http://schemas.android.com/tools">
          <uses name="template" />
          <uses name="media" />
          <uses name="projection" tools:ignore="InvalidUsesTagAttribute" />
      </automotiveApp>
      ```
      Verify: build in step 3 confirms the resource merges; `dumpsys` in step 4 confirms the descriptor is the projection variant on device.

- [ ] 3. Build and unit-test the personal flavor to confirm nothing regressed.
      Files: none (verification only)
      Verify:
      - `./gradlew :app:assemblePersonalDebug` — BUILD SUCCESSFUL; APK produced.
      - `./gradlew :app:testPersonalDebugUnitTest` — all unit tests pass (no test references `automotive_app_desc`, so this is a regression guard, not a direct assertion of the fix).

- [ ] 4. Install on the connected phone and confirm Android Auto now discovers the projection entry while both NAVIGATION services remain.
      Files: none (on-device verification; this is the authoritative proof of the fix)
      Verify (device serial YXEMRCGYAI49S4SS):
      - `./gradlew :app:installPersonalDebug` — INSTALL SUCCESSFUL.
      - `adb -s YXEMRCGYAI49S4SS shell dumpsys package dev.autobridge | grep -A3 -iE "CATEGORY_PROJECTION|CarAppService"` — still shows `.projection.ProjectionCarService` with both CATEGORY_PROJECTION categories AND both NAVIGATION services (`.car.AutoBridgeCarAppService`, `.duoscreen.DuoScreenCarAppService`). The fix must not remove any of these.
      - Reconnect Android Auto (unplug/replug or toggle the connection) so gearhead re-enumerates, then: `adb -s YXEMRCGYAI49S4SS logcat -d | grep "OEMAppProvider.getApps"` — the printed app list now INCLUDES `dev.autobridge/...ProjectionCarService` alongside ScreenOnAuto and Fermata. This is the pass/fail signal for the bug.
      - On the head unit / Android Auto app list, confirm **"AutoBridge Browser"** appears and launches into the split layout (app in the main area, Maps shrunk to the side panel), and that "AutoBridge" and "Duo Screen" still appear and still work.
      - If "AutoBridge Browser" still does not appear after reconnect, add `<uses name="service" />` and `<uses name="notification" />` to the override (the two other roles the SDK README lists) and repeat the install + OEMAppProvider check — mark this contingency explicitly as the next step rather than concluding the root cause is wrong.

- [ ] 5. Record the review verdict for the workflow stop contract.
      The reviewer writes `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/projection-review.json` with top-level `"verdict"` set to `"APPROVED"` once steps 1-4 pass (or `"CHANGES_REQUESTED"` with findings if the OEMAppProvider check in step 4 still omits the projection entry after the step-4 contingency).
      Files: `.agents/tasks/projection-review.json`
      Verify: the loop reads `verdict == "APPROVED"` and stops.

## Notes / assumptions

- `safe` (Play) flavor is intentionally untouched; it has no projection source set and must not
  advertise a projection role.
- DHU cannot reproduce this (DHU 2.0 is incompatible with Android Auto 17.7.x per `docs/DHU.md`), so
  step 4 relies on the real phone + head unit, which matches how the bug was observed.
- The change is additive to the descriptor and touches no service/activity declarations, so the
  NAVIGATION and media routes are unchanged by construction.
