#!/bin/sh
# Prints the build's version name and code, used to stamp the APK and the service.
#   VERSION file          base version of the next release, e.g. 0.5.0
#   BWD_VERSION_SUFFIX    e.g. -dev.42 for test builds (CI sets it), empty for releases
#   BWD_VERSION_CODE      the build number (CI: its run number)
#   BWD_PRERELEASE        1 for a GitHub pre-release (a beta), which ranks below its release
# Usage: tools/version.sh name | code | pkg
#
# Android versionCode: (major*10000 + minor*100 + patch) * 1000, plus 999 for a
# release, or the build number (1-998) for anything else. So a release outranks
# every test build and beta of its own version, and the next version outranks
# both: pm refuses to install a lower code over a higher one ("downgrade"). It
# used to be the run number alone, so a release (0.7.0 was run 89) ranked below
# every test build made after it. Each part of VERSION must be under 100.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE=$(tr -d ' \n\r' < "$ROOT/VERSION")
case "$1" in
  name) echo "${BASE}${BWD_VERSION_SUFFIX}";;
  code)
    IFS=. read -r MA MI PA <<X
$BASE
X
    for n in "$MA" "$MI" "${PA:-0}"; do
      case "$n" in ''|*[!0-9]*) echo "VERSION must be numbers, e.g. 0.8.0 (got $BASE)" >&2; exit 2;; esac
      [ "$n" -lt 100 ] || { echo "each part of VERSION must be under 100 (got $BASE)" >&2; exit 2; }
    done
    if [ -z "$BWD_VERSION_SUFFIX" ] && [ "$BWD_PRERELEASE" != 1 ]; then
      b=999
    else
      b=$(( ${BWD_VERSION_CODE:-1} % 999 )); [ "$b" -gt 0 ] || b=1
    fi
    echo $(( ((MA * 10000) + (MI * 100) + ${PA:-0}) * 1000 + b ));;
  # NEMESIS package-version: dotted numbers only. Releases: 0.5.1; test builds: 0.5.1.<code>
  pkg)  if [ -n "$BWD_VERSION_SUFFIX" ]; then echo "${BASE}.${BWD_VERSION_CODE:-1}"; else echo "$BASE"; fi;;
  *) echo "usage: $0 name|code|pkg" >&2; exit 2;;
esac
