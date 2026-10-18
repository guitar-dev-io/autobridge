# 13 — Browser package management

Status: **plan only**. Covers how the shared runtime and each browser get installed, verified,
updated and removed, and what the Browser Manager screen shows.

## Principles

- **Download on request only.** The APK carries PRoot and nothing else from this feature. The rootfs
  is downloaded the first time the user installs *any* desktop browser. Each browser is downloaded
  only when the user picks it.
- **Runtime once.** Installing the second browser reuses the rootfs, X, Pulse and every shared library.
  It costs one `apt-get install`, not a second runtime.
- **Vendor sources only.** Chrome comes from Google's repo and Brave from Brave's, both on the
  device, over HTTPS, signed. Nothing is rehosted, mirrored or copied from another app
  (VelvetLauncher included).
- **One apt at a time.** All package operations go through one queue holding the dpkg lock. Two
  installs never race.
- **Never break the embedded browser.** Every failure path ends with the embedded browser still
  working.

## Sources and distribution rights

| Component | Source | Redistribution | What we do |
|---|---|---|---|
| PRoot | build from source (GPL-2.0), shipped as `libproot.so`/`libproot-loader.so` in `nativeLibraryDir` | allowed, GPL-2.0. Ship its licence and offer its source. It runs as a separate executable, not linked into MIT code | ship in personal/lab APKs |
| Debian 13 arm64 rootfs | official Debian image (debuerreotype/docker `debian:trixie` arm64), or a tarball we build from it and publish with SHA-256 | allowed (DFSG). Ship the Debian copyright notice | download on request, check SHA-256 |
| Brave | `https://brave-browser-apt-release.s3.brave.com/`, `stable main`, keyring `brave-browser-archive-keyring.gpg` | **not** rehosted | apt on the device |
| Chrome | `https://dl.google.com/linux/chrome/deb/`, `stable main`, Google Linux signing key | **not** redistributable (Chrome ToS) | show the Chrome ToS link and get acceptance, then apt on the device |

The user's acceptance of Chrome's terms is stored with a timestamp in `BrowserRegistry`, and installing
is blocked until it exists. Brave gets a short "you are installing Brave from brave.com" note.

## Integrity chain

```
pinned key fingerprint (in AutoBridge source)
   └─▶ repo key file downloaded over HTTPS, fingerprint must match, else abort
         └─▶ apt verifies InRelease signature
               └─▶ Packages SHA-256
                     └─▶ .deb SHA-256 (apt checks before dpkg unpacks)
```

- Key fingerprints are copied from the vendors' own pages when this is implemented, written into
  the adapter, and covered by a unit test so a change is a reviewed code change.
- The rootfs tarball is checked against a SHA-256 constant in the source **before** extraction. It
  is extracted into an empty staging directory and renamed into place only after it succeeds.
- No `--allow-unauthenticated`, no `trusted=yes`, ever.

## State machine (per browser, plus one for the runtime)

```
NOT_INSTALLED ──install──▶ DOWNLOADING ─▶ INSTALLING ─▶ INSTALLED ◀──┐
      ▲                         │ fail          │ fail        │ update │
      │                         ▼               ▼             ▼        │
      └──────remove──────── BROKEN ◀──────── BROKEN       UPDATING ────┘
```

The runtime has the same states. A browser install first makes sure the runtime is `INSTALLED`
(installing it if needed), then runs its own steps. `BROKEN` always offers **Repair** (re-run
`apt-get install --reinstall`, `dpkg --configure -a`) and **Remove**.

On every app start, `BrowserRegistry` is reconciled with `dpkg-query -W` inside the rootfs. dpkg is
the source of truth, and the Android-side state is a cache of it.

## Operations

