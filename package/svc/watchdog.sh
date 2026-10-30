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
# stdout is captured by NEMESIS:  nemesis log service battery.watchdog/monitor
# Config: one-value files in $STATE (written by the app / post.sh).
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
[ -n "$BWD_TEST_PATH" ] && PATH=$BWD_TEST_PATH:$PATH   # desktop tests only (mock service/am/dumpsys)

VERSION=0.2.4
TAG=battery.watchdog
STATE=${BWD_STATE:-/data/nemesis/state/$TAG}
SVC=tm_hscan_service
TX_SOC=137
TX_SOH=214
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
put() { _f=$1; shift; echo "$*" > "$_f.tmp" 2>/dev/null && cat "$_f.tmp" > "$_f" 2>/dev/null; rm "$_f.tmp" 2>/dev/null; }

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

SOH_RAW=""
sample() {   # sets S_RAW, S_PCT10 (-1 if invalid)
    S_RAW=""; S_PCT10=-1
    _try=0
    while [ "$_try" -lt 5 ]; do
        if call_word "$TX_SOC"; then S_RAW=$RAW; S_PCT10=$(decode "$RAW"); [ "$S_PCT10" -ge 0 ] && break; fi
        _try=$((_try + 1)); sleep 2
    done
    if call_word "$TX_SOH"; then SOH_RAW=$(hex2dec "$RAW"); fi
}

append() {   # event raw pct10
    echo "$(now),$1,$2,$3,$SOH_RAW" >> "$LOG"
    LINES=$((LINES + 1))
    if [ "$LINES" -ge "$MAX_LINES" ]; then mv "$LOG" "$STATE/log.old.csv" 2>/dev/null; LINES=0; fi
    put "$STATE/lines" "$LINES"
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
        echo "warn=$WARN_TEXT"
    } > "$STATE/status.tmp" 2>/dev/null && cat "$STATE/status.tmp" > "$STATE/status" 2>/dev/null
    rm "$STATE/status.tmp" 2>/dev/null
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
    GAP=$(cfg gap_s 600)          # wall-clock jump that means "the unit was asleep"
    [ "$INTERVAL" -lt 30 ] 2>/dev/null && INTERVAL=30
    if [ -n "$BWD_TEST" ]; then INTERVAL=${BWD_TEST_INTERVAL:-2}; POLL=1; SETTLE=0; fi
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
    log "session [$1]: 12V $(fmt10 "$S_PCT10")% raw=$S_RAW soh_raw=$SOH_RAW"
    PS_T=""; PS_P=""; PE_T=""; PE_P=""
    [ -r "$STATE/sess_start" ] && read -r PS_T PS_P < "$STATE/sess_start"
    [ -r "$STATE/last" ] && read -r PE_T PE_P < "$STATE/last"

    [ "$S_PCT10" -lt $((WARN_PCT * 10)) ] && WARN_TEXT="12V battery low: $(fmt10 "$S_PCT10")%"
    if [ -n "$PE_P" ] && [ "$PE_P" -ge 0 ] 2>/dev/null; then
        DROP=$((PE_P - S_PCT10))
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
        log "WARN $WARN_TEXT"; echo "$(now) $WARN_TEXT" >> "$STATE/warnings"; toast "$WARN_TEXT"
    elif [ "$TOAST_START" = "1" ]; then
        toast "12V battery $(fmt10 "$S_PCT10")%"
    fi
    write_status ok
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

DISP=$(display_state)
PREV_DISP=$DISP
LAST_TICK=$(now)
LAST_SAMPLE=0
SESS_T=""; SESS_P=""; PREV_LINE=""; WARN_TEXT=""; S_PCT10=-1; S_RAW=""
NEED_SESSION=service_start
LOOPS=0

while :; do
    load_cfg
    if [ "$ENABLED" != "1" ]; then
        DISP=$(display_state); write_status disabled
        sleep 60; LAST_TICK=$(now); continue
    fi

    T=$(now)
    DISP=$(display_state)
    if [ "$PREV_DISP" = off ] && [ "$DISP" != off ] && [ -z "$NEED_SESSION" ]; then NEED_SESSION=display_wake; fi
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
