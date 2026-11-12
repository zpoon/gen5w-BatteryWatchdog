#!/system/bin/sh
# Refuse to install while Battery Watchdog is installed as an HMG-API mod and
# turned on: both use the same app, so two copies would fight over it, and
# removing one would take the app away from the other. Remove the mod first,
# or keep using it. (A mod that's turned off doesn't count: then this package
# installs, and the monitor steps aside again if the mod is turned back on.)
MODS=${BWD_HMGAPI_MODS:-/data/hmgapi/mods}
if [ -f "$MODS/battery-watchdog/mod.json" ] && [ ! -f "$MODS/battery-watchdog/.disabled" ]; then
    echo "Battery Watchdog is already installed as an HMG-API mod on this head unit."
    echo "Keep using the mod (HMG-API updates it), or remove it in the HMG-API app before installing this package."
    exit 1
fi
[ -x /system/bin/hmgapid ] && echo "HMG-API is installed: the Battery Watchdog HMG-API mod is the recommended way to run it with HMG-API. Installing the NEMESIS package anyway."
exit 0
