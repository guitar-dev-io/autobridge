# CarView-style entertainment comparison

Updated: 2026-09-08

CarView Auto is a product/behavior reference, not a source dependency. AutoBridge independently implements a smaller parked-only entertainment path on top of its own mirror and media boundaries.

## Implemented in AutoBridge

- Phone-hosted entertainment activity projected through the existing consented mirror path.
- HTTPS WebView with local file/content access disabled and no native bridge.
- YouTube search URL generation, optional Android speech-recognizer search, and external HTTPS browser launch.
- Direct HTTPS/progressive/HLS/DASH media through Media3 and a visible video surface when policy allows.
- Local audio/video selection through the document picker with persisted read permission.
- Favorites and last-source restore, including native media position persistence.
- Explicit `ContentKind.WEB`, `AUDIO`, and `VIDEO` routing to `Feature.BROWSER`, `Feature.MEDIA`, and `Feature.VIDEO`.
- Native MediaSession controls for direct media, playlist next/previous, controller authorization logging, and audio continuity when video/browser/mirror policy is denied.
- Video tracks, WebView content, and video surfaces are stopped/hidden when the activity is not resumed or the vehicle is moving/unknown.

## How it differs from a product-level CarView claim

- The car output is still MediaProjection/AUTO_MIRROR and requires fresh system consent; it is not an independent car-side browser renderer.
- Reopen-last-source is explicit/foreground-scoped and never silently replays capture consent or launches a background activity.
- WebView playback is not promoted into the native MediaSession; direct media is.
- No Google Assistant/"Hey Google" integration, custom steering-wheel voice mapping, paid catalog/add-on service, Full/Go tier, or compatibility guarantee is implemented.
- Android Auto host category rules, Ford behavior, wired/wireless playback, and hardware keys require real device validation.
- The parked-only safety rule is enforced in code; product marketing assumptions cannot replace `FeaturePolicy`/`CAR_SPEED`.

## Manual flow

1. Build/install `personalDebug` for a personal test or `labDebug` on an emulator.
2. Connect Android Auto/DHU and establish an allowed parked state.
3. Start mirror and accept system capture consent.
4. Open the entertainment activity.
5. Use YouTube/HTTPS for WEB, direct URL/local picker for AUDIO or VIDEO, and test Favorites/Resume.
6. Change to MOVING/UNKNOWN and verify audio may remain available while WebView/video/mirror are denied.

See [`FEATURE_POLICY.md`](FEATURE_POLICY.md), [`MIRROR_ENGINE.md`](MIRROR_ENGINE.md), and [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md).
