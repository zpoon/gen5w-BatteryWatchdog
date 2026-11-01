#!/bin/sh
# Prints the build's version name and code, used to stamp the APK and the service.
#   VERSION file          base version of the next release, e.g. 0.5.0
#   BWD_VERSION_SUFFIX    e.g. -dev.42 for test builds (CI sets it), empty for releases
#   BWD_VERSION_CODE      Android versionCode, must keep increasing (CI: run number)
# Usage: tools/version.sh name | code | pkg
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BASE=$(tr -d ' \n\r' < "$ROOT/VERSION")
case "$1" in
  name) echo "${BASE}${BWD_VERSION_SUFFIX}";;
  code) echo "${BWD_VERSION_CODE:-1}";;
  # NEMESIS package-version: dotted numbers only. Releases: 0.5.1; test builds: 0.5.1.<code>
  pkg)  if [ -n "$BWD_VERSION_SUFFIX" ]; then echo "${BASE}.${BWD_VERSION_CODE:-1}"; else echo "$BASE"; fi;;
  *) echo "usage: $0 name|code|pkg" >&2; exit 2;;
esac