| Feature | What it runs | Notes |
|---|---|---|
| **Install runtime** (implicit) | download + verify rootfs → extract → `apt-get update` → install shared deps (`xvfb` or X client libs, `pulseaudio`, `libasound2-plugins`, `fonts-*`, `x11-utils`) | ~0.4–0.5 GB. Needs Wi-Fi by default, with a warning on mobile data |
| **Install Brave** | add Brave source + keyring → `apt-get update` → `apt-get install brave-browser` → write managed policy | ~143 MB download / 465 MiB installed |
| **Install Chrome** | ToS acceptance → add Google source + key → `apt-get install google-chrome-stable` → write managed policy | ~136 MB / 434 MiB |
| **Select default browser** | sets `BrowserRegistry.default` = EMBEDDED / BRAVE / CHROME | only installed engines offered |
| **Switch browser** | stop the current session (SIGTERM → 5 s → SIGKILL its proot), start the other on the same display | the runtime stays up |
| **Update browser** | `apt-get update` (that browser's source only, `-o Dir::Etc::sourcelist=…`) → `apt-get install --only-upgrade <pkg>` | the browser must be stopped. If it is running, the update is queued for its next stop. Shared libs are upgraded only by **Update runtime** |
| **Update runtime** | `apt-get upgrade` for Debian packages | separate button, so a browser update never pulls a Debian point release by surprise |
| **Remove browser** | stop → `apt-get purge <pkg>` → `apt-get autoremove` → remove its source + key → optionally delete its profile/cache | apt keeps libraries the other browser still needs. Asks before deleting the profile |
| **Remove everything** | stop all → delete the rootfs directory | only offered when no browser is left, or as an explicit "remove desktop browsers" |
| **Clear cache** | stop that browser → delete `~/.autobridge/<id>/cache` | the profile (logins, bookmarks) is untouched because the cache dir is separate |
| **Reset profile** | stop → move `~/.autobridge/<id>/profile` to `profile.bak-<date>` → relaunch fresh | one backup kept, so a reset can be undone once |
| **Show versions** | `dpkg-query -W -f '${Version}'` per package, plus Debian version and glibc (`ldd --version`) | |
| **Show storage** | `du` of `/opt/<vendor dir>`, profile, cache per browser, plus the shared runtime total | computed off the main thread, cached for 1 h |
| **Diagnostics** | see below | |

**Rollback:** apt cannot downgrade by itself. Before an update, keep the previous .deb in
`/var/cache/apt/archives`, so **Roll back** = `dpkg -i <previous>.deb`. Brave's repo also keeps
old versions, while Chrome's keeps only the latest, so the cached .deb is the only rollback for Chrome.

## Runtime diagnostics screen

- Runtime: state, rootfs size, Debian version, glibc version, PRoot version, last `apt` log tail.
- X: running or not, display number, size/DPI, the window currently on the car surface (`WM_CLASS`).
- Audio: Pulse running, sink latency, AutoBridge audio focus state.
- Per browser: state, version, executable present, last start time, last exit code and signal,
  crash count in the last hour, last 50 lines of its stderr, PSS of its process tree, GPU mode, Widevine present.
- Buttons: copy diagnostics, Repair, Restart runtime.

## Fallbacks

| Situation | Behaviour |
|---|---|
| Device is not arm64-v8a, or is the safe flavor | the Desktop Browser section is hidden |
| Free storage < needed + 500 MB | install is blocked, with the size shown |
| Download or signature failure | state goes back to NOT_INSTALLED, staging is deleted, the error is shown |
| Browser crashes 3× in 60 s | the car falls back to the embedded browser with the same URL, and the browser is marked "needs attention" |
| Runtime will not start | all desktop engines unavailable, the embedded browser is the default until Repair succeeds |
| Low memory kill of the browser tree | relaunch once. If it is killed again, fall back to embedded |

## Storage layout (Android side)

```
filesDir/desktop-browser/
  rootfs/                      Debian tree (home, /opt/google, /opt/brave.com inside)
  staging/                     downloads in progress, deleted on failure
  logs/<browser>/              stderr and crash logs, rotated
  registry (DataStore)         install state, default, ToS acceptance, last versions
```

Kept in internal storage only, never on shared storage. It is excluded from AutoBridge's existing
backup (`dev.autobridge.backup`) because it is large and can be downloaded again. Only the
registry and the user's choice of default are backed up.
