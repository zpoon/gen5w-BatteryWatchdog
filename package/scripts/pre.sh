#!/system/bin/sh
# NEMESIS preinstall: runs as root, with the staged package as the working
# directory, before NEMESIS copies the package into place and installs its app.
#
# On a 2023 Ioniq 6 (US 260507, issue #13) NEMESIS's own install of a new app
# fails every time ("pm install failed", from USB and the Package Store alike,
# and for other packages too), while the same install from /data/local/tmp
# works. Permissions and free space were fine. So when the app isn't installed
# yet, install it from there first, the way NEMESIS does (sys.boot_completed 0
# for the call: the firmware only takes non-OEM signatures then). NEMESIS's own
# install then only updates it, which works. An update (app already there) is
# left to NEMESIS. Never stops the install: if this fails, NEMESIS tries as usual.
export PATH=${BWD_PATH:-/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin}:$PATH
umask 022
PKG=com.nemesis.batterywatchdog
APK=app/batterywatchdog.apk
TMP=${BWD_TMP:-/data/local/tmp}
STATE=${BWD_STATE:-/data/nemesis/state/battery.watchdog}
LOG=$STATE/install.log
mkdir -p "$STATE" 2>/dev/null
log() { echo "$(date 2>/dev/null) preinstall: $*" >> "$LOG" 2>/dev/null; }

[ -f "$APK" ] || { log "no $APK in the package"; exit 0; }
case "$(service check package 2>/dev/null)" in
    *": found"*) ;;
    *) log "Android isn't up: NEMESIS installs the app"; exit 0;;
esac
case "$(pm path "$PKG" 2>/dev/null)" in
    *package:*) log "app already installed: NEMESIS updates it"; exit 0;;
esac

T=$TMP/BatteryWatchdog-install.apk
if ! cat "$APK" > "$T" 2>/dev/null; then log "couldn't copy the app to $TMP"; rm -f "$T"; exit 0; fi
chmod 644 "$T"
b=$(getprop sys.boot_completed)
[ "$b" = 1 ] && setprop sys.boot_completed 0
r=$(pm install -r "$T" 2>&1)
[ "$b" = 1 ] && setprop sys.boot_completed 1
rm -f "$T"
why=""
while IFS= read -r l; do
    case "$l" in *Success*|*Failure*|*Error*|*Exception*) why=$l; break;; esac
done <<X
$r
X
case "$why" in
    *Success*) log "installed the app from $TMP";;
    *) log "installing the app from $TMP didn't work (${why:-no answer from pm}); NEMESIS tries its own way";;
esac
exit 0
