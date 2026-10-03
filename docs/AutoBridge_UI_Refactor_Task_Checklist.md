# AutoBridge UI Refactor Task + Checklist

## Goal
Refactor the Android Auto AutoBridge Home dashboard to match the approved reference UI as closely as possible while preserving the current Surface/Canvas architecture, navigation behavior, fallback behavior, and DHU compatibility.

---

# PART 1 — IMPLEMENTATION TASK

## 1. Preserve Existing Architecture
- Keep `CarHomeDashboardScreen` as the main Android Auto Home screen.
- Keep `NavigationTemplate` as the Android Auto host shell.
- Keep `SurfaceCallback` and Canvas-based rendering.
- Keep `MirrorSurfaceOwnership`.
- Keep existing `CarHomeNavigator` destination behavior.
- Keep `GridTemplate` fallback for Car API < 5.
- Do not rewrite the screen using Compose, Fragment, or Activity.
- Do not remove working playback, browser, TV, radio, streaming, or mirror logic.

The reference image is a visual target only.
The existing AutoBridge Surface/Canvas architecture is authoritative.
Adapt the design to the current implementation instead of rewriting the project around the mockup.

---

## 2. New Home Dashboard Structure

Refactor the current Home screen from a simple 3x2 launcher into a media dashboard.

Target hierarchy:

1. AutoBridge Header
2. Continue Watching
3. Quick Access
4. Recently Sent
5. Queue

Do not draw over Android Auto system UI.

Respect:
- `stableArea`
- `visibleArea`
- surface dimensions
- head-unit DPI

---

## 3. Header

Create a compact AutoBridge header.

Display:
- AutoBridge logo
- AutoBridge brand name

Keep the existing Android Auto ActionStrip / More action behavior.

Do not add another Settings button if Settings is already reachable from More.

---

## 4. Continue Watching

Add a full-width Continue Watching card.

Layout example:

```text
[ Thumbnail ]  Japan Travel 4K - Amazing Places
               YouTube
               █████████░░░░    12:34 / 31:20
```

Requirements:
- rounded thumbnail
- play overlay
- title
- source
- progress bar
- current position
- total duration
- entire card clickable
- ellipsize long title
- hide section if no valid resumable item exists

Use existing session/recent/playback state where possible.

Do not use fake production data.

If additional metadata is needed, extend the existing state/store cleanly.

Useful metadata:
- title
- source
- URL/media ID
- thumbnail
- positionMs
- durationMs
- timestamp

---

## 5. Quick Access

Keep the existing 6 main destinations:

- YouTube
- YouTube Music
- Streaming
- TV
- Radio
- Web Browser

Layout:
- 3 columns x 2 rows
- large touch targets
- rounded cards
- icon centered
- label below icon

Visual state:

### Normal
Dark card with subtle border.

### Focused
Bright AutoBridge blue border.
Optional subtle glow if rendering cost stays low.

### Pressed
Slightly darker fill / depressed state.
Use existing short press feedback timing.

Do not change what each menu item opens.

---

## 6. Recently Sent

Add a Recently Sent section.

Home should show a maximum of 2 items.

Each item should contain:

```text
[thumbnail]  Title
             Source • Sent from phone • Relative time        [Play]
```

Header:
`Recently Sent >`

Behavior:
- click item = open/play
- click header = open full Recently Sent/Recent screen

Use existing `RecentActivityStore`, Send-to-Car state, or other existing state before creating new persistence.

If origin metadata is needed, consider a field similar to:

```text
CAR
PHONE
SHARE_INTENT
AGENT
```

Items from phone/share flow can populate Recently Sent.

If there are no items:
- hide section
- collapse layout naturally

---

## 7. Queue

Add a compact Queue card.

Header:
`Queue 3 >`

Show up to 3 items:

```text
[thumb] Japan Travel 4K          31:20
[thumb] Chill Lofi Mix         1:02:34
[thumb] City Night Drive        28:15
```

Requirements:
- click header = full queue
- click item = play/select item
- no drag-and-drop required on car UI
- do not waste large space when queue is empty

