#!/usr/bin/env bash
# Copies the pinned Matter library (8 jars, 2 .so files) and the two CHIPTool source
# files that are lifted unchanged, from a built connectedhomeip checkout into this project.
#
# Usage: tools/copy-matter-libs.sh /path/to/connectedhomeip
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 /path/to/connectedhomeip" >&2
  exit 2
fi
if [ ! -d "$1" ]; then
  echo "ERROR: '$1' is not a directory." >&2
  exit 2
fi

CHIP="$(cd "$1" && pwd)"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$CHIP/out/android-arm64-chip-tool"
CHIPTOOL_SRC="$CHIP/examples/android/CHIPTool/app/src/main/java/com/google/chip/chiptool"

LIBS_DIR="$PROJECT_ROOT/app/libs"
JNI_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
MATTER_SRC_DIR="$PROJECT_ROOT/app/src/main/java/app/matterplug/matter"

JARS=(
  "lib/src/controller/java/CHIPController.jar"
  "lib/src/controller/java/CHIPInteractionModel.jar"
  "lib/src/controller/java/CHIPClusters.jar"
  "lib/src/controller/java/CHIPClusterID.jar"
  "lib/src/controller/java/OnboardingPayload.jar"
  "lib/src/controller/java/libMatterTlv.jar"
  "lib/src/controller/java/libMatterJson.jar"
  "lib/src/platform/android/AndroidPlatform.jar"
)
SOS=(
  "lib/jni/arm64-v8a/libCHIPController.so"
  "lib/jni/arm64-v8a/libc++_shared.so"
)
BLE_SRC="$CHIPTOOL_SRC/bluetooth/BluetoothManager.kt"
LISTENER_SRC="$CHIPTOOL_SRC/GenericChipDeviceListener.kt"

# Check everything exists before copying anything.
missing=0
for f in "${JARS[@]}" "${SOS[@]}"; do
  if [ ! -f "$OUT/$f" ]; then
    echo "ERROR: missing build output: $OUT/$f" >&2
    missing=1
  fi
done
for f in "$BLE_SRC" "$LISTENER_SRC"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: missing CHIPTool source: $f" >&2
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "Build CHIPTool first (BUILD-CHIPTOOL.md, milestone M0) and check the path you passed." >&2
  exit 1
fi

mkdir -p "$LIBS_DIR" "$JNI_DIR" "$MATTER_SRC_DIR"

for f in "${JARS[@]}"; do
  cp -f "$OUT/$f" "$LIBS_DIR/"
done
for f in "${SOS[@]}"; do
  cp -f "$OUT/$f" "$JNI_DIR/"
done

git -C "$CHIP" describe --tags > "$LIBS_DIR/MATTER_VERSION.txt"

# Lifted CHIPTool files: package line rewritten, licence header kept.
# BluetoothManager.kt -> BleTransport.kt: only the class declaration is renamed, so that
# references to android.bluetooth.BluetoothManager inside the file are left alone.
# VERIFY: the class declaration in BluetoothManager.kt reads "class BluetoothManager"; the check below fails loudly if not.
sed -e 's/^package .*/package app.matterplug.matter/' \
    -e 's/^\(\(public\|internal\|open\) \)\{0,1\}class BluetoothManager\b/\1class BleTransport/' \
    -e '/^import com\.google\.chip\.chiptool\.ChipClient$/d' \
    -e 's/\bChipClient\b/ChipController/g' \
    "$BLE_SRC" > "$MATTER_SRC_DIR/BleTransport.kt"

sed -e 's/^package .*/package app.matterplug.matter/' \
    "$LISTENER_SRC" > "$MATTER_SRC_DIR/GenericChipDeviceListener.kt"

grep -q '^package app.matterplug.matter$' "$MATTER_SRC_DIR/BleTransport.kt" \
  || { echo "ERROR: package rewrite failed in BleTransport.kt" >&2; exit 1; }
# CHIPTool's ChipClient is replaced by this app's ChipController (same package, no import needed).
if grep -q 'com\.google\.chip\.chiptool' "$MATTER_SRC_DIR/BleTransport.kt"; then
  echo "ERROR: BleTransport.kt still refers to CHIPTool classes:" >&2
  grep -n 'com\.google\.chip\.chiptool' "$MATTER_SRC_DIR/BleTransport.kt" >&2
  exit 1
fi
grep -q 'class BleTransport' "$MATTER_SRC_DIR/BleTransport.kt" \
  || { echo "ERROR: could not rename the class to BleTransport; edit BleTransport.kt by hand" >&2; exit 1; }
grep -q '^package app.matterplug.matter$' "$MATTER_SRC_DIR/GenericChipDeviceListener.kt" \
  || { echo "ERROR: package rewrite failed in GenericChipDeviceListener.kt" >&2; exit 1; }

echo "Copied Matter library $(cat "$LIBS_DIR/MATTER_VERSION.txt") into $PROJECT_ROOT"
