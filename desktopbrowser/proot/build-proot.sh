#!/usr/bin/env bash
# Rebuilds src/main/jniLibs/arm64-v8a/libproot.so and libproot-loader.so from source.
#
# PRoot is GPL-2.0 (termux/proot, https://github.com/termux/proot); talloc is LGPL-3.0
# (https://talloc.samba.org). Both are built here from their published sources so the binaries in
# this module can be reproduced and their source offered, as the licences require. They run as
# separate executables (ProcessBuilder), not linked into AutoBridge's own code.
#
# Usage: desktopbrowser/proot/build-proot.sh [ndk-dir]
set -euo pipefail

PROOT_COMMIT=38a60cbed019a8f12e698f5fb8d869e83564ad2b
PROOT_SHA256=20d829a820fee743ac488a36a5675ffc928e8a88a978784a75f497d4bc1de20d
TALLOC_VERSION=2.4.3
TALLOC_SHA256=dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
API=29

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../src/main/jniLibs/arm64-v8a"
NDK="${1:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # url sha256 out
  curl -fsSL "$1" -o "$3"
  echo "$2  $3" | shasum -a 256 -c -
}

fetch "https://github.com/termux/proot/archive/$PROOT_COMMIT.tar.gz" "$PROOT_SHA256" "$WORK/proot.tar.gz"
fetch "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" "$WORK/talloc.tar.gz"
mkdir -p "$WORK/proot" "$WORK/talloc" "$WORK/static/lib" "$WORK/static/include"
tar -xzf "$WORK/proot.tar.gz" -C "$WORK/proot" --strip-components 1
tar -xzf "$WORK/talloc.tar.gz" -C "$WORK/talloc" --strip-components 1

# talloc: waf cross build with canned answers (nothing can run on the build host), then only the
# talloc object goes into a static archive.
(
  cd "$WORK/talloc"
  cp "$HERE/talloc-cross-answers.txt" cross-answers.txt
  CC="$TC/aarch64-linux-android$API-clang" AR="$TC/llvm-ar" \
    ./configure build --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt >/dev/null
  obj="$(ls bin/default/talloc.c.*.o | tail -1)"
  "$TC/llvm-ar" rcs "$WORK/static/lib/libtalloc.a" "$obj"
  cp talloc.h "$WORK/static/include/"
)

# PRoot: one upstream file uses strcmp/memset without <string.h>; NDK clang rejects implicit
# declarations. The loader is unbundled so it can live in nativeLibraryDir, the only place an app
# targeting API 29+ may exec from.
(
  cd "$WORK/proot/src"
  sed -i.orig '1i\
#include <string.h>
' extension/ashmem_memfd/ashmem_memfd.c
  export CC="$TC/aarch64-linux-android$API-clang" LD="$TC/aarch64-linux-android$API-clang"
  export STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump"
  export CFLAGS="-I$WORK/static/include -O2" LDFLAGS="-L$WORK/static/lib" PROOT_UNBUNDLE_LOADER=.
  make -j8 proot >/dev/null
  "$STRIP" -o "$OUT/libproot.so" proot
  "$STRIP" -o "$OUT/libproot-loader.so" loader/loader
)
shasum -a 256 "$OUT/libproot.so" "$OUT/libproot-loader.so"
