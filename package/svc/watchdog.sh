#!/system/bin/sh
# battery.watchdog - 12V battery monitor for Gen5W (Hyundai/Kia) head units.
#
# READ-ONLY: only calls getters on the telematics hscan service.
#   12V SOC:  service call tm_hscan_service 137   (0x89 getBatSoc)   <- verify with the probe
#   HV SOH:   service call tm_hscan_service 214   (0xd6 getXEV_Bms_GDS_SOH, raw byte)
# Source for both: egmp-aa-ev-feat docs/DATA-SOURCES.md (NEEV.USA ... 260507).
#
# Each head-unit boot is treated as one "session" (one drive). At start it
# compares the new reading with the end of the previous session and warns via
# the factory toast if the 12V is low, dropped a lot while parked, or did not
# recover during the previous drive. Then it samples every interval_s.
#
# Config: one-value files in $STATE (written by the app / post.sh).
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
[ -n "$BWD_TEST_PATH" ] && PATH=$BWD_TEST_PATH:$PATH   # desktop tests only (mock `service`/`am`)

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

now() { _t=$(date +%s 2>/dev/null); case "$_t" in ''|*[!0-9]*) echo 0;; *) echo "$_t";; esac; }

# fmt10 475 -> 47.5
fmt10() { _n=$1; _s=""; [ "$_n" -lt 0 ] && { _s="-"; _n=$((0 - _n)); }; echo "$_s$((_n / 10)).$((_n % 10))"; }

ENABLED=$(cfg enabled 1)
INTERVAL=$(cfg interval_s 300)
SETTLE=$(cfg settle_s 30)
WARN_PCT=$(cfg warn_pct 60)
DROP_PCT=$(cfg drop_pct 15)
DECODE=$(cfg decode 0)        # 0 auto, 1 int, 2 float
SCALE=$(cfg scale_x10 10)     # % per raw unit, x10 (10 = 1 %, 5 = 0.5 %)
TOAST_START=$(cfg toast_start 0)
MAX_LINES=$(cfg max_lines 4000)
MAX_WAIT=$(cfg max_wait 120)
[ "$INTERVAL" -lt 30 ] 2>/dev/null && INTERVAL=30
[ -n "$BWD_TEST" ] && INTERVAL=${BWD_TEST_INTERVAL:-1}

log "start enabled=$ENABLED interval=$INTERVAL warn=$WARN_PCT drop=$DROP_PCT decode=$DECODE scale_x10=$SCALE"
if [ "$ENABLED" != "1" ]; then log "disabled"; echo "state=disabled" > "$STATE/status"; exit 0; fi

# ---- binder read --------------------------------------------------------
# `service call` prints e.g.  Result: Parcel(00000000 0000005f   '...._...')
# Sets RAW (8 hex digits) or returns 1.
call_word() {
    RAW=""
    _o=$(service call "$SVC" "$1" 2>/dev/null)
    case "$_o" in *'Parcel('*) ;; *) return 1;; esac
    _o=${_o#*Parcel(}
    set -- $_o
    [ "$1" = "00000000" ] || return 1          # exception code must be 0
    case "$2" in [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;; *) return 1;; esac
    RAW=$2
    return 0
}

# float bits -> value x10 (non-negative, < 2^23), or -1
float10() {
    _b=$((0x$1))
    [ "$_b" -lt 0 ] && { echo -1; return; }            # sign bit set (32-bit shells)
    _e=$(( ((_b >> 23) & 255) - 127 ))
    [ "$_b" -eq 0 ] && { echo 0; return; }
    [ "$_e" -lt -4 ] && { echo 0; return; }
    [ "$_e" -gt 22 ] && { echo -1; return; }
    _m=$(( (_b & 8388607) | 8388608 ))
    echo $(( (_m * 10) >> (23 - _e) ))
}

# RAW -> PCT10 (tenths of a percent) or -1
decode() {
    _b=$((0x$1))
    _mode=$DECODE
    if [ "$_mode" = "0" ]; then
        if [ "$_b" -ge 805306368 ] || [ "$_b" -lt 0 ]; then _mode=2; else _mode=1; fi   # >= 0x30000000 looks like a float
    fi
    if [ "$_mode" = "2" ]; then
        _v=$(float10 "$1"); [ "$_v" -lt 0 ] && { echo -1; return; }
        _p=$(( _v * SCALE / 10 ))
    else
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
    if call_word "$TX_SOH"; then SOH_RAW=$((0x$RAW)); fi
}

