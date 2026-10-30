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
rm -rf "$W"