If no queue exists yet, first inspect current playback/session code and reuse existing playlist/queue infrastructure if available.

---

## 8. Responsive Layout

Do not hard-code the mockup resolution.

Create or refactor layout computation based on:
- safe area
- width
- height
- head-unit DPI

Suggested layout model:

```text
HomeDashboardLayout
├── headerBounds
├── continueWatchingBounds
├── quickAccessTitleBounds
├── quickAccessCardBounds[6]
├── recentlySentBounds
├── queueBounds
├── scrollUp
├── scrollDown
├── maxScroll
└── scrollStep
```

Wide DHU:
- Recently Sent and Queue can sit side-by-side.

Narrow DHU:
- stack Recently Sent and Queue vertically.

If content exceeds vertical space:
- keep existing scrolling behavior
- do not reduce button size too aggressively

---

## 9. Renderer Refactor

Prefer a clear renderer structure such as:

```text
HomeDashboardRenderer
HomeDashboardLayout
HomeDashboardTheme
```

Optional reusable renderer pieces:

```text
ContinueWatchingRenderer
QuickAccessRenderer
RecentSentRenderer
QueueRenderer
```

Do not over-engineer if the current codebase favors simpler classes.

Reuse current project components where possible.

---

## 10. Touch Hit Testing

Update Surface click handling for the new dashboard.

Priority:

1. Continue Watching
2. Quick Access
3. Recently Sent header
4. Recently Sent items
5. Queue header
6. Queue items
7. scroll controls

Preserve current surface release behavior before opening another screen.

Do not break:

```text
releaseSurface()
CarHomeNavigator.open(...)
```

Browser/video/mirror screens must still be able to claim their own surface producer.

---

## 11. Icons

Find and add consistent icons.

Required icons:
- AutoBridge logo
- YouTube
- YouTube Music
- Streaming
- TV
- Radio
- Web Browser
- Play
- Queue
- More / Apps
- Chevron Right

Preferred approach:
- official brand assets where appropriate
- Material Symbols / Material Icons for generic actions
- existing project assets where suitable

Suggested generic mappings:
- TV → television
- Radio → radio
- Web → language/public
- Streaming → live_tv/video_library
- Play → play_arrow
- Queue → playlist_play / queue_music
- More → apps
- Chevron → chevron_right

Do not use random icon packs from unknown sources.

Keep the icon family visually consistent.

---

## 12. Icon Asset Loading

Inspect `DashboardArtwork` first.

If it can be extended, reuse it.

If vector drawables need Canvas bitmap conversion, create a reusable cached helper.

Example responsibility:

```text
CarIconAssets.bitmap(
    context,
    drawableRes,
    sizePx
)
```

Requirements:
- cache decoded bitmap
- cache scaled bitmap when sensible
- never decode drawables on every render frame

---

## 13. Theme Tokens

Centralize styling.

Example groups:

```text
background
surface
surfaceElevated
cardBorder
focusedBorder
primaryBlue

textPrimary
textSecondary

cornerRadiusLarge
cornerRadiusMedium

headerTextSize
sectionTextSize
cardTextSize
bodyTextSize

horizontalPadding
verticalPadding
sectionGap
cardGap
```

Use head-unit DPI.

Do not use phone density for the car layout.

Avoid scattered magic numbers.

---

## 14. Rendering Performance

Keep Canvas rendering efficient.

Requirements:
- reuse `Paint`
- cache bitmap/icon resources
- reuse typefaces
- avoid repeated drawable decoding
- minimize allocations in `draw()`
- avoid continuous redraw loops
- only redraw when state changes

Continue using:

1. `lockHardwareCanvas()`
2. fallback to `lockCanvas()`

---

## 15. Required States

Verify UI for:

- Continue Watching exists
- no Continue Watching
- Recently Sent exists
- no Recently Sent
- Queue has items
- Queue empty
- Recent and Queue both absent
- long title
- missing thumbnail
- different DHU resolutions
- different DPI
- resize/reconnect
- Car API < 5 fallback

