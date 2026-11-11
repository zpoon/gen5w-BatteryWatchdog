#!/bin/sh
# The battery report (app/src/.../Report.java), off the car: plain Java, no Android.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
javac -nowarn -d "$D" "$ROOT/app/src/com/nemesis/batterywatchdog/Report.java" "$ROOT/tests/report/ReportTest.java"
java -cp "$D" com.nemesis.batterywatchdog.ReportTest
