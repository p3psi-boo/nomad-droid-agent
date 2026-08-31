#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'usage: %s <output-so>\n' "$0" >&2
  exit 2
fi

OUTPUT="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/nomadcore"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK_DIR" ]]; then
  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  NDK_DIR="$SDK_ROOT/ndk/28.2.13676358"
fi

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android31-clang"
AR="$TOOLCHAIN/llvm-ar"

if [[ ! -x "$CC" || ! -x "$AR" ]]; then
  printf 'Android NDK compiler not found: %s\n' "$CC" >&2
  exit 1
fi

BUILD_DIR="$SCRIPT_DIR/build"
SHIM_DIR="$BUILD_DIR/android-link-shims"
mkdir -p "$BUILD_DIR" "$SHIM_DIR" "$(dirname "$OUTPUT")"

# libcap/psx asks the external linker for libpthread. Android exposes pthread
# symbols from libc and intentionally ships no separate libpthread archive.
# An empty archive satisfies that Linux-oriented linker flag without replacing
# or intercepting any symbol; pthread calls continue to resolve from libc.
"$AR" crs "$SHIM_DIR/libpthread.a"

(
  cd "$MODULE_DIR"
  CC="$CC" \
  GOOS=android \
  GOARCH=arm64 \
  CGO_ENABLED=1 \
  CGO_LDFLAGS="-L$SHIM_DIR" \
    go build \
      -trimpath \
      -buildmode=c-shared \
      -ldflags='-s -w' \
      -o "$BUILD_DIR/libnomad_android.so" \
      .
)

install -m 0644 "$BUILD_DIR/libnomad_android.so" "$OUTPUT"
