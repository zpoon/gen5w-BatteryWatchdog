#!/system/bin/sh
# Battery Watchdog - 12V verification probe.
#
# WARNING: this calls transactions 137, 214, 74 and 211 by NUMBER. They are
# read-only getters on the reference firmware (2023 Ioniq 5 SEL US, 260507),
# but numbers can shift on other firmware and reach a setter or a reset.
# On any other firmware, first let the Battery Watchdog app resolve the
# numbers by name, or check them offline (docs/TECHNICAL.md) before running this.
# Answers: is hscan 0x89 (getBatSoc) really the 12V state of charge, is it
# an int or a float, and what scale? Also records SOH (0xd6) for reference.
#
# Put this + NEMESIS.data at the root of a USB drive, insert with the car ON
# and PARKED, approve. Takes ~3 minutes. Note the 12V % (if your cluster or
# a scan tool shows it) while it runs, then read PROBE-12V.txt on a computer.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:$PATH
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

U=/storage/usb0; [ -d "$U" ] || U=/data/nemesis
OUT=$U/PROBE-12V.txt
S=tm_hscan_service

echo "12V probe $(date 2>/dev/null)" > "$OUT"
echo "firmware: $(getprop ro.build.display.id 2>/dev/null) / $(getprop ro.build.version.incremental 2>/dev/null)" >> "$OUT"
echo "--- service list (hscan/hev)" >> "$OUT"
service list 2>/dev/null | grep -iE 'hscan|hev' >> "$OUT"

# getters only: 0x89 getBatSoc, 0xd6 SOH, 0x4a remainBatteryPercentage, 0xd3 BatPwrSum
i=0
while [ "$i" -lt 12 ]; do
    echo "--- sample $i  $(date 2>/dev/null)" >> "$OUT"
    for t in 137 214 74 211; do
        r=$(service call $S $t 2>&1)
        echo "txn $t:" $r >> "$OUT"   # unquoted: folds multi-line parcels onto one line
    done
    sync 2>/dev/null
    i=$((i + 1))
    sleep 15
done

echo "--- logcat hits" >> "$OUT"
logcat -d -v time 2>/dev/null | grep -iE 'BatSoc|Ibs|12V|Batt_Soc|BatSOC' >> "$OUT" 2>/dev/null
echo "done" >> "$OUT"; sync 2>/dev/null
exit 0
