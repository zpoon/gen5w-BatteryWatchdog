#!/system/bin/sh
# Before NEMESIS removes (or updates) Battery Watchdog: leave a copy of the history
# where the next copy of the app can import it, e.g. the HMG-API mod after moving
# over, or this package again after a reinstall (Settings -> Tools -> Import history).
# /data/local/tmp is the shell user's (0771), so the app can reach a folder there
# by its exact path; the folder is readable to it, nothing else is copied.
# Only history: the battery log, drives, warnings and collapses. No settings, no
# firmware numbers, nothing that identifies the car.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
STATE=${BWD_STATE:-/data/nemesis/state/battery.watchdog}
H=${BWD_HANDOVER:-/data/local/tmp/battery.watchdog-history}
[ -f "$STATE/log.csv" ] || [ -f "$STATE/log.old.csv" ] || [ -f "$STATE/sessions" ] || exit 0
umask 022
mkdir -p "$H" 2>/dev/null && chmod 755 "$H" 2>/dev/null || { echo "battery.watchdog: no hand-over copy (can't create $H)"; exit 0; }
for f in log.old.csv log.csv sessions warnings dips; do
    if [ -f "$STATE/$f" ]; then cat "$STATE/$f" > "$H/$f" 2>/dev/null && chmod 644 "$H/$f" 2>/dev/null
    else rm -f "$H/$f" 2>/dev/null; fi
done
echo "$(date 2>/dev/null)" > "$H/from" 2>/dev/null; chmod 644 "$H/from" 2>/dev/null
echo "battery.watchdog: history copied to $H for importing"
exit 0
