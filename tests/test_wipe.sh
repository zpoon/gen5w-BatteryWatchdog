#!/bin/sh
# Delete all data (app/src/.../Wipe.java): runs the exact command the app sends to the
# root shell against a fake tree with traps (symlinks in and around the folders), and
# checks that nothing outside the app's own files is touched.
# Usage: tests/test_wipe.sh [shell]   (CI also runs it under mksh and mksh R43)
set -e
SH=${1:-sh}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
J=$(mktemp -d); W=$(mktemp -d); trap 'rm -rf "$J" "$W"' EXIT
javac -nowarn -d "$J" "$ROOT/app/src/com/nemesis/batterywatchdog/Wipe.java" 2>&1 | grep -v JAVA_TOOL || true
cat > "$J/WipeCheck.java" <<'J'
package com.nemesis.batterywatchdog;
public class WipeCheck {
    public static void main(String[] a) {
        String[] ok = { "/data/nemesis/state/battery.watchdog", "/data/hmgapi/run/battery-watchdog/watch" };
        String[] no = { "", "/", "/data", "/data/nemesis", "/data/nemesis/state", "/data/nemesis/state/battery.watchdog/",
            "/data/nemesis/state/battery.watchdog/..", "/data/nemesis/state/other", "/data/hmgapi/run/../watch",
            "/data/hmgapi/run//watch", "/data/hmgapi/run/x/watch/..", "/data/hmgapi/run/.x/watch", "/data/hmgapi/run/x y/watch",
            "/system", "/data/local/tmp" };
        int f = 0;
        for (String s : ok) if (!Wipe.allowedState(s)) { System.out.println("FAIL: refused " + s); f++; }
        for (String s : no) if (Wipe.allowedState(s)) { System.out.println("FAIL: accepted '" + s + "'"); f++; }
        if (Wipe.allowedState(null)) { System.out.println("FAIL: accepted null"); f++; }
        System.exit(f);
    }
}
J
javac -nowarn -cp "$J" -d "$J" "$J/WipeCheck.java" 2>&1 | grep -v JAVA_TOOL || true
java -cp "$J" com.nemesis.batterywatchdog.WipeCheck 2>&1 | grep -v JAVA_TOOL || true
java -cp "$J" com.nemesis.batterywatchdog.WipeCheck >/dev/null 2>&1 || { echo "FAIL: allowedState"; exit 1; }
echo "  allowedState: only the two expected folders"

fail() { echo "FAIL: $*"; exit 1; }
cmd() { java -cp "$J" com.nemesis.batterywatchdog.Wipe "$@" 2>/dev/null; }
# a fresh tree: R/state (ours), R/handover (ours), R/log, and things that must survive
tree() {
    R=$W/$1; rm -rf "$R"; mkdir -p "$R/state/sub" "$R/handover" "$R/outside/dir" "$R/stateparent"
    for f in log.csv sessions warn_pct txn status; do echo x > "$R/state/$f"; done
    echo x > "$R/state/.hidden"; echo x > "$R/state/..odd"; echo x > "$R/state/-rf"; echo x > "$R/state/sub/f"
    echo x > "$R/handover/log.csv"; echo keep > "$R/log"
    echo precious > "$R/outside/file"; echo precious > "$R/outside/dir/file"; echo precious > "$R/sibling"
    R=$(cd -P "$R" && pwd -P)
}
intact() { [ "$(cat "$R/outside/file")" = precious ] && [ "$(cat "$R/outside/dir/file")" = precious ] && [ "$(cat "$R/sibling")" = precious ] || fail "$1: something outside was touched"; }

# W1: the normal case: everything ours goes, nothing else
tree w1; "$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ -d "$R/state" ] && [ -z "$(ls -A "$R/state")" ] || fail "W1: state not emptied: $(ls -A "$R/state")"
[ ! -e "$R/handover" ] || fail "W1: handover left"
[ ! -s "$R/log" ] || fail "W1: log not emptied"
[ ! -s "$W/out" ] || fail "W1: printed: $(cat "$W/out")"
intact W1; echo "  W1 normal: state emptied (dotfiles and a file named -rf too), copy and log gone, the rest intact"

# W2: links inside the state folder point outside: the links go, their targets stay
tree w2; ln -s "$R/outside/file" "$R/state/link"; ln -s "$R/outside/dir" "$R/state/dirlink"; ln -s "$R/outside" "$R/handover/up"
"$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ -z "$(ls -A "$R/state")" ] && [ ! -e "$R/handover" ] || fail "W2: not deleted"
intact W2; echo "  W2 symlinks inside: removed, targets intact"

# W3: the state folder itself is a symlink to somewhere else: nothing there is deleted
tree w3; rm -rf "$R/state"; ln -s "$R/outside" "$R/state"
"$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
intact W3; grep -q 'skipped' "$W/out" || fail "W3: no 'skipped' message"
echo "  W3 state folder is a symlink: skipped, target intact"

# W4: a folder on the way to it is a symlink: skipped too
tree w4; mkdir -p "$R/outside/dir/battery"; echo precious > "$R/outside/dir/battery/f"; ln -s "$R/outside/dir" "$R/via"
"$SH" -c "$(cmd "$R/via/battery" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ "$(cat "$R/outside/dir/battery/f")" = precious ] || fail "W4: deleted through a symlinked parent"
intact W4; echo "  W4 symlink on the way: skipped"

# W5: the copy folder is a symlink: the link is removed, the target stays
tree w5; rm -rf "$R/handover"; ln -s "$R/outside" "$R/handover"
"$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ ! -e "$R/handover" ] && [ ! -L "$R/handover" ] || fail "W5: link left"
intact W5; echo "  W5 copy folder is a symlink: link removed, target intact"

# W6: the log is a symlink to another file: not emptied
tree w6; rm "$R/log"; ln -s "$R/sibling" "$R/log"
"$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
intact W6; echo "  W6 log is a symlink: target not emptied"

# W7: nothing there at all: quiet, nothing created
tree w7; rm -rf "$R/state" "$R/handover" "$R/log"
"$SH" -c "$(cmd "$R/state" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ ! -s "$W/out" ] && [ ! -e "$R/state" ] && [ ! -e "$R/handover" ] || fail "W7: $(cat "$W/out")"
intact W7; echo "  W7 nothing there: quiet"

# W8: a path with a space and a quote is quoted, not split
tree w8; mkdir -p "$R/odd dir'x"; echo x > "$R/odd dir'x/f"; mkdir -p "$R/odd"; echo precious > "$R/odd/f"
"$SH" -c "$(cmd "$R/odd dir'x" "$R/handover" "$R/log")" > "$W/out" 2>&1
[ -z "$(ls -A "$R/odd dir'x")" ] && [ "$(cat "$R/odd/f")" = precious ] || fail "W8: quoting"
intact W8; echo "  W8 quoting: only the named folder"
echo "PASS ($SH)"
