#!/bin/sh
# History import (app/src/.../Importer.java), off the car: plain Java, no Android.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
javac -nowarn -d "$D" "$ROOT/app/src/com/nemesis/batterywatchdog/Importer.java" "$ROOT/tests/importer/ImporterTest.java"
java -cp "$D" com.nemesis.batterywatchdog.ImporterTest
