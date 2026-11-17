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
  screen and pauses the phone's own copy.
- **Controlling it.** A rear screen with touch uses the player's own controls. For one without
  (most HDMI monitors), the phone shows a notification with pause/play and stop.
- **Ending it.** Stop in the notification, Back on a touch rear screen, or unplugging the screen.

### Known limits

- **USB port.** A phone that sends HDMI over its USB-C port cannot also run wired Android Auto
  there; it needs wireless Android Auto. Many phones (most Redmi models among them) have no video
  output on USB-C at all.
- **Sound.** Android chooses where the rear player's sound goes. With HDMI it usually follows the
  picture to the monitor; with Miracast, to the receiver.
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
