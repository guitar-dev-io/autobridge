# Media bridge

Updated: 2026-10-03

AutoBridge is a bridge between the phone, the head unit, and whatever is rendering the content.
This document covers the parts added for that: the share entry point, the session manager, the
content router, the three playback engines, and the split between the phone and car UIs.

For the projection/mirroring side of the app see [`ARCHITECTURE.md`](ARCHITECTURE.md); for the
remote-stream transport see [`REMOTE_STREAM.md`](REMOTE_STREAM.md).

## Shape

```text
Phone app / Browser / YouTube / any app's share sheet
        |
        |  ACTION_SEND text  |  ACTION_VIEW http(s)
        v
ShareToCarActivity  ──────────────┐
                                  |
BridgeControllerActivity ─────────┤   (phone: search, queue, recents, favorites, transport)
                                  |
CarHomeMoreScreen / car rows ─────┤   (car: "Now playing")
                                  v
                    AutoBridgeSessionManager          <-- the only live session state
                                  |
                                  v
                          ContentRouter               <-- pure decision
                                  |
            ┌─────────────────────┼──────────────────────┐
            v                     v                      v
   NativePlaybackEngine   BrowserPlaybackEngine   RemoteStreamPlaybackEngine
   (MediaPlaybackService  (CarBrowserRuntime      (RemoteStreamReceiver
    + the shared session)  + WebMediaHub)          + MediaCodec)
            |                     |                      |
            └─────────────────────┴──────────────────────┘
                                  v
                       CarBridgePlayerScreen / CarBrowserScreen
                                  v
                              DHU / head unit
```

## Routing

`ContentRouter` is pure and is the only place the choice is made:

| Input                                             | Engine          |
|---------------------------------------------------|-----------------|
| A host on the protected list                       | none — refused  |
| A direct media URL (`.mp4`, `.m3u8`, `video/*`, …) | `NATIVE`        |
| An `http(s)` page                                  | `BROWSER`       |
| Anything else, with a stream host configured       | `REMOTE_STREAM` |
| Anything else                                      | none — refused  |

One fallback exists: `BROWSER → REMOTE_STREAM`, taken only when the browser engine reports it
could not render the page *and* a host is configured. `NATIVE` has none — a direct URL ExoPlayer
refused is a broken file, not a page.

The DRM check sits **above every other branch**, including the direct-media one, so there is no
ordering by which a protected URL reaches an engine. See the DRM section below.

## Ownership

`AutoBridgeSessionManager` owns the live session: connection, current surface, current source,
current engine, position, and the pending Send-to-Car request. The UIs read its one `StateFlow`
and do not keep copies, which is what stops the phone offering to control something the car has
already closed.

Durable state stays where it already lived:

| What                    | Where                                          |
|-------------------------|------------------------------------------------|
| Queue                   | `BrowserPlayQueue` (`autobridge_browser`)       |
| Favorites               | `WebBookmarkStore` (`autobridge_web_bookmarks`) |
| Recents                 | `RecentActivityStore` (`autobridge_recent`)     |
| Pending send, last session | `BridgeStore` (`autobridge_bridge`)          |
| Remote stream host      | `RemoteStreamConfig` (`autobridge_remote_stream`) |

No new storage library. Every file carries the `autobridge_` prefix, so `AppDataManager`
enumerates and clears them without being told about them.

## Phone and car

The split is the point of the design, not a style choice.

**Phone** (`BridgeControllerActivity`) — search, URL entry, recents, favorites, queue with
remove/clear/play-next, Send to Car, the transport bar (play/pause, ±10 s, restart, next, seek),
keyboard input, and the current playback readout including which engine is in use.

**Car** (`CarBridgePlayerScreen`) — the content, playback state, and five controls: back,
play/pause, next, −10 s, +10 s. No address bar, no menus, no settings, no tab strip. A
`PaneTemplate` carries the loading, error, empty and parked-gate states, because a
`NavigationTemplate` has nowhere to put text.

Four actions is also the practical ceiling before a head unit starts dropping them, so anything
added to the car strip silently removes something else.

## Send to Car, connected or not

`sendToCar` stores the request when nothing is connected rather than refusing it: the usual
sequence is "share a link indoors, then walk to the car". `AutoBridgeSession`'s `onCreate` calls
`onCarConnected`, which replays exactly one held request — the most recent, because "open this on
the car" means the latest one. Anything worth keeping goes in the queue.

The last session is **not** auto-resumed on connect. Something starting to play unasked when a
phone is plugged in is startling; the snapshot stays available for an explicit resume.

## Handoff

**Phone → car** carries URL, title and, where it can, position. For a direct media URL the
position is a `seekTo` on the shared session. For a YouTube watch page it is written into the URL
by `BrowserResumePoint`, which is the only general mechanism a web page offers. For every other
page the URL and title cross and the position does not — that is the documented floor.

**Car → phone** is prepared but not automatic: `saveSnapshot` runs on every disconnect, stop, and
car-player stop, so `lastSession()` always has the last URL/title/position. What is missing is the
phone-side decision of *where* to continue it, which is left for a later change.

## Errors

`BridgeErrorType` has the seven classes from the spec, each with a string resource. The raw cause
(`BridgeError.detail`, an ExoPlayer code, a socket message) goes to the log and is never rendered.

## Logging

Everything the bridge does logs under one tag:

```bash
adb logcat -s AutoBridge
```

Lines are `event key=value …`. The events are `share.received`, `send.pending`, `send.refused`,
`car.connected`, `car.pending_replay`, `route.decided`, `route.fallback`, `engine.selected`,
`engine.failed`, `native.*`, `browser.*`, `remote.*`, `surface.changed`, `cmd.*`,
`session.saved`, `session.resume`. URL query values are redacted (`BridgeLog.redact`) because the
in-app log screen is readable from the car.

## DRM

Protected services (Netflix, Disney+, Prime Video, Max, Apple TV, Hulu, and the others in
`ContentRouter.PROTECTED_HOSTS`) are **detected and refused**, with
`DRM_PROTECTED_CONTENT` shown to the user.

Nothing in this change strips, bypasses, downgrades or works around content protection:

- the DRM check runs before every engine branch, so no routing order reaches an engine with one;
- `ContentRouter.fallback` returns null for a protected host, so a browser failure on one cannot
  become a remote-stream render of it;
- `BrowserPlaybackEngine.canHandle` and `RemoteStreamPlaybackEngine.canHandle` each refuse one
  independently, so a direct call that skipped the router still cannot open one;
- `NativePlaybackEngine` maps ExoPlayer's DRM error range to `DRM_PROTECTED_CONTENT` and stops.

A protected stream rendered on a remote host would produce black frames there for the same
reason it does here — the host's own compositor refuses to capture a protected surface. The app
neither asks a host to try nor assists one that does.
