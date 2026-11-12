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
140) echo "Result: Parcel(00000000 00000002   '........')";;
141) echo "Result: Parcel(00000000 00000000   '........')";;
142) echo "Result: Parcel(00000000 00000001   '........')";;
225) echo "Result: Parcel(00000000 00000001   '........')";;
86)  echo "Result: Parcel(00000000 00000003   '........')";;
*)   echo "Result: Parcel(00000000 00000000   '........')";;
esac
EOT
    printf '#!/bin/sh\necho "$*" >> %s/am\ncase "$3" in *PANEL_REFRESH) exit 0;; esac\necho "$6" >> %s/toasts\n' "$T" "$T" > "$T/bin/am"
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
grep -q ',705,12,,,,,$' "$T/state/log.csv" || fail "A: log rows should carry hv10 and temp (extras empty on known firmware)"
grep -q PANEL_REFRESH "$T/am" 2>/dev/null && fail "A: no panel refresh outside HMG-API"
rm -rf "$T"; echo "  A known firmware: ok"

# A2: hand-verified firmware (no lookup by name) never calls the extra getters
T=$(mktemp -d); setup "$T"; run "$T" 3
grep -qE '^(140|141|142|225|86)$' "$T/calls" && fail "A2: known_firmware must not call extra getters"
rm -rf "$T"; echo "  A2 known firmware calls no extras: ok"

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

# Q: extra getters resolved by name are called and logged raw (columns 8-12)
T=$(mktemp -d); setup "$T"
printf 'fw=%s\nsoc=137\nhv=74\nsoh=214\nsns_state=140\nsns_invalid=141\nsns_error=142\naux_opt=225\nchg_status=86\nsource=/system/framework/x.odex\next=1\n' "$KNOWN_FW" > "$T/state/txn"
run "$T" 3
grep -q '^verify=resolved_by_name$' "$T/state/status" || fail "Q: should use the resolved numbers"
line=$(grep ',sample,' "$T/state/log.csv" | head -n 1)
case "$line" in *,2,0,1,1,3) ;; *) fail "Q: expected the extras at the end of the line, got: $line";; esac
for n in 140 141 142 225 86; do grep -q "^$n\$" "$T/calls" || fail "Q: txn $n not called"; done
rm -rf "$T"; echo "  Q extra getters logged: ok"

# R: an override never calls extra getters, even if the file lists them
T=$(mktemp -d); setup "$T"
printf 'fw=%s\nsoc=137\nhv=74\nsoh=214\nsns_state=140\nsns_error=142\nsource=manual-override\n' "$KNOWN_FW" > "$T/state/txn"
run "$T" 3
grep -qE '^(140|142)$' "$T/calls" && fail "R: an override must not call the extra getters"
line=$(grep ',sample,' "$T/state/log.csv" | head -n 1)
case "$line" in *,,,,,) ;; *) fail "R: extras should be empty, got: $line";; esac
rm -rf "$T"; echo "  R override calls no extras: ok"

# P: denser sampling at the start of a drive
T=$(mktemp -d); setup "$T"
BWD_TEST_INTERVAL=600 BWD_TEST_EARLY_S=1 run "$T" 8
n=$(grep -c ',sample,' "$T/state/log.csv")
[ "$n" -ge 3 ] || fail "P: expected several early samples, got $n"
rm -rf "$T"; echo "  P early sampling: $n samples"

# M: nothing temporary left behind in the state dir
T=$(mktemp -d); setup "$T"; printf '  mOutTempC = 4\n' > "$T/datc"; run "$T" 3
ls -a "$T/state" | grep -E '\.tmp$|^\.datc|^\.temp' && fail "M: temp files left in the state dir"
rm -rf "$T"; echo "  M no temp files: ok"

