#!/bin/sh
# Applies sdk-patch/rexglue-android.patch to a ReXGlue SDK source tree.
# usage: tools/apply_sdk_patch.sh /path/to/rexglue-sdk
set -e
SDK="${1:?usage: apply_sdk_patch.sh <rexglue-sdk dir>}"
PATCH="$(cd "$(dirname "$0")/.." && pwd)/sdk-patch/rexglue-android.patch"
cd "$SDK"
if [ -f include/rex/ui/surface_android.h ]; then echo "already patched"; exit 0; fi
patch -p1 --dry-run < "$PATCH" >/dev/null || { echo "patch does not apply cleanly: SDK version differs from the one it was written against" >&2; exit 1; }
patch -p1 < "$PATCH"
echo "patched. Now: git submodule update --init --recursive"
