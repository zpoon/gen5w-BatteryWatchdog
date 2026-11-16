#!/bin/sh
# tools/version.sh: versionCodes only ever go up, and a release outranks its own test builds and betas.
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
mkdir -p "$D/tools"; cp "$ROOT/tools/version.sh" "$D/tools/"
code() {   # VERSION suffix prerelease build
    echo "$1" > "$D/VERSION"
    BWD_VERSION_SUFFIX=$2 BWD_PRERELEASE=$3 BWD_VERSION_CODE=$4 "$SH" "$D/tools/version.sh" code
}
fail() { echo "FAIL: $*"; exit 1; }
rel070=$(code 0.7.0 "" "" 89)
dev080=$(code 0.8.0 -dev.120 "" 120)
beta080=$(code 0.8.0 "" 1 130)
rel080=$(code 0.8.0 "" "" 131)
dev081=$(code 0.8.1 -dev.5 "" 1005)
rel100=$(code 1.0.0 "" "" 2000)
[ "$rel070" = 700999 ] || fail "0.7.0 release: $rel070"
[ "$rel070" -gt 102 ] || fail "must outrank the old run-number codes"
[ "$dev080" -gt "$rel070" ] || fail "0.8.0 test build above 0.7.0"
[ "$beta080" -gt "$dev080" ] || fail "later build above earlier one"
[ "$rel080" -gt "$beta080" ] || fail "0.8.0 release above its beta"
[ "$dev081" -gt "$rel080" ] || fail "0.8.1 test build above 0.8.0 release"
[ "$rel100" -gt "$dev081" ] || fail "1.0.0 above 0.8.1"
[ "$rel100" -lt 2147483647 ] || fail "fits Android's int"
[ "$(code 0.8.0 -dev.999 "" 999)" -gt 800000 ] || fail "build 999 still a valid test code"
if code 0.100.0 "" "" 1 2>/dev/null; then fail "a part over 99 must be refused"; fi
echo "PASS ($SH): 0.7.0=$rel070 0.8.0-dev=$dev080 beta=$beta080 0.8.0=$rel080 0.8.1-dev=$dev081"
