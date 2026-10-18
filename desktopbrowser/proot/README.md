# PRoot for the desktop browser runtime

`src/main/jniLibs/arm64-v8a/libproot.so` and `libproot-loader.so` are PRoot built from source by
`build-proot.sh`. They are shipped as "libraries" only so Android extracts them into
`nativeLibraryDir`, the one directory an app targeting API 29+ may `exec` from. They are
executables, started with `ProcessBuilder`, never loaded with `System.loadLibrary`.

| Component | Version | Licence | Source |
|---|---|---|---|
| PRoot | termux/proot @ `38a60cbed019a8f12e698f5fb8d869e83564ad2b` | GPL-2.0-or-later | https://github.com/termux/proot |
| talloc (static, inside libproot.so) | 2.4.3 | LGPL-3.0-or-later | https://download.samba.org/pub/talloc/ |

One local change: `#include <string.h>` added to `src/extension/ashmem_memfd/ashmem_memfd.c`
(upstream relies on an implicit declaration that NDK clang rejects).

Only the personal and lab flavors depend on this module, so the safe (Play) build ships neither
binary.