# ---- HMG-API mode (hmgapi-mod/): readings by name through a mocked hmgapid ----
# hm_setup <dir>: mock hmgapid answering from one file per reading ("-" = unknown)
hm_setup() {
    setup "$1"
    mkdir -p "$1/hm"
    cat > "$1/bin/hmgapid" <<EOT
#!/bin/sh
[ "\$1" = vehicle ] || exit 2
shift
for n; do v=\$(cat "$1/hm/\$n" 2>/dev/null); echo "\$n \${v:--}"; done
EOT
    chmod +x "$1/bin/hmgapid"
    echo 83 > "$1/hm/battery12v.soc"; echo 70.5 > "$1/hm/hv.soc"; echo 96 > "$1/hm/hv.health"
    echo 1 > "$1/hm/hv.ready"; echo 0 > "$1/hm/charging"; echo 12.5 > "$1/hm/outside_temp"
    : > "$1/logcat"
}
hm_run() {   # hm_run <dir> <loops>: data dir from HMG-API, no BWD_STATE
    HMGAPI_MOD=battery-watchdog HMGAPI_DATA="$1/state" HMGAPI_MOD_DIR="$1/mod" BWD_TEST_PATH="$1/bin" BWD_TEST=1 BWD_TEST_LOOPS=$2 "$SH" "$ROOT/package/svc/watchdog.sh" > "$1/out" 2>&1
}

# HA1: readings by name, no binder calls, same log format; car off/on = two drives
T=$(mktemp -d); hm_setup "$T"; echo eng.other.20990101.000000 > "$T/fw"
( sleep 4; echo 0 > "$T/hm/hv.ready"; sleep 3; echo 64 > "$T/hm/battery12v.soc"; echo 1 > "$T/hm/hv.ready" ) &
hm_run "$T" 12; wait
grep -q '^state=ok' "$T/state/status" || fail "HA1: status not ok"
grep -q '^verify=hmgapi$' "$T/state/status" || fail "HA1: reason should be hmgapi"
[ ! -s "$T/calls" ] || fail "HA1: made service calls under HMG-API: $(tr '\n' ' ' < "$T/calls")"
grep -q '^pct10=640' "$T/state/status" || fail "HA1: last reading should be 64.0%"
grep -q '^hv10=705$' "$T/state/status" || fail "HA1: main battery should be 70.5%"
grep -q '^temp_c=12$' "$T/state/status" || fail "HA1: outside temp should be 12 C"
[ "$(grep -c ',start,' "$T/state/log.csv")" -eq 2 ] || fail "HA1: expected 2 drives from hv.ready"
grep -q ',start,83,830,96,705,12,,,,,0$' "$T/state/log.csv" || fail "HA1: first row should be 83,830,96,705,12 and charging 0"
grep -q 'PANEL_REFRESH -n com.nemesis.batterywatchdog/.PanelWidget' "$T/am" || fail "HA1: should refresh the panel page"
rm -rf "$T"; echo "  HA1 HMG-API readings: ok"

# HA2: no 12V reading from HMG-API (car not mapped) -> unverified, nothing logged
T=$(mktemp -d); hm_setup "$T"; rm "$T/hm/battery12v.soc"
hm_run "$T" 3
grep -q '^state=unverified' "$T/state/status" || fail "HA2: should be unverified"
grep -q '^verify=hmgapi_no_reading$' "$T/state/status" || fail "HA2: reason should be hmgapi_no_reading"
[ ! -s "$T/state/log.csv" ] || fail "HA2: logged readings without a 12V value"
rm -rf "$T"; echo "  HA2 no reading: unverified"

# HA3: decimals, negative temperature, unknown extras
T=$(mktemp -d); hm_setup "$T"; echo 77.25 > "$T/hm/battery12v.soc"; echo -3.5 > "$T/hm/outside_temp"
rm "$T/hm/hv.soc" "$T/hm/hv.health"
hm_run "$T" 3
grep -q '^pct10=772$' "$T/state/status" || fail "HA3: 77.25 should be 772"
grep -q '^temp_c=-3$' "$T/state/status" || fail "HA3: -3.5 C should be -3"
grep -q ',start,77.25,772,,,-3,,,,,0$' "$T/state/log.csv" || fail "HA3: unknown readings should be empty columns"
rm -rf "$T"; echo "  HA3 decimals and unknowns: ok"

