#!/bin/sh
# package/scripts/preun.sh: the hand-over copy of the history for importing.
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$SH" -n "$ROOT/package/scripts/preun.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*"; exit 1; }
run() { BWD_STATE="$T/state" BWD_HANDOVER="$T/h" "$SH" "$ROOT/package/scripts/preun.sh" > "$T/out" 2>&1; }

# U1: no history: nothing copied
mkdir -p "$T/state"; echo 60 > "$T/state/warn_pct"; run
[ ! -d "$T/h" ] || fail "U1: copied with no history"
echo "  U1 no history: nothing copied"

# U2: history copied, settings and numbers not
printf '1793595600,start,00000053,830,100,705,12,,,,,\n' > "$T/state/log.csv"
printf '1793595600 60 830 850\n' > "$T/state/sessions"
echo "fw=x" > "$T/state/txn"
run
cmp -s "$T/state/log.csv" "$T/h/log.csv" || fail "U2: log.csv"
cmp -s "$T/state/sessions" "$T/h/sessions" || fail "U2: sessions"
[ ! -e "$T/h/txn" ] && [ ! -e "$T/h/warn_pct" ] || fail "U2: copied more than history"
[ -s "$T/h/from" ] || fail "U2: no from file"
[ "$(stat -c %a "$T/h")" = 755 ] && [ "$(stat -c %a "$T/h/log.csv")" = 644 ] || fail "U2: not readable to the app"
echo "  U2 history copied, readable, nothing else"

# U3: a file gone since the last copy is removed from the copy too
rm "$T/state/sessions"; run
[ ! -e "$T/h/sessions" ] || fail "U3: stale sessions left"
echo "  U3 stale file removed"
echo "PASS ($SH)"
