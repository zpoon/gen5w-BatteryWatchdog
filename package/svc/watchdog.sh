#!/system/bin/sh
# battery.watchdog - 12V battery monitor for Gen5W (Hyundai/Kia) head units.
#
# READ-ONLY: only calls getters on the telematics hscan service.
#   12V SOC:  service call tm_hscan_service 137   (0x89 getBatSoc, int %, probe-confirmed)
#   HV SOH:   service call tm_hscan_service 214   (0xd6 getXEV_Bms_GDS_SOH, raw byte)
#
# Runs as a NEMESIS mode=restart service (started on install and at boot, kept
# alive). Gen5W usually does NOT reboot between drives (QuickBoot / suspend),
# so a "drive" (session) starts when the display wakes up, or when the wall
# clock jumps forward because the unit was suspended. At each session start it
# compares the reading with the end of the previous session and warns via the
# factory toast. While the display is on it samples every interval_s.
#
# stdout is captured by NEMESIS:  nemesis log service battery.watchdog/watch
# Config: one-value files in $STATE (written by the app / post.sh).
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
[ -n "$BWD_TEST_PATH" ] && PATH=$BWD_TEST_PATH:$PATH   # desktop tests only (mock service/am/dumpsys)

VERSION=0.5.3
TAG=battery.watchdog
STATE=${BWD_STATE:-/data/nemesis/state/$TAG}
SVC=tm_hscan_service
# Transaction numbers are NOT hard-wired: see verify() below.
TX_SOC=""; TX_SOH=""; TX_HVSOC=""
mkdir -p "$STATE" 2>/dev/null
LOG=$STATE/log.csv

log() { echo "watchdog: $(date 2>/dev/null) $*"; }

cfg() { _f="$STATE/$1"; _d="$2"; if [ -r "$_f" ]; then read -r _v < "$_f" 2>/dev/null; case "$_v" in ''|*[!0-9-]*) echo "$_d";; *) echo "$_v";; esac; else echo "$_d"; fi; }

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

# wall-clock seconds (keeps counting through suspend). If this date can't do
# +%s, fall back to whole seconds of uptime (no suspend-gap detection then).
uptime_s() { _u=0; [ -r /proc/uptime ] && read -r _u _x < /proc/uptime; echo "${_u%%.*}"; }
CLOCK_OK=0
case "$(date +%s 2>/dev/null)" in ''|*[!0-9]*) ;; *) CLOCK_OK=1;; esac
now() { if [ "$CLOCK_OK" = 1 ]; then date +%s; else uptime_s; fi; }

# fmt10 475 -> 47.5
fmt10() { _n=$1; _s=""; [ "$_n" -lt 0 ] && { _s="-"; _n=$((0 - _n)); }; echo "$_s$((_n / 10)).$((_n % 10))"; }

# hex string -> decimal without relying on 0x/16# arithmetic support.
# Values >= 0x80000000 wrap negative in 32-bit shells; callers treat <0 as invalid.
hex2dec() {
    _h=$1; _r=0
    while [ -n "$_h" ]; do
        _c=${_h%"${_h#?}"}; _h=${_h#?}
        case "$_c" in
            [0-9]) _d=$_c;; a|A) _d=10;; b|B) _d=11;; c|C) _d=12;; d|D) _d=13;; e|E) _d=14;; f|F) _d=15;;
            *) echo -1; return;;
        esac
        _r=$(( _r * 16 + _d ))
    done
    echo "$_r"
}

# atomic-enough write without mv -f (toolbox mv on KitKat takes no options)
# one small write, straight to the file (no temp copy: fewer flash writes; readers
# just retry on their next refresh if they ever catch a half-written file)
put() { _f=$1; shift; echo "$*" > "$_f" 2>/dev/null; }

# Command output is split into lines in memory with a newline IFS (and globbing
# off), instead of going through a temp file or a here-document, which mksh
# also backs with a temp file. Keeps the per-sample writes down.
NL='
'
lines_on() { _oifs=$IFS; IFS=$NL; set -f; }
lines_off() { IFS=$_oifs; set +f; }

