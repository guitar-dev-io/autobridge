# Projection role added to car descriptor so Android Auto surfaces "AutoBridge Browser"

The change makes gearhead's OEMAppProvider list and bind `dev.autobridge/.projection.ProjectionCarService` (the CATEGORY_PROJECTION service labelled "AutoBridge Browser") alongside the two existing NAVIGATION entries, which gives the user the Maps split layout they asked for. The root cause identified in the plan is that the package's single car descriptor (`@xml/automotive_app_desc`, pointed to by the `com.google.android.gms.car.application` meta-data) only advertised `template` + `media` roles, so OEMAppProvider classified the package as templated/media and never surfaced the correctly-registered projection service. The fix is additive and flavor-scoped: a new `app/src/projection/res/xml/automotive_app_desc.xml` override declares `template` + `media` + `projection` for the personal/lab flavors only, wired in by a one-line `res.srcDir("src/projection/res")` addition to `app/build.gradle.kts`. The safe (Play) flavor keeps the main descriptor with no projection role.

Watch for: the authoritative on-head-unit OEMAppProvider discovery signal is **not** yet captured (confirmed) — it remains a user step and is non-blocking per the task contract. The projection fix lives inside a large unrelated uncommitted change set (confirmed), which the user has explicitly accepted shipping as one unit.

**Verdict**: APPROVED

## High-level view

The descriptor-role diagnosis matches the ScreenOnAuto/Fermata comparison: those apps expose the same unofficial-SDK CATEGORY_PROJECTION service from a descriptor that declares `projection`, and the only thing AutoBridge lacked was that role. Adding it is additive — it changes which roles the package advertises, not any service or activity declaration — so there is a plausible, evidence-backed causal path from this edit to gearhead binding the service.

The gating is correct. The `projection` role is confined to the personal/lab source set via a same-named flavor resource that overrides `main`, and the build intermediates recorded in the verification note confirm the personal variant resolves to the three-role descriptor while the safe variant keeps the two-role one. Play is untouched, consistent with the project's hard separation of projection from the Play flavor.

Both NAVIGATION services survive. `.car.AutoBridgeCarAppService` sits unchanged in the main manifest and `.duoscreen.DuoScreenCarAppService` sits in the projection manifest, and the on-device `dumpsys` dump records all three services registered after install, with the projection service carrying both CATEGORY_PROJECTION categories. The fix removed none of them.

The known gaps are the head-unit discovery proof (deferred to the user, with the exact `OEMAppProvider.getApps` command recorded) and the entanglement of this fix with a broad working tree (Duo Screen feature, version system, lint baseline, dependency and icon changes). The user has decided that entire tree ships together, so the entanglement is an accepted release decision rather than an open blocker.

<details>
<summary>Issues (2)</summary>

1. **Head-unit discovery unconfirmed (non-blocking)** — the OEMAppProvider enumeration that is the actual pass/fail signal was not captured because no live gearhead session re-enumerated after install. User must reconnect the head unit and run `adb logcat -d | grep OEMAppProvider.getApps`, expecting the projection entry alongside ScreenOnAuto/Fermata. The command is recorded in the verification note; the task explicitly permits deferring this.
2. **Fix entangled with a large working tree (accepted)** — the two projection-fix files are mixed with ~50 tracked + untracked files of unrelated in-progress work. The user decided (option A) this all ships as one unit, so it is not a blocker, but it means the fix cannot be reverted in isolation if the head-unit check later fails.

</details>

<details>
<summary>Details</summary>

## Descriptor role as the discovery lever

The package wires exactly one car descriptor through `app/src/main/AndroidManifest.xml`'s `com.google.android.gms.car.application` meta-data (unchanged by this diff), and that descriptor is what OEMAppProvider reads to decide which car-app roles the package offers. The projection service's own intent-filter (`MAIN` + both CATEGORY_PROJECTION categories) was already correct per the pre-existing `dumpsys` evidence, which is why editing the service was correctly ruled out. The fix targets the one knob that governs OEMAppProvider listing — the descriptor's `projection` role — which is consistent with the plan's claim that NAVIGATION (androidx car-app discovery) and projection (OEMAppProvider) travel through different providers, so advertising the projection role is additive to the NAVIGATION route rather than competing with it.

The causal link to gearhead binding cannot be proven from source — gearhead's enumeration is a black box reproducible only on a live head unit — but the plan grounds it in the SDK's documented descriptor contract and the ScreenOnAuto/Fermata parity argument, and the verification note preserves that final proof as a recorded user command rather than claiming it passed.