Use clean fallback artwork when thumbnails are unavailable.

---

## 16. Android Auto Host UI

Never draw over:
- bottom Android Auto dock
- side rail
- host chrome

Preserve the current safe-area behavior.

---

## 17. Older Host Fallback

Do not remove `gridTemplate()`.

For Car API < 5:
- keep the existing native GridTemplate route
- keep all current destinations accessible
- custom dashboard additions are optional there

---

## 18. DHU Testing

After implementation:

- build the project
- fix compilation issues
- launch Desktop Head Unit
- capture screenshots
- test navigation and surface ownership

Test at more than one DHU resolution if available.

---

## 19. Final Delivery

Return a report containing:

- files inspected
- files changed
- classes added
- icon assets added
- source of icon assets
- state/data sources reused
- new state/data added
- DHU test results
- before screenshot
- after screenshot
- known limitations
- remaining issues

Do not finish with analysis only.
Implement, build, run, test, and verify.

---

# PART 2 — IMPLEMENTATION CHECKLIST

## A. Pre-change Inspection

- [ ] Locate `CarHomeDashboardScreen`
- [ ] Locate `HomeMenuRenderer`
- [ ] Locate `HomeMenuLayout`
- [ ] Locate `HomeMenuTheme`
- [ ] Locate `HomeMenuItem`
- [ ] Locate `DashboardArtwork`
- [ ] Locate `RecentActivityStore`
- [ ] Locate Send-to-Car state/logic
- [ ] Locate existing media/session state
- [ ] Locate existing queue/playlist implementation
- [ ] Locate browser screen
- [ ] Locate YouTube screen
- [ ] Locate YouTube Music screen
- [ ] Locate Streaming screen
- [ ] Locate TV screen
- [ ] Locate Radio screen
- [ ] Confirm current `MirrorSurfaceOwnership` flow
- [ ] Confirm current `releaseSurface()` flow
- [ ] Confirm Car API < 5 fallback still works before editing

---

## B. Architecture

- [ ] Keep `NavigationTemplate`
- [ ] Keep `SurfaceCallback`
- [ ] Keep Canvas renderer
- [ ] Keep GridTemplate fallback
- [ ] Create/update `HomeDashboardLayout`
- [ ] Create/update `HomeDashboardRenderer`
- [ ] Create/update central dashboard theme
- [ ] Avoid Compose/Activity rewrite
- [ ] Avoid duplicate navigation/state systems

---

## C. Header

- [ ] AutoBridge logo rendered
- [ ] AutoBridge text rendered
- [ ] Host More/Apps action still works
- [ ] No duplicate Settings button
- [ ] Header respects safe area

---

## D. Continue Watching

- [ ] Read real resume state
- [ ] Thumbnail rendered
- [ ] Fallback thumbnail supported
- [ ] Title rendered
- [ ] Long title ellipsized
- [ ] Source rendered
- [ ] Progress rendered
- [ ] Time/duration rendered
- [ ] Play overlay rendered
- [ ] Entire card clickable
- [ ] Resume opens correct content
- [ ] Section hides if no resume item
- [ ] Layout collapses when hidden

---

## E. Quick Access

- [ ] YouTube card
- [ ] YouTube Music card
- [ ] Streaming card
- [ ] TV card
- [ ] Radio card
- [ ] Web Browser card
- [ ] 3x2 layout
- [ ] touch targets large enough
- [ ] normal state
- [ ] focused state
- [ ] pressed state
- [ ] each card opens existing destination
- [ ] returning Home preserves focused section

---

## F. Icons

- [ ] AutoBridge icon verified
- [ ] YouTube icon added/verified
- [ ] YouTube Music icon added/verified
- [ ] Streaming icon added/verified
- [ ] TV icon added/verified
- [ ] Radio icon added/verified
- [ ] Web icon added/verified
- [ ] Play icon added
- [ ] Queue icon added
- [ ] More/Apps icon verified
- [ ] Chevron icon added
- [ ] icon sources documented
- [ ] consistent visual family
- [ ] no unknown/random icon packs
- [ ] bitmaps/vector assets cached

