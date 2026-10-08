**Version:** `0.4.54` &nbsp;·&nbsp; **Build (versionCode):** `68`

AutoBridge is a personal/development Android project for experimenting with a parked-only phone-to-Android-Auto surface bridge. It is an independent codebase, not a merge of the reference projects tracked in the project's internal `docs/` notes.

**Safety boundary:** by default the parked/mode gate holds and nothing is bypassed — mirroring, video, browser, touch, and Quick App launching are gated on an authoritative `PARKED` state, `MOVING` stops projection and leaves the car app, `UNKNOWN` is fail-closed, and LAB controls never falsify a real vehicle's speed. An optional, off-by-default user override can open that gate, and a separate audio option can keep media playing through the reverse-gear chime; both are deliberate opt-ins the driver turns on at their own risk (see [Safety first](#️-safety-first--ความปลอดภัยต้องมาก่อน)).

## ⚠️ Safety first / ความปลอดภัยต้องมาก่อน

**English**
- Use AutoBridge only while the vehicle is **parked**. Do not watch video, browse, or operate the phone/car screen while driving.
- The driver is always fully responsible for operating the vehicle safely and for obeying all local traffic laws. Distracted driving is dangerous and in many places illegal.
- The parked-only gate is a software safeguard, not a guarantee. It depends on a correct `CAR_SPEED` signal from the host/vehicle, which can be delayed, missing, or wrong on real hardware — never rely on it as a substitute for your own judgement.
- Set everything up before you drive. Pull over and park before changing settings, picking content, or interacting with the car screen.

_Optional overrides — advanced, at your own risk_

- **Safety bypass (parked/mode override).** AutoBridge ships a runtime override of the parked/mode gate. It is **off by default**; a fresh install behaves exactly like the stock gate and nothing is bypassed. Turning it on is a deliberate opt-in that lifts the `PARKED` requirement, so parked-only features can open in any vehicle state (including while the car is moving). Its default scope also lifts the mode (SAFE/PERSONAL/LAB) restriction; a narrower "parked only" scope is available. You can toggle it from the phone **Settings** ("Safety bypass" row) or an `adb` broadcast.
- **Persistent notification.** While the bypass is on, AutoBridge keeps an ongoing, non-dismissible notification ("Safety bypass ON") so you always see that the gate is open; it carries a one-tap "Turn off" action.
- **Keep media playing through the reverse chime (audio option).** Also **off by default**. When on, playback no longer stops for the reverse-gear / rear-camera chime — but by the same mechanism it also no longer pauses or quiets for phone calls or navigation prompts. It is toggled from the car browser's audio settings.
- Enabling either option lifts a protection you were relying on. You assume all risk and legal responsibility for doing so. Do not enable them to use features while driving; leave them off unless the vehicle is parked and you understand exactly what they change.

**ไทย**
- ใช้ AutoBridge เฉพาะตอน **จอดรถ** เท่านั้น อย่าดูวิดีโอ ท่องเว็บ หรือใช้งานหน้าจอมือถือ/จอรถขณะขับขี่
- ผู้ขับขี่เป็นผู้รับผิดชอบการขับขี่อย่างปลอดภัยและการปฏิบัติตามกฎจราจรทั้งหมดเสมอ การขับรถโดยเสียสมาธิเป็นอันตรายและผิดกฎหมายในหลายพื้นที่
- ระบบล็อกเฉพาะตอนจอด เป็นเพียงมาตรการป้องกันทางซอฟต์แวร์ ไม่ใช่การรับประกัน เพราะอาศัยสัญญาณ `CAR_SPEED` ที่ถูกต้องจาก host/รถ ซึ่งบนฮาร์ดแวร์จริงอาจมาช้า ขาดหาย หรือผิดพลาดได้ อย่าใช้แทนวิจารณญาณของตนเอง
- ตั้งค่าทุกอย่างให้เสร็จก่อนออกรถ หากต้องเปลี่ยนการตั้งค่า เลือกเนื้อหา หรือแตะจอรถ ให้จอดรถในที่ปลอดภัยก่อน

_ตัวเลือกการปลดล็อก — สำหรับผู้ใช้ขั้นสูง ยอมรับความเสี่ยงเอง_

- **การปลดล็อกข้อจำกัดความปลอดภัย (ข้ามการเช็กจอดรถ/โหมด)** AutoBridge มีสวิตช์สำหรับข้ามการเช็กจอดรถ/โหมดในระดับรันไทม์ โดย **ปิดไว้เป็นค่าเริ่มต้น** การติดตั้งใหม่จะทำงานเหมือนระบบล็อกมาตรฐานทุกประการและไม่มีการข้ามอะไร การเปิดใช้งานเป็นการเลือกเองโดยตั้งใจ ซึ่งจะยกเลิกเงื่อนไข `PARKED` ทำให้ฟีเจอร์ที่ปกติใช้ได้เฉพาะตอนจอดสามารถเปิดได้ในทุกสถานะของรถ (รวมถึงขณะรถกำลังเคลื่อนที่) ค่า scope เริ่มต้นยังยกเลิกข้อจำกัดเรื่องโหมด (SAFE/PERSONAL/LAB) ด้วย และมี scope แบบแคบ ("เฉพาะจอด") ให้เลือก เปิด/ปิดได้จาก **ตั้งค่า** บนมือถือ (แถว "ปลดล็อกข้อจำกัดความปลอดภัย") หรือผ่าน `adb` broadcast
- **การแจ้งเตือนแบบค้างไว้** ขณะที่เปิดการปลดล็อกอยู่ AutoBridge จะแสดงการแจ้งเตือนแบบค้าง (ปัดทิ้งไม่ได้ "เปิดการปลดล็อกข้อจำกัดแล้ว") เพื่อให้เห็นเสมอว่าระบบล็อกถูกเปิดออก พร้อมปุ่ม "ปิด" แบบแตะครั้งเดียว
- **เล่นสื่อต่อผ่านเสียงเตือนถอยหลัง (ตัวเลือกเสียง)** **ปิดไว้เป็นค่าเริ่มต้น** เช่นกัน เมื่อเปิด เสียงจะไม่หยุดเพราะเสียงเตือนถอยรถ/กล้องหลัง แต่ด้วยกลไกเดียวกันนี้ มันจะไม่หยุดหรือหรี่เสียงให้สายโทรเข้าหรือเสียงนำทางด้วย เปิด/ปิดได้จากการตั้งค่าเสียงของเบราว์เซอร์บนจอรถ
- การเปิดตัวเลือกใด ๆ ข้างต้นเป็นการยกเลิกการป้องกันที่คุณเคยพึ่งพาอยู่ คุณยอมรับความเสี่ยงและความรับผิดชอบทางกฎหมายทั้งหมดเอง อย่าเปิดเพื่อใช้ฟีเจอร์ขณะขับขี่ และควรปิดไว้เสมอจนกว่าจะจอดรถและเข้าใจชัดเจนว่าตัวเลือกเหล่านี้เปลี่ยนอะไรบ้าง

## Disclaimer / ข้อจำกัดความรับผิดชอบ

**English**

AutoBridge is a personal, non-commercial project built for learning and experimentation. It is provided "as is", without any warranty, and is used entirely at your own risk; the author is not liable for any damage, loss, legal consequence, or accident arising from its use.

It is **not affiliated with, endorsed by, or sponsored by** Google, Android, Android Auto, Ford, or any car manufacturer, platform, or brand. All product names, logos, and trademarks belong to their respective owners and are used for identification only. AutoBridge does not bypass DRM and is not intended for any use that violates a platform's terms of service or your local laws.

AutoBridge also includes optional, off-by-default capabilities — a user override of the parked/mode safety gate and an audio option that keeps media playing through the reverse-gear chime (and, as a side effect, through phone calls and navigation prompts). These are disabled on a fresh install; enabling them is a deliberate choice, and doing so is entirely at your own risk and responsibility. This is separate from the DRM statement above, which remains true: lifting the parked/mode gate does not bypass DRM.

**ไทย**

AutoBridge เป็นโปรเจกต์ส่วนตัวที่ไม่ใช่เชิงพาณิชย์ จัดทำขึ้นเพื่อการศึกษาและทดลองเท่านั้น ให้บริการตามสภาพ ("as is") โดยไม่มีการรับประกันใด ๆ และผู้ใช้ยอมรับความเสี่ยงเองทั้งหมด ผู้พัฒนาไม่รับผิดชอบต่อความเสียหาย การสูญเสีย ผลทางกฎหมาย หรืออุบัติเหตุใด ๆ ที่เกิดจากการใช้งาน

โปรเจกต์นี้ **ไม่มีส่วนเกี่ยวข้อง ไม่ได้รับการรับรอง และไม่ได้รับการสนับสนุน** จาก Google, Android, Android Auto, Ford หรือผู้ผลิตรถ แพลตฟอร์ม หรือแบรนด์ใด ๆ ชื่อผลิตภัณฑ์ โลโก้ และเครื่องหมายการค้าทั้งหมดเป็นของเจ้าของนั้น ๆ ใช้เพื่อการอ้างอิงเท่านั้น AutoBridge ไม่หลบเลี่ยง DRM และไม่ได้มีไว้เพื่อการใช้งานที่ละเมิดเงื่อนไขบริการของแพลตฟอร์มหรือกฎหมายในพื้นที่ของคุณ

AutoBridge ยังมีความสามารถที่เป็นทางเลือกและปิดไว้เป็นค่าเริ่มต้นด้วย ได้แก่ การปลดล็อกข้อจำกัดจอดรถ/โหมดโดยผู้ใช้ และตัวเลือกเสียงที่ให้สื่อเล่นต่อผ่านเสียงเตือนถอยหลัง (และเป็นผลข้างเคียงให้เล่นต่อผ่านสายโทรเข้าและเสียงนำทางด้วย) ความสามารถเหล่านี้ปิดอยู่ในการติดตั้งใหม่ การเปิดใช้งานเป็นการตัดสินใจเองโดยตั้งใจ และถือเป็นความเสี่ยงและความรับผิดชอบของผู้ใช้เองทั้งหมด ทั้งนี้แยกต่างหากจากข้อความเรื่อง DRM ข้างต้นซึ่งยังคงเป็นจริง การปลดล็อกการเช็กจอดรถ/โหมดไม่ได้เป็นการหลบเลี่ยง DRM

## Current implementation

