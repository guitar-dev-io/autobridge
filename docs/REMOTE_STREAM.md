# Remote stream (experimental)

Updated: 2026-10-03

Lets another machine render content and send the resulting video to AutoBridge, which decodes it
onto the Android Auto surface. For where this sits in the bridge, see
[`MEDIA_BRIDGE.md`](MEDIA_BRIDGE.md).

**Off by default.** It needs a host the user runs themselves, and the router skips the remote
branch entirely while it is off, so an unconfigured install never waits on a machine that was
never set up.

## Pipeline

```text
host (browser / player / capture)
  → H.264 Annex-B access units
  → WebSocket binary frames          ─┐
                                       ├─ one socket
  ← JSON control messages (text)     ─┘
  → WebSocketTransport
  → RemoteStreamReceiver
  → H264Decoder (MediaCodec, async)
  → Surface (the car's own)
  → DHU / head unit
```

Decoded frames are never copied into the app. `MediaCodec` is configured with the car's surface
and `releaseOutputBuffer(index, true)` renders straight into it, which is the only way this is
affordable on a phone already running a WebView and a projection.

## Why WebSocket first, and where WebRTC goes

The spec asks for WebRTC first with RTSP as the alternative. The transport is behind
`RemoteStreamTransport` — connect, deliver access units, take control messages, report drops —
and everything above it (decoder, status machine, reconnect, control channel, car UI) is written
against that interface, so changing transport is a new class and a config line.

WebSocket is the first implementation because:

- **it can be stood up today.** Any `ws` library plus `ffmpeg` is a working host; the reference
  host below is 60 lines. WebRTC needs a signalling server and a peer implementation before a
  single frame moves.
- **the control channel is free.** The same socket carries commands back, so command latency is
  the video's latency and there is no second connection to keep alive. With WebRTC this is the
  DataChannel, which is the same idea with more setup.
- **no new dependency.** The framing is `WebSocketFrame`, ~150 lines, fully unit tested. The
  WebRTC Android artifact is ~25 MB of native code that the rest of the app would carry for one
  experimental feature.

WebRTC's real advantages are congestion control, jitter buffering, NAT traversal and
loss concealment. All of them matter on a link that is not a LAN, and none of them matter for the
PoC's "laptop and phone on the same Wi-Fi". When this moves past a PoC, WebRTC is the next
transport and the DataChannel replaces the text frames one-for-one.

RTSP was not pursued: it brings its own session protocol and RTP depacketisation for the same
H.264 payload, and unlike WebRTC it offers nothing the control channel needs.

## Status

Reported through `RemoteStreamStatus`, which is what the car screen shows:

| Status         | Means                                               |
|----------------|-----------------------------------------------------|
| `CONNECTING`   | Dialling, or retrying after a drop                   |
| `CONNECTED`    | Link up and frames arriving                          |
| `BUFFERING`    | Link up, no frame for 3 s — stalled, not idle        |
| `DISCONNECTED` | Closed cleanly, or stopped by the user               |
| `ERROR`        | Handshake, socket or decoder failure                 |

`BUFFERING` is distinct from `CONNECTING` on purpose: a stalled link and a dead one look
identical on screen otherwise, and they call for opposite reactions from the user.

## Reconnect

`ReconnectPolicy`: no delay for the first attempt, then 1 s doubling to a 30 s ceiling. A user
stop never retries. The surface belongs to the car screen, not to the stream, so a host that
comes back resumes into the same output with no interaction.

## Control channel

Commands travel back as JSON text frames:

```json
{ "type": "playback",   "action": "seek", "positionMs": 120000 }
{ "type": "playback",   "action": "play" }
{ "type": "playback",   "action": "pause" }
{ "type": "navigation", "action": "up|down|left|right|select|back" }
{ "type": "keyboard",   "action": "text", "text": "hello", "submit": true }
{ "type": "scroll",     "action": "by", "deltaX": 0, "deltaY": -240 }
{ "type": "open",       "action": "url", "url": "https://…", "positionMs": 0 }
```

The host may push its own state the other way:

```json
{ "type": "status", "state": "playing", "title": "…", "positionMs": 1000, "durationMs": 60000, "error": null }
```

**Raw touch forwarding is not implemented.** A touch is meaningless without the host's exact
layout and scale, it breaks when the host window moves or the car surface is a different size,
and it gives the driver a phone-sized hit target at the wheel. Named intents survive all of
that. The spec allows touch forwarding only "if absolutely necessary", and it is not.

## Setting it up

1. Phone → Home → **Open controller** → scroll to **Remote stream (experimental)**.
2. Enter the host, e.g. `192.168.1.20:8080` (bare `host:port` is assumed `ws://`).
3. **Save**, then **Turn on**.

Once on, a page the browser engine cannot render falls back to the host. A page that renders
normally still goes to the browser — the remote stream is a fallback, never a first choice.

## Reference host

`tools/remote-stream-host/` holds a minimal Node host: it accepts a WebSocket, runs `ffmpeg` to
encode a window or a file as baseline H.264, and writes each access unit as one binary frame. It
is a test fixture for the receiver, not a product.

## DRM

The remote stream is **not** a way around content protection. `ContentRouter` refuses protected
hosts before any engine sees them, `ContentRouter.fallback` returns null for one, and
`RemoteStreamPlaybackEngine.canHandle` refuses one independently of both. A host that chose to
render protected content would see the same black frames the car does — a protected surface is
not capturable — and this app neither asks it to nor helps it.
