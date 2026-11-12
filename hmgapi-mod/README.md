# Battery Watchdog as an HMG-API mod (work in progress)

A second way to install Battery Watchdog, for cars running
[HMG-API](https://github.com/VysseCodes/HMG-API). The NEMESIS package in
`package/` stays as it is. Both are built from this repo.

With HMG-API, the mod reads the car through named readings
(`battery12v.soc`, `hv.soc`, `hv.health`, `hv.ready`, `charging`,
`outside_temp`), mapped per car by HMG-API. It needs no transaction numbers, no
firmware list, no temperature hunting and no root plumbing.

## One or the other

The mod and the NEMESIS package use the same app (`com.nemesis.batterywatchdog`,
same signing key), so a head unit runs only one of them:

- The package's install check (`package/scripts/pre.sh`) refuses while the mod
  is installed and turned on (`/data/hmgapi/mods/battery-watchdog/mod.json`
  without `.disabled`), and says why. With HMG-API present but no mod, it
  installs and recommends the mod.
- The package's monitor checks the same thing on every loop: while the mod is
  on, it makes no calls, logs nothing and reports `state=other_install`, which
  the app shows as "Idle" with what to do. If the mod is removed or turned off,
  it monitors again. The mod's copy of the script never checks.
- Nothing stops HMG-API installing the mod over the package, so the package
  side carries the whole guard. Removing either one uninstalls the shared app,
  so owners switching should uninstall the one they have first.
- Tests G1-G3 in `tests/test_service.sh`.

## Files

- `mod.json`: the mod's declaration. It started from HMG-API's
  `tools/mod-draft.py`, then was corrected: version, description, licence,
  readings, USB folder, service user and start.
- `DRAFT-NOTES.txt`: the draft tool's notes on every line of the NEMESIS package
  that needs changing for HMG-API.

## Porting checklist

1. [x] Draft `mod.json` (`tools/mod-draft.py package --out …`).
2. [x] **Service:** one shared `package/svc/watchdog.sh` with an HMG-API mode
       (on when `$HMGAPI_MOD` is set and `hmgapid` is on the PATH; the mod
       package copies it to `svc/watchdog.sh`). In that mode it:
   - [x] reads `hmgapid vehicle battery12v.soc hv.soc hv.health hv.ready charging outside_temp`
         (one call per sample) instead of `service call tm_hscan_service …`
   - [x] skips the firmware check, `known_firmware.txt`, the telematics-service
         wait and the temperature hunting; it counts as verified once
         `battery12v.soc` gives a number (`verify=hmgapi`), otherwise it stays
         unverified (`hmgapi_no_reading`) and retries every minute
   - [x] detects drives by `hv.ready` (car on); when HMG-API doesn't know it,
         falls back to the screen check
   - [x] keeps state in `$HMGAPI_DATA`; output goes to HMG-API's service log
   - [x] writes the same `log.csv` columns (`outside_temp` rounded toward zero
         to whole °C), so `tools/analyze.py`, export and share zips keep working
   - [x] tests HA1–HA4 in `tests/test_service.sh` with a mocked `hmgapid`
   - [ ] check on the car: `hv.ready` is 1 while the car is on (READY), and
         `battery12v.soc` matches the NEMESIS version's reading
3. [x] **App** (same APK, two modes, `MainActivity.detectMode()`):
   - [x] detects HMG-API with `hmgapid whoami` (it answers the mod's id only to
         a mod's own app) and then works on the service's folder,
         `/data/hmgapi/run/<mod>/watch`, as itself: shell commands run as the
         app and files are written directly, with no NEMESIS root channel
   - [x] hides the firmware verification (and its override button) and the
         test run; "Paused" says HMG-API has no 12V reading for this car yet
   - [x] Read sensor now, Restart monitor and Diagnostics go through `hmgapid`
         (`vehicle`, `service-restart watch`, `services`, `service-log watch`, `usb`)
   - [x] Export tries HMG-API's `BatteryWatchdog` folder on a stick first, then the
         drive's top level
   - [ ] check on the car: whether an ordinary app may write to the stick at
         all (Android 4.4 lets only system apps write to removable drives; the
         NEMESIS version writes as root). If not, ask VysseCodes for a way to
         copy into a mod's USB folder.
   - [ ] check on the car: whether the service's toasts (`am broadcast`) and its
         screen fallback (`dumpsys power`) work as the app's user
   - [ ] later: react to `hmgapi.intent.action.USB_FOLDER` (offer Export when a
         stick with the folder goes in), once writing is confirmed
4. [x] **Extras HMG-API makes possible:**
   - [x] app-menu tile in the car's own style: `icons/` (day, night and the two
         greyed-out pictures), made with HMG-API's `tools/mkicon.sh` from the
         battery glyph (`python3 tools/make_icon.py --glyph glyph.png`), listed
         under `provides.launcher` in `mod.json`
   - [x] split-screen panel page "12V battery" (`provides.panel`,
         `PanelWidget`): the 12V %, main battery, outside temperature, the
         parked drop before this drive and any warning, from the service's status
         file. The service refreshes it after each reading; a tap opens the app.
   - [x] the service passes HMG-API's install-time script check
         (`tools/check-scripts.sh`); a few lines were reworded for its
         line-by-line reading, with no change in behaviour (step 5 also fixes
         the check itself)
   - [ ] check on the car: the tile pictures in the app menu (day and night),
         and the page in the split-screen editor and panel
5. [ ] **Upstream (HMG-API PR):** branch `battery12v-sensor-readings` on
       zpoon/HMG-API, for a PR to VysseCodes/HMG-API.
   - [x] readings `battery12v.sensor_state`, `battery12v.sensor_invalid`,
         `battery12v.sensor_error` (`getBATSNSRState`/`Invalid`/`Error`) and
         `battery12v.saver` (`get_BAT_AuxBattOpt`), raw; hook points in the
         catalogue, listed as missing in every car's map until VysseCodes maps them
         from stock files (`tools/map-car.py`) or an owner runs `engine map-car`
         on the car
   - [x] script check: continued lines, `a|b)` case patterns and nested
         parentheses in `$(( ))` no longer show as missing commands; new test
         `core/runtime/scriptcheck-test.sh`
   - [x] the service reads the flags once `mod.json` lists them (columns 8–11);
         until an HMG-API release has them, `mod.json` must not list them, or
         HMG-API refuses the mod. `charging` already fills column 12 (it's the
         same `getChargingStatus` number).
   - [ ] after the PR is in an HMG-API release: add the four names to
         `"vehicle": {"read": …}` in `mod.json`
6. [ ] **Package:** `tools/make_hmgapi_mod.sh <HMG-API checkout> <key>` stages
       the mod (mod.json with the version stamped, `svc/watchdog.sh`, the APK,
       icons, LICENSE), checks its scripts, signs it with HMG-API's
       `tools/mkmodpkg.sh` and wraps it with `tools/mknemesismod.sh`:
       `dist/battery-watchdog-<v>.zip` (the mod),
       `battery-watchdog-<v>-nemesis.zip` (USB stick) and
       `battery-watchdog-<v>-nemesis-package.zip` (NEMESIS package
       `hmgapi.battery-watchdog`, needs HMG-API). Test builds are
       `<VERSION>-beta.<run>`, which NEMESIS sorts below the release.
   - [x] mod-signing key made (mod UUID `49624245-2eef-8b12-b385-823361d61309`);
         kept by the maintainer, backed up, in the `HMGAPI_MOD_KEY` secret
   - [x] CI builds the mod with HMG-API's tools pinned to a commit; skipped
         (with a notice) without the secret. Not attached to releases yet.
   - [ ] catalogue PR (`tools/catalog.py add` in HMG-API) to be listed as a trusted author
   - [ ] the NEMESIS Battery Watchdog (`battery.watchdog`) and this mod must not
         both be installed: both install the same app. Remove the NEMESIS one
         first. Its history stays in `/data/nemesis/state/battery.watchdog`
         (Export to USB first to keep a copy); it isn't moved over yet.
7. [ ] **Test** on a car with HMG-API (the maintainer's Ioniq 5 US 260507, which
       HMG-API lists as "waiting for a check on a car").

## Open questions

- **`"user": "app"`: answered.** HMG-API runs the service as the first app in
  `"apps"` (`ModRuntime.java`: `app` → `app:<package>`). The service and the
  app share one Android user, so the app reads the service's files directly,
  with no root.
- **Licensing:** HMG-API's client library (`hmgapi-client.jar`) is AGPL-3.0.
  Battery Watchdog is MIT, so the mod talks to HMG-API only through the
  `hmgapid` command (from the service and through a shell), not by bundling
  the jar. Ask VysseCodes whether the client is meant to carry a linking
  exception.
- **Version:** the mod keeps the app's version (`VERSION`). Bump `mod.json`
  with each release.
