# Play Console declarations

Updated: 2026-10-05

What has to be written into the Play Console for the **safe** flavor, which is the only one Play
ever sees (`personal` and `lab` carry the unofficial Android Auto SDK, Duo Screen and
`QUERY_ALL_PACKAGES`, and are sideload-only — see [docs/SIDELOAD.md](docs/SIDELOAD.md) and
[docs/MODES.md](docs/MODES.md)).

Three things have to agree, and nothing in the build checks the first two:

1. the **permission declarations** in the console (prose a human types), below;
2. the **Data safety** form, below;
3. the **in-app disclosure** each sensitive permission shows before the system prompt — that part
   is code, and the table says where it lives.

`scripts/check-play-manifest.sh` fails the build if the merged safe manifest declares something
sideload-only, or declares a permission from the list below that this file does not mention. It
runs in CI after the safe assemble.

## The permissions Play asks about

| Permission | In the Play build | In-app disclosure before the prompt |
|---|---|---|
| `QUERY_ALL_PACKAGES` | **No** — `src/projection/AndroidManifest.xml`, personal/lab only | n/a (not shipped to Play) |
| `BIND_ACCESSIBILITY_SERVICE` | Yes | `a11y_disclosure_*`, shown by `PermissionDisclosure` from `MainActivity.openTouchSettings` and `MirrorSetupActivity.openTouchSetup` |
| `RECORD_AUDIO` | Yes | `mic_disclosure_*`, shown by `CarDisclosureScreen` from `CarAgentScreen.startListening` |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Yes | per-site `geo_*` dialog in `BrowserGeolocation.Prompter`; `gps_speed_disclosure_*` in `CarSettingsScreen.enableGpsSpeed` |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Yes | Android's own capture consent, per session; `MirrorSetupActivity` explains the step first |
| `WRITE_SETTINGS` | Yes | the system's own "Modify system settings" screen, reached from the per-app landscape profile |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Yes | `MirrorSetupActivity.requestBatteryExemption`, an optional step that says why |

### Accessibility service — declaration text

> AutoBridge projects the phone's screen onto the car display. The accessibility service is the
> input path back: it performs the taps, swipes and Back/Home actions that the user makes on the car
> screen, on the phone. Without it the car display can show the phone but cannot control it.
>
> The service subscribes to `typeWindowStateChanged` only and uses `canPerformGestures`. It reads no
> screen content, and no data from it leaves the device.
>
> The user is shown what the service does, in the app, before being sent to Android's accessibility
> settings (string `a11y_disclosure_body`). The alternative input path, Shizuku, is offered first
> where it is available, and the app works without either — mirroring simply becomes view-only.

The functionality is parked-only and, in the Play build, `FeaturePolicy` keeps `TOUCH` and `MIRROR`
mode-disabled anyway (see [docs/FEATURE_POLICY.md](docs/FEATURE_POLICY.md)). The service is declared in
`src/main/AndroidManifest.xml`, so it ships; if Play ever refuses the declaration, moving that one
`<service>` block into `src/projection/AndroidManifest.xml` removes it from the Play build without
touching any code.

### Microphone — declaration text

> One screen uses the microphone: the Agent, where the driver presses the mic, says a command
> ("open YouTube", "resume"), and the app runs it. Recording happens only inside a listen session
> started by that press, and the session ends on a result, an error or a timeout. Speech is
> transcribed by Android's own `SpeechRecognizer`. AutoBridge stores no audio and sends none to its
> own servers. The car microphone is used when the head unit offers one (`CarAudioRecord`), the
> phone's otherwise.

### Location — declaration text

> Foreground only, and never in the background. Two uses, each with its own consent:
>
> 1. A web page in the app's browser calls `navigator.geolocation`. The user is asked per site
>    before the app requests the Android permission, and only HTTPS origins are eligible.
> 2. "GPS speed" in car settings, off by default. AutoBridge needs a speed to decide whether the car
>    is parked, and some head units report none; with the setting on, the speed is read from the
>    phone's location fix while a car session runs. The value is used for the parked/moving decision
>    and the speed readout, and is not stored, logged or transmitted.
>
> Holding the permission is not enough on its own to enable (2): `AppPreferences.gpsSpeed` has to be
> turned on, and the disclosure is shown each time it is.

No `ACCESS_BACKGROUND_LOCATION` is declared, and none is needed.

## Data safety

Derived from the code, not from intent. Re-check it whenever a network call is added.

### Collected or shared by AutoBridge

**None.** The app has no analytics, no crash reporting SDK and no backend of its own. Crash reports
(`CrashReportStore`) and logs (`StructuredLog`) are written to app-private storage and shown in the
app's own diagnostics screen; nothing uploads them. Declare **no** data collection and **no** data
sharing, and answer "no" to third-party analytics.

The requests the app itself makes:

| Destination | What is sent | Why |
|---|---|---|
| `api.open-meteo.com`, `geocoding-api.open-meteo.com` | the coordinates of the place **the user searched for and saved** (`WeatherLocationStore`) — not the device location | the Weather section |
| `api.github.com` (releases) | nothing but the request | the update check |
| the user's own IPTV provider | the credentials that user entered | the IPTV sections |
| whatever site the user opens | ordinary web traffic | the browser |

### Answers the form still needs

- **Data encrypted in transit:** yes for everything AutoBridge requests itself (HTTPS). Note the
  exception honestly: IPTV portals are overwhelmingly plain HTTP, so cleartext is permitted for
  user-entered hosts only, through `res/xml/network_security_config.xml`. That traffic is between the
  user and the provider they chose.
- **Users can request deletion:** there is nothing to delete off a server, because nothing is sent.
  In-app, "clear all data" (`AppDataManager.clearAllData`) wipes every preference file, the caches
  and the Keystore key.
- **Committed to the Families policy / ads:** no ads, no ad SDK.

### App functionality the form counts as "accessed, not collected"

These touch sensitive data on the device without it leaving:

- microphone audio — transcribed on the device by `SpeechRecognizer`, never stored;
- location — used for the speed reading and for web geolocation, never stored;
- photos/audio/video (`READ_MEDIA_*`) — the Folders, Playlists and Gallery sections read the user's
  media to play it, in place;
- screen contents (`MediaProjection`) — captured to draw on the car display, per session, never
  written to a file.

Credentials are the one thing the app persists that is worth naming: IPTV usernames, passwords and
portal URLs. They are encrypted with an Android Keystore key (`SecretText`), excluded from cloud
backup and device transfer (`res/xml/data_extraction_rules.xml`), and unrecoverable after an
uninstall, which drops the key.
