# 12 — Chrome vs Brave compatibility

Status: **research only**. "Verified" means read from the vendor's own package index or site on
2026-10-18. "Reported" means secondary sources. "To measure" means it needs the phone.

## 1. Official Linux ARM64 availability

| | Google Chrome | Brave |
|---|---|---|
| Official arm64 Linux build | **Yes, verified.** `binary-arm64` in `dl.google.com/linux/chrome/deb`, Release lists `amd64 arm64` | **Yes, verified.** `binary-arm64` in `brave-browser-apt-release.s3.brave.com`, Release lists `amd64 arm64` |
| Current stable (2026-10-18) | `google-chrome-stable 155.0.8059.84-1` | `brave-browser 1.97.59` |
| How long it has existed | New. First shipped around 30 Jul – 3 Aug 2026 (reports disagree); Google promised Q2 2026 | Years. The arm64 index goes back to `1.51.114` (2023) |
| Channels on arm64 | stable, beta (157), canary (158) all present in the index | stable, plus separate beta/nightly repos |
| Formats | .deb (APT repo + direct `google-chrome-stable_current_arm64.deb`), .rpm | .deb (APT), .rpm (verify aarch64 in the RPM repo before relying on it) |
| Licence of the binary | Proprietary, Google Chrome Terms of Service. **Not redistributable** | Brave binary + trademark. Source is MPL-2.0, but the official build is Brave's. **Do not rehost** |

Both have to be **downloaded on the device from the vendor's repo at the user's request**, never bundled
or mirrored. The licence and integrity flow is in [13](13-browser-package-management.md).

## 2. Dependencies and glibc

From the arm64 `Packages` entries:

- Both: `libc6 (>= 2.25)`, `libgtk-3-0 | libgtk-4-1`, `libnss3`, `libnspr4`, `libasound2`, `libgbm1`,
  `libvulkan1`, `libx11-6`, `libxcb1`, `libxcomposite1`, `libxdamage1`, `libxext6`, `libxfixes3`,
  `libxkbcommon0`, `libxrandr2`, `libcups2`, `libdbus-1-3`, `libpango-1.0-0`, `libcairo2`,
  `libatk*`, `libcurl*`, `fonts-liberation`, `xdg-utils`, `wget`, `ca-certificates`.
- Chrome only: `libudev1`, `libexpat1`.
- Brave only: `libdrm2`, `libu2f-udev`, `brave-keyring`.

glibc 2.25 is far below any current Debian (12: 2.36, 13: 2.41). **One Debian 13 arm64 rootfs
covers both.** On trixie `libasound2` is provided by `libasound2t64`. apt resolves it, but T1
below must confirm it on the device.

Ubuntu is not a good base: its `chromium` is a snap, and snaps do not run under PRoot. Debian is
also the vendors' primary target.

## 3. `--ozone-platform=x11`

Chromium on Linux has used Ozone only since the old X11 path was removed. Both browsers support
`--ozone-platform=x11` (and `wayland`, and `auto`). The adapter always passes `x11` explicitly with
`DISPLAY=:1`, so a stray `WAYLAND_DISPLAY` cannot change the backend. Test: T2.

## 4. One X server and one rootfs for both

Yes. Nothing in either package conflicts: different `/opt` trees, different `/usr/bin` wrappers,
different policy directories (`/etc/opt/chrome/policies/managed` vs `/etc/brave/policies/managed`),
and different default profile directories (which we override anyway). Both are ordinary X11
clients, so they can share a display. Showing one at a time is a product choice (doc 11), not a
limitation.

## 5. Resource cost

| | Chrome 155 arm64 | Brave 1.97.59 arm64 | Source |
|---|---|---|---|
| Download (.deb) | 136.1 MB | 142.8 MB | verified, `Size:` |
| Installed (`/opt` tree) | 434 MiB | 465 MiB | verified, `Installed-Size:` |
| Shared runtime (rootfs + GTK/NSS/X/mesa deps) | ~0.4–0.5 GB, paid once | same, shared | estimate, to measure |
| RAM, one tab idle / one 1080p video | — | — | to measure (T5) |
| CPU during 720p/1080p software video | — | — | to measure (T5) |
| Cold start to first frame on car | — | — | to measure (T5) |

