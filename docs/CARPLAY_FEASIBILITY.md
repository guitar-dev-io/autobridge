# Apple CarPlay feasibility

Updated: 2026-09-29

This is a feasibility survey, not a roadmap commitment. It exists to answer one question honestly before any iOS work starts: how much of AutoBridge could realistically reach Apple CarPlay, given Apple's app model and current policy. Sources checked 2026-09-29: [Apple CarPlay Developer Guide (June 2026)](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf), [Requesting CarPlay Entitlements](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements), [CarPlay — Apple Developer](https://developer.apple.com/carplay/), and reporting on the WWDC26 CarPlay video category.

## Verdict

AutoBridge's core mechanism — mirror an arbitrary phone screen into the car surface, run a general web browser there, and inject synthetic touch/accessibility events — has no CarPlay equivalent and cannot be built under Apple's public APIs or entitlement policy, at any effort level. This is not a hard engineering problem to solve later; Apple's platform does not expose the primitives (no raw drawable surface handed to third-party apps, no WebView for dynamic/browsable content, no synthetic input injection).

A narrow slice of AutoBridge's *content* — the IPTV/radio catalogs, not the mirroring/browser/input machinery — could map onto CarPlay's Audio category today, and onto the new parked-only Video category if a target vehicle's head unit ever certifies CarPlay video (none has as of this writing). Both require a separate iOS codebase and a separate Apple entitlement application; neither reuses AutoBridge's Android runtime, Car App templates, MediaProjection pipeline, or input backends.

## How the CarPlay app model actually works

CarPlay is not "Android Auto for iOS." The differences that matter here:

- **Single-category lock.** Apple assigns one CarPlay entitlement per app category (Audio, Navigation, Communication, Parking, EV Charging, Quick Food Ordering, Fueling, Driving Task, and as of 2026 Video). An app gets the templates for its category and nothing else; you cannot request a generic "give me a surface" entitlement. Audio + Video entitlements can be combined in one app; so can EV Charging + Fueling. There is no category that fits "mirror whatever is on the phone" or "browse the web."
- **Templates only, no custom canvas.** Every CarPlay screen is built from Apple-defined templates (`CPListTemplate`, `CPNowPlayingTemplate`, `CPMapTemplate`, etc.). Even the Navigation category's map view — the closest thing to a drawable surface — is restricted to drawing a map only; apps get no overlays, no arbitrary UI, and no direct tap/drag events in that view. There is nothing resembling Android Auto's `NavigationTemplate` custom `Surface` callback that AutoBridge's `MirrorCarScreen` currently repurposes for pixel mirroring.
- **WebView is explicitly restricted to static content.** Apple's own guidance: "Use a web view to present static content only. Do not use a web view to display dynamic content or to offer web browsing." A general in-car browser is a guideline violation, not a gray area. One CarPlay app briefly shipped a hidden browser via an update that slipped past App Review; Apple did not change policy in response, and the workaround does not represent a sanctioned path.
- **No input injection surface.** There is no CarPlay analog to AutoBridge's accessibility/Shizuku touch backends. Interaction only happens through the template's own defined controls.
- **Entitlement approval is manual, case-by-case, and unpredictable.** Apps request a category at <https://developer.apple.com/contact/carplay>. Reported turnaround ranges from a few days to several months, with no published SLA. Apple reviews the app's actual purpose against the category, not just a form checkbox — a mirroring/browser app applying under "Audio" would misrepresent its function and risks rejection or later removal.

## Feature-by-feature mapping

| AutoBridge capability | CarPlay path | Status |
|---|---|---|
| MediaProjection screen mirroring (AUTO_MIRROR / SELF_DRAWN) | none | Not possible — no drawable surface is exposed to third-party apps |
| In-app web browser (general browsing, YouTube/YT Music/YT Kids via WebView) | none | Not possible — WebView restricted to static content, no browsing |
| Accessibility / Shizuku touch injection, Quick Apps launching | none | Not possible — no input-injection API, no arbitrary app launching |
| IPTV/M3U/Xtream **radio** (audio-only streams) | CarPlay **Audio** category (`CPListTemplate` browsing + `CPNowPlayingTemplate`, `AVPlayer`/`MediaPlayer`) | Feasible, separate iOS app, own entitlement |
| IPTV/M3U/Xtream **TV** (video streams) | CarPlay **Video** category, parked-only (new in iOS 26/WWDC26) | Conditionally feasible: needs Apple entitlement approval *and* a vehicle head unit certified for CarPlay video — no automaker has committed as of 2026-09-29 |
| Folders/Playlists/Gallery (local media) | CarPlay Audio category for audio files only; local video has the same head-unit gap as IPTV TV | Partial, audio only, near-term |
| Favorites, recently-played, catalog cache | Reusable as *product logic* rewritten in Swift, not as shared code | N/A — no Kotlin/Swift code sharing without a KMP rewrite |
| Smart Mode, per-app profiles, mirror diagnostics, LAB mode | none — these exist only because mirroring exists | Not applicable |

## What would actually be buildable, if pursued

A **separate iOS project** (own repo or an `ios/` tree outside `:app`, since Gradle/Kotlin and Xcode/Swift toolchains don't mix in one module) implementing only:

1. A CarPlay **Audio** app: browse the existing Xtream/M3U radio catalogs via `CPListTemplate`, play through `AVPlayer`, show `CPNowPlayingTemplate`. This is the standard, well-trodden CarPlay integration path (same shape as any internet-radio CarPlay app) and has the least entitlement risk.
2. Optionally, later, a CarPlay **Video** entitlement for the TV catalogs, parked-only, gated entirely on (a) Apple granting the entitlement and (b) a target vehicle's manufacturer certifying CarPlay video on that head unit. Treat this as speculative until at least one automaker ships it.

Neither of these carries over AutoBridge's `FeaturePolicy`, `VehicleStateSession`, `SpeedGate`, mirror/input stack, or Car App templates — the parked-only safety gating would need to be re-derived from whatever CarPlay itself provides (for Video, CarPlay already pauses playback on vehicle motion at the platform level, so app-side speed gating like `SpeedGate` is not needed there the way it is for Android's MediaProjection mirroring).

## Explicitly out of scope, not just deferred

Mirroring, the in-app browser, touch/gesture injection, and Quick Apps launching are not on a CarPlay roadmap at any horizon under current Apple policy. Don't file these as "TODO — CarPlay" in `ROADMAP.md`; they are policy-blocked, not effort-blocked.

## If this gets picked up later

- Start the entitlement request for the Audio category early (`developer.apple.com/contact/carplay`) — the multi-week-to-months turnaround is the actual critical path, not the client code.
- Scope the first iOS milestone to radio/audio only. Don't bundle a Video-category bet into the same submission; it adds an approval that depends on external automaker adoption that doesn't exist yet.
- Expect to reimplement catalog parsing/caching logic in Swift (or introduce Kotlin Multiplatform if long-term code sharing matters); there is no shortcut through the existing `:app` module.
