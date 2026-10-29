#!/bin/bash
# Zip the NEMESIS package (package/) into dist/lvbatt.monitor.zip, then build a
# ready-to-flash USB bundle. Run tools/build_apk.sh first and copy the signed
# APK to package/app/lvbatt.apk.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/package/app/lvbatt.apk" ] || {
  echo "missing package/app/lvbatt.apk - run tools/build_apk.sh and copy app/lvbatt.apk there first"; exit 1; }

mkdir -p "$ROOT/dist"
rm -f "$ROOT/dist/lvbatt.monitor.zip"
( cd "$ROOT/package" && zip -r -X "$ROOT/dist/lvbatt.monitor.zip" . -x '.*' >/dev/null )
echo "wrote dist/lvbatt.monitor.zip"

# USB bundle: NEMESIS.data (type=install) at root + the package zip under packages/
TMP="$ROOT/dist/usb-bundle"
rm -rf "$TMP"; mkdir -p "$TMP/packages"
cp "$ROOT/dist/lvbatt.monitor.zip" "$TMP/packages/lvbatt.monitor.zip"
cat > "$TMP/NEMESIS.data" <<'EOF'
[nemesis]
version=1.0
type=install
path=packages/lvbatt.monitor.zip
EOF
rm -f "$ROOT/dist/lvbatt-monitor-USB.zip"
( cd "$ROOT/dist" && zip -r -X lvbatt-monitor-USB.zip usb-bundle >/dev/null && rm -rf usb-bundle )
echo "wrote dist/lvbatt-monitor-USB.zip (extract to a FAT32/exFAT USB root)"