There are no RAM or CPU figures here because published desktop numbers do not carry over to a
phone under PRoot (ptrace syscall overhead, no GPU). Both are Chromium-based, so expect similar
numbers. Brave usually does better on ad-heavy pages because fewer scripts load. Measure this, do not
assume it.

Disk, one browser: ~0.9–1.0 GB. Both: ~1.4–1.5 GB. Two separate runtimes would be ~1.9 GB.

## 6. Brave Shields, ad blocking, privacy

- **Brave:** Shields are native (Rust adblock engine, EasyList/EasyPrivacy/uBO-style lists, tracker
  and cross-site cookie blocking, fingerprint randomisation, HTTPS upgrade). They are not an
  extension, so Manifest V3 limits do not apply, and they work the same on Linux arm64.
  This is the closest match to what the embedded browser's `AdBlockList`/`BrowserAdBlock` give
  car users today.
- **Chrome:** no built-in blocker. MV2 uBlock Origin no longer runs, and only MV3 blockers
  (uBlock Origin Lite and others) can be installed from the Web Store by the user. AutoBridge does not
  sideload extensions.
- Brave extras we turn **off** by managed policy in car sessions: Rewards, Wallet, VPN, Leo AI chat,
  Tor windows (`BraveRewardsDisabled`, `BraveWalletDisabled`, `BraveVPNDisabled`,
  `BraveAIChatEnabled=false`, `TorDisabled`. Check these names against Brave's current policy list
  before shipping).
- Both: password manager and first-run UI off by policy. Sync and sign-in stay up to the user.

## 7. HTML5 media, audio, fullscreen, DRM

