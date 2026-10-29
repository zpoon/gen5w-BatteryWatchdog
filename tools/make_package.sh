#!/bin/bash
# Zip the NEMESIS package (package/) into dist/battery.watchdog.zip, then build a
# ready-to-flash USB bundle. Run tools/build_apk.sh first and copy the signed
# APK to package/app/batterywatchdog.apk.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/package/app/batterywatchdog.apk" ] || {
  echo "missing package/app/batterywatchdog.apk - run tools/build_apk.sh and copy app/batterywatchdog.apk there first"; exit 1; }

mkdir -p "$ROOT/dist"
rm -f "$ROOT/dist/battery.watchdog.zip"
( cd "$ROOT/package" && zip -r -X "$ROOT/dist/battery.watchdog.zip" . -x '.*' -x '*/.keep' >/dev/null )
echo "wrote dist/battery.watchdog.zip"

# USB bundle: NEMESIS.data (type=install) at root + the package zip under packages/
TMP="$ROOT/dist/usb-bundle"
rm -rf "$TMP"; mkdir -p "$TMP/packages"
cp "$ROOT/dist/battery.watchdog.zip" "$TMP/packages/battery.watchdog.zip"
cat > "$TMP/NEMESIS.data" <<'EOF'
[nemesis]
version=1.0
type=install
path=packages/battery.watchdog.zip
EOF
rm -f "$ROOT/dist/gen5w-BatteryWatchdog-USB.zip"
# zip the bundle's CONTENTS so NEMESIS.data lands at the USB root when extracted
( cd "$TMP" && zip -r -X "$ROOT/dist/gen5w-BatteryWatchdog-USB.zip" . >/dev/null ) && rm -rf "$TMP"
echo "wrote dist/gen5w-BatteryWatchdog-USB.zip (extract to a FAT32/exFAT USB root)"
