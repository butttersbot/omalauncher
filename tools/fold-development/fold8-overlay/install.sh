#!/bin/bash
# fold8-overlay/install.sh: build the two Fold-match overlays and put them on the Mac's Fold8 emulator (never a phone).
#   cutout/  static: the cover's camera cutout made small (read once at boot)
#   bars/    mutable: 42 dp status bar (110 px, measured on Boss's SM-F971U1), set above pixel_9_pro_fold
# Needs the emulator started with -writable-system (tools/oma-emu start does that). Signed with a throwaway key made here.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)
JAR=$(ls "$ANDROID_HOME"/platforms/android-3*/android.jar | tail -1)
# The lane's own emulator (SOMA_EMU_LANE=B -> 5556, C -> 5558), as tools/oma-emu picks it; lane A's is 5554.
case "${SOMA_EMU_LANE:-A}" in B) SERIAL=emulator-5556 ;; C) SERIAL=emulator-5558 ;; *) SERIAL=emulator-5554 ;; esac
# Or the one named (the pool's base image, tools/walkthrough/emu_pool.py).
SERIAL=${SOMA_EMU_SERIAL:-$SERIAL}
A() { adb -s "$SERIAL" "$@"; }
[ "$(A shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo "$SERIAL is not an emulator; nothing done" >&2; exit 1; }
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
keytool -genkeypair -keystore "$out/k.jks" -storepass overlay -keypass overlay -alias o -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=Soma Fold8 emulator overlay" >/dev/null 2>&1
for x in cutout bars; do
  "$BT/aapt2" compile --dir "$x/res" -o "$out/$x.zip"
  "$BT/aapt2" link -o "$out/$x-u.apk" --manifest "$x/AndroidManifest.xml" -I "$JAR" "$out/$x.zip" --min-sdk-version 29 --target-sdk-version 37
  "$BT/zipalign" -f 4 "$out/$x-u.apk" "$out/$x-a.apk"
  "$BT/apksigner" sign --ks "$out/k.jks" --ks-pass pass:overlay --out "$out/SomaFold8-$x.apk" "$out/$x-a.apk" 2>/dev/null
done
A root >/dev/null; sleep 2; A wait-for-device; A remount >/dev/null
for x in cutout bars; do A push "$out/SomaFold8-$x.apk" "/product/overlay/SomaFold8-$x.apk" >/dev/null; A shell chmod 644 "/product/overlay/SomaFold8-$x.apk"; done
echo "Installed; reboot the emulator (adb -s $SERIAL reboot), then: tools/oma-emu fold-bars"
