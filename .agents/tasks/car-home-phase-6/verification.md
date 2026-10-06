# Verification — Phase 6: Car Home Dashboard Restyle

Branch: `ui/phase-6`  
Commit: `5a003ce` — *UI redesign Phase 6: car Home dashboard look to match design 01*

---

## Build & Test Command

```
./gradlew testPersonalDebugUnitTest assemblePersonalDebug
```

Run from repo root `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`.

**Result: PASS — BUILD SUCCESSFUL** (88 actionable tasks, all up-to-date).  
The `personalDebug` APK assembled and all unit tests passed (60 test suites, 0 failures across the entire `testPersonalDebugUnitTest` task).

### Car-specific test suites

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `dev.autobridge.car.HomeDashboardLayoutTest` | 19 | 0 | 0 |
| `dev.autobridge.car.HomeDashboardClockTest` | 6 | 0 | 0 |
| `dev.autobridge.car.CarThumbnailsTest` | 4 | 0 | 0 |
| `dev.autobridge.car.HeaderActionConstraintTest` | 1 | 0 | 0 |

---

## What WAS Verifiable (Code Level)

All items below were confirmed by inspecting the diff (`git diff main...ui/phase-6`), reading the source, and running the build/tests above.

### 1. Build success
`assemblePersonalDebug` produces a valid APK. No compile errors, no lint failures blocking the build.

### 2. Unit tests green
All 60 test suites pass. `HomeDashboardLayoutTest` (19 tests) confirms layout budgets, `continueTitle` non-null assertion, scrolling geometry, and side-by-side decisions are intact.

### 3. Layout logic untouched
`HomeDashboardLayout.kt` is **not in the diff at all**. Budget calculations, `place()` passes, `maxScroll`, the side-by-side decision, and hit-testing are byte-for-byte unchanged.

### 4. Car API < 5 fallback intact
`CarHomeDashboardScreen.kt` is **not in the diff**. The fallback logic is unchanged:
```kotlin
private val drawsMenu: Boolean by lazy { page == 0 && carContext.carAppApiLevel >= 5 }
override fun onGetTemplate(): Template = if (drawsMenu) menuTemplate() else gridTemplate()
```
Hosts with `carAppApiLevel < 5` continue to receive `gridTemplate()`.

### 5. Action strip = single "More" action
`menuTemplate()` builds its `ActionStrip` with exactly one action: `CarIcons.APPS` → `openMore()`. No other action is added. This code is unchanged (not in the diff).

### 6. Design tokens reused (not ad-hoc literals)
The one new constant is `HomeDashboardTheme.Dp.CARD_ICON_LABEL_GAP = 16f`, added in `HomeDashboardTheme.kt`. All other tokens (`CARD_PADDING`, icon-size clamps, tile tints `ICON_TILE_TINT`/`ICON_TILE_BORDER_TINT`, `heroMetaSize`, etc.) are existing and reused. No ad-hoc pixel literals were introduced.

### 7. EN + TH strings present
All 9 `car_home_*` string keys exist in both `app/src/main/res/values/strings.xml` (EN) and `values-th/strings.xml` (TH). No new string keys were added or renamed by this phase. Parity confirmed.

### 8. No behavior logic changed
The diff touches only 3 files — `HomeMenuCard.kt`, `HomeDashboardRenderer.kt`, `HomeDashboardTheme.kt` — all drawing/rendering code. No changes to: playback, mirroring, bridge, IPTV parsing, safety gates, screen navigation, scrolling, input handling, or any `CarHomeDashboardScreen` template logic.

---

## What Was NOT Verifiable (Requires DHU / Manual Testing)

The following items **cannot be verified in a headless build environment** and require a running Android Auto Desktop Head Unit (DHU) or a real car head unit. These are a manual verification step for the user.

### Visual verification at three display profiles

| Profile | Resolution | What to check |
|---|---|---|
| Small | 800×480 | Nothing clipped. Horizontal Quick Access cards render icon + label within card bounds. Hero card's four-line stack (caption, title, source, progress) does not overflow. The `continueTitle` reserved band (now unpainted) does not leave an awkward visible gap above the hero. |
| Medium | 1280×720 | Standard layout renders correctly. Cards are appropriately sized. |
| Wide | 1920×720 | Wide profile uses space correctly. Side-by-side layout (if triggered) renders correctly. |

### Specific visual checks

- **Horizontal Quick Access cards**: Icon tile on the left, label left-aligned to its right, both vertically centred (matches design `01_Main.png`).
- **Label truncation on narrow profiles**: The layout's label-fit loop sizes against full card width, but the horizontal card draws labels in a narrower column (card width minus icon and gap). Long labels ("YouTube Music", "Web browser") may ellipsize on 800×480 side-by-side cards. If truncation is unacceptable, derive the label-fit width from icon+gap in the renderer/card.
- **Scroll buttons**: Appear only when content overflows the visible area; not shown otherwise.
- **Tap targets**: Tapping a card after scrolling lands on the correct card (hit-test alignment with scroll offset).
- **Text readability**: All text (labels, captions, titles) is readable at arm's length on each profile.
- **"Continue Watching" in-card caption**: Rendered in secondary colour inside the hero card's text column, above the title line (matches design `01_Main.png`).
- **Wordmark, count pill + chevron headers, progress readout**: Already matched the design before Phase 6; confirm they remain intact.

### Review finding (non-blocking)

**Label-fit width mismatch**: The layout fits `labelSize` to full card width while the horizontal card draws in a narrower column. This may cause visual truncation on 800×480. See `review.json` for details. Confirm on DHU; fix if needed by computing the label width from icon+gap in the renderer/card rather than changing layout budgets.

---

## Files Changed

| File | Change |
|---|---|
| `app/src/main/java/dev/autobridge/car/HomeMenuCard.kt` | Quick Access card painting: icon left + label left-aligned right, both vertically centred. `Paint.Align.CENTER` → `LEFT`. Ellipsize to real column width. |
| `app/src/main/java/dev/autobridge/car/HomeDashboardRenderer.kt` | Stopped painting standalone "Continue Watching" section title. `drawHero` now stacks caption/title/source/progress with collapsible inter-line gap. |
| `app/src/main/java/dev/autobridge/car/HomeDashboardTheme.kt` | New token `CARD_ICON_LABEL_GAP = 16f`. |

---

*Last updated after final verification run. Review: `review.json` (APPROVED with 1 non-blocking finding).*
