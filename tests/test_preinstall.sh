#!/bin/sh
# package/scripts/pre.sh off the car: installs the app from a temp copy only when
# it isn't installed yet and Android is up, restores sys.boot_completed, logs pm's
# answer, and never fails the install.
# Usage: tests/test_preinstall.sh [shell]
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
fail() { echo "FAIL: $*"; [ -f "$T/state/install.log" ] && cat "$T/state/install.log"; exit 1; }

setup() {   # dir: a staged package, stubs, an empty temp and state dir
    T=$1; mkdir -p "$T/bin" "$T/stage/app" "$T/tmp" "$T/state"
    echo apk > "$T/stage/app/batterywatchdog.apk"
    cat > "$T/bin/pm" <<X
#!/bin/sh
echo "pm \$*" >> "$T/calls"
case "\$1" in
  path) [ -f "$T/installed" ] && echo "package:/data/app/com.nemesis.batterywatchdog-1.apk"; exit 0;;
  install) printf '\tpkg: %s\n' "\$3"; cmp -s "\$3" "$T/stage/app/batterywatchdog.apk" || { echo "Failure [INSTALL_FAILED_INVALID_URI]"; exit 0; }
           [ -f "$T/pm_fails" ] && echo "Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]" || { echo Success; : > "$T/installed"; };;
esac
X
    printf '#!/bin/sh\n[ -f %s/android_down ] && echo "Service package: not found" || echo "Service package: found"\n' "$T" > "$T/bin/service"
    printf '#!/bin/sh\ncat %s/boot 2>/dev/null || echo 1\n' "$T" > "$T/bin/getprop"
    printf '#!/bin/sh\necho "$2" > %s/boot; echo "setprop $*" >> %s/calls\n' "$T" "$T" > "$T/bin/setprop"
    chmod +x "$T/bin/"*
}
run() { ( cd "$T/stage" && BWD_PATH="$T/bin" BWD_TMP="$T/tmp" BWD_STATE="$T/state" "$SH" "$ROOT/package/scripts/pre.sh" ) || fail "pre.sh exited non-zero"; }

# P1: new install: from the temp copy, boot_completed 0 for the call and back to 1
T=$(mktemp -d); setup "$T"; run
grep -q "pm install -r $T/tmp/BatteryWatchdog-install.apk" "$T/calls" || fail "P1: should install from the temp copy"
[ "$(grep setprop "$T/calls" | tr '\n' ' ')" = "setprop sys.boot_completed 0 setprop sys.boot_completed 1 " ] || fail "P1: boot_completed 0 then 1"
[ -f "$T/installed" ] || fail "P1: installed"
[ ! -e "$T/tmp/BatteryWatchdog-install.apk" ] || fail "P1: temp copy left behind"
grep -q "installed the app from" "$T/state/install.log" || fail "P1: logged"
rm -rf "$T"; echo "  P1 new install from a temp copy: ok"

# P2: already installed (an update): left to NEMESIS
T=$(mktemp -d); setup "$T"; : > "$T/installed"; run
grep -q "pm install" "$T/calls" && fail "P2: should not install over an installed app"
grep -q setprop "$T/calls" && fail "P2: should not touch boot_completed"
grep -q "already installed" "$T/state/install.log" || fail "P2: logged"
rm -rf "$T"; echo "  P2 update left to NEMESIS: ok"

# P3: pm refuses: pm's reason logged, boot_completed restored, install goes on
T=$(mktemp -d); setup "$T"; : > "$T/pm_fails"; run
grep -q "INCONSISTENT_CERTIFICATES]); NEMESIS tries" "$T/state/install.log" || fail "P3: pm's reason logged"
[ "$(cat "$T/boot")" = 1 ] || fail "P3: boot_completed back to 1"
rm -rf "$T"; echo "  P3 pm refuses: ok"

# P4: Android not up: nothing done
T=$(mktemp -d); setup "$T"; : > "$T/android_down"; run
grep -q "pm " "$T/calls" 2>/dev/null && fail "P4: no pm while Android is down"
grep -q "isn't up" "$T/state/install.log" || fail "P4: logged"
rm -rf "$T"; echo "  P4 Android down: ok"

# P5: boot_completed already 0 (still booting): left at 0
T=$(mktemp -d); setup "$T"; echo 0 > "$T/boot"; run
grep -q setprop "$T/calls" && fail "P5: boot_completed was 0, leave it"
[ -f "$T/installed" ] || fail "P5: installed"
rm -rf "$T"; echo "  P5 boot_completed already 0: ok"

echo "PASS ($SH)"
