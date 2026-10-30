#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
STATE=/data/nemesis/state/battery.watchdog
mkdir -p "$STATE" 2>/dev/null
# seed defaults only if not already set (keeps settings + history on reinstall)
[ -r "$STATE/enabled" ]     || echo 1   > "$STATE/enabled"
[ -r "$STATE/warn_pct" ]    || echo 60  > "$STATE/warn_pct"
[ -r "$STATE/drop_pct" ]    || echo 15  > "$STATE/drop_pct"
[ -r "$STATE/interval_s" ]  || echo 300 > "$STATE/interval_s"
[ -r "$STATE/decode" ]      || echo 1   > "$STATE/decode"
[ -r "$STATE/scale_x10" ]   || echo 10  > "$STATE/scale_x10"
[ -r "$STATE/toast_start" ] || echo 0   > "$STATE/toast_start"
# Start (or restart) the monitor once NEMESIS has registered the new service,
# so an update takes effect without a reboot. Runs in the background.
( sleep 15
  : > /data/nemesis/logs/battery.watchdog/watch.log 2>/dev/null
  /system/xbin/nemesis service stop battery.watchdog/watch >/dev/null 2>&1
  /system/xbin/nemesis service start battery.watchdog/watch >/dev/null 2>&1 ) &
echo "battery.watchdog installed (warn_pct=$(cat "$STATE/warn_pct" 2>/dev/null))"
exit 0
