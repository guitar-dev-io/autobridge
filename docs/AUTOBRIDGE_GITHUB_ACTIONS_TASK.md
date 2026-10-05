# AutoBridge GitHub Actions Task + Checklist

## Goal
Give AutoBridge a reproducible CI/CD pipeline on GitHub Actions that mirrors what the
project already verifies locally — the `safe` flavor build, JVM unit tests, Android lint,
and the i18n guard (`scripts/check-i18n.sh`) — and that can cut a signed release APK from a
tag without exposing the keystore.

The pipeline is a safety net around the existing build, not a new build system. It must use
the committed Gradle wrapper (`./gradlew`, Gradle 8.13) and the same toolchain the project
compiles with locally (JDK 17, AGP 8.13.2, Kotlin 2.4.10, compileSdk 36).

---

# PART 1 — IMPLEMENTATION TASK

## 1. Respect the existing build

- Use the committed Gradle wrapper. Do not install or pin a different Gradle version.
- Build with **JDK 17** (Temurin). The project sets `sourceCompatibility`/`targetCompatibility`
  and `jvmTarget` to 17; a newer JDK changes bytecode and lint behavior.
- Let the Android SDK be provisioned by the runner's image / `android-actions/setup-android`;
  `compileSdk = 36` must be available.
- Do not commit `local.properties`, keystores, or `keystore/release.properties`. They are
  gitignored and must stay out of the repo and the logs.

## 2. Flavors and what CI can build

AutoBridge has three product flavors on the `mode` dimension:

| Flavor     | Pulls in `libs/aauto.aar`? | Play-safe? | CI role |
|------------|----------------------------|------------|---------|
| `safe`     | no                         | yes        | primary CI build + tests |
| `personal` | yes (sideload SDK)         | no         | compile-only check (optional) |
| `lab`      | yes (sideload SDK)         | no         | compile-only check (optional) |

`aauto.aar` is committed under `app/libs/`, so `personal`/`lab` *do* compile in CI. But the
`safe` flavor is the Play-shippable one, so it is the gate. Treat `personal`/`lab` compilation
as an optional extra job, not a required check.

## 3. CI workflow (`.github/workflows/ci.yml`)

Trigger on push to the main branch and on pull requests.

Required steps, in order:

1. Checkout.
2. Set up JDK 17 (Temurin) with Gradle caching enabled.
3. Set up the Android SDK.
4. i18n guard — run `scripts/check-i18n.sh`. This is a gate (it exits non-zero on a drift
   between `res/values-*`, `locales_config.xml`, and `resourceConfigurations`, or on Thai
   UI text left in Kotlin). It needs `python3`, which the runner already has.
5. Lint — `./gradlew lintSafeDebug`. `MissingTranslation` / `ExtraTranslation` are errors
   in `app/build.gradle.kts`, so a translation gap fails the build here too.
6. Unit tests — `./gradlew testSafeDebugUnitTest`.
7. Assemble — `./gradlew assembleSafeDebug`.
8. Upload the debug APK and the lint/test reports as build artifacts.

Rules:
- No signing secrets in CI. Debug builds use the auto-generated debug keystore.
- Keep the wrapper validation action so a tampered wrapper jar fails fast.
- Never log secrets; never `set -x` around credential handling.

## 4. Release workflow (`.github/workflows/release.yml`)

Trigger on pushing a tag matching `v*` (and allow manual `workflow_dispatch`).

Steps:

1. Checkout, JDK 17, Android SDK (same as CI).
2. Reconstruct signing material from repository secrets, never from the repo:
   - `RELEASE_KEYSTORE_BASE64` → decode to `keystore/release.jks`.
   - `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
   - Write `keystore/release.properties` pointing `storeFile` at the decoded jks.
     `app/build.gradle.kts` already reads this file and wires the `release` signing config
     when it is present.
3. `./gradlew assembleSafeRelease`.
4. Attach the signed APK to a GitHub Release for the tag.
5. Scrub the decoded keystore and properties file at the end of the job (the runner is
   ephemeral, but do not rely on that).

Rules:
- Release signing is **opt-in** via secrets. If the secrets are absent, the build still
  runs but produces an unsigned APK — it must not fail trying to read a missing keystore.
- The keystore only ever exists on disk inside the runner, decoded from a secret.

## 5. Keep it close to `build-check.sh`

`build-check.sh` already documents the local expectation: Java present, Gradle wrapper
present, SDK available, i18n set consistent. CI is the same contract enforced on every push.
If a step is added to the local check, mirror it in CI.

---

# PART 2 — CHECKLIST

## CI workflow
- [x] `.github/workflows/ci.yml` exists and triggers on push + pull_request.
- [x] JDK 17 (Temurin) with Gradle cache.
- [x] Android SDK set up; compileSdk 36 resolvable.
- [x] Gradle wrapper validation step present.
- [x] `scripts/check-i18n.sh` runs and gates the build.
- [x] `./gradlew lintSafeDebug` runs.
- [x] `./gradlew testSafeDebugUnitTest` runs.
- [x] `./gradlew assembleSafeDebug` runs.
- [x] Debug APK uploaded as an artifact.
- [x] Lint + test reports uploaded (always, even on failure).
- [x] No secrets referenced in the CI workflow.

## Release workflow
- [x] `.github/workflows/release.yml` exists and triggers on `v*` tags + manual dispatch.
- [x] Keystore reconstructed from `RELEASE_KEYSTORE_BASE64` secret only.
- [x] `keystore/release.properties` generated at build time, never committed.
- [x] `./gradlew assembleSafeRelease` runs.
- [x] Signed APK attached to the GitHub Release.
- [x] Build degrades gracefully (unsigned) when signing secrets are absent.
- [x] Keystore + properties scrubbed after the build.

## Repo hygiene
- [x] `keystore/`, `*.jks`, `*.keystore`, `local.properties` remain gitignored.
- [x] No signing material committed anywhere in the workflow change.

## Required repository secrets (for signed releases)
> Configured in the GitHub repo settings, not in this repo. The release workflow degrades to an
> unsigned build when they are absent, so these stay unchecked until set on GitHub.
- [ ] `RELEASE_KEYSTORE_BASE64` — `base64 -i keystore/release.jks` output.
- [ ] `RELEASE_STORE_PASSWORD`
- [ ] `RELEASE_KEY_ALIAS`
- [ ] `RELEASE_KEY_PASSWORD`

## Release artifacts (per flavor)
- [x] `safe`: AAB (`bundleSafeRelease`, Play upload) **and** APK (`assembleSafeRelease`, sideload).
- [x] `personal`: APK only (`assemblePersonalRelease`) — sideload, not Play-accepted.
- [x] `lab`: APK only (`assembleLabRelease`) — sideload, not Play-accepted.
- [x] All of the above attached to the GitHub Release for the tag.

## Optional / nice-to-have
- [x] Separate job compiling `personal` + `lab` (`assemblePersonalDebug`, `assembleLabDebug`).
- [x] Concurrency group to cancel superseded runs on the same ref.
- [x] Dependency caching keyed on Gradle files.
