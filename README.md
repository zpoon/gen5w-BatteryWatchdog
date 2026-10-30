# Battery Watchdog (battery.watchdog) — NEMESIS package for Gen5W

Logs the 12V battery's state of charge every time the car starts and every
few minutes while it's on. It warns with the factory toast when:

- the 12V is **below a threshold** at startup (default 60 %)
- it **dropped a lot while parked** since the end of the last drive (default 15+ points)
- it **didn't recover during the last drive** (a drive of 20+ min that ended lower than it started)

The aim is to catch a weak 12V battery or a failing ICCU/LDC before the car
won't start.

**Read-only.** The service only calls getters on the telematics hscan service.
It never writes to the car.

> **Status: data source checked on one car; package not yet run on a car.**
> Probe on a **2023 Ioniq 5 SEL (US), head-unit firmware 260507**, car on and parked:
>
> | txn | getter | reply | meaning |
> |---|---|---|---|
> | 137 (0x89) | `getBatSoc` | int `0x53` = **83** | 12V SOC, 1 % per unit. Logcat: `TM_HSCANService engine : true/batSoc : 83` |
> | 74 (0x4a) | `remainBatteryPercentage` | float `0x428d0000` = **70.5** | HV (traction) SOC, 0.5 % steps |
> | 214 (0xd6) | SOH | int `0x64` = 100 | HV pack health index |
> | 211 (0xd3) | BatPwrSum | 0 | not used |
>
> 137 is an **integer** and is clearly a different battery from the 70.5 % HV
> SOC, so the defaults are now decode = Integer, scale = 1 %. Values were steady
> over 3 minutes. That's normal for a battery-sensor SOC, but it means the
> "should rise after start" check hasn't been seen yet.

## Layout

```
research/probe-12v/     USB probe (type=execute): samples 0x89 / 0xd6 / 0x4a / 0xd3 for ~3 min
package/                the NEMESIS package
  NEMESIS.data          app + background service
  svc/watchdog.sh         the monitor service (root, read-only)
  scripts/post.sh       seeds default settings
  app/batterywatchdog.apk        (built, copy here)
app/                    settings / history app (Java, no resources, built with dx)
tools/build_apk.sh      javac -> dx (DEX 035) -> aapt -> v1 sign   (from hkmc-coldstart-heat)
tools/make_package.sh   package zip + USB install bundle            (from hkmc-coldstart-heat)
```

## 1. Verify the data source (do this first)

1. Copy `research/probe-12v/NEMESIS.data` and `NEMESIS.sh` to the root of a USB drive.
2. Car on, parked: insert the drive and approve. Leave it plugged in ~3 minutes.
3. Open `PROBE-12V.txt`. Each `txn 137:` line looks like `Result: Parcel(00000000 XXXXXXXX ...)`:
   - `XXXXXXXX` is a small number (e.g. `0000005a` = 90): it's an **int**. If it
     matches the 12V % you'd expect, set decode = Integer, scale = 1 %. If it's
     about double that, use scale = 0.5 %.
   - It starts with `42…` / `41…` (e.g. `42b40000` = 90.0): it's a **float**. Set decode = Float.
   - It's `00000000` every time, or the first word isn't `00000000`: this
     firmware doesn't fill it. Don't install; the monitor would have nothing to show.
4. If you can, compare against a known 12V reading (OBD scanner / Car Scanner
   app). The value should go **up a few points** in the minutes after the car
   turns on (the LDC starts charging). That's a good sign it's the 12V and not
   something else.

"Auto" decode treats anything ≥ `0x30000000` as a float and anything smaller as
an int. That works for both cases, but it's better to set the type explicitly
once the probe shows which it is.

## 2. Build

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`)
with the official Android SDK tools. Download the `gen5w-BatteryWatchdog-USB.zip`
artifact from the run. Pushing a `v*` tag also attaches the files to a GitHub release.

To build locally:

Needs a JDK and an Android SDK with build-tools that still ship `dx` (e.g. 30.0.3).

```
ANDROID_SDK=~/Android/Sdk tools/build_apk.sh
cp app/batterywatchdog.apk package/app/batterywatchdog.apk
tools/make_package.sh
```

Extract `dist/gen5w-BatteryWatchdog-USB.zip` to a FAT32/exFAT USB root and insert it in the car to install.

## 3. Use

The **Battery Watchdog** app is laid out for the 1920×720 Gen5W screen, in
three columns with no page scrolling:

- **Left:** the latest 12V % (green, or amber below the warning level), the
  current warning, and a color-coded **HV battery health (SOH)** box.
- **Middle:** a full-height chart of recent samples. Dots mark the start of
  each drive, and the amber line is the warning level.
- **Right:** warning settings. Under **Advanced**: sampling interval, decode,
  scale, **Read sensor now** (live `getBatSoc` value and raw reply),
  **Diagnostics**, **Restart monitor**, **Test run (10 s)** and **Clear history**.

The screen refreshes every 30 s while it's open. Settings take effect on the
next start of the car.

State lives in `/data/nemesis/state/battery.watchdog/`:

| file | contents |
|---|---|
| `log.csv` | `epoch,event(start/sample),raw_hex,pct10,soh_raw` — rotates to `log.old.csv` at `max_lines` |
| `sessions` | one line per finished drive: `start_epoch minutes start_pct10 end_pct10` |
| `warnings` | every warning raised, timestamped |
| `status` | latest state for the app |
| `sess_start`, `last` | start and most recent sample of the current session |

`pct10` is tenths of a percent (`725` = 72.5 %).

## Notes / limits

- Gen5W usually doesn't reboot between drives (QuickBoot/suspend), so the
  service runs as `mode=restart` and starts a new session (one drive) when the
  display wakes up, or when the wall clock jumps by `gap_s` (default 10 min)
  because the unit was asleep. The "end" of a drive is the last sample, so it
  can be up to `interval_s` before you turned the car off.
- Service output: `nemesis log service battery.watchdog/watch`, or the app's
  **Advanced → Diagnostics**.
- The IBS (battery sensor) SOC estimate can jump after the car sleeps or
  re-learns. Treat a single odd reading as noise and watch the trend.
- SOH (`0xd6`) is the **HV pack's** health index (100 = new). The app reads it
  live each refresh and colors the box green ≥ 90, amber 75–89, red < 75. It
  isn't monitored and never triggers a warning.
- Desktop test of the service script with mocked `service`/`am`:
  `BWD_TEST=1 BWD_TEST_PATH=<mock bin> BWD_STATE=<tmp dir> sh package/svc/watchdog.sh`

## Credits

Build/packaging tools and the app's root-config pattern are adapted from
[hkmc-coldstart-heat](https://github.com/eclipseempire/hkmc-coldstart-heat) (MIT).
The service names, transaction codes and toast broadcast come from
[egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat).
Unofficial. Not affiliated with Hyundai, Kia, Genesis or HKMC.
