# Android Auto direct

AutoBridge speaks the phone side of the Android Auto protocol to the car itself, without Google's
Android Auto app. The car shows AutoBridge's own screens. Sideload flavors (`personal`, `lab`)
only.

Status: **phase 1, USB.** The protocol layer is covered by JVM tests against a scripted head unit.
The Android side compiles but has **not been run against a real car yet**.

## The certificate

The car is the TLS client and the phone is the TLS server. A factory head unit only accepts a
phone whose certificate chains to the Android Auto certificate authority. AutoBridge does not ship
or obtain such a key. The user uploads a certificate and private key (PEM) on the phone screen. A
car that refuses the certificate shows a handshake alert in the connection log. Open-source head
units (OpenAuto/aasdk-based, Crankshaft) do not check the chain, so they are the practical test
target.

## Layout

| Where | What |
|---|---|
| `:aap` (`dev.autobridge.aap`) | Protocol only, no Android types. `AapFrames` (framing), `Proto` (hand-written protobuf), `AapTls` (SSLEngine as server, PEM loading), `AapProtocol` (message ids and bodies), `AapSession` (the state machine). |
| `app/src/projection/.../aapdirect` | `UsbAccessoryActivity` (receives the accessory attach), `DirectProjectionService` (owns a session), `UsbAccessoryTransport`, `CarVideoEncoder` (H.264 baseline from a Surface), `CarDisplay` (private virtual display + touch routing), `CarHomeActivity` (the car's first screen), `DirectProjectionActivity` (phone screen). |

## Session flow

1. Car → `VERSION_REQUEST`. Phone → `VERSION_RESPONSE` (plain).
2. TLS records inside `SSL_HANDSHAKE` messages, with the car as client. Then car → `AUTH_COMPLETE`.
3. Phone → `SERVICE_DISCOVERY_REQUEST`. Car → the channels it offers.
4. Phone opens video, input and sensor channels (`CHANNEL_OPEN_REQUEST`, CONTROL flag).
5. Video: setup (H.264 BP) → car picks a config and an unacked window → `MEDIA_START` → frames
   (`MEDIA_WITH_TIMESTAMP`, 8-byte µs timestamp + Annex-B). Codec config goes as `MEDIA_DATA`.
6. Touch and key events arrive on the input channel. Night mode, driving status and speed arrive
   on the sensor channel.

Every message after `AUTH_COMPLETE` is encrypted. Each frame's TLS records decrypt on their own.
Messages of 16 kB or more are split into FIRST/MIDDLE/LAST frames. The FIRST frame carries the
total size.

## What the car shows

`CarDisplay` is a private virtual display rendering into the encoder's input surface.
`CarHomeActivity` opens on it. Its tiles start the normal phone activities (Library, Browser,
Weather), which the sideload manifest marks `allowEmbedded` so they can open on an untrusted
display. Touches are dispatched in-process to the activity in front on that display.

## Known gaps (next phases)

- **Wireless** (Bluetooth RFCOMM + the car's Wi-Fi). The "Listen for wireless" path is not built.
- **Audio channels** are not opened, so sound plays on the phone or its Bluetooth link.
- **Margins**: when the car crops a margin off the stream, the display is not inset yet. A line in
  the log says so.
- Dialogs and popups over a car-display activity do not get touches. Only the activity window does.
- Phone mirroring (MediaProjection) as an alternative to the app display.
- Using the car's speed and driving status in `VehicleState`.

## Trying it

1. Upload the certificate and key under Settings › Android Auto direct.
2. If Google's Android Auto is installed, it may claim the USB accessory first. Use the "Android
   Auto app" row to open its app info, then disable it or clear its defaults.
3. Plug in the cable. If the car switches the phone into accessory mode, Android offers AutoBridge.
4. If it fails, use "Copy connection log" and attach the text to the issue.