# ---- display state (from hkmc-factory82's detector) --------------------------
DUMPSYS=/system/bin/dumpsys
[ -n "$BWD_TEST_PATH" ] && DUMPSYS=dumpsys
display_state() {
    _p=$($DUMPSYS power 2>/dev/null)
    case "$_p" in
        *'mDisplayPowerRequest=policy=OFF'*|*'mDisplayPowerRequest=policy=0'*) echo off; return ;;
        *'mDisplayPowerRequest=policy=BRIGHT'*|*'mDisplayPowerRequest=policy=DIM'*) echo on; return ;;
        *'mScreenOn=false'*|*'mInteractive=false'*|*'mWakefulness=Asleep'*|*'mWakefulness=Dozing'*) echo off; return ;;
        *'mScreenOn=true'*|*'mInteractive=true'*|*'mWakefulness=Awake'*) echo on; return ;;
    esac
    echo unknown
}

# ---- binder read -------------------------------------------------------------
# `service call` prints e.g.   Result: Parcel<00000000 00000053 '....S...'>
# (with round brackets). Split on whitespace and use the words, so this code
# contains no bracket characters at all: KitKat's mksh failed to parse two
# earlier versions that matched on the bracket ("'{' unmatched" here).
#   word 1 = "Result:"   word 2 = "Parcel" + status   word 3 = value
call_word() {
    RAW=""
    _o=$(service call "$SVC" "$1" 2>/dev/null)
    set -- $_o
    [ "$1" = "Result:" ] || return 1
    case "$2" in *00000000) ;; *) return 1;; esac
    _v=$3
    case "$_v" in [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;; *) return 1;; esac
    RAW=$_v
}

float10() {   # float bits (hex) -> value x10, or -1
    _b=$(hex2dec "$1")
    [ "$_b" -lt 0 ] && { echo -1; return; }
    [ "$_b" -eq 0 ] && { echo 0; return; }
    _e=$(( ((_b >> 23) & 255) - 127 ))
    [ "$_e" -lt -4 ] && { echo 0; return; }
    [ "$_e" -gt 22 ] && { echo -1; return; }
    _m=$(( (_b & 8388607) | 8388608 ))
    echo $(( (_m * 10) >> (23 - _e) ))
}

decode() {   # RAW -> tenths of a percent, or -1
    _b=$(hex2dec "$1")
    _mode=$DECODE
    if [ "$_mode" = "0" ]; then
        if [ "$_b" -ge 805306368 ] || [ "$_b" -lt 0 ]; then _mode=2; else _mode=1; fi
    fi
    if [ "$_mode" = "2" ]; then
        _v=$(float10 "$1"); [ "$_v" -lt 0 ] && { echo -1; return; }
        _p=$(( _v * SCALE / 10 ))
    else
        [ "$_b" -lt 0 ] && { echo -1; return; }
        _p=$(( _b * SCALE ))
    fi
    if [ "$_p" -lt 0 ] || [ "$_p" -gt 1000 ]; then echo -1; else echo "$_p"; fi
}

# ---- outside temperature (context only; never used for decisions) ------------
# Sets TEMP_C (whole deg C) and TEMP_SRC, or leaves TEMP_C empty. TEMP_LINE keeps
# the climate-dump line it looked at, for Diagnostics. Sources, in order:
#   1. telematics ECO JSON "outTemperature" in logcat (deg C), as coldstart-heat
#   2. UI log "setOutsideTempUIAdvanced::temp = N" (deg F), coldstart-heat's fallback
#   3. dumpsys of the climate service: OutTempC / OutTempF raw CAN values,
#      decoded as below. Available right away, unlike 1 and 2.
TEMP_SRC=""; TEMP_LINE=""
trim() { _t=$1; while :; do case "$_t" in ' '*) _t=${_t# };; *' ') _t=${_t% };; *) break;; esac; done; echo "$_t"; }
in_range_c() { [ "$1" -ge -45 ] 2>/dev/null && [ "$1" -le 55 ]; }   # believable outdoor range

# The climate dump shows the raw CAN values in hex, e.g. on a 2023 Ioniq 5:
#     OutTempC : 0xff      (0xff = not available)
#     OutTempF : 0x6e
# Decoded as C = 0.5 x raw - 40 and F = raw - 40. On the car, 0x6e read 70 F
# with the dash at 70 F, and egmp-aa-ev-feat found 0.5 x raw - 40 C matches
# the dash for the same DATC signal. (commaai/opendbc lists offsets of -41 C
# and -42 F for CR_Datc_OutTempC/F, which read 2 F low here.) Only these two
# exact keys with hex values are used; 0xfe/0xff mean "not available".
temp_from_datc() {
    _out=$($DUMPSYS com.hkmc.micom.datc 2>/dev/null | grep -i 'outtemp')
    _tc=""; _tf=""
    lines_on
    for _x in $_out; do
        [ -n "$TEMP_LINE" ] || TEMP_LINE=$_x
        case "$_x" in *:*) ;; *) continue;; esac
        _k=$(trim "${_x%%:*}"); _v=$(trim "${_x#*:}"); _v=${_v%% *}
        case "$_v" in 0x*|0X*) ;; *) continue;; esac
        _n=$(hex2dec "${_v#0[xX]}")
        case "$_n" in ''|-*|*[!0-9]*) continue;; esac
        [ "$_n" -ge 254 ] && continue
        case "$_k" in
            OutTempC) _tc=$(( _n / 2 - 40 ));;
            OutTempF) _tf=$(( (_n - 40 - 32) * 5 / 9 ));;
        esac
    done
    lines_off
    for _c in $_tc $_tf; do
        if in_range_c "$_c"; then TEMP_C=$_c; TEMP_SRC=climate; return 0; fi
    done
    return 1
}

