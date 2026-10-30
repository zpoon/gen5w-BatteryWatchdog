# Battery Watchdog

A [NEMESIS](#requirements) package for Hyundai, Kia and Genesis cars with the
**Gen5W** head unit. It keeps an eye on the **12V battery** and warns you on
the car's screen before a weak battery or a charging problem leaves you
unable to start the car.

On E-GMP cars like the Ioniq 5, a dead 12V battery means the car won't turn
on, even with a full main battery. Battery Watchdog logs the 12V charge level
on every drive and tells you when something looks wrong.

> Unofficial. Not affiliated with Hyundai, Kia, Genesis or HKMC. Use at your
> own risk. Battery Watchdog only **reads** values from the car; it never
> sends commands to it.

## Features

- **12V charge level** at the start of every drive and every 5 minutes while
  the screen is on.
- **Warnings on the head unit screen** when:
  - the 12V is **low** when you start the car (default: below 60 %),
  - it **dropped a lot while parked** (default: 15 points or more),
  - it **didn't recover during your last drive** (a drive of 20+ minutes that
    ended lower than it started).
- **Parked drain:** how long the car sat before this drive, how much the 12V
  changed in that time, and the rate per day.
- **Context:** main battery charge and outside temperature, since cold weather
  lowers 12V readings.
- **HV battery health (SOH)** in a color-coded box (display only).
- **History chart** of recent readings, with the start of each drive marked.
- **Built-in diagnostics** for troubleshooting.

All data stays on the head unit.

## Compatibility

| Car | Head-unit firmware | Status |
|---|---|---|
| 2023 Hyundai Ioniq 5 SEL (US) | 260507 | Working |

Other Gen5W cars may work if their firmware provides the same values. Run the
probe described in [docs/TECHNICAL.md](docs/TECHNICAL.md#porting-to-another-car-or-firmware)
to check, and please open an issue with the result so the table can grow.

## Requirements

- A Gen5W head unit with **NEMESIS** installed. NEMESIS is the community
  package manager for these head units; this project doesn't install it.
- A USB drive formatted FAT32 or exFAT.

## Install

1. Download `gen5w-BatteryWatchdog-USB.zip` from the
   [latest release](../../releases/latest).
   (Development builds are attached to each run on the
   [Actions](../../actions) page as a zip containing the same file.)
2. Extract it to the **root** of the USB drive. You should see `NEMESIS.data`
   and a `packages` folder at the top level.
3. With the car on and **parked**, insert the drive and approve the install.
4. Open **Battery Watchdog** from the app list. The first reading appears
   within about a minute.

To update, install a newer version the same way, over the top. Settings and
history are kept.

## Using it

The screen has three columns:

- **Left:** the 12V charge (green, or amber below your warning level), how
  long the car was parked and how the 12V changed meanwhile, main battery
  charge, outside temperature, the latest warning, and the HV battery health
  box.
- **Middle:** recent readings. White dots mark the start of each drive and the
  amber line is your warning level. A healthy pattern is a small dip at each
  start and a recovery during the drive.
- **Right:** settings.

| Setting | Default | What it does |
|---|---|---|
| Monitor | On | Turns background monitoring on or off |
| Warn below | 60 % | Warn if the 12V is below this at the start of a drive |
| Warn if it drops | 15 pts | Warn if it fell this much while parked |
| Pop-up every start | Warnings only | Also show the reading on every start |
| Advanced → Sample every | 5 min | How often to read while the screen is on |

Tap **Save** after changing settings; they apply from the next drive.

**HV battery health** colors: green 90 and above, amber 75–89, red below 75.
It's the car's health index for the main battery pack (100 = new), not a
capacity measurement, and it never triggers warnings.

### How warnings appear

When you start the car and the screen wakes up, Battery Watchdog takes a
reading and compares it with the end of your previous drive. If a warning
applies, it shows the car's own pop-up message over whatever is on screen,
for example:

> 12V dropped 16.0 pts while parked: 84.0% -> 68.0%, outside 34F

Warnings also appear in amber in the app. Nothing is sent to your phone or the
instrument cluster.

### Reading the numbers

- The 12V figure is the car's battery-sensor estimate, not a voltage. It can
  jump after the car has slept or recalibrated, so watch the trend rather
  than a single reading.
- The car periodically recharges the 12V from the main battery while parked,
  so a reading that **rose** while parked is normal.
- Cold lowers 12V readings. A drop on a freezing night is less worrying than
  the same drop on a mild one.

## Troubleshooting

The app shows **Waiting…** and no reading:

1. Open **Advanced → Diagnostics**. The first line is the service status; it
   should end in `running`. Below it, the service log is listed newest first.
2. **Advanced → Test run (10 s)** runs the monitor once, directly. If it shows
   a 12V reading and `exit code 0`, the monitor itself works and the problem
   is with how NEMESIS is starting it.
3. **Advanced → Restart monitor** stops and starts the background service.

If Diagnostics or NEMESIS's own log shows `giving up on battery.watchdog/...`,
see [docs/TECHNICAL.md](docs/TECHNICAL.md#nemesis-giving-up).

When reporting a problem, include your car, model year, head-unit firmware
and a photo of the top of Diagnostics.

## Uninstall

Remove `battery.watchdog` from the NEMESIS manager (or run
`nemesis uninstall battery.watchdog`). Settings and history are stored in
`/data/nemesis/state/battery.watchdog/` and may remain after uninstalling.

## Building from source

Every push to `main` is built and tested by GitHub Actions
([`.github/workflows/build.yml`](.github/workflows/build.yml)) with the
official Android SDK tools, and pushing a `v*` tag publishes a release.

To build locally you need a JDK and an Android SDK with build-tools 30.0.3
(the last version with `dx`, which the KitKat-based head unit needs) and
platform 30:

```
ANDROID_SDK=~/Android/Sdk tools/build_apk.sh
cp app/batterywatchdog.apk package/app/batterywatchdog.apk
tools/make_package.sh            # -> dist/gen5w-BatteryWatchdog-USB.zip
tests/test_service.sh            # service test with mocked car services
```

Project layout:

```
package/                 the NEMESIS package
  NEMESIS.data           app + background service (battery.watchdog/watch)
  svc/watchdog.sh        the monitor (root, read-only)
  scripts/post.sh        default settings; restarts the service after install
app/                     the on-screen app (Java, no resources)
research/probe-12v/      USB probe for checking a new car or firmware
tests/                   service test with mocked car services
tools/                   APK build and packaging scripts
docs/TECHNICAL.md        data sources, internals, porting notes
```

## Credits

- Build and packaging tools, and the app's settings pattern, are adapted from
  [hkmc-coldstart-heat](https://github.com/eclipseempire/hkmc-coldstart-heat) (MIT).
- Service names, transaction codes and the pop-up broadcast come from the
  research in [egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat).
- Display wake detection is based on
  [hkmc-factory82](https://github.com/armandodollia/nemesis-package-builds).

## License

[MIT](LICENSE)
