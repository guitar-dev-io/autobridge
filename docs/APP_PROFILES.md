# Quick Apps and app profiles

Updated: 2026-09-08

Quick Apps are persisted records shared by the phone control center and Android Auto car screens. They are not a UI-only alias for the old favorite set.

## Quick App records

`QuickApp` stores:

- package name and display label;
- optional icon key;
- enabled/disabled state;
- explicit sort order;
- profile ID.

`SharedPreferencesQuickAppsRepository` persists the records. `QuickAppsCatalog` sorts by order then case-insensitive label, filters disabled entries, and intersects records with the current launchable installed-app list. An uninstalled package is filtered out, not fabricated as a launch target.

`FavoriteAppsStore` remains a compatibility source for one-time migration. Current phone/car UI reads Quick App records. Toggling a new record may update the legacy favorite set for older installations, but the favorite set is not the current source of truth.

## App profile fields

`AppProfile` is persisted per package and profile ID:

| Field | Meaning |
|---|---|
| `autoMirror` | User preference to mirror the app after launch; it never silently grants MediaProjection consent. |
| `autoFullscreen` | Persisted preference only; AutoBridge cannot force another app's immersive window through a public API. |
| `rotationMode` | `AUTO`, `PHONE`, `PORTRAIT`, or `LANDSCAPE`. |
| `scaleMode` | FIT/FILL/STRETCH/ONE_TO_ONE preference; current AUTO_MIRROR output remains FIT. |
| `preferredFps` | Stored per-app preference for a future/self-drawn renderer; not a per-frame guarantee today. |
| `preferredResolution` | AUTO/720p/1080p/custom preference boundary. |
| `touchEnabled` | User preference consumed by the control flow; final input still requires `Feature.TOUCH` and a backend. |
| `audioMode` | MEDIA, MIRROR, or OFF preference used by Smart Mode/profile UI. |
| `keepPhoneScreenOn` | Profile preference for the phone-side session. |

`DefaultAppProfiles` supplies conservative defaults for Netflix, YouTube, Maps, Spotify, Chrome, and unknown packages. Netflix is classified as a parked mirror/video app so Quick Apps do not route it to the native-media fallback; its protected video may still be blank in MediaProjection output. `PerAppProfileStore`/`SharedPreferencesAppProfileRepository` use tolerant enum decoding and support reset.

## Smart Mode

`SmartModeResolver` is pure and returns both a mode and a reason:

1. known audio-first packages such as Spotify/music/podcast packages choose `MEDIA`;
2. an explicit native-media profile chooses `MEDIA` when it does not request mirroring;
3. Chrome/browser packages choose `MIRROR` only when the profile asks for it, otherwise `BROWSER`;
4. YouTube/video packages choose parked `MIRROR`;
5. Maps/navigation packages choose `MIRROR` when requested, otherwise `NATIVE_CAR`;
6. other `autoMirror` profiles choose `MIRROR`;
7. the fallback is `NATIVE_CAR`.

The phone profile screen displays the selected mode and reason so a launch is not a black-box rule.

## Launch flow

`QuickAppLauncher.launch`:

1. checks `Feature.QUICK_APPS` through `FeaturePolicy`;
2. resolves a package launch intent and its persisted profile;
3. resolves Smart Mode and checks the required feature (`MEDIA`, `MIRROR`, `BROWSER`, or `QUICK_APPS`);
4. applies public `WRITE_SETTINGS` force-landscape support when requested, restoring the previous rotation snapshot on failure;
5. starts the target activity;
6. applies the requested display/rotation preference to shared runtime state and records a restorable session;
7. never starts MediaProjection without explicit user consent.

All launch paths are parked-only. Projection stop restores any temporary rotation setting through `AppRotationController`/`ProjectionService` cleanup. If a target app cannot be launched or refuses the requested behavior, the launcher returns failure rather than claiming success.

## Session restore

`SessionRestoreStore` stores the last package/profile/Smart Mode snapshot with tolerant pure codecs. Phone and car screens expose explicit Resume/Forget actions. Restore does not replay MediaProjection consent or silently launch a background activity.

## Car UI boundary

Android Auto uses host-managed `ListTemplate` screens (`CarAppsScreen`, dashboard, media/settings/control screens). It does not embed Compose or arbitrary phone overlays on the car surface. The live phone display remains the `NavigationTemplate` custom surface owned by the mirror engine.

## Deliberate limitations

- Fullscreen is a preference, not a hidden-API hack.
- Direct AUTO_MIRROR is full-display and FIT; target-app-only output needs a dedicated own-content display/self-drawn renderer.
- App launch, touch, browser, video, and mirroring remain subject to `FeaturePolicy` and authoritative vehicle state.
- Android background-start and third-party resizeability rules can reject per-display or background launches.