temp_from_logcat() {
    _out=$(logcat -d 2>/dev/null | grep -E 'outTemperature|setOutsideTempUIAdvanced')
    _eco=""; _ui=""
    lines_on
    for _x in $_out; do
        case "$_x" in
            *'"outTemperature":'*) _eco=$_x;;
            *'setOutsideTempUIAdvanced::temp ='*) _ui=$_x;;
        esac
    done
    lines_off
    if [ -n "$_eco" ]; then
        _c=${_eco#*\"outTemperature\":}; _c=${_c%%[!0-9-]*}
        case "$_c" in ''|-|*[!0-9-]*) ;; *) if in_range_c "$_c"; then TEMP_C=$_c; TEMP_SRC=eco_log; return 0; fi;; esac
    fi
    if [ -n "$_ui" ]; then
        _f=${_ui#*temp = }; _f=${_f%% *}; _f=${_f%%.*}
        case "$_f" in ''|-|*[!0-9-]*) ;; *) _c=$(( (_f - 32) * 5 / 9 )); if in_range_c "$_c"; then TEMP_C=$_c; TEMP_SRC=ui_log; return 0; fi;; esac
    fi
    return 1
}

# The ECO log line only shows up every so often (on the car it appeared a few
# minutes into a drive), so keep the last value for up to 30 min instead of
# blanking it between messages.
TEMP_T=0
read_temp() {
    TEMP_LINE=""
    # the logs match the dash exactly but only appear a few minutes into a drive;
    # the climate dump is there from the start, so it fills that gap
    if temp_from_logcat || temp_from_datc; then TEMP_T=$(now); return; fi
    if [ -n "$TEMP_C" ] && [ $(( $(now) - TEMP_T )) -le 1800 ]; then return; fi
    TEMP_C=""; TEMP_SRC=""
}

SOH_RAW=""; HV10=""; TEMP_C=""
sample() {   # sets S_RAW, S_PCT10 (-1 if invalid)
    S_RAW=""; S_PCT10=-1
    _try=0
    while [ "$_try" -lt 5 ]; do
        [ -n "$TX_SOC" ] || break
        if call_word "$TX_SOC"; then S_RAW=$RAW; S_PCT10=$(decode "$RAW"); [ "$S_PCT10" -ge 0 ] && break; fi
        _try=$((_try + 1)); sleep 2
    done
    if [ -n "$TX_SOH" ] && call_word "$TX_SOH"; then SOH_RAW=$(hex2dec "$RAW"); fi
    if [ -n "$TX_HVSOC" ] && call_word "$TX_HVSOC"; then
        _h=$(float10 "$RAW")
        if [ "$_h" -ge 0 ] && [ "$_h" -le 1000 ]; then HV10=$_h; fi
    fi
    read_temp
}

append() {   # event raw pct10
    echo "$(now),$1,$2,$3,$SOH_RAW,$HV10,$TEMP_C" >> "$LOG"
    LINES=$((LINES + 1))
    if [ "$LINES" -ge "$MAX_LINES" ]; then mv "$LOG" "$STATE/log.old.csv" 2>/dev/null; LINES=0; put "$STATE/lines" 0
    elif [ $((LINES % 50)) -eq 0 ]; then put "$STATE/lines" "$LINES"; fi   # save the count only now and then
}