- One shared `:app` module and one `src/main` implementation with `safe`, `personal`, and `lab` product flavors.
- Central `FeaturePolicy` decisions backed by `RuntimeContextStore` and `VehicleState` flows.
- Production `CAR_SPEED`/`SpeedGate` provider with strict finite-zero parking classification and a three-second stale-update timeout.
- Emulator-only LAB mock provider with `PARKED`, `MOVING`, and `UNKNOWN` transitions; a physical debug phone stays on the production provider.
- Android 14+ MediaProjection foreground service with two explicit renderers: default direct `AUTO_MIRROR` and opt-in `SELF_DRAWN` (`ImageReader -> Canvas -> Android Auto Surface`) for app-controlled FIT/FILL/STRETCH/ONE_TO_ONE output and measured frame diagnostics.
- Shared surface lifecycle, reconnect tracking, safe insets, visible-area bounds, rotation-aware coordinate mapping, and structured mirror diagnostics.
- Accessibility and bounded Shizuku input backends with capability reporting and parked-only enforcement.
- Optional Shizuku user-service capability probes for panel-only power-off and a real pointer-id/action `MotionEvent` sink; both are disabled by default and remain device/host dependent.
- Quick Apps, installed-app filtering, legacy-favorite migration, per-app profiles, Smart Mode, force-landscape cleanup, and explicit session resume/forget actions.
- Media3 progressive/HLS/DASH/local playback through a MediaSession, with controller authorization and independent audio/video policy.
- Entertainment routing that distinguishes HTTPS web pages, audio, and video instead of treating all sources as browser content.
- A Fermata-Xtream-style home on both surfaces: one shared `HomeSection` list renders as the phone launcher grid and as the Android Auto dashboard (TV, Radio, Web browser, Youtube, YouTube Music, , Folders, Favorites, Playlists, Gallery, then Mirror/Apps/Remote/Settings).
- Xtream Codes and M3U IPTV sources for the TV and Radio sections, with unit-tested credential/playlist parsing, a shared catalog cache, host-aware list paging, favourites and a recently-played list.
- Built-in free public playlists so TV and Radio have channels out of the box. A fresh install seeds these defaults (each can be removed for good — removal is remembered per URL, so a deleted default never comes back):
  - TV: **Free-TV** ([Free-TV/IPTV](https://github.com/Free-TV/IPTV), ~2,000 free-to-air channels grouped by country) and **Thai (dearbulut)** (Thai-language channels).
  - Radio: **radio-browser · Thailand** (Thai stations from the [radio-browser](https://www.radio-browser.info/) community database).

  Two more lists are one tap away in the picker without being seeded: **iptv-org · All countries** ([iptv-org](https://github.com/iptv-org/iptv), very large; the first load takes a while) for TV, and **radio-browser · Top voted** (the 100 highest-voted stations worldwide) for Radio. AutoBridge stores addresses only and fetches each list live from the project that publishes it under that project's own terms; nothing is hosted, bundled, or redistributed here, and nothing is added without an explicit action. Community-playlist conventions are read rather than ignored: a channel whose entry is a YouTube/Twitch watch page opens in the browser instead of failing inside the player, and Free-TV's `Ⓢ`/`Ⓖ`/`Ⓨ` name markers become subtitle hints.
- Channel logos from the playlist on both the phone and the head unit, including the lists that spell the attribute differently or write the address relative to themselves. The phone shows channels as a two-column grid of logo tiles; the car shows them as list rows with the logo beside them.
- A ping check that runs by itself when a channel list opens, on both surfaces and with nothing to press: each channel reads `88 ms` in green, a slow answer in amber, or `HTTP 404` / `No answer` in red, so a retired or geo-blocked channel is visible before it is opened rather than as a player that spins.
- MediaStore-backed Folders, Playlists and Gallery sections on the phone and in the car, reusing the existing MediaSession and car video surface.
- An AutoBridge phone design language (`AutoBridgeDesign`): ink surfaces, hairline borders, a per-section accent that carries from the home card into that section's screens and player, a dependency-free cached image loader for channel logos, a shared now-playing bar, and a designed player with a scrubber and a LIVE state.
- A step-by-step car setup screen: notifications, an input backend (Shizuku or accessibility) and screen capture in the order they happen, each with its live state and one action, plus an optional Bluetooth media-session start.
- Optional YouTube add-ons for the in-app browser, off by default: SponsorBlock segment skipping with per-category switches and a privacy-preserving hash-prefix lookup, an auto-highest-quality setting, and ad-skip. All of them run in the phone and car browsers from one implementation — see [YouTube add-ons](#youtube-add-ons--ส่วนเสริม-youtube) for what each one can and cannot do.
- Bounded Compose phone control-center content embedded in the existing Activity; Android Auto remains host-managed through Car App templates.
- ScreenOnAuto-inspired mirror automation: optional prevent-sleep, timed auto-dim, opt-in panel-only screen-off, stop-on-disconnect, last-app auto-launch, consented auto-open, Shizuku onboarding, and explicit self-drawn renderer selection.

The panel-only and real-touch work is implemented independently from the public [ScreenOnAuto releases](https://github.com/slzn/ScreenOnAuto-releases), which are credited as the behavioral/reference source. AutoBridge does not copy or redistribute an upstream source tree or assets.

The code is implemented and flavored builds/tests are the primary local validation. Ford hardware, production Android Auto host behavior, exact `CAR_SPEED` delivery, MediaProjection consent, MediaSession controller identities, Shizuku, hidden display-power APIs, and raw touch injection remain device-dependent checks.

## Modes

| Flavor | Use | Important boundary |
|---|---|---|
| `safe` | Conservative baseline | Policy enables media and parked Quick Apps; mirror/browser/video/touch are disabled. |
| `personal` | Personal parked use | Broad feature set, but parked-only features still require `PARKED`. |
| `lab` | Emulator/DHU or controlled bench | Broad feature set plus LAB controls; real-car state is never replaced. |

Mode, feature-policy and LAB behaviour are described in the project's internal `docs/` notes.

## Requirements

- Android Studio with Android SDK 36
- JDK 17
- Android 10+ phone (`minSdk 29`, `targetSdk 36`)
- Android Auto/DHU for host testing
- Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.4.10
- AndroidX Car App 1.7.0 and Media3 1.11.0

The bounded Compose slice uses the Kotlin Compose compiler plugin and Compose BOM `2025.06.01`, selected because the current project targets compileSdk 36/AGP 8.13. See the [official Compose dependency setup guidance](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

## Build and test

Use a specific flavor; all modes share the same source code:

```bash
./gradlew testSafeDebugUnitTest assembleSafeDebug
./gradlew testPersonalDebugUnitTest assemblePersonalDebug
./gradlew testLabDebugUnitTest assembleLabDebug
```

Full local verification:

```bash
./gradlew \
  testSafeDebugUnitTest \
  testPersonalDebugUnitTest \
  testLabDebugUnitTest \
  assembleSafeDebug \
  assemblePersonalDebug \
  assembleLabDebug
```

Optional lint tasks, when available in the local Android toolchain:

```bash
./gradlew lintSafeDebug lintPersonalDebug lintLabDebug
```

### Building with the translation engines (off by default)

On-device subtitle translation is the one feature with heavyweight dependencies behind it: ML Kit
Translate and ONNX Runtime (the Opus-MT/Marian engine). Both are mostly native code shipped per
ABI, about 17 MB of the packaged APK between them. For that reason `autobridge.subtitleTranslation`
in `gradle.properties` is **`false` by default**, so a standard build leaves both libraries out
entirely — no dependency, no `.so`, and neither of the two engine classes compiled. Opt in to
compile the feature back in with the flag set to `true`:

```bash
./gradlew assembleSafeRelease -Pautobridge.subtitleTranslation=true
AUTOBRIDGE_SUBTITLE_TRANSLATION=true ./gradlew assembleSafeRelease
```

Subtitles themselves are unaffected — a track still decodes and renders, only untranslated. When
the feature is left off, `SubtitleSettings.enabled()` is false regardless of the stored preference,
so a phone that had translation on before does not route cues at an engine that is no longer there,
and the player hides the button that opens the translation settings.

### Setting the version

`version.properties` at the repo root is the only place the version is written; `app/build.gradle.kts`
reads it, so no build file holds a literal version number. Bump it with the script, which keeps
`versionCode` increasing and syncs the numbers into this README:

```bash
./scripts/set-version.sh 0.5.0      # explicit versionName, versionCode + 1
./scripts/set-version.sh patch      # 0.4.12 -> 0.4.13
./scripts/set-version.sh minor      # 0.4.12 -> 0.5.0
./scripts/set-version.sh major      # 0.4.12 -> 1.0.0
./scripts/set-version.sh 0.5.0 --code 30   # pin versionCode too
./scripts/set-version.sh --show     # what is set right now
./scripts/set-version.sh minor --tag       # bump and create the v0.5.0 tag
```

A single build can override either value without editing a file — a Gradle property wins over an
environment variable, which wins over `version.properties`:

```bash
./gradlew assembleSafeRelease -PversionName=0.5.0-rc1 -PversionCode=27
AUTOBRIDGE_VERSION_NAME=0.5.0-rc1 ./gradlew assembleSafeRelease
./gradlew -q :app:printVersion   # prints "0.4.12 26" — what a build would stamp
```

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the signed safe AAB + APK and
publishes a GitHub Release. The workflow fails early if the tag and `version.properties` disagree,
so `set-version.sh` (or an equivalent commit) has to land before the tag.

### Checking for updates in the app

AutoBridge is sideloaded, so nothing tells the user that a newer build exists. **Settings > About >
Check for updates** asks GitHub for the latest published release, compares its tag with the running
`versionName`, and offers the attached APK and the release page when it is newer. It only runs when
that row is tapped — never on launch or in the background — the request carries no identity beyond
an `AutoBridge/<version>` User-Agent, and the result is cached so the row shows what it last found.
Installing stays with the browser and the package installer; the app never replaces itself.

Version comparison and the release parsing are unit-tested in
`app/src/test/java/dev/autobridge/update/UpdateCheckTest.kt`, which is where a tag shape that the
check should understand (`v0.5.0`, `0.5.0-rc1`) belongs.

### whisper.cpp submodule (offline voice recognition)

The `:whisper` module builds [whisper.cpp](https://github.com/ggml-org/whisper.cpp) from a git
submodule pinned to a release tag, so the NDK and CMake are needed and the submodule has to be
checked out before the first build:

```bash
git submodule update --init --recursive
```

Whisper models are not in the APK; they are downloaded (or imported) on the phone from
Settings > Voice Recognition. The model list lives in `WhisperModelCatalog`.

### Installing on a physical phone

The debug variants are signed with the local Android debug key and can be installed directly for development:

```bash
./gradlew assemblePersonalDebug
adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk
```

If more than one device is connected, add `-s <device-serial>` to the `adb install` command. The `personal`, `safe`, and `lab` variants use the same `applicationId` (`dev.autobridge`), so install only one at a time unless you intentionally want to replace the existing variant.

`app-personal-release-unsigned.apk` is not installable because it has no signing certificate. The local release signing setup is now available:

```bash
./gradlew assemblePersonalRelease
adb install -r app/build/outputs/apk/personal/release/app-personal-release.apk
```

Release builds are signed with the project's own release keystore, which is kept outside the repository (ignored by Git) and never published here. Back it up securely: the same keystore must be reused for every future update to keep the app identity, and losing it prevents those updates.

Do not rename or sideload the `-unsigned.apk` file. A release APK distributed through Google Play/Internal testing must continue using the same release keystore for all future updates.

### Google Play upload

Build the signed Android App Bundle for Google Play Internal testing or App Sharing:

```bash
./gradlew :app:bundlePersonalRelease
```

Upload this file in Play Console:

```text
app/build/outputs/bundle/personalRelease/app-personal-release.aab
```

The bundle is signed with the AutoBridge release keystore and contains `dev.autobridge`, version `0.4.54`, and `versionCode 68`. Increment `versionCode` for every later upload with `./scripts/set-version.sh` (see [Setting the version](#setting-the-version)); keep the same keystore for updates. Start with Internal testing/App Sharing before attempting production release. The current Android Auto surface is a development/personal-use POC using a `NavigationTemplate` for mirroring, so Play/Android Auto policy approval is not guaranteed.

### Deobfuscation (R8 mapping) files

Release builds run R8, so Play Console gets the deobfuscation file it asks for and uploaded crashes
and ANRs retrace to real class and method names. Nothing to do by hand for a bundle: AGP writes the
mapping into the `.aab` itself, at
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, and Play reads it on upload.
Confirm it is there before uploading with:

```bash
unzip -l app/build/outputs/bundle/safeRelease/app-safe-release.aab | grep obfuscation
```

Each variant also leaves its own copy on disk, which is what the sideloaded `personal` and `lab`
APKs need — they never go through Play, so a stack trace from one is unreadable without it:

```text
app/build/outputs/mapping/<variant>Release/mapping.txt
```

R8's output is not reproducible from a later build, so keep the file for any build handed to
someone else; the release workflow uploads all three as the `autobridge-release-mapping` artifact.
To read a trace back:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/retrace" \
  app/build/outputs/mapping/safeRelease/mapping.txt crash.txt
```

It resolves inlined frames too, so one obfuscated line can come back as the two or three real ones
R8 collapsed into it.

What R8 is allowed to rename is bounded by `app/proguard-rules.pro`, which pins the places the app
is reached by name instead of by reference (JNI into ONNX Runtime, the Shizuku user service, the
Android Auto host instantiating a `CarActivity`). Add a rule there — with the reason — if a release
build hits a `ClassNotFoundException` or `NoSuchMethodError` that the debug build does not.
Resource shrinking is intentionally left off: `play-services-oss-licenses` resolves its generated
notices through `Resources.getIdentifier()`, so the Settings "Open-source licenses" screen would
silently come up empty.

## Development flow

### Emulator/DHU LAB flow

1. Install `labDebug` on an Android emulator.
2. Start the Desktop Head Unit and connect it to the emulator.
3. Open AutoBridge in the DHU launcher.
4. Select a non-`REAL_CAR` LAB environment and set the mock vehicle state to `PARKED`.
5. Start mirror on the phone and approve the system screen-capture consent.
6. Verify the `READY` projection state, surface/mirror diagnostics, and visible pixels.
7. Change the mock state to `MOVING`; projection must stop and the car app must exit.
8. Change it to `UNKNOWN`; parked-only actions must remain blocked.

### Production-style flow

1. Install `personalDebug` only for development or a controlled personal test. For a real vehicle, Android Auto may require a trusted distribution source for apps built with the Android for Cars App Library; use the Desktop Head Unit for sideloaded APK validation, or distribute a signed build through Google Play Internal testing/App Sharing for a vehicle test.
2. Connect Android Auto/DHU and open AutoBridge.
3. Request/grant `CAR_SPEED`; wait for a valid zero-speed callback.
4. Start mirror from the phone and approve fresh MediaProjection consent.
5. Enable Accessibility or Shizuku only if touch testing is required.
6. If testing panel-only screen-off, connect Shizuku, confirm the capability status, and leave the opt-in setting disabled until the device behavior is understood.
7. Stop the session before changing vehicle state; a non-zero speed must stop it automatically.

Android Auto's official testing guidance states that the unknown-sources exception does not apply to apps built with the Android for Cars App Library. This limitation is summarized from the [official Android testing guidance](https://developer.android.com/training/cars/testing/); content was rephrased for compliance with licensing restrictions. Therefore, an ADB-installed `personalDebug` APK can be valid and discoverable by Android's service resolver while still being absent from a real vehicle's launcher.

The official Android Auto app model does not provide a general arbitrary-screen-mirroring category. AutoBridge uses a `NavigationTemplate` custom surface as a development/personal POC; do not present it as a compliant navigation app for store distribution.

## Architecture at a glance

```text
Phone MainActivity / bounded Compose controls
             |
             | explicit MediaProjection consent
             v
ProjectionService (foreground service)
             |
             v
MirrorCoordinator -- AUTO_MIRROR --> Android Auto Surface
       |
       +-------------- SELF_DRAWN --> ImageReader -> RenderPlan/Canvas -> Surface
                                      |
                                      v
                               DHU / vehicle host
       ^
       |
FeaturePolicy <--- RuntimeContextStore <--- VehicleStateSession <--- SpeedGate/LAB mock
       |
       +--> TouchRouter --> Shizuku / Accessibility --> phone input
       |                     |
       |                     +--> optional panel power / raw-touch privileged sink
       +--> Quick Apps / profiles / MediaSession / Entertainment
```

Ownership and lifecycle details live in the project's internal `docs/` notes (`ARCHITECTURE.md`, `MIRROR_ENGINE.md`).

## Platform limitations

- Direct `AUTO_MIRROR` is OS-owned FIT output. The opt-in `SELF_DRAWN` pipeline applies FILL, STRETCH, crop, and ONE_TO_ONE through `RenderPlan`/Canvas; it adds copy/draw latency and must be measured on the target device.
- `SELF_DRAWN` still captures the default display. A dedicated `OWN_CONTENT`/app-owned display remains unavailable and is rejected or falls back safely; panel-only power control is a separate capability and does not claim that own-content boundary.
- The optional prevent-sleep/auto-dim controls use public, deprecated screen WakeLock APIs as a best-effort policy. They do not lock or sleep the device intentionally and must be validated on a real phone.
- When explicitly enabled, the Shizuku user service can attempt panel-only power-off through hidden `SurfaceControl`/`DisplayControl` APIs after capability probing. It defaults to off, falls back to public dimming on failure, and restores panel power during teardown; Android/OEM support is not guaranteed.
- When explicitly enabled, the real-touch sink uses hidden `InputManager.injectInputEvent(MotionEvent)` with bounded pointer IDs, pointer count, and coordinates. The current Android Auto host still exposes click/scroll/fling/scale callbacks rather than a raw pointer stream, so this sink is not fed by `onScale`; the existing pinch path remains synthetic Accessibility input.
- Target-app-only capture/fullscreen cannot be forced reliably through public APIs.
- Protected video surfaces (for example Netflix/Widevine content) may be blank in MediaProjection/AUTO_MIRROR output. AutoBridge does not bypass DRM; use an officially supported car/app integration for protected playback.
- Ford profile values are placeholders until measured on real hardware (see the internal `docs/FORD_NEXT_GEN.md` note).
- A successful JVM test/build does not establish DHU, Ford, wireless Android Auto, panel-off behavior, raw touch landing, media-button, or Shizuku compatibility.

## Futures / วิสัยทัศน์อนาคต

> These are direction and intent, not promises. Anything parked-only stays gated behind an authoritative `PARKED` state, and anything host- or OEM-dependent stays a device check until it is measured on real hardware.
> นี่คือทิศทางและความตั้งใจ ไม่ใช่คำสัญญา ฟีเจอร์ที่ใช้ได้เฉพาะตอนจอด (parked-only) จะยังถูกล็อกด้วยสถานะ `PARKED` ที่เชื่อถือได้เสมอ และส่วนที่ขึ้นกับ host/OEM จะยังเป็นสิ่งที่ต้องทดสอบบนฮาร์ดแวร์จริงก่อน

### Mobile (phone surface)

> These are the capabilities the phone app actually ships today — the full inventory of what the mobile surface can do right now, not just future direction. Items marked _parked-only_ are controlled from the phone but only open on the car once an authoritative `PARKED` state is present; items marked _flavor-gated_ depend on which build you installed (see the flavor note below).
> นี่คือความสามารถที่แอปบนมือถือ ทำได้จริงในวันนี้ — รายการทั้งหมดของสิ่งที่ฝั่งมือถือทำได้ตอนนี้ ไม่ใช่แค่ทิศทางในอนาคต รายการที่ระบุว่า _parked-only_ สั่งงานจากมือถือได้ แต่จะเปิดบนจอรถเมื่ออยู่ในสถานะ `PARKED` ที่เชื่อถือได้เท่านั้น ส่วนที่ระบุว่า _flavor-gated_ ขึ้นกับ build ที่ติดตั้ง (ดูหมายเหตุเรื่อง flavor ด้านล่าง)

**English**

_Home & navigation_
- Phone launcher Home with Quick Launch tiles over the full section list: TV, Radio, Web browser, YouTube, YouTube Music, Streaming (TikTok/Twitch and more), Folders, Favorites, Playlists, Gallery, Weather, Mirror, Apps, Remote, and Settings.
- Bilingual UI (English / ไทย) with a device-language option, selectable in Settings ▸ Display & control.
- A speed-safety header widget on Home (display-only; it never gates features).

_Entertainment & IPTV_
- IPTV via Xtream Codes accounts and M3U playlists, split into TV and Radio sources, with categories, catch-up entries, and a "recently played" list.
- Channel logos plus a per-list ping check that reports latency, HTTP status, "no answer", or "cannot be checked".
- Favorites and recently-played across TV, Radio, Web, and media, saved on the phone and shared with the car.
- Weather by city (Open-Meteo, no API key) that the car Weather screen reads.

_Media player & subtitles_
- Built-in Media3/ExoPlayer with a scrubber, LIVE indicator, previous/next, and a 10-second skip; it keeps playing when the screen is left (background playback) and supports picture-in-picture.
- Player options: preferred decoder (Auto/Hardware/Software), aspect ratio (Auto/Fill/Stretch/16:9/4:3), split layout, channel-change gesture, player-control density, show-delay, and picture enhancement (brightness/contrast/saturation).
- Plays on-device files and MP4/HLS/DASH stream links; MediaStore Folders, Playlists, and a photo/clip Gallery.
- On-device subtitle translation (_optional, off by default_): when compiled in, it translates a subtitle track entirely on the phone with ML Kit or Opus-MT (ONNX Runtime), with source/target language, show-original, and Wi-Fi-only model downloads. The translation stack is left out of a standard build and is only compiled in when `-Pautobridge.subtitleTranslation=true` is set; otherwise subtitles still render untranslated.

_Browser & YouTube add-ons_
- Full phone web browser: tabs, address/search bar, history, bookmarks, downloads, find-in-page, desktop-site request, custom User-Agent, clear browsing data, and reset site permissions.
- "Send to car" and a play queue: push the current page or a search to the car screen, or queue items to play next.
- YouTube add-ons (all off by default): SponsorBlock segment skipping by category, auto-highest-quality, and YouTube ad-skip — what each one does, and how to tell whether it is working, is in [YouTube add-ons](#youtube-add-ons--ส่วนเสริม-youtube).

_Mirroring & projection control_ (_parked-only_, _flavor-gated_)
- Screen mirroring/projection to the car with a foreground-service notification and a Stop action; renderer pipeline choice (AUTO_MIRROR / SELF_DRAWN), mirror rotation, crop/fit, resolution and frame-rate targets, and a head-unit profile.
- Projection automation: prevent sleep, auto-dim after inactivity, dim-now / restore-screen, panel-off on auto-dim (needs Shizuku/root, falls back to dim), and stop-on-disconnect.

_Input backends_ (_parked-only_, _flavor-gated_)
- Car-screen touch forwarding through an Accessibility service (preferred) or an optional Shizuku backend, with real (privileged) pointer injection when a raw pointer stream is available.

_App profiles & Smart Mode_ (_flavor-gated_)
- Per-app profiles for favorite apps: auto-mirror intent, auto-fullscreen, force-landscape, scale, resolution, frame rate, touch backend, audio output, keep-screen-on, and a Smart Mode preference, applied on launch.

_Remote & agent_
- A Remote/Control screen that sends commands and URLs to the car, a "send text to car" field, quick actions, and a command history (last 20) that can be re-run.
- An Agent that interprets free text/voice into actions, with per-command confirmation, haptics, resume-last-feature, and auto-submit toggles.
- An experimental self-hosted remote-stream fallback for pages that will not render on the car (never used for copy-protected services).

_Settings, updates & support_
- Settings for Display & Mirror, Input & Touch, Car & Connection, App Profiles, Video, YouTube, Agent & Commands, plus Advanced (Diagnostics log, Debug/WebView+MediaDrm report, Storage/cache reset).
- In-app "Check for updates" that compares the build against the latest GitHub release and links to the APK/release page.
- About with version/flavor, credits & attributions, open-source licenses, GitHub, and a Buy-me-a-coffee / PromptPay QR donate option (_flavor-gated_: the PromptPay row is hidden on the `safe` flavor).

_Advanced / safety override_ (_at your own risk_)
- Safety bypass: a Settings toggle (also reachable via an `adb` broadcast) that overrides the parked/mode gate so parked-only features can open in any vehicle state. Off by default; its default scope also lifts the SAFE/PERSONAL/LAB mode restriction, with a narrower "parked only" scope available. While on, an ongoing, non-dismissible notification shows the gate is open and offers a one-tap turn-off.
- Keep media playing through the reverse chime: an audio option (in the car browser's audio settings) that stops playback from pausing for the reverse-gear / rear-camera chime — and, by the same mechanism, from pausing or ducking for phone calls and navigation prompts. Off by default.

_Design / UI_
- The AutoBridge design language across the phone surface, with icon-tiled sections and bilingual labels shared with the car dashboard.

**ไทย**

_หน้าหลักและการนำทาง_
- หน้า Home บนมือถือแบบ launcher มีไทล์ Quick Launch อยู่เหนือรายการ section ทั้งหมด: TV, Radio, เว็บเบราว์เซอร์, YouTube, YouTube Music, Streaming (TikTok/Twitch และอื่น ๆ), โฟลเดอร์, Favorites, เพลย์ลิสต์, แกลเลอรี, สภาพอากาศ, Mirror, แอป, Remote และตั้งค่า
- UI สองภาษา (English / ไทย) พร้อมตัวเลือกตามภาษาเครื่อง เลือกได้ที่ ตั้งค่า ▸ Display & control
- วิดเจ็ตความเร็ว/ความปลอดภัยบนหน้า Home (แสดงผลอย่างเดียว ไม่ได้ใช้ล็อกฟีเจอร์)

_ความบันเทิงและ IPTV_
- IPTV ผ่านบัญชี Xtream Codes และเพลย์ลิสต์ M3U แยกเป็นแหล่ง TV และ Radio พร้อมหมวดหมู่ รายการ catch-up และรายการ "เล่นล่าสุด"
- โลโก้ช่อง พร้อมการ ping ทั้งลิสต์ที่รายงานค่า latency, สถานะ HTTP, "ไม่ตอบสนอง" หรือ "ตรวจสอบไม่ได้"
- Favorites และเล่นล่าสุด ของ TV, Radio, เว็บ และสื่อ บันทึกบนมือถือและแชร์กับจอรถ
- สภาพอากาศตามเมือง (Open-Meteo ไม่ต้องใช้ API key) ซึ่งหน้า Weather บนจอรถดึงไปแสดง

_เครื่องเล่นสื่อและซับไตเติล_
- เครื่องเล่นในตัวด้วย Media3/ExoPlayer มีแถบเลื่อน (scrubber), ตัวบอก LIVE, ก่อนหน้า/ถัดไป และข้าม 10 วินาที เล่นต่อได้แม้ออกจากหน้าจอ (เล่นเบื้องหลัง) และรองรับ picture-in-picture
- ตัวเลือกเครื่องเล่น: ตัวถอดรหัสที่เลือก (Auto/Hardware/Software), อัตราส่วนภาพ (Auto/Fill/Stretch/16:9/4:3), เลย์เอาต์แบบแบ่งจอ, ท่าทางเปลี่ยนช่อง, ความหนาแน่นของปุ่มควบคุม, การแสดง delay และการปรับภาพ (ความสว่าง/คอนทราสต์/ความอิ่มสี)
- เล่นไฟล์บนเครื่อง และลิงก์สตรีม MP4/HLS/DASH รวมถึง โฟลเดอร์, เพลย์ลิสต์ และแกลเลอรีรูป/คลิป จาก MediaStore
- การแปลซับไตเติลบนเครื่อง (_ทางเลือก, ปิดเป็นค่าเริ่มต้น_): เมื่อ build รวมเข้ามา จะแปลแทร็กซับไตเติลบนมือถือล้วน ๆ ด้วย ML Kit หรือ Opus-MT (ONNX Runtime) เลือกภาษาต้นทาง/ปลายทาง, แสดงต้นฉบับ และดาวน์โหลดโมเดลเฉพาะตอนต่อ Wi-Fi โดยค่าเริ่มต้นชุดการแปลจะไม่ถูก build เข้ามา และจะรวมเข้าเฉพาะเมื่อตั้ง `-Pautobridge.subtitleTranslation=true` เท่านั้น มิฉะนั้นซับไตเติลจะยังแสดงแบบไม่แปล

_เบราว์เซอร์และส่วนเสริม YouTube_
- เบราว์เซอร์บนมือถือเต็มรูปแบบ: แท็บ, แถบที่อยู่/ค้นหา, ประวัติ, บุ๊กมาร์ก, ดาวน์โหลด, ค้นหาในหน้า, ขอหน้าแบบเดสก์ท็อป, กำหนด User-Agent เอง, ล้างข้อมูลการท่องเว็บ และรีเซ็ตสิทธิ์เว็บไซต์
- "ส่งไปจอรถ" และคิวเล่น: ส่งหน้าปัจจุบันหรือการค้นหาขึ้นจอรถ หรือเพิ่มเข้าคิวให้เล่นถัดไป
- ส่วนเสริม YouTube (ปิดไว้โดยค่าเริ่มต้นทั้งหมด): ข้ามช่วงด้วย SponsorBlock ตามหมวดหมู่, เลือกคุณภาพสูงสุดอัตโนมัติ และข้ามโฆษณา YouTube — รายละเอียดแต่ละตัวและวิธีเช็กว่าทำงานหรือไม่ อยู่ที่ [YouTube add-ons](#youtube-add-ons--ส่วนเสริม-youtube)

_การ Mirror และควบคุมการฉายภาพ_ (_parked-only_, _flavor-gated_)
- Mirror/ฉายหน้าจอขึ้นจอรถ พร้อม notification ของ foreground service และปุ่ม Stop เลือก renderer pipeline (AUTO_MIRROR / SELF_DRAWN), การหมุนภาพ, crop/fit, เป้าหมายความละเอียดและเฟรมเรต และโปรไฟล์ head-unit
- ระบบอัตโนมัติของการฉายภาพ: กันเครื่องหลับ, หรี่จออัตโนมัติเมื่อไม่มีการใช้งาน, หรี่ทันที/คืนค่าหน้าจอ, ดับเฉพาะพาเนลเมื่อหรี่อัตโนมัติ (ต้องใช้ Shizuku/root ถ้าไม่ได้จะหรี่แทน) และหยุดเมื่อหลุดการเชื่อมต่อ

_แบ็กเอนด์อินพุต_ (_parked-only_, _flavor-gated_)
- ส่งต่อการสัมผัสจากจอรถผ่านบริการ Accessibility (แนะนำ) หรือแบ็กเอนด์ Shizuku (ทางเลือก) พร้อมการฉีด pointer จริง (แบบ privileged) เมื่อมีสตรีม raw pointer ให้ใช้

_โปรไฟล์รายแอปและ Smart Mode_ (_flavor-gated_)
- โปรไฟล์รายแอปสำหรับแอปโปรด: ตั้ง auto-mirror, auto-fullscreen, บังคับแนวนอน, สเกล, ความละเอียด, เฟรมเรต, แบ็กเอนด์การสัมผัส, เอาต์พุตเสียง, คงหน้าจอให้ติด และค่า Smart Mode ซึ่งจะถูกใช้ตอนเปิดแอป

_Remote และ Agent_
- หน้า Remote/Control ที่ส่งคำสั่งและ URL ขึ้นจอรถ, ช่อง "ส่งข้อความไปจอรถ", quick actions และประวัติคำสั่ง (20 รายการล่าสุด) ที่สั่งซ้ำได้
- Agent ที่ตีความข้อความ/เสียงอิสระให้เป็นการกระทำ พร้อมสวิตช์ยืนยันแต่ละคำสั่ง, การสั่นตอบสนอง, เปิดฟีเจอร์ล่าสุดต่อ และ auto-submit
- remote-stream แบบ self-hosted (ทดลอง) เป็นทางสำรองสำหรับหน้าที่เรนเดอร์บนจอรถไม่ได้ (ไม่เคยใช้กับบริการที่มีการป้องกันการคัดลอก)

_ตั้งค่า อัปเดต และการสนับสนุน_
- ตั้งค่า Display & Mirror, Input & Touch, Car & Connection, App Profiles, Video, YouTube, Agent & Commands และ Advanced (บันทึก Diagnostics, Debug/รายงาน WebView+MediaDrm, Storage/ล้างแคช)
- "Check for updates" ในแอปที่เทียบ build กับ GitHub release ล่าสุด และลิงก์ไปหน้า APK/release
- About ที่มีเวอร์ชัน/flavor, เครดิตและการอ้างอิง, ไลเซนส์โอเพนซอร์ส, GitHub และปุ่มสนับสนุนแบบ Buy-me-a-coffee / QR PromptPay (_flavor-gated_: แถว PromptPay จะถูกซ่อนในแฟลเวอร์ `safe`)

_ขั้นสูง / การปลดล็อกความปลอดภัย_ (_ยอมรับความเสี่ยงเอง_)
- ปลดล็อกข้อจำกัดความปลอดภัย: สวิตช์ในหน้าตั้งค่า (สั่งผ่าน `adb` broadcast ได้ด้วย) ที่ข้ามการเช็กจอดรถ/โหมด ทำให้ฟีเจอร์ที่ปกติใช้ได้เฉพาะตอนจอดเปิดได้ในทุกสถานะของรถ ปิดไว้เป็นค่าเริ่มต้น ค่า scope เริ่มต้นยังยกเลิกข้อจำกัดโหมด SAFE/PERSONAL/LAB ด้วย และมี scope แบบแคบ ("เฉพาะจอด") ให้เลือก ขณะเปิดอยู่จะมีการแจ้งเตือนแบบค้าง (ปัดทิ้งไม่ได้) บอกว่าระบบล็อกถูกเปิดออก พร้อมปุ่มปิดแบบแตะครั้งเดียว
- เล่นสื่อต่อผ่านเสียงเตือนถอยหลัง: ตัวเลือกเสียง (อยู่ในการตั้งค่าเสียงของเบราว์เซอร์บนจอรถ) ที่ทำให้เสียงไม่หยุดเพราะเสียงเตือนถอยรถ/กล้องหลัง และด้วยกลไกเดียวกันนี้ ก็ไม่หยุดหรือหรี่เสียงให้สายโทรเข้าหรือเสียงนำทางด้วย ปิดไว้เป็นค่าเริ่มต้น

_ดีไซน์ / UI_
- ภาษาการออกแบบ AutoBridge ตลอดฝั่งมือถือ พร้อม section แบบไทล์ไอคอนและป้ายกำกับสองภาษาที่ใช้ร่วมกับแดชบอร์ดบนจอรถ

### Car (Android Auto surface)

**English**
- More reliable mirroring across hosts: validated `AUTO_MIRROR` and opt-in `SELF_DRAWN` behaviour on a repeatable DHU + physical-host matrix, with measured frame/latency counters.
- Measured Ford/SYNC surface profiles (dimensions, DPI, visible area) replacing today's placeholder values, once a real session is available.
- Better input on the head unit where the host allows it: continued Accessibility and bounded Shizuku backends, and raw pointer input only if a supported host actually supplies the raw events.
- Validated optional panel-only screen-off on explicitly supported phone/OEM/Android combinations, always with safe fallback and power restore on teardown.
- Distribution/category compliance explored separately from the personal POC — only if and when Android Auto's rules allow it.

**ไทย**
- การ mirror ที่เสถียรขึ้นในหลาย host: ทดสอบพฤติกรรม `AUTO_MIRROR` และ `SELF_DRAWN` (แบบ opt-in) บน DHU และเครื่องจริงอย่างเป็นระบบ พร้อมวัดค่า frame/latency
- โปรไฟล์หน้าจอ Ford/SYNC ที่วัดจริง (ขนาด, DPI, พื้นที่ที่มองเห็น) มาแทนค่า placeholder ปัจจุบัน เมื่อมีเครื่องจริงให้ทดสอบ
- อินพุตบนจอรถที่ดีขึ้นเท่าที่ host อนุญาต: ยังคงใช้ Accessibility และ Shizuku แบบมีขอบเขต และจะรองรับ raw pointer เฉพาะเมื่อ host ที่รองรับส่ง event จริงมาให้เท่านั้น
- ทดสอบฟีเจอร์ดับเฉพาะหน้าจอ (panel-only screen-off) แบบ opt-in บนชุดเครื่อง/OEM/Android ที่รองรับชัดเจน พร้อม fallback ที่ปลอดภัยและคืนค่าพลังงานจอเสมอเมื่อปิดการทำงาน
- พิจารณาเรื่องการ distribute/หมวดหมู่แอปแยกจากตัว POC ส่วนตัว เฉพาะเมื่อกฎของ Android Auto เปิดให้ทำได้

ดูลำดับงานถัดไปแบบละเอียดได้ที่ [`ROADMAP.md`](ROADMAP.md) · See [`ROADMAP.md`](ROADMAP.md) for the detailed next-order list.

## APK sideload vs Store / ติดตั้งแบบ APK กับจาก Store ต่างกันอย่างไร

AutoBridge is sideloaded today. How you install it changes what the **car** can do — the **phone** app works either way.

ปัจจุบัน AutoBridge เป็นแอป sideload วิธีติดตั้งมีผลกับสิ่งที่ทำได้บน **จอรถ** ส่วนแอปบน **มือถือ** ทำงานได้เหมือนกันทั้งสองแบบ

| | APK ปกติ (plain sideload) | APK + KingInstaller (Play-origin) | Google Play (เมื่อผ่านนโยบาย) |
|---|---|---|---|
| **Phone / มือถือ** | ✅ ใช้ได้เต็ม (home, IPTV, media, browser, settings) | ✅ ใช้ได้เต็ม | ✅ ใช้ได้เต็ม |
| **Car launcher / แสดงบนจอรถ** | ❌ Android Auto มักไม่แสดงไอคอน | ✅ มักแสดง (ตั้ง installer origin เป็น Play + เปิด Unknown sources) | ✅ แสดงปกติ |
| **Updates / อัปเดต** | ผู้ใช้โหลด APK เอง + In-app "Check for updates" | เหมือนกัน แต่ลงทับผ่าน KingInstaller ทุกครั้ง | อัปเดตผ่าน Play อัตโนมัติ |
| **Setup effort / ความยุ่งยาก** | ต่ำ | ต่ำ–ปานกลาง (วิธี Classic ไม่ต้องตั้งอะไร, Shizuku/root เฉพาะเมื่อไม่ผ่าน) | ต่ำที่สุด |
| **Status / สถานะ** | ใช้ได้วันนี้ | ใช้ได้วันนี้ (วิธีที่แนะนำสำหรับจอรถ) | ยังไม่การันตี — ขึ้นกับนโยบาย Android Auto |

**Why the difference / ทำไมต่างกัน:**
Google's Android Auto policy does not generally recognise an app installed by plain APK sideloading, so it may not appear in the car launcher even though Android's service resolver sees it. The project's current car surface is a `NavigationTemplate` custom-surface POC for personal/development use, so Play/Android Auto approval is not guaranteed.

นโยบาย Android Auto ของ Google โดยทั่วไปจะไม่ยอมรับแอปที่ติดตั้งด้วย APK แบบปกติ จึงอาจไม่โผล่ในหน้า launcher ของรถแม้ระบบ Android จะมองเห็นแอปแล้ว ตัวจอรถของโปรเจกต์นี้เป็น POC แบบ `NavigationTemplate` สำหรับใช้งานส่วนตัว/พัฒนา ดังนั้นการผ่านนโยบาย Play/Android Auto จึงยังไม่การันตี

> **Note:** AutoBridge never bypasses DRM, and parked-only features (mirror, video, browser, touch, Quick App launch) always require an authoritative `PARKED` state regardless of how it was installed.
> **หมายเหตุ:** AutoBridge ไม่หลบ DRM และฟีเจอร์ที่ใช้ได้เฉพาะตอนจอด (mirror, วิดีโอ, เบราว์เซอร์, ทัช, เปิด Quick App) ต้องการสถานะ `PARKED` ที่เชื่อถือได้เสมอ ไม่ว่าจะติดตั้งแบบไหน

## Installation / ขั้นตอนการติดตั้ง

### A. Phone app only (plain APK) / ติดตั้งแอปบนมือถืออย่างเดียว

**English**
1. Download the APK (a release from the project, or build one yourself — see [Build and test](#build-and-test)).
2. On the phone, allow installing from your browser/file manager (Settings → Apps → Install unknown apps).
3. Open the APK and tap **Install**.
4. Launch AutoBridge; the phone home, IPTV/media, and browser work immediately. The car launcher may not show it yet — continue with section B for the car.

**ไทย**
1. ดาวน์โหลดไฟล์ APK (จาก release ของโปรเจกต์ หรือ build เอง — ดูหัวข้อ [Build and test](#build-and-test))
2. บนมือถือ อนุญาตติดตั้งจากเบราว์เซอร์/ตัวจัดการไฟล์ (ตั้งค่า → แอป → ติดตั้งแอปที่ไม่รู้จัก)
3. เปิดไฟล์ APK แล้วกด **ติดตั้ง**
4. เปิด AutoBridge หน้าโฮม, IPTV/สื่อ และเบราว์เซอร์บนมือถือใช้ได้ทันที แต่จอรถอาจยังไม่แสดงไอคอน ให้ทำตามหัวข้อ B ต่อสำหรับจอรถ

### B. Show it on the car (KingInstaller + Play-origin install) / ให้แสดงบนจอรถ

This is the recommended path to make Android Auto show AutoBridge. Follow
[KingInstaller](https://github.com/fcaronte/KingInstaller)'s own three-step order and start with the
method that needs no privileges at all: on most phones the **Classic** method is enough, and Shizuku
or root only come into it if that fails.

วิธีที่แนะนำเพื่อให้ Android Auto แสดง AutoBridge โดยทำตามลำดับ 3 ขั้นของ
[KingInstaller](https://github.com/fcaronte/KingInstaller) เอง เริ่มจากวิธีที่ไม่ต้องใช้สิทธิ์พิเศษเลย —
บนเครื่องส่วนใหญ่แค่วิธี **Classic** ก็พอ ส่วน Shizuku หรือ root จะใช้ก็ต่อเมื่อวิธีแรกไม่ผ่าน

#### Tools to download first / เครื่องมือที่ต้องโหลดก่อน

KingInstaller is the one app you always need; Shizuku only comes in for the **Shizuku Trick**, the
second of its three methods, and is not needed at all if the first one works. Neither is
written by, bundled with, or affiliated with AutoBridge — download them from their own projects
only, and check the source before installing anything that claims to be either of them.

KingInstaller เป็นแอปเดียวที่ต้องใช้แน่ ๆ ส่วน Shizuku ใช้เฉพาะตอนถอยไปวิธี **Shizuku Trick** (วิธีที่ 2 จาก 3 วิธีของมัน)
ถ้าวิธีแรกผ่านก็ไม่ต้องลงเลย ทั้งคู่เป็นของผู้พัฒนาอื่น
ไม่ได้มาพร้อม AutoBridge และไม่มีความเกี่ยวข้องกัน ให้โหลดจากโปรเจกต์ต้นทางเท่านั้น

| Tool | Source / download | What it does / ใช้ทำอะไร |
|---|---|---|
| **KingInstaller** by fcaronte<br>_required / ต้องใช้_ | [Source](https://github.com/fcaronte/KingInstaller) · [Releases (APK)](https://github.com/fcaronte/KingInstaller/releases/latest) | Installs an APK while recording the Play Store as its installer, which is what makes Android Auto list a sideloaded app. Carries three install methods (Classic / Shizuku Trick / Root Trick), an **App Diagnostic Checker** and an **Auto-Fixer**. APK only — it is not on Google Play / ติดตั้ง APK โดยบันทึกค่า installer เป็น Play Store ซึ่งเป็นเงื่อนไขที่ทำให้ Android Auto ยอมแสดงแอปที่ sideload มา มีสามวิธีในตัว (Classic / Shizuku Trick / Root Trick) พร้อมเครื่องมือตรวจสถานะแอปและตัวซ่อมค่า installer อัตโนมัติ มีเฉพาะไฟล์ APK ไม่มีบน Play |
| **Shizuku** by RikkaApps<br>`moe.shizuku.privileged.api`<br>_only if Classic fails / เฉพาะเมื่อ Classic ไม่ผ่าน_ | [Source](https://github.com/RikkaApps/Shizuku) · [Releases (APK)](https://github.com/RikkaApps/Shizuku/releases/latest) · [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) · [IzzyOnDroid F-Droid repo](https://apt.izzysoft.de/fdroid/index/apk/moe.shizuku.privileged.api) · [shizuku.rikka.app](https://shizuku.rikka.app/)<br>**Shizuku-Next** by rushiranpise: [Source](https://github.com/rushiranpise/Shizuku-Next) | Hands ADB-level privileges to apps that ask, started over Android 11+ wireless debugging with no PC and no root. Feeds KingInstaller's Shizuku Trick, and is optionally AutoBridge's touch-input backend. KingInstaller recommends the **Shizuku-Next** fork, which can start again without Wi-Fi / ให้สิทธิ์ระดับ ADB กับแอปที่ขอ เริ่มผ่าน wireless debugging ของ Android 11+ ไม่ต้องใช้คอมและไม่ต้องรูท ใช้กับ Shizuku Trick ของ KingInstaller และเป็น input backend ของ AutoBridge ได้ด้วย ทาง KingInstaller แนะนำ **Shizuku-Next** เพราะเริ่มเองได้แม้ไม่มี Wi-Fi |

Versions seen on 2026-10-05: Shizuku `v13.6.0`, KingInstaller `v2.3` (`KingInstaller-v2.3.apk`).
The links above point at each project's *latest* release, so they stay correct as those projects move
on. KingInstaller states that the Classic method works on many stock devices and custom ROMs on
Android 10–16; on Xiaomi/POCO/Redmi (MIUI, HyperOS) it says Shizuku **usually fails** and root is
currently the only reliable method, so those phones go straight to the **Root Trick**.

เวอร์ชันที่ตรวจเมื่อ 2026-10-05: Shizuku `v13.6.0`, KingInstaller `v2.3` — ลิงก์ด้านบนชี้ที่ release ล่าสุดของแต่ละโปรเจกต์
KingInstaller ระบุว่าวิธี Classic ใช้ได้กับเครื่อง stock และ custom ROM จำนวนมากบน Android 10–16 ส่วนเครื่อง
Xiaomi/POCO/Redmi (MIUI, HyperOS) ทาง Shizuku **มักไม่ผ่าน** และตอนนี้ root เป็นวิธีเดียวที่เชื่อถือได้ เครื่องกลุ่มนี้จึงข้ามไปใช้ **Root Trick** ได้เลย

**English**
1. **Install KingInstaller and point it at the APK:** install `KingInstaller-v*.apk` from its [Releases](https://github.com/fcaronte/KingInstaller/releases/latest), open it, tap the folder icon and pick the AutoBridge APK (the car route is compiled into `personal`/`lab` only).
2. **Step 1 — Classic (try this first):** "just select your APK and hit **Install** normally (without enabling any switches)". No Shizuku, no root. KingInstaller says this is enough on many stock devices and custom ROMs on Android 10–16, where the system installer records the origin by itself.
3. **Step 2 — Shizuku Trick (only if Classic fails):** if the app is not flagged as coming from the Play Store, or Android Auto still rejects it, start Shizuku and install again with the **Shizuku** switch on.
   - Enable Developer Options: Settings → About phone → tap **Build number** 7 times.
   - Turn on **Wireless debugging**, pair in the Shizuku app with the pairing code, tap **Start** until it reports "running" (Android 11+ needs no PC).
   - Grant KingInstaller Shizuku access — **Allow all the time**.
   - KingInstaller recommends the [Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next) fork, which can start again even without Wi-Fi.
4. **Step 3 — Root Trick (last resort):** turn the **Root** switch on when both of the above fail against heavy vendor restrictions. On Xiaomi/POCO/Redmi (MIUI, HyperOS) go here directly: KingInstaller states Shizuku usually fails there and root is currently the only reliable method.
5. **Verify with the Golden Rule:** open KingInstaller's **App Diagnostic Checker** and look at AutoBridge — **"Installed by" MUST be the Play Store**, and "Requested by" should ideally be the Package Installer. If it is wrong, reinstall one step further down the list; KingInstaller's **Auto-Fixer** also repairs the installer identity right after an install whenever Shizuku or root is available.
6. **Enable Unknown sources in Android Auto:** open Android Auto settings → tap **Version** 10 times → **Developer settings** → check **Unknown sources**.
7. **Connect to the car** (USB or wireless Android Auto) and open the Android Auto launcher — AutoBridge should now appear. If not, check **Customize launcher** in Android Auto.
8. Shizuku (or root) is only needed for installing/updating; you do **not** need it running while using the app in the car. A later plain reinstall over the top resets the recorded installer, so update through KingInstaller too.

**ไทย**
1. **ลง KingInstaller แล้วเลือกไฟล์ APK:** ติดตั้ง `KingInstaller-v*.apk` จาก [Releases](https://github.com/fcaronte/KingInstaller/releases/latest) เปิดแอป → แตะไอคอนโฟลเดอร์ → เลือกไฟล์ APK ของ AutoBridge (เส้นทางจอรถคอมไพล์เข้าเฉพาะ `personal`/`lab`)
2. **ขั้น 1 — Classic (ลองอันนี้ก่อน):** เลือกไฟล์ APK แล้วกด **Install** ตามปกติ **โดยไม่เปิดสวิตช์ใด ๆ** ไม่ต้องใช้ Shizuku ไม่ต้องรูท ทาง KingInstaller ระบุว่าวิธีนี้พอแล้วสำหรับเครื่อง stock และ custom ROM จำนวนมากบน Android 10–16 เพราะ system installer บันทึกค่า origin ให้เอง
3. **ขั้น 2 — Shizuku Trick (เฉพาะเมื่อ Classic ไม่ผ่าน):** ถ้าค่า installer ไม่ขึ้นเป็น Play Store หรือ Android Auto ยังไม่ยอมรับ ให้เริ่ม Shizuku แล้วติดตั้งใหม่โดยเปิดสวิตช์ **Shizuku**
   - เปิด Developer Options: ตั้งค่า → เกี่ยวกับโทรศัพท์ → แตะ **หมายเลขบิลด์ (Build number)** 7 ครั้ง
   - เปิด **Wireless debugging** → จับคู่ในแอป Shizuku ด้วยรหัส pairing → กด **Start** จนขึ้นสถานะ "running" (Android 11+ ไม่ต้องใช้คอมพิวเตอร์)
   - อนุญาตให้ KingInstaller ใช้ Shizuku แบบ **Allow all the time**
   - ทาง KingInstaller แนะนำ [Shizuku-Next](https://github.com/rushiranpise/Shizuku-Next) เพราะเริ่มเองได้แม้ไม่มี Wi-Fi
4. **ขั้น 3 — Root Trick (ทางสุดท้าย):** เปิดสวิตช์ **Root** เมื่อสองวิธีแรกไม่ผ่านเพราะข้อจำกัดของผู้ผลิต เครื่อง Xiaomi/POCO/Redmi (MIUI, HyperOS) ข้ามมาขั้นนี้ได้เลย เพราะ KingInstaller ระบุว่า Shizuku มักไม่ผ่านบนเครื่องกลุ่มนี้ และตอนนี้ root เป็นวิธีเดียวที่เชื่อถือได้
5. **ตรวจด้วย Golden Rule:** เปิด **App Diagnostic Checker** ใน KingInstaller แล้วดูที่ AutoBridge — ช่อง **"Installed by" ต้องเป็น Play Store เท่านั้น** ส่วน "Requested by" ควรเป็น Package Installer ถ้ายังไม่ถูกให้ติดตั้งซ้ำด้วยวิธีขั้นถัดไป ทั้งนี้ **Auto-Fixer** ของ KingInstaller จะซ่อมค่า installer ให้อัตโนมัติหลังติดตั้งเสร็จถ้ามี Shizuku หรือ root อยู่แล้ว
6. **เปิด Unknown sources ใน Android Auto:** เปิดการตั้งค่า Android Auto → แตะ **Version** 10 ครั้ง → **Developer settings** → ติ๊ก **Unknown sources**
7. **เชื่อมต่อกับรถ** (ผ่าน USB หรือ Wireless Android Auto) แล้วเปิด launcher ของ Android Auto จะเห็นไอคอน AutoBridge หากไม่เห็นให้เช็ก **Customize launcher** ใน Android Auto
8. Shizuku (หรือ root) จำเป็นเฉพาะตอนติดตั้ง/อัปเดตเท่านั้น ตอนใช้งานในรถ **ไม่ต้อง** เปิดค้างไว้ และถ้าภายหลังลงทับด้วยวิธีปกติ ค่า installer จะถูกรีเซ็ต จึงควรอัปเดตผ่าน KingInstaller ทุกครั้ง

> **Step-by-step guide:** [quick-install.md](quick-install.md) covers this in full — which flavor to build (the car route is compiled into `personal`/`lab` only), KingInstaller's Classic → Shizuku Trick → Root Trick order (Xiaomi/MIUI needs the Root Trick), the in-app **Enable on Android Auto** button for a build that is already installed, Play Protect, and how to verify the recorded installer.
> **คู่มือละเอียด:** [quick-install.md](quick-install.md) อธิบายครบ — ต้อง build flavor ไหน (เส้นทางจอรถคอมไพล์เข้าเฉพาะ `personal`/`lab`), ลำดับ Classic → Shizuku Trick → Root Trick ของ KingInstaller (Xiaomi/MIUI ต้องใช้ Root Trick), ปุ่ม **Enable on Android Auto** ในแอปสำหรับเครื่องที่ลงไปแล้ว, Play Protect และวิธีตรวจค่า installer ที่ระบบบันทึกไว้

> Developer/signed APK details, keystore, and release signing are in [Installing on a physical phone](#installing-on-a-physical-phone) and [Google Play upload](#google-play-upload).
> รายละเอียด APK แบบ debug/signed, keystore และการเซ็น release อยู่ที่หัวข้อ [Installing on a physical phone](#installing-on-a-physical-phone) และ [Google Play upload](#google-play-upload)

### C. In-app updates / อัปเดตในแอป

**Settings → About → Check for updates** asks GitHub for the latest release and offers the APK and release page when a newer `versionName` exists. It only runs when tapped, sends no identity beyond an `AutoBridge/<version>` User-Agent, and never replaces itself — installing stays with the browser and the package installer.

**ตั้งค่า → เกี่ยวกับ → ตรวจหาอัปเดต** จะถาม GitHub หาเวอร์ชันล่าสุด และเสนอ APK กับหน้า release เมื่อมี `versionName` ใหม่กว่า ทำงานเฉพาะตอนกดเท่านั้น ไม่ส่งข้อมูลระบุตัวตนใดนอกจาก User-Agent `AutoBridge/<version>` และไม่ติดตั้งทับตัวเอง การติดตั้งยังทำผ่านเบราว์เซอร์และตัวติดตั้งแพ็กเกจตามปกติ

## Permissions / สิทธิ์ที่แอปขอ

Updated: 2026-10-05. Every runtime permission below is asked for at the moment the feature that
needs it is used — the one exception is the notification permission, which the phone app asks for on
first launch because the playback and mirroring services cannot run without a notification. Each
sensitive permission shows an in-app explanation before Android's own prompt. The app runs without
any of the optional ones: the feature behind it is what stops working, not the app. The Play Console
wording and the Data safety answers live in [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md).

ทุกสิทธิ์ด้านล่างจะขอ **ตอนใช้ฟีเจอร์นั้นจริง ๆ** ยกเว้นสิทธิ์การแจ้งเตือนที่ขอตอนเปิดแอปครั้งแรก เพราะเซอร์วิสเล่นสื่อ
และฉายจอทำงานไม่ได้ถ้าไม่มีการแจ้งเตือน และสิทธิ์ที่อ่อนไหวจะมีคำอธิบายในแอปก่อนขึ้นกล่องของระบบ
ถ้าไม่ให้สิทธิ์ที่เป็น "ทางเลือก" แอปยังใช้งานได้ปกติ เพียงแต่ฟีเจอร์นั้นจะไม่ทำงาน

| Permission | What it is used for / ใช้กับอะไร | Asked when / ขอเมื่อ | Required? |
|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | IPTV playlists and streams, the browser, weather, SponsorBlock lookups, update checks / เพลย์ลิสต์และสตรีม IPTV, เบราว์เซอร์, สภาพอากาศ, SponsorBlock, ตรวจอัปเดต | Install time (normal permission) | Yes |
| `POST_NOTIFICATIONS` | The playback and mirroring notifications that keep their foreground services alive / การแจ้งเตือนของการเล่นสื่อและการฉายจอ ซึ่งเป็นตัวค้ำ foreground service | First launch of the phone app, and again from the car setup screen if it was denied | Effectively yes |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Audio/video that keeps playing with the screen off or another app in front / เล่นเสียง-วิดีโอต่อเมื่อปิดจอหรือสลับแอป | Install time | Yes |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` + screen-capture consent | Mirroring the phone screen onto the car display; Android asks for capture consent per session and it cannot be remembered / ฉายจอมือถือขึ้นจอรถ — ระบบขอยืนยันการจับภาพทุกครั้งที่เริ่ม และจำค่าไว้ไม่ได้ | Each time mirroring starts | Mirroring only |
| `WAKE_LOCK` | Prevent-sleep and auto-dim while a car session runs / กันเครื่องหลับและหรี่จออัตโนมัติระหว่างต่อรถ | Install time | Mirroring only |
| `RECORD_AUDIO` | Voice commands on the car Agent screen (through the car microphone where the head unit offers one) and the phone Home microphone. With a Whisper model downloaded, speech is transcribed on the phone, offline. Recording starts on the mic press and ends on a pause, a result, an error or a timeout; nothing is stored or sent to this project / คำสั่งเสียงบนหน้า Agent ในรถ (ใช้ไมค์ของรถถ้ามี) และไมค์หน้าแรกบนมือถือ ถ้าดาวน์โหลดโมเดล Whisper ไว้ จะถอดเสียงบนมือถือแบบออฟไลน์ เริ่มอัดเมื่อกดไมค์และหยุดเมื่อเงียบ/ได้ผล/ผิดพลาด/หมดเวลา ไม่เก็บและไม่ส่งไปที่ไหน | First mic press | Voice only |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Two separate uses, foreground only, never in the background: a web page calling `navigator.geolocation` (asked per HTTPS site), and the opt-in **GPS speed** fallback for the parked/moving decision on head units that report no speed / สองกรณีและเฉพาะตอนเปิดแอป ไม่มีการใช้เบื้องหลัง: หน้าเว็บเรียก `navigator.geolocation` (ถามแยกต่อเว็บไซต์) และ **GPS speed** ที่ผู้ใช้เปิดเองสำหรับตัดสินว่ารถจอดหรือวิ่ง เมื่อจอรถไม่รายงานความเร็ว | On the page's request, or on enabling GPS speed | Optional |
| `READ_MEDIA_AUDIO` / `_VIDEO` / `_IMAGES` (`READ_EXTERNAL_STORAGE` on Android 12L and older) | Read-only listing for the Folders, Playlists and Gallery sections / อ่านรายการไฟล์สำหรับหัวข้อ โฟลเดอร์, เพลย์ลิสต์ และแกลเลอรี (อ่านเท่านั้น) | Opening one of those sections | Optional |
| `BLUETOOTH_CONNECT` | Only so Android delivers the `ACL_CONNECTED` broadcast, which is what the "start media when the car connects" option listens for. No device is read and nothing is scanned / มีไว้ให้ระบบส่ง broadcast `ACL_CONNECTED` มาเท่านั้น ซึ่งเป็นตัวจุดออปชัน "เริ่มเล่นสื่อเมื่อต่อรถ" ไม่อ่านข้อมูลอุปกรณ์และไม่สแกนหาอะไร | Enabling Bluetooth media auto-start | Optional |
| `BIND_ACCESSIBILITY_SERVICE` (special) | The input path back from the car screen: it performs the taps, swipes and Back/Home that are made on the car display, on the phone. Subscribes to `typeWindowStateChanged` only, reads no screen content, and nothing from it leaves the device. Shizuku is offered first where available; without either, mirroring is view-only / ทางส่งอินพุตกลับจากจอรถ — แตะ/ปัด/Back/Home ที่ทำบนจอรถ ให้ไปเกิดบนมือถือ รับเฉพาะ `typeWindowStateChanged` ไม่อ่านเนื้อหาบนหน้าจอ และไม่มีข้อมูลออกจากเครื่อง ถ้ามี Shizuku จะเสนอ Shizuku ก่อน ถ้าไม่ให้ทั้งคู่ การฉายจอจะเป็นแบบดูได้แต่กดไม่ได้ | Turning on touch control | Touch control only |
| `WRITE_SETTINGS` (special) | The per-app force-landscape profile, which writes the rotation setting while a quick app is open / โปรไฟล์บังคับแนวนอนต่อแอป ซึ่งต้องเขียนค่าการหมุนจอตอนเปิดแอปนั้น | Turning that profile on | Optional |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows Android's "allow background activity" prompt from the car setup screen. Some OEM battery managers (MIUI/HyperOS and similar) kill the mirroring and playback services without it, even though both are declared and started correctly / เรียกกล่อง "อนุญาตให้ทำงานเบื้องหลัง" ของระบบจากหน้าตั้งค่ารถ เพราะตัวจัดการแบตของบางยี่ห้อ (MIUI/HyperOS ฯลฯ) ฆ่าเซอร์วิสฉายจอ/เล่นสื่อ แม้จะประกาศและเริ่มถูกต้องแล้ว | Optional step in car setup | Optional |
| `androidx.car.app.ACCESS_SURFACE`, `NAVIGATION_TEMPLATES`, `com.google.android.gms.permission.CAR_SPEED` | The Android Auto surface itself: drawing on the car video surface, the templates that route needs, and the host's own speed value for the parked/moving decision / ตัวหน้าจอ Android Auto เอง — วาดบน surface วิดีโอของรถ, เทมเพลตที่เส้นทางนี้ต้องใช้ และค่าความเร็วจาก host สำหรับตัดสินจอด/วิ่ง | Install time | Car surface |
| `QUERY_ALL_PACKAGES` | **Sideload builds only** (`personal`/`lab`): Duo Screen's pane picker lists every launchable app, which package visibility hides on Android 11+. The Play (`safe`) build has neither the picker nor this permission / **เฉพาะบิลด์ sideload** (`personal`/`lab`) — ตัวเลือกแอปของ Duo Screen ต้องเห็นรายการแอปทั้งหมด ซึ่ง Android 11+ ซ่อนไว้ บิลด์ `safe` ที่ขึ้น Play ไม่มีทั้งฟีเจอร์และสิทธิ์นี้ | Install time, sideload flavors | No (not in Play build) |

Not requested at all: contacts, call logs, SMS, background location, `MANAGE_EXTERNAL_STORAGE`,
`REQUEST_INSTALL_PACKAGES` (the update check hands the APK to the browser and the system installer
instead), and anything that would let the app install or update itself.

ไม่ได้ขอเลย: รายชื่อผู้ติดต่อ, ประวัติการโทร, SMS, ตำแหน่งเบื้องหลัง, `MANAGE_EXTERNAL_STORAGE`,
`REQUEST_INSTALL_PACKAGES` (ตัวตรวจอัปเดตส่งไฟล์ APK ให้เบราว์เซอร์กับตัวติดตั้งของระบบทำต่อ)
และอะไรที่จะทำให้แอปติดตั้งหรืออัปเดตตัวเองได้

## YouTube add-ons / ส่วนเสริม YouTube

All three are **off by default** in Settings → YouTube, and all three run inside the app's own
WebView — on the phone and on the car screen — from one implementation. None of them is a
guarantee: each one drives YouTube's page from the outside, and YouTube can change that page at any
time.

ทั้งสามอย่าง **ปิดเป็นค่าเริ่มต้น** (ตั้งค่า → YouTube) และทำงานใน WebView ของแอปเอง ทั้งบนมือถือและบนจอรถ
ด้วยโค้ดชุดเดียวกัน แต่ไม่มีอะไรรับประกันได้ 100% เพราะทุกตัวสั่งหน้าเว็บ YouTube จากภายนอก และ YouTube
เปลี่ยนหน้าเว็บของตัวเองได้ทุกเมื่อ

**SponsorBlock** asks the public [SponsorBlock](https://sponsor.ajay.app/) database which stretches
of the video other people marked as sponsor reads, self-promotion, "like and subscribe" and so on,
then skips them. Only `sponsor`, `selfpromo` and `interaction` are on when the feature is switched
on; intro, outro, preview, non-music and filler are opt-in, because skipping those by default would
cut content people came to watch. The request carries the **first four hex characters of the
SHA-256 of the video id**, not the id: the server answers with every video in that bucket — roughly
1 in 65,536 — and the match is made on the phone. Only `skip` segments are acted on; `mute` and
`full` are ignored.

How the skipping is wired, and what it survives:

- The listener sits on the **document in the capture phase**, not on one `<video>` element, so a
  player YouTube builds or swaps out after the lookup finished is still covered. (An earlier
  version attached to whatever `<video>` existed at the moment it armed, and never retried — on a
  slow connection that was the common case, and nothing was skipped at all.)
- The jump prefers the player's own `seekTo`, which carries the progress bar with it, and falls back
  to writing `currentTime`.
- A segment the player refuses to leave — an unbuffered range on a stream still loading — is tried
  four times and then left alone, instead of re-seeking four times a second for its whole length.
- Nothing is skipped while the page is on a different video than the one that was looked up, or
  while an ad is playing: the ad has its own clock, and the previous video's timestamps must not cut
  into the next one.
- A failed lookup is not cached, so reopening the video asks again. There is one retry, 1.5 s apart;
  beyond that nothing polls, because the database is a free public service.

**Auto-highest-quality** asks the page's own player for its quality list and selects the first
entry, once per video, about a second after the page settles. On a metered connection that is a
deliberate data cost, which is why it is off by default.

**Ad-skip** presses YouTube's own Skip button when there is one and otherwise seeks the ad to its
end, and hides the feed/watch-page ad slots with a stylesheet. It cannot block the ad request
itself: YouTube serves ad video from the same `googlevideo.com` host as the video asked for, and
describes the break inside the same `/youtubei/v1/player` reply that carries the stream URLs — drop
either and there is no playback. So an ad may flash up for a frame or two before the seek lands, and
YouTube may answer ad blocking with an interstitial of its own, which nothing here tries to defeat.

**If an add-on seems to do nothing.** Every selector and player call above is YouTube's own internal
naming, and a change there stops a skip quietly. Each attempt is logged under the `YOUTUBE` tag, and
the entries are readable without a computer — the phone's **Developer tools** screen and the car's
own log screen show the same ring — or with `adb logcat -s YOUTUBE:I`. What the lines mean:

| Line | Meaning |
|---|---|
| `SponsorBlock 3 segment(s) -> armed` | The page accepted the script and the skipper is live. |
| `… -> updated` | Already armed on this document; only the segment list was replaced. |
| `SponsorBlock: no segments` | The database knows nothing for this video — common outside large English-language channels. Not a bug. |
| `SponsorBlock lookup failed (1/2): …` | Network, not the page. It retries once and asks again next time the video is opened. |
| `auto quality -> no-player` | The script ran before the player existed; the next navigation tries again. |

**ถ้ารู้สึกว่าส่วนเสริมไม่ทำงาน** — ชื่อ selector และ API ที่ใช้เป็นของภายใน YouTube ทั้งหมด พอ YouTube แก้
การข้ามก็จะหยุดเงียบ ๆ แอปเขียน log ทุกครั้งที่พยายามไว้ใต้แท็ก `YOUTUBE` ซึ่งดูได้ในแอปเลยที่หน้า
**Developer tools** บนมือถือ หรือหน้า log บนจอรถ (ข้อมูลชุดเดียวกัน) หรือใช้ `adb logcat -s YOUTUBE:I`
ความหมายของแต่ละบรรทัดอยู่ในตารางด้านบน: `armed` คือหน้าเว็บรับสคริปต์แล้ว, `updated` คืออาร์มไว้ก่อนแล้ว,
`no segments` คือฐานข้อมูลไม่มีข้อมูลของคลิปนี้ (ไม่ใช่บั๊ก) และ `lookup failed` คือปัญหาที่เน็ต ไม่ใช่ที่หน้าเว็บ

## Documentation

- [`ROADMAP.md`](ROADMAP.md) — implemented scope and remaining work

The following are kept as internal development notes under `docs/` in the working tree and are intentionally not published with the repository:

- `docs/ARCHITECTURE.md` — ownership and runtime boundaries
- `docs/MODES.md` — SAFE/PERSONAL/LAB variants
- `docs/FEATURE_POLICY.md` — centralized feature decisions
- `docs/MIRROR_ENGINE.md` — projection/surface/reconnect lifecycle
- `docs/INPUT_SYSTEM.md` — transforms and input backends
- `docs/APP_PROFILES.md` — Quick Apps, profiles, Smart Mode
- `docs/LAB_MODE.md` — emulator/DHU development boundaries
- `docs/FORD_NEXT_GEN.md` — measured Ford profile procedure
- `docs/REFERENCE_PROJECTS.md` — independent reference matrix
- `docs/SCREENON_AUTO_INTEGRATION.md` — independent ScreenOnAuto feature mapping and limits
- `docs/YOUTUBE_ADDONS.md` — optional SponsorBlock and quality behaviour
- `docs/DHU_SCENARIOS.md` — manual host/device scenarios
- `docs/FORD_TEST.md` — DHU/Ford safety checklist
- `docs/CARVIEW_PARITY.md` — product-reference comparison
- `docs/PROGRESS.md` — current verification record

## iOS companion app / แอปคู่ฝั่ง iOS

**English**

A separate SwiftUI app lives in [`ios/`](ios/README.md). It is not a port of the mirroring runtime — iOS blocks that outright — but it now carries the whole entertainment surface: TV and Radio over Xtream Codes and M3U (live, VOD and series), source management and the public-list picker, channel logos, the automatic channel check, favourites and recently-played, the in-app browser with the same opt-in YouTube add-ons, a shared player with lock-screen controls and background audio, and a CarPlay **audio** scene for the car. Credentials live in the keychain, the UI is English and Thai, and the ported logic is pinned down by the same rules the Android tests assert. What iOS cannot do — screen mirroring, touch injection, an arbitrary video surface on the car display — is stated in the app's own Settings rather than left to be discovered.

**ไทย**

แอป SwiftUI แยกต่างหากอยู่ใน [`ios/`](ios/README.md) ไม่ใช่การพอร์ตระบบมิเรอร์ (iOS ทำไม่ได้โดยตรง) แต่ตอนนี้มีส่วนความบันเทิงครบแล้ว ได้แก่ ทีวีและวิทยุผ่าน Xtream Codes และ M3U (ช่องสด หนัง และซีรีส์) การจัดการแหล่งข้อมูลและตัวเลือกรายการสาธารณะ โลโก้ช่อง การตรวจสอบช่องอัตโนมัติ รายการโปรดและรายการที่เล่นล่าสุด เบราว์เซอร์ในแอปพร้อมส่วนเสริม YouTube แบบเลือกเปิดเอง เครื่องเล่นกลางที่ควบคุมจากหน้าจอล็อกและเล่นเสียงเบื้องหลังได้ และหน้าจอ CarPlay แบบ **เสียง** สำหรับในรถ ข้อมูลรับรองเก็บใน keychain หน้าตาแอปมีทั้งภาษาอังกฤษและไทย และตรรกะที่พอร์ตมาถูกยืนยันด้วยกฎชุดเดียวกับที่เทสต์ฝั่ง Android ตรวจไว้ ส่วนที่ iOS ทำไม่ได้ — มิเรอร์หน้าจอ ส่งคำสั่งแตะ และแสดงวิดีโออิสระบนจอรถ — ระบุไว้ในหน้าตั้งค่าของแอปเอง ไม่ปล่อยให้ผู้ใช้ไปค้นพบเอง

## Support

AutoBridge is a personal, non-commercial project. If it is useful to you and you would like to buy the developer a coffee, support is welcome but entirely optional.

- **Buy Me a Coffee:** [buymeacoffee.com/guitar.story](https://buymeacoffee.com/guitar.story)
- **PromptPay (Thailand):** scan the QR below with your banking app

<img src="assets/donate_promptpay_qr.png" alt="PromptPay donate QR" width="260" />

Developed by Guitar-Story Thailand.

## Inspiration / แรงบันดาลใจ

AutoBridge is an independent implementation under the `dev.autobridge` package. The projects below informed its behaviour and architecture only — no upstream source tree or asset was merged into this repository, and each project's own license/terms stay independent of AutoBridge's.

AutoBridge เป็น implementation อิสระภายใต้แพ็กเกจ `dev.autobridge` โปรเจกต์ด้านล่างเป็นเพียง "แรงบันดาลใจ" ด้านพฤติกรรมและสถาปัตยกรรมเท่านั้น ไม่มีการนำ source หรือ asset ของโปรเจกต์ต้นทางมารวมไว้ในรีโปนี้ และไลเซนส์/เงื่อนไขของแต่ละโปรเจกต์ยังเป็นอิสระจาก AutoBridge

- **[MirrorMobile](https://github.com/chenxiaolong/MirrorMobile)** (GPL-3.0) — Android Auto surface lifecycle, authoritative `CAR_SPEED`, and fail-closed parked behaviour. No GPL source was copied into this MIT tree. / วงจรชีวิตเซอร์เฟซบน Android Auto, `CAR_SPEED` ที่เชื่อถือได้ และพฤติกรรม fail-closed ตอนจอด (ไม่มีการคัดลอกซอร์ส GPL เข้ามาในทรี MIT นี้)
- **[ScreenOnAuto](https://github.com/slzn/ScreenOnAuto-releases)** — touch UX, Quick Apps, force-landscape, optional privileged input, MediaSession, and screen-off ideas, credited as a behavioural/documentation reference. / แนวคิดเรื่อง touch UX, Quick Apps, บังคับแนวนอน, อินพุตแบบ privileged (ทางเลือก), MediaSession และ screen-off โดยอ้างเป็นแหล่งอ้างอิงเชิงพฤติกรรม/เอกสาร
- **[Fermata-Xtream](https://github.com/malebuffy/Fermata-Xtream)** (GPL-3.0) — the home section set and ordering (TV, Radio, Web, YouTube family, Folders, Favorites, Playlists, Gallery) and the Xtream Codes account flow. No upstream source or assets were copied into this MIT tree. / ชุดและลำดับ section หน้าหลัก (TV, Radio, เว็บ, กลุ่ม YouTube, โฟลเดอร์, Favorites, เพลย์ลิสต์, แกลเลอรี) และ flow บัญชี Xtream Codes (ไม่มีการคัดลอกซอร์สหรือ asset เข้ามาในทรี MIT นี้)

## License

AutoBridge is MIT licensed — see [`LICENSE`](LICENSE). The same file covers the separate [`ios/`](ios/README.md) tree.

MIT is a deliberate choice, not a leftover. Two of the reference projects tracked in the internal `docs/REFERENCE_PROJECTS.md` note (MirrorMobile, Fermata) are GPL-3.0, and they informed behaviour and architecture only: no upstream source or asset was copied into this tree, so their terms stay independent of this repository. Anything that would change that — pasted copyleft source, a vendored upstream file, a decompiled asset — has to be raised before it lands, because it would force a relicense rather than just a review comment.

Third-party dependencies keep their own terms. The in-app list is generated at build time by the `oss-licenses` Gradle plugin from the dependency POMs and opens from the Settings "Open-source licenses" row.




adb install -r app-debug.apk

# force stop app เรา
adb shell am force-stop YOUR.PACKAGE.NAME

# restart Android Auto
adb shell am force-stop com.google.android.projection.gearhead
adb shell monkey -p com.google.android.projection.gearhead 1



https://screenonauto.lzn.idv.tw/docs/en/grant-mirror-permission-via-adb/