#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -P "$(dirname "$0")/.." && pwd)
SRC_DIR="$ROOT_DIR/local-overrides/react-native-webrtc/android/src/main/java/com/oney/WebRTCModule"
DST_DIR="$ROOT_DIR/node_modules/react-native-webrtc/android/src/main/java/com/oney/WebRTCModule"

if [ ! -d "$DST_DIR" ]; then
  echo "[overrides] skip: destination not found: $DST_DIR"
  exit 0
fi

cp "$SRC_DIR/CameraCaptureController.java" "$DST_DIR/CameraCaptureController.java"
cp "$SRC_DIR/GetUserMediaImpl.java" "$DST_DIR/GetUserMediaImpl.java"
cp "$SRC_DIR/UsbVideoCapturer.java" "$DST_DIR/UsbVideoCapturer.java"

echo "[overrides] react-native-webrtc Java overrides applied"