write_status() {   # state
    {
        echo "state=$1"
        echo "version=$VERSION"
        echo "updated=$(now)"
        echo "pct10=$S_PCT10"
        echo "raw=$S_RAW"
        echo "soh_raw=$SOH_RAW"
        echo "display=$DISP"
        echo "session_start=$SESS_T $SESS_P"
        echo "prev_session=$PREV_LINE"
        echo "hv10=$HV10"
        echo "temp_c=$TEMP_C"
        echo "temp_src=$TEMP_SRC"
        echo "temp_line=$TEMP_LINE"
        echo "parked_min=$PARK_MIN"
        echo "parked_drop10=$PARK_DROP10"
        echo "parked_rate10_day=$PARK_RATE10"
        echo "firmware=$FW"
        echo "verify=$VERIFY_WHY"
        echo "warn=$WARN_TEXT"
    } > "$STATE/status" 2>/dev/null
}

load_cfg() {
    ENABLED=$(cfg enabled 1)
    INTERVAL=$(cfg interval_s 300)
    SETTLE=$(cfg settle_s 30)
    WARN_PCT=$(cfg warn_pct 60)
    DROP_PCT=$(cfg drop_pct 15)
    DECODE=$(cfg decode 1)        # 0 auto, 1 int = probe-confirmed, 2 float
    SCALE=$(cfg scale_x10 10)     # % per raw unit, x10: 10 = 1 %, 5 = 0.5 %
    TOAST_START=$(cfg toast_start 0)
    MAX_LINES=$(cfg max_lines 4000)
    POLL=$(cfg poll_s 15)
    MIN_OFF=$(cfg min_off_s 600)  # screen off at least this long = parked = a new drive
    GAP=$(cfg gap_s "$MIN_OFF")   # wall-clock jump that means "the unit was asleep"
    [ "$INTERVAL" -lt 30 ] 2>/dev/null && INTERVAL=30
    if [ -n "$BWD_TEST" ]; then INTERVAL=${BWD_TEST_INTERVAL:-2}; POLL=1; SETTLE=0; MIN_OFF=${BWD_TEST_MIN_OFF:-2}; GAP=600; fi
}

# ---- a new drive: compare with the previous one and warn -----------------------
session_start() {   # reason
    [ "$SETTLE" -gt 0 ] 2>/dev/null && sleep "$SETTLE"
    sample
    append start "$S_RAW" "$S_PCT10"
    SESS_T=$(now); SESS_P=$S_PCT10; LAST_SAMPLE=$SESS_T
    WARN_TEXT=""; PREV_LINE=""
    if [ "$S_PCT10" -lt 0 ]; then
        log "session [$1]: no valid 12V reading, raw=$S_RAW"
        write_status no_reading
        return
    fi
    log "session [$1]: 12V $(fmt10 "$S_PCT10")% raw=$S_RAW soh_raw=$SOH_RAW hv10=$HV10 temp_c=$TEMP_C"
    PS_T=""; PS_P=""; PE_T=""; PE_P=""
    [ -r "$STATE/sess_start" ] && read -r PS_T PS_P < "$STATE/sess_start"
    [ -r "$STATE/last" ] && read -r PE_T PE_P < "$STATE/last"

    [ "$S_PCT10" -lt $((WARN_PCT * 10)) ] && WARN_TEXT="12V battery low: $(fmt10 "$S_PCT10")%"
    PARK_MIN=""; PARK_DROP10=""; PARK_RATE10=""
    if [ -n "$PE_P" ] && [ "$PE_P" -ge 0 ] 2>/dev/null; then
        DROP=$((PE_P - S_PCT10))
        # how long it sat since the last sample of the previous drive
        if [ "$PE_T" -gt 0 ] 2>/dev/null && [ "$SESS_T" -gt "$PE_T" ]; then
            PARK_MIN=$(( (SESS_T - PE_T) / 60 ))
            PARK_DROP10=$DROP
            [ "$PARK_MIN" -ge 60 ] && PARK_RATE10=$(( DROP * 1440 / PARK_MIN ))
        fi
        if [ "$DROP" -ge $((DROP_PCT * 10)) ]; then
            W="12V dropped $(fmt10 "$DROP") pts while parked: $(fmt10 "$PE_P")% -> $(fmt10 "$S_PCT10")%"
            WARN_TEXT=${WARN_TEXT:+$WARN_TEXT; }$W
        fi
        if [ -n "$PS_P" ] && [ "$PS_P" -ge 0 ] 2>/dev/null && [ "$PS_T" -gt 0 ] 2>/dev/null && [ "$PE_T" -gt "$PS_T" ] 2>/dev/null; then
            DRIVE_MIN=$(( (PE_T - PS_T) / 60 ))
            REC=$((PE_P - PS_P))
            PREV_LINE="$PS_T $DRIVE_MIN $PS_P $PE_P"
            echo "$PREV_LINE" >> "$STATE/sessions"
            if [ "$DRIVE_MIN" -ge 20 ] && [ "$DRIVE_MIN" -le 1440 ] && [ "$REC" -lt 0 ]; then
                W="12V did not recover on last drive: $(fmt10 "$PS_P")% -> $(fmt10 "$PE_P")% over ${DRIVE_MIN} min"
                WARN_TEXT=${WARN_TEXT:+$WARN_TEXT; }$W
            fi
        fi
    fi
    put "$STATE/sess_start" "$SESS_T $SESS_P"
    put "$STATE/last" "$SESS_T $SESS_P"
    if [ -n "$WARN_TEXT" ]; then
        [ -n "$TEMP_C" ] && WARN_TEXT="$WARN_TEXT, outside $(( TEMP_C * 9 / 5 + 32 ))F"
        log "WARN $WARN_TEXT"; echo "$(now) $WARN_TEXT" >> "$STATE/warnings"; toast "$WARN_TEXT"
    elif [ "$TOAST_START" = "1" ]; then
        toast "12V battery $(fmt10 "$S_PCT10")%"
    fi
    write_status ok
}