| | Chrome | Brave | Notes |
|---|---|---|---|
| H.264 / AAC / VP9 / Opus | yes | yes | both ship proprietary codecs |
| Hardware video decode | **no** | **no** | no VA-API/V4L2 inside PRoot, so decode is in software. 1080p VP9/AV1 is likely too heavy; plan for 720p |
| GPU | **no** | **no** | SwiftShader/llvmpipe software GL by default. A GPU path (virgl/Turnip) is a later LAB experiment |
| Audio | ALSA → PulseAudio → bridge | same | `libasound2-plugins` routes ALSA to Pulse |
| Fullscreen | yes, inside the X window | yes | `--kiosk` for car sessions |
| Widevine | **bundled** linux_arm64 CDM (reported, Jul 2026) | downloaded by Brave on first DRM page after the user agrees; aarch64 issue [brave/brave-browser#56129](https://github.com/brave/brave-browser/issues/56129) closed Aug 2026 | Linux Widevine is L3 (software): Netflix and similar cap at ~720p/1080p, no HDR/4K. Whether the CDM loads under PRoot is **unknown** (T6) |

Video stays parked-only. See doc 11 § Safety.

## 8. Startup arguments

Common to both adapters:

```
--ozone-platform=x11
--user-data-dir=<profile>  --disk-cache-dir=<cache>
--no-first-run  --no-default-browser-check
--password-store=basic                 # no keyring daemon in the rootfs
--no-sandbox                           # PRoot cannot give Chromium's namespace/seccomp sandbox
--disable-dev-shm-usage                # unless /dev/shm is bind-mounted to a real tmpfs dir
--window-position=0,0  --window-size=<W>,<H>  --force-device-scale-factor=<s>
--kiosk  (car)  |  --start-maximized  (phone/LAB)
GPU mode: --disable-gpu  |  --use-gl=angle --use-angle=swiftshader
```

Differences:

| | Chrome | Brave |
|---|---|---|
| Executable | `/opt/google/chrome/google-chrome` | `/opt/brave.com/brave/brave-browser` |
| Managed policy dir | `/etc/opt/chrome/policies/managed/` | `/etc/brave/policies/managed/` |
| Extra policy | — | Rewards/Wallet/VPN/Leo/Tor off |
| Self-update | none on Linux (apt + a daily cron that re-adds the repo; cron does not run under PRoot, harmless) | none on Linux (apt) |
| Repo key package | key installed by the .deb | `brave-keyring` |
| `WM_CLASS` | `Google-chrome` | `Brave-browser` (to confirm in T2) |

`--no-sandbox` is the real security cost of this design. A renderer exploit is not contained by
the Chromium sandbox, only by the Android app sandbox around AutoBridge. Both browsers show a warning
bar for it, which should stay visible. This is one more reason the safe flavor never gets this.

## 9. Stopping one browser without the other

Yes, by design: one PRoot tracer per browser session, with X and Pulse as separate processes
(doc 11 § Process model). To verify: T7.

## Which browser first

**Brave first, then Chrome.**

1. **Track record on arm64 Linux.** Brave has shipped arm64 .debs for about three years. Chrome's
   arm64 build is about ten weeks old. The first adapter's job is to prove the shared runtime, so
   it should use the browser least likely to fail for its own reasons.
2. **Matches what car users have today.** Shields give native ad and tracker blocking, like the
   embedded browser's blocker. Chrome has no equivalent without user-installed MV3 extensions.
3. **Install flow.** Brave only needs the user to accept what they get. Chrome needs the
   Google Chrome Terms of Service shown and accepted before download, so it is a little more UI.
4. **The cost of doing Chrome second is small.** Once the runtime and the Brave adapter exist,
   Chrome is one adapter, one repo key, and one policy file.

**Change the order if** reliable DRM streaming is the main reason for the desktop browser.
Chrome bundles Widevine, while Brave's arm64 Widevine fix rests on one closed issue. T6 decides
this. It can run as soon as the runtime exists, before either adapter is finished.

## On-device test plan

Run on the Redmi phone (and the HyperOS tablet), personal/LAB flavor, after the runtime prototype exists.

| # | Test | Pass |
|---|---|---|
| T1 | `apt-get install` each browser in a fresh trixie rootfs under PRoot | installs, `dpkg -s` OK, no held packages |
| T2 | launch with `--ozone-platform=x11` on `:1`, read `WM_CLASS` | one window at the display size |
| T3 | input: tap, scroll, text via XTest | lands where tapped |
| T4 | audio: YouTube in each | sound through car audio focus, pauses on focus loss |
| T5 | RAM (PSS of the tree), CPU %, cold start → first frame; idle page, news page, 720p and 1080p video | numbers recorded for both |
| T6 | Widevine: `chrome://components` / `brave://components`, a DRM test page | CDM loads, plays (or the failure is documented) |
| T7 | run both, SIGTERM Brave | Chrome keeps running, X and Pulse keep running, Brave relaunches with its profile intact |
| T8 | car moves (LAB mock `MOVING`) | frame pump stops, media pauses, browser not killed |
| T9 | low memory: open 10 tabs | Android does not kill AutoBridge, or the fallback to the embedded browser triggers cleanly |

## Sources

- Package indexes read directly: `dl.google.com/linux/chrome/deb/dists/stable/…/binary-arm64/Packages`,
  `brave-browser-apt-release.s3.brave.com/dists/stable/…/binary-arm64/Packages`
- [Installing Brave on Linux](https://brave.com/linux/)
- [Linuxiac: Google Chrome quietly arrives on ARM64 Linux](https://linuxiac.com/google-chrome-quietly-arrives-on-arm64-linux/)
- [OMG! Ubuntu: Chrome lands on Arm64 Linux (with Widevine DRM)](https://www.omgubuntu.co.uk/2026/07/chrome-arm64-linux-available)
- [AlternativeTo: Chrome for Arm64 Linux with native Widevine](https://alternativeto.net/news/2026/7/google-chrome-launches-for-arm64-linux-with-native-widevine-drm/)
- [Open Source For You: Google ends Widevine workarounds on Arm64 Linux](https://www.opensourceforu.com/2026/07/google-ends-widevine-workarounds-on-arm64-linux/)
- [brave/brave-browser#56129](https://github.com/brave/brave-browser/issues/56129)
