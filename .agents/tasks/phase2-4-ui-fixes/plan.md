# Implementation Plan — Phase 2–4 UI design-match fixes

Scope: UI and navigation ONLY. Do not touch playback, mirroring, bridge, IPTV parsing or safety-gate logic. Keep every existing string; ADD new strings (EN in `app/src/main/res/values/strings.xml`, TH in `app/src/main/res/values-th/strings.xml`). Use only `AutoBridgeDesign` / `ComposeTokens` color/radius tokens — introduce no new color or radius values.

Build/verify (run after each phase): `cd /Users/anuwat.t/Documents/ChatGPT/AutoBridge && ./gradlew testPersonalDebugUnitTest assemblePersonalDebug`. There are no UI unit tests for these screens; compilation + existing tests passing is the gate.

Design-token reference (already defined, do not redefine): ACCENT `0xFF4DA3FF`, ACCENT_ONLINE `0xFF5BE3B4`, DANGER `0xFFFF9AA8`, TEXT_MUTED `0xFFA9B0BA`, ACCENT_FAVORITE (library). Compose mirror in `ComposeTokens` (`Accent`, `Ok`, `Danger`, `TextMuted`, `AccentSoft`, …).

Intentional omissions — DO NOT add: tabs-count badge on the browser toolbar; Tabs/New tab tiles in the All Actions NAVIGATE section; Split tile in the All Actions THIS PAGE section.

## Phase 2 — Control (Compose) — `app/src/main/java/dev/autobridge/ui/ControlScreen.kt`

- [ ] 1. History icon → clock glyph.
      In `ControlScreen()` (~line 195) change `HeaderButton("↺", …)` glyph to a clock glyph `"🕘"` (or `"⏱"`). Keep string + onClick.
      Files: ControlScreen.kt
      Verify: build succeeds; header shows a clock, not ↺.

- [ ] 2. 'On the car' eyebrow uppercase + domain-first order.
      In `OnTheCarCard()` (~line 723): `.uppercase()` the eyebrow text so it reads `ON THE CAR · BROWSER`. In the `detail` buildString (~line 735) put `state.source.displayHost` first, then the playback label, then times.
      Files: ControlScreen.kt
      Verify: build succeeds.

- [ ] 3. QUICK ACTIONS → single horizontal LazyRow of pill chips.
      Replace the `quickCommands.chunked(3).forEach { item { Row { QuickCard … } } }` block (~line 258) with one `item { LazyRow(…) }` of a new `QuickChip` composable (chip-shaped, ~18dp radius, `CardAltColor`, accent icon, Edit-mode `✕` badge preserved). Keep `AutoBridgeCommandBus.send(...)` routing. Import `androidx.compose.foundation.lazy.LazyRow` + `items`.
      Files: ControlScreen.kt
      Verify: build succeeds; Quick Actions scrolls horizontally as chips.

- [ ] 4. Remove RemoteStreamCard.
      Delete `item { RemoteStreamCard() }` (~line 314) and the whole `private @Composable fun RemoteStreamCard()` (~line 965). Remove the now-unused `RemoteStreamConfig` import only if nothing else references it (grep first).
      Files: ControlScreen.kt
      Verify: build succeeds; grep confirms RemoteStreamCard gone.

- [ ] 5. Tabs → text tab + blue underline.
      Replace the Queue/Recent/Favorites `SegmentChip` row (~line 283) with a new `UnderlineTab(label, selected, onClick)` (plain text, accent when selected, 2dp accent underline Box when selected). Keep the Queue `· N` count badge. The Open/search↔Type mode toggle KEEPS `SegmentChip`.
      Files: ControlScreen.kt
      Verify: build succeeds.

- [ ] 6. Queue/Recent/Favorites rows → leading thumbnail + `···` overflow menu.
      Rework `LinkRow` (~line 920): add a 40dp leading thumbnail (placeholder colored square with first title letter, `ComposeTokens.AccentSoft`; optional `artworkUrl` param defaulting null). Replace the two `TextButton`s with one `···` `IconButton` opening a `DropdownMenu` with Send (`bridge_controller_send`) and the action label items. Keep onClick/onAction.
      Files: ControlScreen.kt; new strings `control_queue_overflow` (EN values + TH values-th).
      Verify: build succeeds; new strings present in both files.

## Phase 3 — Library + TV Channels (Views) — `library/LibraryActivity.kt`, `ui/AutoBridgeDesign.kt`

