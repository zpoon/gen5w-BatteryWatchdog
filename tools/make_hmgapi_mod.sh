#!/bin/sh
# Build Battery Watchdog as a signed HMG-API mod, and the NEMESIS package that
# installs it through HMG-API (for NEMESIS Manager, the Package Store or a USB stick).
#
#   tools/make_hmgapi_mod.sh <HMG-API checkout> <mod signing key>
#
# Needs the built APK (app/batterywatchdog.apk, tools/build_apk.sh), a JDK, zip,
# python3, and a checkout of VysseCodes/HMG-API for its packaging tools
# (tools/mkmodpkg.sh signs, tools/mknemesismod.sh wraps). Nothing from HMG-API is
# copied into the mod: Battery Watchdog stays MIT and talks to HMG-API only through
# the hmgapid command.
#
# The key is the mod's identity: HMG-API derives the mod's UUID from it and only
# installs updates signed with the same key. CI reads it from the
# HMGAPI_MOD_KEY secret. Keep a backup: a new key means owners must remove the
# mod before installing the next version.
#
# Writes to dist/:
#   battery-watchdog-<v>.zip                  the signed mod (HMG-API app, adb, HMGAPI/updates/ on a stick)
#   battery-watchdog-<v>-nemesis.zip          for a USB stick, NEMESIS's layout
#   battery-watchdog-<v>-nemesis-package.zip  the NEMESIS package itself (id hmgapi.battery-watchdog)
# Version: VERSION, or VERSION-beta.<BWD_VERSION_CODE> for test builds
# (BWD_VERSION_SUFFIX set); NEMESIS sorts a beta below its release.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=${1:?usage: make_hmgapi_mod.sh <HMG-API checkout> <key file>}
KEY=${2:?usage: make_hmgapi_mod.sh <HMG-API checkout> <key file>}
H=$(cd "$H" && pwd)
case $KEY in /*) ;; *) KEY=$PWD/$KEY ;; esac
[ -f "$H/tools/mkmodpkg.sh" ] || { echo "no HMG-API checkout at $H"; exit 1; }
[ -f "$KEY" ] || { echo "no key at $KEY"; exit 1; }
# the public half lives in the repo (hmgapi-mod/mod-key.pub): HMG-API's signer
# reads <key>.pub, and every package must come out with the mod's one UUID
[ -f "$KEY.pub" ] || cp "$ROOT/hmgapi-mod/mod-key.pub" "$KEY.pub"
cmp -s "$KEY.pub" "$ROOT/hmgapi-mod/mod-key.pub" || { echo "$KEY.pub isn't hmgapi-mod/mod-key.pub: wrong key?"; exit 1; }
APK=$ROOT/app/batterywatchdog.apk
[ -f "$APK" ] || { echo "no $APK: run tools/build_apk.sh first"; exit 1; }

BASE=$(tr -d ' \n\r' < "$ROOT/VERSION")
if [ -n "$BWD_VERSION_SUFFIX" ]; then V="$BASE-beta.${BWD_VERSION_CODE:-1}"; else V=$BASE; fi

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
M=$W/battery-watchdog
mkdir -p "$M/svc" "$M/app" "$M/icons"
cp "$ROOT/hmgapi-mod/icons/"*.png "$M/icons/"
cp "$APK" "$M/app/batterywatchdog.apk"
cp "$ROOT/LICENSE" "$M/LICENSE"
cp "$ROOT/hmgapi-mod/settings.json" "$M/settings.json"   # HMG-API's settings page for the mod
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$M/settings.json" || { echo "settings.json isn't valid JSON"; exit 1; }
sed "s/^VERSION=.*/VERSION=$V   # stamped by tools\/make_hmgapi_mod.sh/" "$ROOT/package/svc/watchdog.sh" > "$M/svc/watchdog.sh"
python3 - "$ROOT/hmgapi-mod/mod.json" "$M/mod.json" "$V" <<'PY'
import json, sys
src, dst, v = sys.argv[1:]
m = json.load(open(src))
m["version"] = v
for k in ("apps",):
    for a in m.get(k, []):
        assert a["apk"] == "app/batterywatchdog.apk", a
assert [s["script"] for s in m["services"]] == ["svc/watchdog.sh"], m["services"]
json.dump(m, open(dst, "w"), indent=2, ensure_ascii=False)
open(dst, "a").write("\n")
PY

# HMG-API's own checks: the scripts against the head unit's commands
( cd "$H" && sh tools/check-scripts.sh "$M" ) | grep -v '^ok' && { echo "script check failed"; exit 1; }

Z=$( cd "$H" && sh tools/mkmodpkg.sh "$M" "$KEY" | tail -n 1 )
[ -f "$Z" ] || { echo "mkmodpkg.sh made no package"; exit 1; }
mkdir -p "$ROOT/dist"
cp "$Z" "$ROOT/dist/"
( cd "$H" && sh tools/mknemesismod.sh "$Z" "$ROOT/dist" )
# the signed package must verify as this mod's UUID (a wrong private key fails here)
UUID=49624245-2eef-8b12-b385-823361d61309
V1=$(mktemp -d); unzip -q "$Z" -d "$V1"
( cd "$H" && java -cp out/engine-host hmgapi.engine.Signing verify "$V1" "$UUID" ) || { rm -rf "$V1"; echo "package doesn't verify as $UUID"; exit 1; }
rm -rf "$V1"
echo "mod uuid: $UUID"
ls -l "$ROOT/dist/"*battery-watchdog-"$V"*
