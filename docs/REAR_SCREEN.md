# Rear screen

Updated: 2026-11-17

Some drivers asked for the passengers' screen (a headrest or roof monitor) to show a video while
the car's own screen keeps navigation or music. Android Auto gives an app only the car's main
screen, so this cannot go through Android Auto: the phone has to feed the rear screen itself.

There are two kinds of rear screen, and both are planned:

| Phase | Rear screen | How the picture gets there | State |
|---|---|---|---|
| 1 | A screen Android sees as a display: HDMI or USB-C DisplayPort from the phone, a Miracast "cast screen" receiver, or a second screen on an Android head unit that runs AutoBridge | AutoBridge opens its own full-screen player on that display | This change |
| 2 | An Android tablet or headrest screen on the car's Wi-Fi | AutoBridge on the tablet in a "rear screen" mode, paired by QR code, plays what the phone sends | Not started |

## Phase 1: a display Android can see

- **Finding the screen.** [`RearDisplays`](../app/src/main/java/dev/autobridge/display/RearDisplays.kt)
  picks a display that is not the phone's own, accepts presentations, is not private, and is not
  one of AutoBridge's own virtual displays (`AutoBridge…` names: the car browser, Duo Screen panes,
  mirroring). Android Auto's projection display is private, so it is never chosen.
- **Playing.** [`RearScreenActivity`](../app/src/main/java/dev/autobridge/rear/RearScreenActivity.kt)
  is launched onto that display with `ActivityOptions.setLaunchDisplayId`. It has its own
  ExoPlayer, separate from the MediaSession the car drives, so the car's music, the steering-wheel
  buttons and the media card are untouched. It does not take audio focus, so starting the rear
  video does not pause the car's audio.
- **Starting it.** The phone player's header gets a rear-screen button while such a display is
  connected. It hands the current video (and its position, for a non-live video) to the rear
  screen. The phone and the car keep playing: both screens show the picture at once.
- **Sound.** The car has one set of speakers, and two copies of one video never stay in step, so
  both playing sound would echo. If the front is playing when the video is sent, the rear starts
  without sound and the sound stays in front. The notification's "Sound on" gives the rear its own
  sound, for a rear screen with speakers or headphones, or once the front plays something else.
- **Controlling it.** A rear screen with touch uses the player's own controls. For one without
  (most HDMI monitors), the phone shows a notification with pause/play, sound on/off and stop.
- **Ending it.** Stop in the notification, Back on a touch rear screen, or unplugging the screen.

### Known limits

- **USB port.** A phone that sends HDMI over its USB-C port cannot also run wired Android Auto
  there; it needs wireless Android Auto. Many phones (most Redmi models among them) have no video
  output on USB-C at all.
- **Where the rear sound goes.** Once the rear has sound, Android chooses its output. With HDMI it
  usually follows the picture to the monitor; with Miracast, to the receiver.
- **Not frame-locked.** The two screens play separate copies, so they can drift apart by a moment.
  Locking them together would need one decoder feeding both screens; not planned unless asked.
- **Lock screen.** The rear player shows over the lock screen, so a locked phone does not cover
  the passengers' video with the keyguard.

### Trying it without a monitor

Developer options → "Simulate secondary displays" adds an overlay display that AutoBridge treats
like a real rear screen. The on-device test uses the same:

```bash
adb shell settings put global overlay_display_devices 1280x720/240
adb shell am instrument -w -e class dev.autobridge.rear.RearScreenActivityTest \
  -e rearUrl http://10.0.2.2:8765/test.mp4 -e holdMs 15000 \
  dev.autobridge.test/androidx.test.runner.AndroidJUnitRunner
```

`rearUrl` is any video the device can reach (`10.0.2.2` is the Mac, from an emulator); `holdMs`
keeps it playing for a look before the test closes it.

## Phase 2: a tablet on the car's Wi-Fi

For a headrest or roof screen that is an Android tablet: AutoBridge is installed on the tablet too,
and put in **rear screen mode**. The phone sends it what to play over the car's Wi-Fi (usually the
phone's own hotspot). No second app, no cloud, no internet needed beyond what the video itself needs.

- **Tablet.** Settings › Car & Connection › Rear screen › "Use this device as a rear screen" opens
  [`RearReceiverActivity`](../app/src/main/java/dev/autobridge/rear/wifi/RearReceiverActivity.kt):
  full screen, kept on, showing its name, its address and a 4-digit pairing code until a phone
  sends something. It runs a small HTTP server
  ([`RearServer`](../app/src/main/java/dev/autobridge/rear/wifi/RearServer.kt)) and announces itself on
  the network (`_abrear._tcp`, Android's NSD/mDNS) while the screen is open.
- **Pairing, once.** On the phone, the same Rear screen page lists the tablets it finds. Tapping
  one asks for the code shown on the tablet; the tablet answers with a token the phone keeps. Every
  later command carries the token, so another phone on the same Wi-Fi cannot take over the
  passengers' screen. Five wrong codes lock pairing for a minute. A tablet that is not found (some
  hotspots block mDNS) can be added by the address it shows.
- **Sending.** The player's 🖥 button sends to a wired rear display when there is one (phase 1),
  otherwise to the paired tablet: same position, same mute rule (muted while the front plays), and
  the same phone notification with pause/play, sound and stop.
- **Protocol.** JSON over HTTP on port 47820 (the next free port if taken): `GET /info`,
  `POST /pair {code}` → `{token}`, then with header `X-AutoBridge-Token`: `POST /play
  {url,title,positionMs,muted}`, `POST /toggle`, `POST /mute`, `POST /stop`, `GET /status`.

Limits: the tablet must reach the video's URL itself (it plays the URL, not a copy of the phone's
picture), so a video that only the phone can open (a local file, a logged-in page) does not play
there yet. The server runs only while rear screen mode is open on the tablet.
