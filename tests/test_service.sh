#!/bin/sh
# Desktop test of package/svc/watchdog.sh with mocked service/am/dumpsys/logcat/getprop.
# Usage: tests/test_service.sh [shell]   (default: sh; CI also runs it under mksh R43)
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KNOWN_FW=eng.vmadmin.20260507.094910
IFACE=com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService

"$SH" -n "$ROOT/package/svc/watchdog.sh"

# setup <dir>: mock bin + defaults (known firmware, real interface, 83 %, display on)
setup() {
    T=$1
    mkdir -p "$T/state" "$T/bin"
    cat > "$T/bin/service" <<EOT
#!/bin/sh
case "\$1" in
check) echo "Service \$2: found"; exit 0;;
list)  echo "17	tm_hscan_service: [\$(cat $T/iface)]"; exit 0;;
esac
echo "\$3" >> $T/calls
case "\$3" in
\$(cat $T/txn_soc)) echo "Result: Parcel(00000000 \$(cat $T/socval)   '....S...')";;
214) echo "Result: Parcel(00000000 00000064   'd...')";;
74)  echo "Result: Parcel(00000000 428d0000   '.......B')";;
*)   echo "Result: Parcel(00000000 00000000   '........')";;
esac
EOT
    printf '#!/bin/sh\necho "$6" >> %s/toasts\n' "$T" > "$T/bin/am"
    printf '#!/bin/sh\ncase "$1" in power) cat %s/disp;; com.hkmc.micom.datc) cat %s/datc 2>/dev/null;; esac\n' "$T" "$T" > "$T/bin/dumpsys"
    printf '#!/bin/sh\ncat %s/fw\n' "$T" > "$T/bin/getprop"
    printf '#!/bin/sh\ncat %s/logcat 2>/dev/null\n' "$T" > "$T/bin/logcat"
    printf '10-29 18:42:18.630 I/ECO( 548): {"speed":0,"outTemperature":12,"x":1}\n' > "$T/logcat"
    chmod +x "$T/bin/"*
    echo 00000053 > "$T/socval"; echo 137 > "$T/txn_soc"
    echo "mWakefulness=Awake" > "$T/disp"
    echo "$KNOWN_FW" > "$T/fw"; echo "$IFACE" > "$T/iface"
    : > "$T/calls"
}
run() {   # run <dir> <loops>
    BWD_TEST_PATH="$1/bin" BWD_STATE="$1/state" BWD_TEST=1 BWD_TEST_LOOPS=$2 "$SH" "$ROOT/package/svc/watchdog.sh" > "$1/out" 2>&1
}
fail() { echo "FAIL: $*"; [ -n "$T" ] && { cat "$T/out" 2>/dev/null; cat "$T/state/status" 2>/dev/null; }; exit 1; }

# A: known firmware, two drives with the display off in between
T=$(mktemp -d); setup "$T"
( sleep 4; echo "mWakefulness=Asleep" > "$T/disp"; sleep 3; echo 00000040 > "$T/socval"; echo "mWakefulness=Awake" > "$T/disp" ) &
run "$T" 12; wait
grep -q '^state=ok' "$T/state/status" || fail "A: status not ok"
grep -q '^verify=known_firmware$' "$T/state/status" || fail "A: should use the known-firmware numbers"
grep -q '^pct10=640' "$T/state/status" || fail "A: last reading should be 64.0%"
[ "$(grep -c ',start,' "$T/state/log.csv")" -eq 2 ] || fail "A: expected 2 sessions"
grep -q '00000053,830' "$T/state/log.csv" || fail "A: first reading should be 83.0%"
grep -q 'dropped 19.0 pts while parked: 83.0% -> 64.0%, outside 53F' "$T/toasts" || fail "A: expected parked-drop warning toast"
grep -q '^hv10=705$' "$T/state/status" || fail "A: main battery SOC should be 70.5%"
grep -q '^temp_c=12$' "$T/state/status" || fail "A: outside temp should be 12 C"
grep -q '^parked_min=0$' "$T/state/status" || fail "A: parked minutes should be recorded"
grep -q ',705,12$' "$T/state/log.csv" || fail "A: log rows should carry hv10 and temp"
rm -rf "$T"; echo "  A known firmware: ok"

# B: unknown firmware, nothing resolved -> no binder calls at all
T=$(mktemp -d); setup "$T"; echo eng.other.20990101.000000 > "$T/fw"
run "$T" 3
grep -q '^state=unverified' "$T/state/status" || fail "B: should be unverified"
grep -q '^verify=unverified_firmware$' "$T/state/status" || fail "B: reason should be unverified_firmware"
[ ! -s "$T/calls" ] || fail "B: made binder calls on unverified firmware: $(cat "$T/calls" | tr '\n' ' ')"
[ ! -s "$T/state/log.csv" ] || fail "B: logged readings on unverified firmware"
rm -rf "$T"; echo "  B unknown firmware: no calls"

# C: unknown firmware with numbers resolved by name for THIS firmware
T=$(mktemp -d); setup "$T"; echo eng.other.20990101.000000 > "$T/fw"; echo 140 > "$T/txn_soc"
printf 'fw=eng.other.20990101.000000\nsoc=140\nhv=74\nsoh=214\nsource=test\n' > "$T/state/txn"
run "$T" 3
grep -q '^state=ok' "$T/state/status" || fail "C: should run with resolved numbers"
grep -q '^verify=resolved_by_name$' "$T/state/status" || fail "C: reason should be resolved_by_name"
grep -q '^pct10=830' "$T/state/status" || fail "C: should read 83% via txn 140"
grep -q '^137$' "$T/calls" && fail "C: must not call the built-in 137"
rm -rf "$T"; echo "  C resolved numbers: ok"