LINES=$(cfg lines 0)
append() {   # event raw pct10
    echo "$(now),$1,$2,$3,$SOH_RAW" >> "$LOG"
    LINES=$((LINES + 1))
    if [ "$LINES" -ge "$MAX_LINES" ]; then mv -f "$LOG" "$STATE/log.old.csv" 2>/dev/null; LINES=0; fi
    echo "$LINES" > "$STATE/lines"
}

write_status() {   # state
    {
        echo "state=$1"
        echo "updated=$(now)"
        echo "pct10=$S_PCT10"
        echo "raw=$S_RAW"
        echo "soh_raw=$SOH_RAW"
        echo "session_start=$SESS_T $SESS_P"
        echo "prev_session=$PREV_LINE"
        echo "warn=$WARN_TEXT"
    } > "$STATE/status.tmp" && mv -f "$STATE/status.tmp" "$STATE/status"
}

# ---- wait for the service ---------------------------------------------------
i=0
while [ "$i" -lt "$MAX_WAIT" ]; do
    service check "$SVC" 2>/dev/null | grep -q found && break
    sleep 2; i=$((i + 2))
done
[ "$i" -ge "$MAX_WAIT" ] && log "$SVC not found after ${MAX_WAIT}s; continuing anyway"
[ -z "$BWD_TEST" ] && sleep "$SETTLE"

# ---- session start ----------------------------------------------------------
sample
append start "$S_RAW" "$S_PCT10"
SESS_T=$(now); SESS_P=$S_PCT10
WARN_TEXT=""
PREV_LINE=""

if [ "$S_PCT10" -lt 0 ]; then
    log "no valid 12V reading (raw=$S_RAW); check the probe output / decode setting"
    write_status no_reading
else
    log "12V $(fmt10 "$S_PCT10")% (raw $S_RAW) soh_raw=$SOH_RAW"
    # previous session: start + last sample (= end)
    PS_T=""; PS_P=""; PE_T=""; PE_P=""
    [ -r "$STATE/sess_start" ] && read -r PS_T PS_P < "$STATE/sess_start"
    [ -r "$STATE/last" ] && read -r PE_T PE_P < "$STATE/last"

    if [ "$S_PCT10" -lt $((WARN_PCT * 10)) ]; then
        WARN_TEXT="12V battery low: $(fmt10 "$S_PCT10")%"
    fi
    if [ -n "$PE_P" ] && [ "$PE_P" -ge 0 ] 2>/dev/null; then
        DROP=$((PE_P - S_PCT10))
        if [ "$DROP" -ge $((DROP_PCT * 10)) ]; then
            W="12V dropped $(fmt10 "$DROP") pts while parked ($(fmt10 "$PE_P")% -> $(fmt10 "$S_PCT10")%)"
            WARN_TEXT=${WARN_TEXT:+$WARN_TEXT; }$W
        fi
        if [ -n "$PS_P" ] && [ "$PS_P" -ge 0 ] 2>/dev/null && [ "$PS_T" -gt 0 ] && [ "$PE_T" -gt "$PS_T" ]; then
            DRIVE_MIN=$(( (PE_T - PS_T) / 60 ))
            REC=$((PE_P - PS_P))
            PREV_LINE="$PS_T $DRIVE_MIN $PS_P $PE_P"
            echo "$PREV_LINE" >> "$STATE/sessions"
            if [ "$DRIVE_MIN" -ge 20 ] && [ "$DRIVE_MIN" -le 1440 ] && [ "$REC" -lt 0 ]; then
                W="12V did not recover on last drive ($(fmt10 "$PS_P")% -> $(fmt10 "$PE_P")% over ${DRIVE_MIN} min)"
                WARN_TEXT=${WARN_TEXT:+$WARN_TEXT; }$W
            fi
        fi
    fi
    echo "$SESS_T $SESS_P" > "$STATE/sess_start"
    echo "$SESS_T $SESS_P" > "$STATE/last"
    if [ -n "$WARN_TEXT" ]; then
        log "WARN $WARN_TEXT"; echo "$(now) $WARN_TEXT" >> "$STATE/warnings"; toast "$WARN_TEXT"
    elif [ "$TOAST_START" = "1" ]; then
        toast "12V battery $(fmt10 "$S_PCT10")%"
    fi
    write_status ok
fi

# ---- periodic samples -------------------------------------------------------
LOOPS=0
while :; do
    sleep "$INTERVAL"
    sample
    append sample "$S_RAW" "$S_PCT10"
    if [ "$S_PCT10" -ge 0 ]; then
        echo "$(now) $S_PCT10" > "$STATE/last"
        write_status ok
    fi
    LOOPS=$((LOOPS + 1))
    [ -n "$BWD_TEST" ] && [ "$LOOPS" -ge "${BWD_TEST_LOOPS:-2}" ] && break
done
exit 0