---

## G. Recently Sent

- [ ] Inspect existing Send-to-Car data
- [ ] Reuse existing recent store where possible
- [ ] Add origin metadata only if required
- [ ] Show max 2 items on Home
- [ ] Thumbnail
- [ ] Title
- [ ] Source
- [ ] relative sent time
- [ ] play/open button
- [ ] item click works
- [ ] header click works
- [ ] empty state collapses
- [ ] no duplicated persistence layer

---

## H. Queue

- [ ] Reuse existing queue if available
- [ ] Show queue count
- [ ] Show max 3 items
- [ ] Thumbnail/fallback artwork
- [ ] Title
- [ ] Duration
- [ ] item click works
- [ ] header click works
- [ ] empty queue handled cleanly
- [ ] no drag-and-drop required

---

## I. Hit Testing

- [ ] Continue Watching hit region
- [ ] Quick Access card hit regions
- [ ] Recently Sent header hit region
- [ ] Recently Sent item hit regions
- [ ] Queue header hit region
- [ ] Queue item hit regions
- [ ] scroll-up hit region
- [ ] scroll-down hit region
- [ ] no overlapping hit regions
- [ ] press feedback still works

---

## J. Responsive Layout

- [ ] Uses `stableArea`
- [ ] Uses `visibleArea`
- [ ] Uses surface dimensions
- [ ] Uses head-unit DPI
- [ ] wide layout tested
- [ ] narrow layout tested
- [ ] recent/queue side-by-side when possible
- [ ] recent/queue stack when necessary
- [ ] vertical scroll works
- [ ] no clipping
- [ ] no content under Android Auto bottom bar

---

## K. Performance

- [ ] Paint instances reused
- [ ] Typeface reused
- [ ] bitmaps cached
- [ ] no repeated drawable decoding
- [ ] minimal allocation in render loop
- [ ] no unnecessary continuous redraw
- [ ] hardware Canvas used first
- [ ] Canvas fallback retained

---

## L. Surface Ownership

- [ ] Home claims surface correctly
- [ ] Home releases surface before Browser
- [ ] Home releases surface before Video
- [ ] Home releases surface before Mirror
- [ ] child screen can attach its producer
- [ ] Back returns surface ownership to Home
- [ ] reconnect does not leak surface
- [ ] screen switching does not crash

---

## M. Existing Features Regression

- [ ] YouTube works
- [ ] YouTube Music works
- [ ] Streaming works
- [ ] TV works
- [ ] Radio works
- [ ] Web Browser works
- [ ] Mirror works
- [ ] Quick Launch/Apps works
- [ ] Settings accessible
- [ ] Back navigation works
- [ ] Resume last session still works
- [ ] Vehicle safety logic still works

---

## N. Car API Compatibility

- [ ] Car API >= 5 custom dashboard works
- [ ] Car API < 5 GridTemplate fallback works
- [ ] no template rejection
- [ ] host action strip valid
- [ ] surface callbacks valid
- [ ] rotary fallback remains accessible

---

## O. DHU Validation

- [ ] Project builds successfully
- [ ] App installs successfully
- [ ] DHU connects
- [ ] AutoBridge Home opens
- [ ] screenshot captured before changes
- [ ] screenshot captured after changes
- [ ] visual hierarchy matches reference
- [ ] Continue Watching tested
- [ ] six Quick Access cards tested
- [ ] Recently Sent tested
- [ ] Queue tested
- [ ] scroll tested
- [ ] screen resize tested
- [ ] disconnect/reconnect tested
- [ ] app does not crash

---

## P. Final Review

- [ ] Compare actual DHU screenshot with reference
- [ ] Check card spacing
- [ ] Check corner radii
- [ ] Check icon size consistency
- [ ] Check typography hierarchy
- [ ] Check focused blue border
- [ ] Check pressed feedback
- [ ] Check long Thai text
- [ ] Check long English text
- [ ] Check no fake placeholder production content
- [ ] Remove debug-only temporary code
- [ ] Final build successful
- [ ] Final report completed
