#!/usr/bin/env bash
# CI (fork): compile the JNI helper assets with the NDK, the commands of docs/agents/regen-assets.md on Linux.
#   tools/ci/build-native.sh <out dir>
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || { echo "CI only" >&2; exit 2; }
out="${1:?out dir}"
ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
[[ -x "$cc" ]] || { echo "no NDK clang at $cc" >&2; exit 1; }
jni="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/jni"
mkdir -p "$out"
"$cc" -shared -fPIC -O2 -Wall -o "$out/linkcompat.bin" "$jni/linkcompat.c" -ldl
"$cc" -shared -fPIC -O2 -Wall -o "$out/boosandbox.bin" "$jni/boosandbox.c" -ldl
"$cc" -O2 -Wall -o "$out/boo-sandbox-run.bin" "$jni/boo-sandbox-run.c"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$out"/*.bin | grep -c AArch64
ls -la "$out"
