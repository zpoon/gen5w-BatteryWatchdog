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
# stamp the version into a staged copy of the package
VER=$("$ROOT/tools/version.sh" name)
STAGE="$ROOT/dist/package-stage"
rm -rf "$STAGE"; cp -R "$ROOT/package" "$STAGE"
sed -i "s/^VERSION=.*/VERSION=$VER/" "$STAGE/svc/watchdog.sh"
PKGVER=$("$ROOT/tools/version.sh" pkg)
sed -i "s/^package-version=.*/package-version=$PKGVER/" "$STAGE/NEMESIS.data"
# the limits NEMESIS 1.16 enforces (an install with these wrong is refused)
python3 - "$STAGE/NEMESIS.data" <<'PY'
import re, sys
kv = dict(re.match(r"([^=]+)=(.*)", l.strip()).groups() for l in open(sys.argv[1], encoding="utf-8")
          if "=" in l and not l.startswith(("[", "#", ";")))
errs = []
if not re.fullmatch(r"\d+(\.\d+)*", kv.get("version", "")): errs.append("version must be dotted numbers")
if not re.fullmatch(r"\d+(\.\d+)*", kv.get("package-version", "")): errs.append("package-version must be dotted numbers: " + kv.get("package-version", ""))
if len(kv.get("short-desc", "")) > 64: errs.append("short-desc over 64 characters")
if len(kv.get("detailed-desc", "")) > 256: errs.append("detailed-desc over 256 characters")
if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", kv.get("id", "")) or ".." in kv["id"]: errs.append("bad id")
if errs: sys.exit("NEMESIS.data: " + "; ".join(errs))
PY
( cd "$STAGE" && zip -r -X "$ROOT/dist/battery.watchdog.zip" . -x '.*' -x '*/.keep' >/dev/null )
rm -rf "$STAGE"
echo "wrote dist/battery.watchdog.zip (version $VER, package-version $PKGVER)"

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
