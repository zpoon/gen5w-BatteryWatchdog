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

1. Run the probe in `research/probe-12v/`: copy its `NEMESIS.data` and
   `NEMESIS.sh` to the root of a USB drive, insert it with the car on and
   parked, and approve. After about 3 minutes, `PROBE-12V.txt` on the drive has
   the raw replies.
2. Check `txn 137`:
   - a small number such as `00000053` is an integer: decode = Integer, and
     scale = 1 % (or 0.5 % if it reads about double the real value);
   - something like `42b40000` is a float: decode = Float;
   - `00000000` every time, or a non-zero first word, means this firmware
     doesn't provide it.
3. Set those under **Advanced** in the app, and please open an issue with your
   car, firmware and probe output so it can go in the compatibility table.

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
```

It mocks `service`, `am`, `dumpsys` and `logcat`, runs two drives (83 % → 64 %
with the display off in between), and checks the status file, the log and the
parked-drop toast.
