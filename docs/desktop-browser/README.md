# Desktop Browser Engine

Linux Google Chrome and Brave (arm64 desktop builds), run in a shared Debian runtime on the phone
and shown on the phone and on Android Auto, next to the built-in AutoBridge browser. Personal and
lab flavors only.

**Status:** implemented. Runtime, Brave install, phone rendering and input are verified on an
Android 15 arm64 emulator. Chrome is verified in an arm64 container. Android Auto/DHU is pending
on the phone. See TEST-RESULTS.md.

| Doc | Topic |
|---|---|
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | what was built, files, sources, sizes, rebuild/test commands |
| [ARCHITECTURE.md](ARCHITECTURE.md) | layers, data flow, choices and why, Android Auto, blockers |
| [TEST-RESULTS.md](TEST-RESULTS.md) | every test with its result and evidence; pending device steps |
| [11-multi-browser-architecture.md](11-multi-browser-architecture.md) | research: one runtime, browser adapters |
| [12-chrome-vs-brave-compatibility.md](12-chrome-vs-brave-compatibility.md) | research: availability, dependencies, flags, DRM, which first |
| [13-browser-package-management.md](13-browser-package-management.md) | research: sources, integrity, operations |
| [ROADMAP.md](ROADMAP.md) | the original phase plan (phases 1–4 are now built) |

The research brief's docs 01–10 were never written separately. The implementation brief replaced
that phase, and ARCHITECTURE.md covers their topics (current architecture, VelvetLauncher
findings, options, rendering, security/licensing, POC, test matrix, roadmap).

VelvetLauncher findings, from static inspection of v0.3.1 only (nothing copied): targetSdk 36,
PRoot + loader in `nativeLibraryDir`, and a GameNative/Winlator-derived stack (box64, FEX, Wine,
DXVK, `libasurface_renderer.so` from GameNative, GPL-3.0). Its runtime comes from
`downloads.gamenative.app`. AutoBridge uses none of it.