# ---- firmware / interface verification -----------------------------------------
# Transaction numbers are positions in ITM_HSCANService's method list and can
# shift between firmware builds, where the same number may reach a setter or a
# reset. So they are never used blindly:
#   1. numbers the app resolved BY NAME from this firmware's own interface
#      definition (TRANSACTION_getBatSoc etc.), saved in $STATE/txn together
#      with the firmware id they were resolved on; or
#   2. numbers listed for this exact build in known_firmware.txt (verified by hand).
# With neither, the monitor makes no calls and reports state=unverified.
# The service must also report the expected interface descriptor.
HSCAN_IFACE=com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService
# Hand-verified builds and their numbers: known_firmware.txt in the package root.
PKG_DIR=${BWD_PKG:-${0%/*}/..}
KNOWN_FILE=$PKG_DIR/known_firmware.txt
[ -r "$KNOWN_FILE" ] || KNOWN_FILE=/data/nemesis/apps/battery.watchdog/known_firmware.txt
FW=$(getprop ro.build.version.incremental 2>/dev/null)
VERIFIED=0; VERIFY_WHY=""

isnum() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "$1" -gt 0 ] && [ "$1" -lt 1000 ]; }

verify() {
    VERIFIED=0; TX_SOC=""; TX_SOH=""; TX_HVSOC=""
    if ! service list 2>/dev/null | grep "$SVC" | grep -q "$HSCAN_IFACE"; then
        VERIFY_WHY=interface_mismatch; return
    fi
    if [ -r "$STATE/txn" ]; then
        _fw=""; _soc=""; _hv=""; _soh=""
        while IFS='=' read -r _k _v; do
            case "$_k" in fw) _fw=$_v;; soc) _soc=$_v;; hv) _hv=$_v;; soh) _soh=$_v;; esac
        done < "$STATE/txn"
        if [ -n "$FW" ] && [ "$_fw" = "$FW" ] && isnum "$_soc"; then
            TX_SOC=$_soc
            isnum "$_hv" && TX_HVSOC=$_hv
            isnum "$_soh" && TX_SOH=$_soh
            VERIFIED=1; VERIFY_WHY=resolved_by_name; return
        fi
    fi
    if [ -n "$FW" ] && [ -r "$KNOWN_FILE" ]; then
        while read -r _f _s _h _o _rest; do
            case "$_f" in ''|'#'*) continue;; esac
            [ "$_f" = "$FW" ] || continue
            isnum "$_s" || continue
            TX_SOC=$_s
            isnum "$_h" && TX_HVSOC=$_h
            isnum "$_o" && TX_SOH=$_o
            VERIFIED=1; VERIFY_WHY=known_firmware; return
        done < "$KNOWN_FILE"
    fi
    VERIFY_WHY=unverified_firmware
}

# ---- main ----------------------------------------------------------------------
load_cfg
LINES=$(cfg lines 0)
log "v$VERSION start; enabled=$ENABLED interval=${INTERVAL}s poll=${POLL}s gap=${GAP}s decode=$DECODE clock_ok=$CLOCK_OK now=$(now) uptime=$(cat /proc/uptime 2>/dev/null)"

# wait (bounded) for the telematics service
i=0
while [ "$i" -lt 120 ]; do
    service check "$SVC" 2>/dev/null | grep -q found && break
    sleep 2; i=$((i + 2))
done
[ "$i" -ge 120 ] && log "$SVC not registered after 120s; continuing anyway"

verify
log "firmware=$FW verify=$VERIFY_WHY soc=$TX_SOC hv=$TX_HVSOC soh=$TX_SOH"

DISP=$(display_state)
PREV_DISP=$DISP
LAST_TICK=$(now)
LAST_SAMPLE=0
SESS_T=""; SESS_P=""; PREV_LINE=""; WARN_TEXT=""; S_PCT10=-1; S_RAW=""
PARK_MIN=""; PARK_DROP10=""; PARK_RATE10=""
NEED_SESSION=service_start
OFF_T=0
LOOPS=0

while :; do
    load_cfg
    if [ "$ENABLED" != "1" ]; then
        DISP=$(display_state); write_status disabled
        sleep 60; LAST_TICK=$(now); continue
    fi
    if [ "$VERIFIED" != 1 ]; then
        # make no calls; recheck in a minute in case the app has resolved the numbers
        write_status unverified
        LOOPS=$((LOOPS + 1))
        [ -n "$BWD_TEST" ] && [ "$LOOPS" -ge "${BWD_TEST_LOOPS:-3}" ] && break
        if [ -n "$BWD_TEST" ]; then sleep 1; else sleep 60; fi
        verify
        [ "$VERIFIED" = 1 ] && { log "verified: $VERIFY_WHY soc=$TX_SOC hv=$TX_HVSOC soh=$TX_SOH"; NEED_SESSION=verified; }
        LAST_TICK=$(now); continue
    fi

    T=$(now)
    DISP=$(display_state)
    [ "$PREV_DISP" != off ] && [ "$DISP" = off ] && OFF_T=$T
    if [ "$PREV_DISP" = off ] && [ "$DISP" != off ] && [ -z "$NEED_SESSION" ]; then
        # a short stop (screen off for less than min_off_s) continues the same drive
        _off=$((T - OFF_T))
        if [ "$CLOCK_OK" != 1 ] || [ "$OFF_T" -le 0 ] || [ "$_off" -ge "$MIN_OFF" ]; then
            NEED_SESSION=display_wake
        else
            log "short stop: screen off ${_off}s, same drive"
        fi
    fi
    if [ "$CLOCK_OK" = 1 ] && [ $((T - LAST_TICK)) -ge "$GAP" ]; then
        _gap=$((T - LAST_TICK))
        [ -z "$NEED_SESSION" ] && NEED_SESSION="resume_after_${_gap}s"
    fi

    if [ -n "$NEED_SESSION" ] && [ "$DISP" != off ]; then
        session_start "$NEED_SESSION"
        NEED_SESSION=""
    elif [ "$DISP" != off ] && [ $((T - LAST_SAMPLE)) -ge "$INTERVAL" ]; then
        sample
        append sample "$S_RAW" "$S_PCT10"
        LAST_SAMPLE=$T
        if [ "$S_PCT10" -ge 0 ]; then put "$STATE/last" "$(now) $S_PCT10"; write_status ok; fi
    fi

    [ "$DISP" != "$PREV_DISP" ] && log "display $PREV_DISP -> $DISP"
    PREV_DISP=$DISP
    LAST_TICK=$(now)
    LOOPS=$((LOOPS + 1))
    [ -n "$BWD_TEST" ] && [ "$LOOPS" -ge "${BWD_TEST_LOOPS:-3}" ] && break
    sleep "$POLL"
done
exit 0