# HA4: hv.ready unknown -> falls back to the screen state
T=$(mktemp -d); hm_setup "$T"; rm "$T/hm/hv.ready"
( sleep 4; echo "mWakefulness=Asleep" > "$T/disp"; sleep 3; echo "mWakefulness=Awake" > "$T/disp" ) &
hm_run "$T" 12; wait
[ "$(grep -c ',start,' "$T/state/log.csv")" -eq 2 ] || fail "HA4: screen fallback should give 2 drives"
rm -rf "$T"; echo "  HA4 screen fallback: ok"

# HA5: the sensor flags and saver option, once mod.json asks for them (not before)
T=$(mktemp -d); hm_setup "$T"
echo 1 > "$T/hm/battery12v.sensor_state"; echo 0 > "$T/hm/battery12v.sensor_invalid"
echo 0 > "$T/hm/battery12v.sensor_error"; echo 2 > "$T/hm/battery12v.saver"
mkdir -p "$T/mod"; echo '{"vehicle": {"read": ["battery12v.soc"]}}' > "$T/mod/mod.json"
hm_run "$T" 3
grep -q ',start,83,830,96,705,12,,,,,0$' "$T/state/log.csv" || fail "HA5: unlisted readings must not be asked for"
rm -f "$T/state/log.csv"
echo '{"vehicle": {"read": ["battery12v.soc", "battery12v.sensor_state", "battery12v.sensor_invalid", "battery12v.sensor_error", "battery12v.saver"]}}' > "$T/mod/mod.json"
hm_run "$T" 3
grep -q ',start,83,830,96,705,12,1,0,0,2,0$' "$T/state/log.csv" || fail "HA5: flags and saver should fill columns 8-11"
rm -rf "$T"; echo "  HA5 sensor flags from HMG-API: ok"

# G1: the HMG-API mod is installed and on -> the native copy stays idle (no
# calls, no log rows); turned off -> it monitors again
T=$(mktemp -d); setup "$T"; mkdir -p "$T/mods/battery-watchdog"; echo '{}' > "$T/mods/battery-watchdog/mod.json"
export BWD_HMGAPI_MODS="$T/mods"; run "$T" 3
grep -q '^state=other_install' "$T/state/status" || fail "G1: should be idle while the mod is on"
grep -q '^[0-9]*,start,' "$T/state/log.csv" 2>/dev/null && fail "G1: logged a drive while the mod is on"
grep -q 'stays idle' "$T/out" || fail "G1: should say why it's idle"
: > "$T/mods/battery-watchdog/.disabled"
run "$T" 4; unset BWD_HMGAPI_MODS
grep -q '^state=ok' "$T/state/status" || fail "G1: should monitor while the mod is turned off"
rm -rf "$T"; echo "  G1 idle while the HMG-API mod is on: ok"

# G2: the package's install check refuses only while the mod is installed and on
T=$(mktemp -d); mkdir -p "$T/mods/battery-watchdog"
BWD_HMGAPI_MODS="$T/mods" "$SH" "$ROOT/package/scripts/pre.sh" > "$T/o" 2>&1 || fail "G2: no mod should install"
echo '{}' > "$T/mods/battery-watchdog/mod.json"
BWD_HMGAPI_MODS="$T/mods" "$SH" "$ROOT/package/scripts/pre.sh" > "$T/o" 2>&1 && fail "G2: should refuse while the mod is on"
grep -q 'already installed as an HMG-API mod' "$T/o" || fail "G2: should say why"
: > "$T/mods/battery-watchdog/.disabled"
BWD_HMGAPI_MODS="$T/mods" "$SH" "$ROOT/package/scripts/pre.sh" > "$T/o" 2>&1 || fail "G2: a turned-off mod shouldn't block"
rm -rf "$T"; echo "  G2 install check: ok"

# G3: the mod itself never idles for its own folder
T=$(mktemp -d); hm_setup "$T"; mkdir -p "$T/mods/battery-watchdog"; echo '{}' > "$T/mods/battery-watchdog/mod.json"
export BWD_HMGAPI_MODS="$T/mods"; hm_run "$T" 3; unset BWD_HMGAPI_MODS
grep -q '^state=ok' "$T/state/status" || fail "G3: the mod should monitor"
rm -rf "$T"; echo "  G3 the mod ignores the check: ok"

echo "PASS ($SH)"
