# Android Auto direct

AutoBridge speaks the phone side of the Android Auto protocol to the car itself, without Google's
Android Auto app. The car shows AutoBridge's own screens. Sideload flavors (`personal`, `lab`)
only.

Status: **phase 2: USB, wireless, sound, margin crop.** JVM tests cover the protocol layer
against a scripted head unit. The Android side compiles but has **not been run against a real car
yet**.

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
| `:aap` (`dev.autobridge.aap`) | Protocol only, no Android types. `AapFrames` (framing), `Proto` (hand-written protobuf), `AapTls` (SSLEngine as server, PEM loading), `AapProtocol` (message ids and bodies), `AapSession` (the state machine), `AapWireless` (the Bluetooth start-up exchange). |
| `app/src/projection/.../aapdirect` | `UsbAccessoryActivity` (receives the accessory attach), `DirectProjectionService` (owns a session), `UsbAccessoryTransport`, `CarVideoEncoder` (H.264 baseline from a Surface), `CarDisplay` (private virtual display + touch routing), `CarHomeActivity` (the car's first screen), `DirectProjectionActivity` (phone screen), `WirelessLink` (RFCOMM + `WifiJoin` + `TcpTransport`), `CarAudioCapture` (playback capture to PCM), `MarginCompositor` (GL: draws the display into the visible part of the stream). |

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

## Wireless

The car is reached over Bluetooth RFCOMM on the service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`.
Cars differ in which side opens that link, so the phone listens on it and also calls the paired
devices that advertise it. The car sends `WifiStartRequest` (its address and port). The phone asks
`WifiInfoRequest`, and the car answers `WifiInfoResponse` (SSID, key, BSSID). The phone then joins
that Wi-Fi with a `WifiNetworkSpecifier`. Android shows a prompt the first time and remembers the
choice after that. The phone opens TCP to the car, and the same Android Auto session as over USB
runs on that socket. The RFCOMM link stays open for the length of the session. When a session
ends, the service goes back to listening.

## Sound

Off by default ("Send sound to the car"). When it is on, the phone opens the car's media audio
channel (16-bit PCM in the car's format) and asks for audio focus. Android lets an app copy other
apps' sound only through playback capture. That needs the screen-capture consent and the
microphone permission, so the phone screen asks for both once the car's channel is ready. Media,
game and unknown usages are captured. The phone also keeps playing the sound itself.

## Margins

When the car crops a margin off the stream, the virtual display is made the size of the visible
area. `MarginCompositor` draws it centred into the full-size encoder surface, with the margin
black. Touch is scaled from the car's touch size to the visible size.

## What the car shows

`CarDisplay` is a private virtual display rendering into the encoder's input surface.
`CarHomeActivity` opens on it. Its tiles start the normal phone activities (Library, Browser,
Weather), which the sideload manifest marks `allowEmbedded` so they can open on an untrusted
display. Touches are dispatched in-process to the activity in front on that display.

Android does not normally let a background service start an activity. The service therefore puts
a splash window (a `Presentation`) on the car display first. That gives the app a visible window,
which allows the launch. If Android still refuses, the log says so, and opening the phone screen
launches the car home again.

## Known gaps (next phases)

- Navigation-prompt and voice audio channels, and the car's microphone (only the music channel is
  opened).
- Wireless details that vary by car: some want the phone to host the Wi-Fi instead, or need a
  `WifiVersionResponse` with content.
- Dialogs and popups over a car-display activity do not get touches. Only the activity window does.
- Phone mirroring (MediaProjection) as an alternative to the app display.
- Using the car's speed and driving status in `VehicleState`.

## Trying it

1. Upload the certificate and key under Settings › Android Auto direct.
2. If Google's Android Auto is installed, it may claim the USB accessory first. Use the "Android
   Auto app" row to open its app info, then disable it or clear its defaults.
3. Plug in the cable. If the car switches the phone into accessory mode, Android offers AutoBridge.
4. If it fails, use "Copy connection log" and attach the text to the issue.
