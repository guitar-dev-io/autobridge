# In-app media prototype

The Android Auto home now opens Video, Music, Browser, Favorites, Now Playing and Apps. Mirror is an optional header action. This implementation reuses the existing Car App surface transport; it does not implement Fermata's projection service or promise equivalent host compatibility.

## Implemented

- Video library: sample MP4/DASH sources and an HTTPS stream entry field.
- CarVideoScreen: attaches the shared Media3 controller to the Android Auto surface, with Back, Home, Play/Pause and seek by 10 seconds. No MediaProjection consent is required for this path. Errors return a readable page with Back. Leaving the screen pauses video and detaches output; the host owns the Surface and the app does not release it.
- Music: reuses the MediaSession and now includes sample track titles in metadata.
- Browser/Favorites: open CarBrowserScreen directly instead of launching the phone Activity. Search returns to the same browser. Home returns to the AutoBridge root. Page rendering pauses while its screen is stopped and the callback is reclaimed when returning.
- New video/browser screens check the reported parking state and feature availability.

## Validation on 2026-09-14

- Personal debug APK assembled successfully.
- APK installed and MainActivity launched successfully on Pixel_8_API_35 emulator.
- Personal unit suite: 78 tests, 73 passed, 5 failed. The failures match the pre-existing report from September 10: three AppProfileRegressionTest policy cases and two CoreRegressionTest speed cases. The working tree already forces parked state in policy/speed code; this change does not repair those unrelated edits. Reported PARKED is therefore not proof of real vehicle state in this checkout.
- DHU attempted against emulator on forwarded port 5277; transport disconnected before a session. The emulator's Android Auto package exposes stub activities and was not ready as a DHU host. Actual car video frames, playback controls and web touch are NOT yet verified.
- Xiaomi 13T Pro (`23078PND5G`, Android 16) with Android Auto 17.6 connected to DHU 2.0 over direct USB. ADB port forwarding was unreliable on this combination, while Android Open Accessory mode completed the protocol 1.7 and TLS handshake.
- Android Auto discovered and bound `AutoBridgeCarAppService`. The native browser received an `800x400` car surface and continuously rendered its WebView without a crash.
- The Frame counter sample reached `PLAYING`, obtained audio focus, configured the MediaTek H.264 decoder for 720p/25 fps, attached the decoder to the car surface, and reported 122-124 rendered frames per five seconds with zero dropped frames. Pause and resume both changed the MediaSession state successfully.
- The device reports a non-fatal `setFrameRate(25)` unsupported error for the host surface. Playback continues at about 25 fps.

## Next device checks

1. Start a working Android Auto head-unit server and connect DHU with this APK.
2. Visually confirm the decoded Frame counter pixels and audio on a physical head unit; DHU playback and decoding are verified from runtime state and codec statistics.
3. Test the on-screen seek buttons, then Back and Home. Confirm audio stops when leaving video. Reopen and reconnect the host to check surface replacement.
4. Enter a valid HTTPS MP4, HLS or DASH link. Try an unavailable URL and verify an error with Back.
5. Open Browser, search for a site, return from search, tap a link and scroll. Open a saved site from Favorites and return Home.
6. Exercise actual PARKED/MOVING/UNKNOWN transitions with an authoritative provider before any real-vehicle validation.

## Prototype limits

NavigationTemplate still supplies the host surface; map-style host controls can remain. This is not a claim of navigation-app eligibility. Direct video aspect ratio/letterboxing and host overlay insets need measurement. WebView software drawing does not guarantee composited video, WebGL, DRM playback or on-page keyboard input; Search is the supported URL entry point. Opening YouTube means opening its website, not verified YouTube video playback. The existing phone file picker is retained; selected local files are not yet wired into the new direct car-video library. Favorites currently store websites, not media files.

References inspected: [Fermata auto manifest](https://github.com/AndreyPavlenko/Fermata/blob/master/fermata/src/auto/AndroidManifest.xml), [MediaController video surface API](https://developer.android.com/reference/androidx/media3/session/MediaController#setVideoSurface(android.view.Surface)). No Fermata source was copied.
