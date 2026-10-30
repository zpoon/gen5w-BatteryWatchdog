#!/system/bin/sh
# READ-ONLY dump probe: copies the firmware's ODEX/JAR/APK files, the binder
# service list and getprop to the USB drive, for checking transaction numbers
# offline (docs/TECHNICAL.md, "Porting"). Makes no binder calls.
# Copies the head unit's ODEX/JAR files plus the binder service list and
# firmware props to the USB drive. Sends nothing to the car.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

U=/storage/usb0; [ -d "$U" ] || U=/data/nemesis
D=$U/odex-dump
mkdir -p "$D/framework" "$D/app" "$D/priv-app" "$D/vendor"
LOG=$D/DUMP_LOG.txt
echo "dump start $(date 2>/dev/null)" > "$LOG"

# firmware + service inventory
getprop > "$D/getprop.txt" 2>/dev/null
service list > "$D/service-list.txt" 2>/dev/null
ls -lR /system/framework /system/app /system/priv-app > "$D/ls-system.txt" 2>/dev/null

# copy with cat (KitKat toolbox may have no cp)
copy_dir() {  # $1=src dir  $2=dest dir
  for f in "$1"/*.odex "$1"/*.jar "$1"/*.apk; do
    [ -f "$f" ] || continue
    cat "$f" > "$2/$(basename "$f")" 2>>"$LOG" && echo "ok  $f" >> "$LOG"
  done
}
copy_dir /system/framework "$D/framework"
copy_dir /system/app       "$D/app"
copy_dir /system/priv-app  "$D/priv-app"
[ -d /vendor/framework ] && copy_dir /vendor/framework "$D/vendor"

sync 2>/dev/null
echo "dump done $(date 2>/dev/null)" >> "$LOG"
sync 2>/dev/null
exit 0
