# Technical notes

Details for contributors and anyone porting Battery Watchdog to another car.
For installing and using it, see the [README](../README.md).

## Data sources

All reads are getters on the telematics service `tm_hscan_service`
(`com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService`), called with
`service call tm_hscan_service <txn>`. Nothing is written to the car.

| txn | getter | type | used for |
|---|---|---|---|
| 137 (0x89) | `getBatSoc` | int, 1 % per unit | 12V state of charge |
| 74 (0x4a) | `remainBatteryPercentage` | float, % | main (HV) battery charge |
| 214 (0xd6) | `getXEV_Bms_GDS_SOH` | int, raw byte | HV battery health index (100 = new) |

Outside temperature comes from the telematics `"outTemperature"` JSON in
logcat (whole °C), the same source
[hkmc-coldstart-heat](https://github.com/eclipseempire/hkmc-coldstart-heat) uses.

Reference probe on a 2023 Ioniq 5 SEL (US), head-unit firmware 260507, car on
and parked:

| txn | reply | decoded |
|---|---|---|
| 137 | `Parcel(00000000 00000053 '....S...')` | 83 %. Logcat at the same time: `TM_HSCANService engine : true/batSoc : 83` |
| 74 | `Parcel(00000000 428d0000 '.......B')` | 70.5 % |
| 214 | `Parcel(00000000 00000064 'd...')` | 100 |
| 211 | `Parcel(00000000 00000000 '........')` | not used |

137 clearly reads a different battery from the HV charge (83 vs 70.5), which
leaves the 12V. Readings later the same evening were 85 %.

The 12V voltage and current aren't exposed by any binder method found so far;
the battery sensor's SOC estimate is the best available signal.

## Firmware verification

Transaction numbers are positions in `ITM_HSCANService`'s method list. They're
stable within a firmware build but can shift between builds, and the same
service has functions that must never be called by accident
(egmp-aa-ev-feat lists `setCanSignalValue`, `reqResetSignals` = 132,
`setValetModeOnOff`, `requestDiag`, `resetDrivingInfo`, charging and climate
requests). So the numbers are never used blindly:

1. **Resolved by name (app).** On launch the app finds the ODEX files that
   mention `ITM_HSCANService` (`grep -l` over `/system/framework`,
   `/system/app`, `/system/priv-app`, `/vendor/framework`), parses them with
   `TxnResolver` (a Java port of hkmc-coldstart-heat's `dexodex.py`), and reads
   the `TRANSACTION_getBatSoc`, `TRANSACTION_remainBatteryPercentage` and
   `TRANSACTION_getXEV_Bms_GDS_SOH` constants from
   `ITM_HSCANService$Stub`. It saves them to `txn` with the firmware id
   (`ro.build.version.incremental`). No binder calls are needed for this.
2. **Hand-verified firmware (app and service).** `package/known_firmware.txt`,
   one line per build with its own numbers:
   `<ro.build.version.incremental> <soc> <hv> <soh>  # car, firmware`
   (`-` for a missing one). Both the service and the app read this one file
   from the installed package. Currently
   `eng.vmadmin.20260507.094910  137  74  214` (2023 Ioniq 5 SEL US, 260507).
3. **Manual override (app).** Advanced → "use built-in numbers…", with a
   warning; saved to `txn` as `source=manual-override` for this firmware only.

The service uses `txn` only if its `fw` matches the running firmware, and
`service list` must show `tm_hscan_service: [com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService]`.
Otherwise it reports `state=unverified` (or `verify=interface_mismatch`),
makes no calls, and rechecks every minute. The app's own reads (SOH box, Read
sensor now, Test run) follow the same rules.

`tests/test_service.sh` checks that an unknown firmware, a `txn` file from a
different firmware, and a wrong interface all result in **zero** binder calls;
`tests/test_resolver.sh` builds an AIDL-shaped fixture into a real DEX with
`dx` and checks the resolver (plain and ODEX-wrapped).

## How the service works

`package/svc/watchdog.sh` runs as a NEMESIS `mode=restart` service
(`battery.watchdog/watch`, root). Gen5W usually suspends rather than reboots
between drives (QuickBoot), so one service process sees many drives.

- Every `poll_s` (15 s) it checks the display state with `dumpsys power`
  (detector from hkmc-factory82).
- A **new drive** (session) starts when the display goes from off to on, when
  the wall clock jumps by `gap_s` (10 min) because the unit was asleep, or when
  the service starts.
- At a session start it waits `settle_s` (30 s), samples, and compares with the
  previous session: low 12V, drop while parked (last sample of the previous
  drive → first sample of this one, with time parked and a per-day rate), and
  whether the previous drive of 20 min or more ended lower than it started.
  Warnings go to the factory toast (`com.hkmc.intent.action.TOAST`) and to
  `warnings`.
- While the display is on it samples every `interval_s` (300 s).

The script is written for the head unit's shell, **mksh R43 (2013)**, and
KitKat's toolbox:

- No `(` inside `${...}` patterns or `case` patterns that R43 can mis-parse.
  An earlier version that matched on `Parcel(` failed with
  `syntax error: '{' unmatched`. The binder reply is split on whitespace instead.
- No `0x`/`16#` arithmetic (hex is converted by hand), no `mv -f`, no `tail`.
- Falls back to uptime if `date +%s` isn't supported.

CI fetches Debian's 2013 build of mksh R43 and runs `tests/test_service.sh`
under it, plus `sh` and a current mksh.

## Files on the head unit

Package: `/data/nemesis/apps/battery.watchdog/`.
Service log: `/data/nemesis/logs/battery.watchdog/watch.log`
(`nemesis log service battery.watchdog/watch`).
NEMESIS's own log: `/data/nemesis/nemesis.log`.

State and settings: `/data/nemesis/state/battery.watchdog/`

| file | contents |
|---|---|
| `log.csv` | `epoch,event,raw_hex,pct10,soh_raw,hv10,temp_c` (`event` = `start`/`sample`). Rotates to `log.old.csv` at `max_lines` (4000) |
| `sessions` | one line per finished drive: `start_epoch minutes start_pct10 end_pct10` |
| `warnings` | every warning raised, timestamped |
| `status` | latest state, read by the app |
| `sess_start`, `last` | first and most recent sample of the current drive |
| one-value files | settings: `enabled`, `warn_pct`, `drop_pct`, `interval_s`, `toast_start`, `decode`, `scale_x10`, and optional `poll_s`, `gap_s`, `settle_s`, `max_lines` |

`pct10` and `hv10` are tenths of a percent (`725` = 72.5 %).

## Porting to another car or firmware

For owners:

1. Install the package and open the app. If Diagnostics shows
   `verification: resolved (…)`, the numbers were found by name and the
   monitor runs. Please still file a
   [firmware report](https://github.com/zpoon/gen5w-BatteryWatchdog/issues/new?template=firmware-report.yml) so the
   compatibility table grows.
2. If it shows **Paused**, check the numbers **offline**, without calling them:
   - put the two files from `research/odex-dump/` on the root of a USB drive,
     insert it with the car on and parked, approve, and wait a few minutes
     (files land in `odex-dump/` on the drive; nothing is sent to the car);
   - on a computer, run `python3 tools/check_firmware.py <drive>/odex-dump`.
   - Paste its output into a firmware report. Don't attach the firmware files.

For maintainers, adding a build:

1. Take the `known_firmware.txt line:` printed by `check_firmware.py` and
   fill in the car and firmware version in the comment.
2. Sanity-check: `getBatSoc` must be present; the interface line must say
   `tm_hscan_service is …ITM_HSCANService`.
3. Append the line to `package/known_firmware.txt`, add a row to the README
   compatibility table, bump the version (`app/AndroidManifest.xml`,
   `VERSION=` in `svc/watchdog.sh`), push, and publish a release. CI runs the
   service and resolver tests on every push.

Builds where the app reports `resolved` don't need a `known_firmware.txt`
entry (the lookup already works), but one doesn't hurt as a fallback.

Only after the numbers are confirmed should anyone run `research/probe-12v/`,
which calls them directly. Its raw replies decode like this: a small number
such as `00000053` is an integer (decode = Integer, scale = 1 %, or 0.5 % if
it reads about double); something like `42b40000` is a float (decode = Float);
`00000000` every time means the firmware doesn't fill it.

## NEMESIS "giving up"

If NEMESIS's log shows `giving up on battery.watchdog/<service>`, it stopped
launching that service after it crashed repeatedly. On the reference car this
persisted across package updates **and** a head-unit reboot, and
`nemesis service start` didn't clear it. Renaming the service (`monitor` →
`watch` in v0.2.6) gave it a clean slate. **Advanced → Test run** runs the
script directly, outside NEMESIS, to tell a script problem from a NEMESIS one.

## Testing

```
tests/test_service.sh            # sh
tests/test_service.sh mksh       # any shell
BT=<build-tools dir with dx> tests/test_resolver.sh   # TxnResolver + check_firmware.py
```

It mocks `service`, `am`, `dumpsys` and `logcat`, runs two drives (83 % → 64 %
with the display off in between), and checks the status file, the log and the
parked-drop toast.
