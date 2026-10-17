# Desktop Browser (research)

Running a real desktop Chromium browser (Brave, Google Chrome) inside a Linux runtime on the
phone and showing it on the Android Auto screen, next to the existing embedded WebView browser.

**Status: research and plan only.** No production code, no runtime downloaded.

| Doc | Topic |
|---|---|
| 01–10 | Earlier single-browser research. **Not in this repository.** Add them here so 11–13 and the roadmap can be checked against them |
| [11-multi-browser-architecture.md](11-multi-browser-architecture.md) | shared runtime, browser adapters, process model, rendering bridge, policy |
| [12-chrome-vs-brave-compatibility.md](12-chrome-vs-brave-compatibility.md) | availability, dependencies, flags, media/DRM, Shields, which browser first, device test plan |
| [13-browser-package-management.md](13-browser-package-management.md) | sources and rights, integrity, install/update/remove, Browser Manager features, diagnostics |
| [ROADMAP.md](ROADMAP.md) | phases |

## Recommendation in one paragraph

Chrome and Brave share **one** runtime (Debian 13 arm64 rootfs, PRoot, one X server, one audio,
input and rendering bridge), and each is a small adapter with its own package, profile, cache,
flags and update path. Both have **official arm64 Linux packages** (verified in the vendors' APT
indexes on 2026-10-18: Chrome 155.0.8059.84, Brave 1.97.59). Build **Brave first**: it has years of
arm64 Linux releases, native ad blocking, and a simpler install flow. Chrome follows as a second
adapter, unless DRM streaming turns out to be the main use case.