# D: resolved numbers from a DIFFERENT firmware are ignored
T=$(mktemp -d); setup "$T"; echo eng.other.20990101.000000 > "$T/fw"
printf 'fw=eng.older.20250101.000000\nsoc=137\n' > "$T/state/txn"
run "$T" 3
grep -q '^state=unverified' "$T/state/status" || fail "D: stale txn file must not be used"
[ ! -s "$T/calls" ] || fail "D: made binder calls with a stale txn file"
rm -rf "$T"; echo "  D stale numbers: ignored"

# E: known firmware but the service isn't the expected interface
T=$(mktemp -d); setup "$T"; echo com.example.ISomethingElse > "$T/iface"
run "$T" 3
grep -q '^verify=interface_mismatch$' "$T/state/status" || fail "E: should detect interface mismatch"
[ ! -s "$T/calls" ] || fail "E: made binder calls on the wrong interface"
rm -rf "$T"; echo "  E wrong interface: no calls"

# F: a build listed in known_firmware.txt with its own numbers
T=$(mktemp -d); setup "$T"; echo eng.kia.20270101.000000 > "$T/fw"; echo 141 > "$T/txn_soc"
mkdir -p "$T/pkg"
printf '# test table\neng.vmadmin.20260507.094910  137  74  214  # reference\neng.kia.20270101.000000  141  -  214  # other build\n' > "$T/pkg/known_firmware.txt"
BWD_PKG="$T/pkg" BWD_TEST_PATH="$T/bin" BWD_STATE="$T/state" BWD_TEST=1 BWD_TEST_LOOPS=3 "$SH" "$ROOT/package/svc/watchdog.sh" > "$T/out" 2>&1
grep -q '^verify=known_firmware$' "$T/state/status" || fail "F: should use the table entry"
grep -q '^pct10=830' "$T/state/status" || fail "F: should read 83% via txn 141"
grep -q '^74$' "$T/calls" && fail "F: hv is '-' for this build, must not call 74"
grep -q '^137$' "$T/calls" && fail "F: must not call the reference build's 137"
rm -rf "$T"; echo "  F per-build numbers from known_firmware.txt: ok"

# G-J: outside temperature sources (context only)
temp_case() {   # temp_case <name> <datc file content or ''> <logcat content> <want temp_c> <want src>
    T=$(mktemp -d); setup "$T"
    if [ -n "$2" ]; then printf '%s\n' "$2" > "$T/datc"; fi
    printf '%s\n' "$3" > "$T/logcat"
    run "$T" 2
    grep -q "^temp_c=$4\$" "$T/state/status" || fail "$1: temp_c should be $4"
    grep -q "^temp_src=$5\$" "$T/state/status" || fail "$1: temp_src should be $5"
    rm -rf "$T"; echo "  $1: ok"
}
temp_case "G climate dump as on the car: C invalid, F raw 0x6e = 70F" "  OutTempC : 0xff
  OutTempF : 0x6e" "" 21 climate
temp_case "H climate dump, C raw 0x78 = 20C preferred over F" "OutTempC : 0x78
OutTempF : 0x6e" "" 20 climate
temp_case "H2 ECO log preferred over the climate dump" "OutTempF : 0x6e" '{"outTemperature":19}' 19 eco_log
temp_case "I UI log in Fahrenheit" "" "10-30 08:00:00.000 D/Climate( 99): setOutsideTempUIAdvanced::temp = 50 F" 10 ui_log
temp_case "J both raw values invalid, ECO log used" "OutTempC : 0xff
OutTempF : 0xfe" '{"outTemperature":-3}' -3 eco_log
temp_case "K decimal or unknown keys ignored" "OutTemp = 77 *glob*
OutTempF : 41
OutTempSnsr : 0x64" '{"outTemperature":5}' 5 eco_log
temp_case "L newest ECO line wins" "" '{"outTemperature":1}
{"outTemperature":7}' 7 eco_log

# N: temperature is kept when the log line ages out between samples
T=$(mktemp -d); setup "$T"
( sleep 3; : > "$T/logcat" ) &
run "$T" 8; wait
grep -q '^temp_c=12$' "$T/state/status" || fail "N: last temperature should be kept"
rm -rf "$T"; echo "  N temperature kept between log lines: ok"

# O: a short stop (screen off < min_off_s) stays the same drive
T=$(mktemp -d); setup "$T"
( sleep 4; echo "mWakefulness=Asleep" > "$T/disp"; sleep 3; echo "mWakefulness=Awake" > "$T/disp" ) &
BWD_TEST_MIN_OFF=600 run "$T" 12; wait
[ "$(grep -c ',start,' "$T/state/log.csv")" -eq 1 ] || fail "O: short stop should not start a new drive"
grep -q 'short stop' "$T/out" || fail "O: should log the short stop"
rm -rf "$T"; echo "  O short stop: same drive"

# M: nothing temporary left behind in the state dir
T=$(mktemp -d); setup "$T"; printf '  mOutTempC = 4\n' > "$T/datc"; run "$T" 3
ls -a "$T/state" | grep -E '\.tmp$|^\.datc|^\.temp' && fail "M: temp files left in the state dir"
rm -rf "$T"; echo "  M no temp files: ok"

echo "PASS ($SH)"