## Flavor scoping keeps Play clean

```
main/res/xml/automotive_app_desc.xml        -> template, media            (safe/Play)
projection/res/xml/automotive_app_desc.xml  -> template, media, projection (personal/lab)
                                                ^ same resource name overrides main
```

The override relies on AGP resolving a same-named resource in a flavor source set over `main`, enabled by the single added `res.srcDir("src/projection/res")` line (mirroring the already-present `src/duoscreen/res` wiring). The verification note's packaged-res intermediates confirm the resolution empirically: `packaged_res/personalDebug/.../automotive_app_desc.xml` carries all three roles and `packaged_res/safeDebug/.../automotive_app_desc.xml` keeps only two. So the Play flavor never advertises a projection role it cannot back with a service — the mismatch that candidate #1 (editing the main descriptor) would have introduced and that Play review flags.

## Both NAVIGATION services intact

`.car.AutoBridgeCarAppService` (NAVIGATION) is declared in `app/src/main/AndroidManifest.xml` and is not touched by the diff. `.duoscreen.DuoScreenCarAppService` (NAVIGATION) lives in `app/src/projection/AndroidManifest.xml`; it is added by Duo Screen feature work in the same tree, not by the descriptor fix, but it is present and the task requires it to remain. The on-device `dumpsys package dev.autobridge` dump in the verification note shows all three services registered after install — the two NAVIGATION services plus the projection service carrying both `CATEGORY_PROJECTION` and `CATEGORY_PROJECTION_OEM`. The additive descriptor change removed none of them, which satisfies the "must not remove or break the NAVIGATION entries" constraint.

## Minimality and the entangled working tree

The projection fix proper is genuinely two files and config-only: the new override and the one-line `res.srcDir` addition. There is no projection-related refactoring. The complication is that `git diff HEAD` shows the fix embedded in a broad change set — the version.properties/printVersion system, a lint baseline, `hiddenapibypass` + `media3-ui` dependencies, Duo Screen services and `QUERY_ALL_PACKAGES` in the projection manifest, duoscreen test source sets, and a launcher icon swap (`@drawable/ic_autobridge_launcher` → `@mipmap/ic_launcher`) in the main manifest. Two of these touch files the plan said to leave unchanged (`app/src/projection/AndroidManifest.xml`, `app/src/main/AndroidManifest.xml`), but those edits are Duo Screen / icon work, not the descriptor fix, and the car-application meta-data pointer in the main manifest is untouched. The prior review flagged this entanglement; the verification note records the user's explicit decision (option A) to ship the whole tree as one continuous unit, which resolves the scope findings as a release decision. The residual cost is that the fix cannot be reverted in isolation if the deferred head-unit check fails — worth noting, not blocking.

## Test coverage

Build (`assemblePersonalDebug`) and unit tests (`testPersonalDebugUnitTest`) pass per the verification note and serve as a regression guard; no unit test references `automotive_app_desc`, so they assert "nothing broke," not "the role resolves." The resource resolution is instead asserted by the packaged-res intermediates, and service registration by `dumpsys`.

Not tested here: the OEMAppProvider head-unit enumeration — the one signal that actually proves gearhead surfaces the entry. It is deferred to the user with the exact command recorded, which the task explicitly permits (DHU cannot reproduce AA 17.7.x). The verification note also records a concrete contingency (add `service` + `notification` roles) if the entry still does not appear after reconnect.

</details>

<details>
<summary>File map</summary>

Projection fix (the reviewed change):
- `app/src/projection/res/xml/automotive_app_desc.xml` (new) — flavor-scoped descriptor override adding the `projection` role to `template` + `media` for personal/lab.
- `app/build.gradle.kts` — one line `res.srcDir("src/projection/res")` so the override resolves (plus unrelated version-system, lint-baseline, and dependency changes bundled in the same tree).

Unrelated changes in the same working tree (accepted by the user, not part of the fix):
- `app/src/main/AndroidManifest.xml` — launcher icon swap only; car-application meta-data unchanged.
- `app/src/projection/AndroidManifest.xml` — Duo Screen services + `QUERY_ALL_PACKAGES` (Duo Screen feature work; supplies the second NAVIGATION entry the task requires).
- ~50 further tracked files + untracked files (Duo Screen feature, version.properties/printVersion, lint baseline, media3-ui/hiddenapibypass deps).

Full diff: `git diff HEAD` in `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`.

</details>