- [ ] 7. Remove the Favorites chip.
      In `chips` list (~line 97) remove `Section.FAVORITES` → 5 chips. Keep the enum value (used via intent extra).
      Files: LibraryActivity.kt
      Verify: build succeeds; chip bar has 5 chips.

- [ ] 8. Section-letter headers accented.
      In `showLibraryCategories()` grouped-letter loop, override the per-letter header color to `AutoBridgeDesign.ACCENT` via `.also { it.setTextColor(AutoBridgeDesign.ACCENT) }`. Do NOT change `AutoBridgeDesign.sectionLabel` (shared by other muted headers).
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 9. 'A–Z ▼' sort dropdown on ALL CATEGORIES header.
      Replace the plain `sectionLabel("All categories • N")` with a horizontal row: label (weight 1) + clickable `A–Z ▼`/`Z–A ▲` TextView. Add `var sortAscending = true`; toggle on tap and re-`draw()`; apply ascending/descending comparator to the `toSortedMap`.
      Files: LibraryActivity.kt; new strings `library_sort_az`, `library_sort_za` (EN + TH).
      Verify: build succeeds; strings in both files.

- [ ] 10. 'Edit' link on PINNED header.
      Replace `sectionLabel("Pinned")` with a horizontal row: label (weight 1) + accent `Edit` TextView opening an AlertDialog of categories with pin checkboxes (read/toggle `IptvPinnedCategoryStore`, `refresh()` on dismiss).
      Files: LibraryActivity.kt; new string `library_edit_pinned` (EN + TH).
      Verify: build succeeds; strings in both files.

- [ ] 11. Source card: folder badge + ▾ chevron.
      In `sourceCard()` set `badgeText = "📁"` and `trailing = "▾"`. Keep `onTrailing = { sourceMenu(source) }`.
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 12. TV grid 3 columns.
      In `showEntries()` grid branch pass `columns = 3` to `AutoBridgeDesign.grid(...)`.
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 13. Header subtitle 'N channels · [source]'.
      In `showEntries()` change the non-search `counted` from `plural(all.size,"entry","entries")` to `"${all.size} channels · ${source.name}"`. Keep the search and 'showing first N' branches.
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 14. Filled star header action.
      Add a star `HeaderAction` (leftmost) in `showEntries()` toggling `IptvPinnedCategoryStore` for the current `categoryId`; `filled`/tint reflect pinned state; re-`draw()` on tap.
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 15. Move 'Check' from header 📶 to a '↻ Check' button on the status line.
      Remove the `📶` `HeaderAction` from `showEntries()` headerActions (keep grid/list toggle + new star). Add an `AutoBridgeDesign.pill(this, "↻ Check", …) { recheckEntries(shown) }` into `checkSummaryRow()` beside 'Hide offline'. Clean up now-dead `signalIndicator`/`SIGNAL_ACTION_TAG` references for this page without breaking `signalTint()`/`updatePingTiles`.
      Files: LibraryActivity.kt; new string `library_check_button` (EN + TH).
      Verify: build succeeds; strings in both files.

- [ ] 16. Status dots colored (green online, red offline).
      In `checkSummaryRow()` color the two `●` glyphs: online `●` = `ACCENT_ONLINE`, offline `●` = `DANGER` (SpannableStringBuilder+ForegroundColorSpan or two TextViews). Remaining text stays `TEXT_MUTED`.
      Files: LibraryActivity.kt
      Verify: build succeeds.

- [ ] 17. Tile status → small colored dot on artwork corner.
      In `AutoBridgeDesign.contentTile()` render `status` as an 8dp colored oval `View` at the artwork FrameLayout's bottom-end (color = status.second); hide when null. Keep the `R.id.autobridge_status_label` view but GONE, and make `setStatus()` update the dot too so `updatePingTiles()` keeps working. Verify other `contentTile` callers (e.g. `sectionCard`/library rows) still compile and look right.
      Files: AutoBridgeDesign.kt (shared — check all callers)
      Verify: build + all unit tests pass.

## Phase 4 — Browser Quick Menu + All Actions — `browser/BrowserMenuSheet.kt`, `browser/MoreActionsSheet.kt`, `browser/BrowserActivity.kt`, `browser/BrowserIcons.kt`

