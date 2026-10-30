#!/bin/sh
# Builds a fixture shaped like the car's AIDL Stub into a real DEX with dx,
# then checks TxnResolver reads the transaction codes by name (plain and ODEX).
# Needs: javac, java, and $BT pointing at Android build-tools with dx.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${BT:?set BT to an Android build-tools dir with dx}"
W=$(mktemp -d)
mkdir -p "$W/fx" "$W/rt"
javac --release 8 -d "$W/fx" $(find "$ROOT/tests/resolver/fixture" -name '*.java')
"$BT/dx" --dex --min-sdk-version=19 --output="$W/fixture.dex" "$W/fx"
javac -d "$W/rt" "$ROOT/app/src/com/nemesis/batterywatchdog/TxnResolver.java" "$ROOT/tests/resolver/ResolverTest.java"
java -cp "$W/rt" ResolverTest "$W/fixture.dex"

# tools/check_firmware.py on a fake odex-dump folder holding the fixture
D="$W/dump"; mkdir -p "$D/framework"
cp "$W/fixture.dex" "$D/framework/telematics.dex"
printf '[ro.build.version.incremental]: [eng.test.20990101]\n[ro.build.display.id]: [TEST dev-keys]\n' > "$D/getprop.txt"
printf '17\ttm_hscan_service: [com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService]\n' > "$D/service-list.txt"
python3 "$ROOT/tools/check_firmware.py" "$D" | tee "$W/check.txt"
grep -q '^eng.test.20990101  137  74  214  # ' "$W/check.txt" || { echo "FAIL: check_firmware.py line"; exit 1; }
echo "check_firmware ok"
rm -rf "$W"
