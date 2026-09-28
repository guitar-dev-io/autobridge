Updated: 2026-09-28

AutoBridge is an independent implementation under the `dev.autobridge` package. The references below inform behavior and architecture; their source trees and assets were not merged into this project.

## Reference set and boundaries

| Reference | Link | Used for | Boundary |
|---|---|---|---|
| BluMirror | [GitHub](https://github.com/BluDood/BluMirror) | Direct MediaProjection-to-Android-Auto surface concepts and mirror-first scope | Public source/license terms apply upstream; AutoBridge implementation is separate. |
| MirrorMobile | [GitHub](https://github.com/chenxiaolong/MirrorMobile) | Android Auto surface lifecycle, `CAR_SPEED`, fail-closed parked behavior, host limitations | Upstream is GPL-3.0; no GPL source was copied into the MIT AutoBridge tree. |
| ScreenOnAuto | [releases/docs](https://github.com/slzn/ScreenOnAuto-releases) | Touch UX, Quick Apps, force-landscape, privileged input, MediaSession, screen-off and own-content ideas | Treat as behavioral/documentation reference and credit source; do not assume proprietary implementation source is reusable. |
| Fermata / Fermata-Xtream | [GitHub](https://github.com/malebuffy/Fermata-Xtream) | Home section set and ordering (TV, Radio, Web, YouTube family, Folders, Favorites, Playlists, Gallery), Xtream Codes account flow, Android Auto content browsing | Upstream Fermata is GPL-3.0; no upstream source or assets were copied into this MIT tree. |
| CarView Auto | [product site](https://carviewapp.com/) | Product framing for YouTube/TV/Web Player, audio continuity, auto-resume, voice/steering interaction | Closed-source commercial product; no assets or implementation are copied. |

The links are provided for study and attribution. Upstream licenses/terms remain independent of AutoBridge's license.

## Adopted ideas and current status

### BluMirror-like mirror path

Adopted: keep the first pipeline small and low latency: `MediaProjection -> VirtualDisplay(AUTO_MIRROR) -> Android Auto Surface`. Current code adds a `MirrorEngine` interface, process-global `MirrorCoordinator`, surface lifecycle controller, reconnect tracking, and diagnostics.

Status:

- full/default-display mirror: implemented locally;
- opt-in self-drawn output: implemented locally for FILL/STRETCH/crop/ONE_TO_ONE/FPS diagnostics;
- single-app/dedicated own-content display: not implemented; `OWN_CONTENT` is rejected/falls back safely;
- direct AUTO_MIRROR output remains OS-owned FIT;
- optional panel-only power control is a separate Shizuku capability and is not an own-content display.

### MirrorMobile-like safety

Adopted: `CAR_SPEED` is authoritative, `UNKNOWN` is unsafe, and movement stops capture/car interaction. Current `SpeedGate` has strict finite-zero classification, stale update expiry, duplicate listener protection, and shared screen leases. The emulator/LAB provider follows the same state path but is explicitly prohibited in `REAL_CAR`.

### ScreenOnAuto-like optional capabilities

Adopted independently and credited to the public [ScreenOnAuto release/documentation reference](https://github.com/slzn/ScreenOnAuto-releases):

- `InputBackend` abstraction with Accessibility fallback and Shizuku user-service command path;
- Quick Apps and per-app profile persistence;
- public force-landscape setting snapshot/restoration;
- Back/Home/Recents actions;
- Media3/MediaSession and playlist controls;
- opt-in panel-only screen-off attempt through hidden display-control APIs, with capability probing, public-dim fallback, and teardown restoration;
- bounded real pointer-id/action injection sink through hidden `InputManager`, exposed only as a separate capability and setting;
- explicit screen-off/per-app-display limitation model;
- structured developer diagnostics.

The Android Auto callback contract still prevents raw pointer streams and native independent multi-touch. The current pinch path is synthesized through Accessibility from one `onScale` callback; the real-touch sink is not falsely fed from that callback.

### CarView-like entertainment framing

Adopted independently:

- an entertainment activity with YouTube search/HTTPS WebView, direct TV/video/audio URLs, local file selection, favorites, and last-source/position restore;
- explicit WEB/AUDIO/VIDEO content kinds;
- native MediaSession for direct media, while WebView playback is not silently promoted into that session;
- parked-only video/browser/mirror behavior and audio continuity when policy allows.

Not adopted as claims: product tier compatibility, bank/ride-hailing guarantees, Google Assistant integration, catalog/add-on services, or Ford/wireless compatibility.

## Current feature matrix

| Feature | AutoBridge status | Evidence/boundary |
|---|---|---|
| Full-display mirror | DONE locally | `MirrorCoordinator`, `ProjectionService`; host/device validation still required. |
| App-only mirror | PARTIAL | `PerAppDisplayController` has a guarded target-display launch path; dedicated display creation and arbitrary-app guarantees remain unavailable. |
| Vehicle fail-closed gate | DONE in code/tests | Real `CAR_SPEED` delivery remains host-dependent. |
| Shared render/touch transform | DONE locally / PARTIAL on devices | `RenderPlan` and `DisplayTransform` drive SELF_DRAWN FIT/FILL/STRETCH/ONE_TO_ONE output and input mapping; AUTO_MIRROR remains OS FIT. |
| Accessibility input | DONE in code | Requires user-enabled service and device validation. |
| Shizuku basic input | DONE in code | Binder/command bounds are implemented; permission/device run remains. |
| Shizuku panel-only power | DONE locally / PARTIAL on devices | Hidden API/token capability probe, opt-in setting, fallback, and teardown restoration exist; OEM/API behavior needs device validation. |
| Shizuku real-touch sink | DONE locally / BLOCKED by current host source | Bounded `MotionEvent` sink and raw router entry points exist; current Android Auto callbacks provide no independent pointer stream. |
| Quick Apps/profiles | DONE in code | Third-party launch/rotation behavior remains device-dependent. |
| MediaSession/HLS/DASH/local media | DONE in code | Actual routes, controller identities, and hardware buttons need device tests. |
| Fermata-style home sections | DONE in code | One `HomeSection` list drives the phone grid and the car dashboard; see [`IPTV_SECTIONS.md`](IPTV_SECTIONS.md). |
| Xtream/M3U TV and Radio | DONE in code | Parsers unit-tested; a live portal round trip and large-catalog paging on a head unit are unverified. |
| Folders/Playlists/Gallery | DONE in code / PARTIAL on devices | MediaStore-backed; playlists are unavailable on Android 11+ and OEM indexing varies. |
| Catch-up / EPG | PARTIAL | Catch-up is modelled and flagged per channel; no timeshift picker or EPG. |
| Screen-off survival | PARTIAL | Supported devices may use the opt-in panel-only backend; AUTO_MIRROR/SELF_DRAWN still capture the default display, and dedicated OWN_CONTENT remains unavailable. |
| Reconnect recovery | DONE structurally | Surface/projection host lifecycle needs DHU/Ford verification. |
| Ford tuning | BLOCKED | Manual unverified profile until measured values exist. |

## Implementation rules retained

1. Independent implementation first; no blind source-tree merge.
2. All feature availability goes through `FeaturePolicy`.
3. Every render/input/launch boundary remains fail-closed on non-parked state.
4. One mirror ownership path and one geometry model.
5. Privileged capabilities remain optional and default-disabled; basic architecture does not require root.
6. Panel power and real-touch teardown must restore/cancel before the Shizuku binder is dropped.
7. DHU/device validation is separate from JVM proof.
8. Android Auto category/distribution assumptions are not treated as product guarantees.

See [`MODES.md`](MODES.md), [`FEATURE_POLICY.md`](FEATURE_POLICY.md), and [`ROADMAP.md`](../ROADMAP.md) for the current implementation decisions.