- [ ] 18. Remove Fullscreen switch from quick-menu card.
      In `BrowserMenuSheet.settingsCard()` delete the `drawer_fullscreen` `switchRow` and its preceding `divider()`. (Fullscreen stays reachable in All Actions ▸ This page.)
      Files: BrowserMenuSheet.kt
      Verify: build succeeds; card has 2 switches only.

- [ ] 19. Menu glyph hamburger → vertical dots.
      Create `app/src/main/res/drawable/ic_browser_more_vert.xml` (vertical 3-dot vector, 24dp). In `BrowserIcons.kt` point `MENU(...)` to `R.drawable.ic_browser_more_vert`. Confirm `BrowserIcon.MENU` is only the toolbar menu affordance.
      Files: ic_browser_more_vert.xml (new), BrowserIcons.kt
      Verify: build succeeds; toolbar shows ⋮.

- [ ] 20. Zoom '100%' readout.
      In `MoreActionsSheet.zoomRow()` add a readout TextView between − and +, default '100%'. Maintain a local `zoomPercent` (±10, clamped) updated before dispatching ZOOM_IN/OUT. If `BrowserMenuState` exposes a real zoom level, prefer that.
      Files: MoreActionsSheet.kt; optional string `browser_zoom_percent` (EN + TH).
      Verify: build succeeds.

- [ ] 21. Remove back arrow from All Actions header.
      In `MoreActionsSheet.header()` remove the circular back `ImageView`. Header = title + close X. Keep the `onBack` constructor param (callers pass it); just don't render the button.
      Files: MoreActionsSheet.kt
      Verify: build succeeds.

- [ ] 22. Ungroup Zoom + Request desktop site.
      Replace the single `pageSettingsCard` block with two standalone rounded rows: one for `zoomRow()`, one for `desktopSwitchRow(current)`. Keep actions/behavior identical.
      Files: MoreActionsSheet.kt
      Verify: build succeeds; navigateTiles still 4, thisPageTiles still 5.

## Phase 4 — Send to Car — `browser/SendToCarSheet.kt`

- [ ] 23. Remove the segmented tab row.
      Delete `container.addView(tabRow())`, the `tabRow()`/`tab()` methods, the `Mode` enum and `mode` field. Change the current-page gate from `if (mode == Mode.URL && url != null)` to `if (url != null)`. One scroll, no tabs.
      Files: SendToCarSheet.kt
      Verify: build succeeds; no Mode/tabRow remain.

- [ ] 24. 'Connected' pill (green) in header.
      In `header()` add a status pill (green dot + label) left of the close X. Read connection from `AutoBridgeSessionManager` session state (add a `connected: () -> Boolean` constructor param wired by `BrowserActivity`, or a direct accessor if one exists — verify first). Connected → green pill `conn_connected_plain`; disconnected → muted pill `conn_not_connected`. Existing tokens only.
      Files: SendToCarSheet.kt; BrowserActivity.kt (pass the lambda). Reuse existing strings.
      Verify: build succeeds.

- [ ] 25. Current-page card: remove ◉ radio glyph.
      In `currentPageCard()` delete the trailing `"◉"` TextView; card stays tappable.
      Files: SendToCarSheet.kt
      Verify: build succeeds.

- [ ] 26. Replace emoji glyphs with BrowserIcon vectors.
      In `primaryButton()` replace `"🚗"` TextView with an ImageView of `BrowserIcon.CAR` (tint `onPrimary`). In `queueButton()` replace `"≡"` TextView with `BrowserIcon.ADD` (tint `accent`). Match existing icon sizing/padding.
      Files: SendToCarSheet.kt
      Verify: build + all unit tests pass; grep confirms no 🚗/≡/◉.

## Notes for the implementer

- Fix 17 edits the SHARED `AutoBridgeDesign.contentTile`; verify every caller (library tiles, any other grid/tile user) still compiles and renders.
- Fix 24 (Connected pill): confirm how `BrowserActivity` currently obtains `AutoBridgeSessionManager` session/connection state before deciding between a constructor lambda and a direct accessor. The `SessionState.connected` field is what `ControlScreen.OnTheCarCard` reads.
- `conn_connected_plain` ("Connected") and `conn_not_connected` ("Not connected") already exist — reuse them for the pill.
- The audit's Phase 4 "mini-player shows local playback not 'Playing on car'" and the intentionally-omitted browser tiles are OUT of scope and must not be changed.
- After each phase, run the full build command and fix any compile errors before moving on.
