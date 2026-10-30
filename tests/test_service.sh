#!/bin/sh
# Desktop test of package/svc/watchdog.sh with mocked service/am/dumpsys.
# Usage: tests/test_service.sh [shell]   (default: sh; CI also runs it under mksh)
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
mkdir -p "$T/state" "$T/bin"
cat > "$T/bin/service" <<EOT
#!/bin/sh
[ "\$1" = check ] && { echo "Service \$2: found"; exit 0; }
case "\$3" in
137) echo "Result: Parcel(00000000 \$(cat $T/socval)   '....S...')";;
214) echo "Result: Parcel(00000000 00000064   'd...')";;
74) echo "Result: Parcel(00000000 428d0000   '.......B')";;
esac
EOT
printf '#!/bin/sh\necho "$6" >> %s/toasts\n' "$T" > "$T/bin/am"
printf '#!/bin/sh\ncat %s/disp\n' "$T" > "$T/bin/dumpsys"
printf '#!/bin/sh\necho "10-29 18:42:18.630 I/ECO( 548): {\\"speed\\":0,\\"outTemperature\\":12,\\"x\\":1}"\n' > "$T/bin/logcat"
chmod +x "$T/bin/"*
echo 00000053 > "$T/socval"; echo "mWakefulness=Awake" > "$T/disp"

"$SH" -n "$ROOT/package/svc/watchdog.sh"
( sleep 4; echo "mWakefulness=Asleep" > "$T/disp"; sleep 3; echo 00000040 > "$T/socval"; echo "mWakefulness=Awake" > "$T/disp" ) &
BWD_TEST_PATH="$T/bin" BWD_STATE="$T/state" BWD_TEST=1 BWD_TEST_LOOPS=12 "$SH" "$ROOT/package/svc/watchdog.sh"
wait

fail() { echo "FAIL: $*"; cat "$T/state/log.csv" 2>/dev/null; exit 1; }
grep -q '^state=ok' "$T/state/status" || fail "status not ok"
grep -q '^pct10=640' "$T/state/status" || fail "last reading should be 64.0%"
[ "$(grep -c ',start,' "$T/state/log.csv")" -eq 2 ] || fail "expected 2 sessions"
grep -q '00000053,830' "$T/state/log.csv" || fail "first reading should be 83.0%"
grep -q 'dropped 19.0 pts while parked: 83.0% -> 64.0%, outside 53F' "$T/toasts" || fail "expected parked-drop warning toast with temperature"
grep -q '^hv10=705$' "$T/state/status" || fail "main battery SOC should be 70.5%"
grep -q '^temp_c=12$' "$T/state/status" || fail "outside temp should be 12 C"
grep -q '^parked_min=0$' "$T/state/status" || fail "parked minutes should be recorded"
grep -q ',705,12$' "$T/state/log.csv" || fail "log rows should carry hv10 and temp"
rm -rf "$T"
echo "PASS ($SH)"
