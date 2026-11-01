#!/usr/bin/env python3
"""
analyze.py - offline analysis of Battery Watchdog exports, for developing
12V health heuristics before they go into the app.

    python3 tools/analyze.py <export folder> [--csv features.csv] [--plot report.png]
    python3 tools/analyze.py <export folder> --share data.zip

<export folder> is a BatteryWatchdog-export-... folder from Advanced -> Export
to USB (it reads raw/log.csv and raw/log.old.csv), or any folder holding
log.csv. Pure Python 3; --plot needs matplotlib.

--share writes a zip for a data submission: only the battery log, drives and
warnings, with every date moved back by the same random number of whole weeks
(time of day and day of week are kept, the real dates are not), plus the
firmware and app version.

What it computes, per drive and per parked period, then compares with this
car's own normal (median and MAD, so one odd reading doesn't move it):

  drives   start/end 12V, length, charge acceptance (rise per 10 min over the
           first 15 min, given the start level), ceiling on drives >= 30 min
  parks    hours parked, 12V change, drain per day, recharges (rises while
           parked), main battery and outside temperature at the next start
  trends   rolling start level, EWMA of acceptance / ceiling / drain, so slow
           drifts show up

and prints a status (Normal / Watch / Check battery) with the reasons. The
thresholds are starting points to tune on real data, not validated limits.
"""
import argparse
import csv
import math
import io
import os
import random
import statistics
import zipfile
import sys
import time

MIN_OFF_S = 600          # a stop shorter than this continues the same drive (as the app)
EARLY_MIN = 15           # charge-acceptance window
CEILING_MIN = 30         # drives at least this long count for the ceiling
BASELINE_DAYS = 14       # learn "normal" from the first two weeks of data
JUMP_PTS = 8.0           # a 12V change this big between samples is a sensor jump, not a trend


# ---------------------------------------------------------------- loading
def load_rows(folder):
    """[(epoch, event, pct, hv_pct, temp_c)] sorted, from raw/log*.csv or log*.csv."""
    rows = []
    for sub in ("raw", "."):
        for name in ("log.old.csv", "log.csv"):
            p = os.path.join(folder, sub, name)
            if not os.path.exists(p):
                continue
            with open(p, newline="") as f:
                for r in csv.reader(f):
                    if len(r) < 4:
                        continue
                    try:
                        t, ev, p10 = int(r[0]), r[1], int(r[3])
                    except ValueError:
                        continue
                    if t <= 0 or p10 < 0:
                        continue
                    hv = int(r[5]) / 10 if len(r) > 5 and r[5].strip().lstrip("-").isdigit() else None
                    tc = int(r[6]) if len(r) > 6 and r[6].strip().lstrip("-").isdigit() else None
                    rows.append((t, ev, p10 / 10, hv, tc))
        if rows:
            break
    rows.sort(key=lambda x: x[0])
    return rows


def split_drives(rows):
    """Drives as lists of rows; a 'start' after a gap >= MIN_OFF_S begins a new one."""
    drives = []
    for r in rows:
        if r[1] == "start" and (not drives or r[0] - drives[-1][-1][0] >= MIN_OFF_S):
            drives.append([r])
        elif drives:
            drives[-1].append(r)
        else:
            drives.append([r])
    return drives


# ---------------------------------------------------------------- features
def drive_features(d):
    t0, p0 = d[0][0], d[0][2]
    t1, p1 = d[-1][0], d[-1][2]
    mins = (t1 - t0) / 60
    # charge acceptance: best linear rise over the first EARLY_MIN minutes, per 10 min
    early = [(r[0], r[2]) for r in d if r[0] - t0 <= EARLY_MIN * 60]
    acc = None
    if len(early) >= 3 and early[-1][0] - t0 >= 5 * 60:
        xs = [(t - t0) / 600 for t, _ in early]
        ys = [p for _, p in early]
        mx, my = statistics.mean(xs), statistics.mean(ys)
        sxx = sum((x - mx) ** 2 for x in xs)
        if sxx > 0:
            acc = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    ceiling = max(r[2] for r in d) if mins >= CEILING_MIN else None
    jumps = sum(1 for a, b in zip(d, d[1:]) if abs(b[2] - a[2]) >= JUMP_PTS)
    return {"start": t0, "end": t1, "minutes": round(mins, 1), "v_start": p0, "v_end": p1,
            "change": round(p1 - p0, 1), "acceptance": None if acc is None else round(acc, 2),
            "ceiling": ceiling, "jumps": jumps, "hv": d[0][3], "temp_c": d[0][4]}


def park_features(prev, cur):
    hrs = (cur["start"] - prev["end"]) / 3600
    change = cur["v_start"] - prev["v_end"]
    return {"start": prev["end"], "hours": round(hrs, 2), "change": round(change, 1),
            "drain_per_day": round(-change / hrs * 24, 2) if hrs >= 1 else None,
            "recharged": change >= 1.0,
            "hv_low": cur["hv"] is not None and cur["hv"] < 20,
            "temp_c": cur["temp_c"]}


# ---------------------------------------------------------------- baselines
def robust(values):
    v = [x for x in values if x is not None]
    if len(v) < 3:
        return None
    med = statistics.median(v)
    mad = statistics.median([abs(x - med) for x in v]) or 0.5   # floor: SOC is in whole %
    return med, mad


def z(x, base):
    if x is None or base is None:
        return None
    return (x - base[0]) / (1.4826 * base[1])


def ewma(values, alpha=0.3):
    out, cur = [], None
    for v in values:
        if v is None:
            out.append(cur)
            continue
        cur = v if cur is None else alpha * v + (1 - alpha) * cur
        out.append(cur)
    return out


# ---------------------------------------------------------------- verdict
def assess(drives, parks):
    reasons, level = [], 0          # 0 normal, 1 watch, 2 check
    if not drives:
        return "No data", ["no drives in the export"]
    t_base = drives[0]["start"] + BASELINE_DAYS * 86400
    base_d = [d for d in drives if d["start"] < t_base] or drives
    base_p = [p for p in parks if p["start"] < t_base] or parks
    recent_d = [d for d in drives if d["start"] >= drives[-1]["start"] - 7 * 86400]
    recent_p = [p for p in parks if p["start"] >= drives[-1]["start"] - 7 * 86400]

    def bump(n, why):
        nonlocal level
        level = max(level, n)
        reasons.append(why)

    # 1. not charging while driving (ICCU / LDC): the app already warns on this
    bad = [d for d in recent_d if d["minutes"] >= 20 and d["change"] < 0 and d["jumps"] == 0]
    if len(bad) >= 2:
        bump(2, f"{len(bad)} drives of 20+ min in the last week ended lower than they started")

    # 2. charge acceptance vs this car's normal
    b = robust([d["acceptance"] for d in base_d])
    acc_recent = [d["acceptance"] for d in recent_d if d["acceptance"] is not None]
    if b and len(acc_recent) >= 3:
        zr = z(statistics.median(acc_recent), b)
        if zr is not None and zr < -3:
            bump(2, f"12V recovers much more slowly at the start of drives (median {statistics.median(acc_recent):+.2f} pts/10 min vs usual {b[0]:+.2f})")
        elif zr is not None and zr < -2:
            bump(1, f"12V recovers more slowly at the start of drives ({statistics.median(acc_recent):+.2f} vs usual {b[0]:+.2f} pts/10 min)")

    # 3. ceiling on long drives
    b = robust([d["ceiling"] for d in base_d])
    ceil_recent = [d["ceiling"] for d in recent_d if d["ceiling"] is not None]
    if b and len(ceil_recent) >= 2:
        drop = b[0] - statistics.median(ceil_recent)
        if drop >= 6:
            bump(2, f"the 12V tops out {drop:.0f} pts lower on long drives than it used to ({statistics.median(ceil_recent):.0f}% vs {b[0]:.0f}%)")
        elif drop >= 3:
            bump(1, f"the 12V tops out {drop:.0f} pts lower on long drives ({statistics.median(ceil_recent):.0f}% vs {b[0]:.0f}%)")

    # 4. parked drain (ignoring parks where the main battery was below 20 %)
    b = robust([p["drain_per_day"] for p in base_p if not p["hv_low"]])
    dr = [p["drain_per_day"] for p in recent_p if p["drain_per_day"] is not None and not p["hv_low"]]
    if b and len(dr) >= 2:
        zr = z(statistics.median(dr), b)
        if zr is not None and zr > 3 and statistics.median(dr) > 5:
            bump(2, f"parked drain is high: {statistics.median(dr):.1f} pts/day vs usual {b[0]:.1f}")
        elif zr is not None and zr > 2:
            bump(1, f"parked drain is up: {statistics.median(dr):.1f} pts/day vs usual {b[0]:.1f}")

    # 5. recharges while parked, per day parked
    def rate(ps):
        hrs = sum(p["hours"] for p in ps if p["hours"] >= 1)
        return (sum(1 for p in ps if p["recharged"]) / (hrs / 24)) if hrs >= 24 else None
    rb, rr = rate(base_p), rate(recent_p)
    if rb is not None and rr is not None and rr >= max(0.5, 2 * rb):
        bump(1, f"the car is recharging the 12V more often while parked ({rr:.2f}/day vs {rb:.2f}/day)")

    # 6. start level trend
    b = robust([d["v_start"] for d in base_d])
    st = [d["v_start"] for d in recent_d]
    if b and len(st) >= 3 and b[0] - statistics.median(st) >= 8:
        bump(1, f"drives are starting lower than usual ({statistics.median(st):.0f}% vs {b[0]:.0f}%)")

    jumps = sum(d["jumps"] for d in recent_d)
    if jumps:
        reasons.append(f"note: {jumps} sudden jump(s) in the last week, likely the battery sensor recalibrating; excluded where possible")
    if level == 0 and not [r for r in reasons if not r.startswith("note")]:
        reasons.insert(0, "nothing outside this car's normal range")
    return ["Normal", "Watch", "Check battery"][level], reasons


# ---------------------------------------------------------------- sharing
SHARE_FILES = ("log.old.csv", "log.csv", "sessions", "warnings", "interval_s", "warn_pct", "drop_pct")


def _shift_line(line, shift, sep):
    """Move the epoch in the first field of a log/sessions/warnings line."""
    head, _, rest = line.partition(sep)
    try:
        t = int(head)
    except ValueError:
        return line
    return f"{t - shift}{sep}{rest}" if t > 0 else line


def make_share(folder, out, weeks=None):
    raw = os.path.join(folder, "raw") if os.path.isdir(os.path.join(folder, "raw")) else folder
    weeks = weeks if weeks is not None else random.SystemRandom().randint(8, 160)
    shift = weeks * 7 * 86400
    info = [f"dates shifted back by an undisclosed number of whole weeks"]
    diag = os.path.join(folder, "diagnostics.txt")
    if os.path.exists(diag):
        for l in open(diag, encoding="utf-8", errors="replace"):
            if l.startswith(("app:", "verification:", "shell:")):
                info.append(l.rstrip("\n"))
    n = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in SHARE_FILES:
            p = os.path.join(raw, name)
            if not os.path.exists(p):
                continue
            lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
            if name in ("log.csv", "log.old.csv"):
                lines = [_shift_line(l, shift, ",") for l in lines]
                n += len(lines)
            elif name in ("sessions", "warnings"):
                lines = [_shift_line(l.strip(), shift, " ") for l in lines]
            z.writestr("raw/" + name, "\n".join(lines) + "\n")
        z.writestr("info.txt", "\n".join(info) + "\n")
    return n


# ---------------------------------------------------------------- output
def fmt_t(t):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(t))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("folder")
    ap.add_argument("--csv", help="write per-drive features to this CSV")
    ap.add_argument("--plot", help="save a PNG report (needs matplotlib)")
    ap.add_argument("--share", metavar="ZIP", help="write a date-shifted zip for a data submission, then exit")
    a = ap.parse_args()

    if a.share:
        n = make_share(a.folder, a.share)
        if not n:
            print("no battery log found (expected raw/log.csv in an export folder)")
            return 1
        print(f"wrote {a.share}: {n} readings, dates shifted. Attach it to a 'Share battery data' issue.")
        return 0

    rows = load_rows(a.folder)
    if not rows:
        print("no readings found (expected raw/log.csv in an export folder)")
        return 1
    drives = [drive_features(d) for d in split_drives(rows)]
    parks = [park_features(p, c) for p, c in zip(drives, drives[1:])]
    days = (rows[-1][0] - rows[0][0]) / 86400

    print(f"{len(rows)} readings, {len(drives)} drives, {len(parks)} parks over {days:.1f} days")
    if days < BASELINE_DAYS:
        print(f"(less than {BASELINE_DAYS} days of data: 'normal' is learned from everything so far, so treat the status as provisional)")
    print()
    print(f"{'drive start':16} {'min':>5} {'start':>6} {'end':>6} {'chg':>6} {'accept':>7} {'ceil':>5}  parked before")
    for i, d in enumerate(drives):
        pk = parks[i - 1] if i > 0 else None
        pks = "" if pk is None else f"{pk['hours']:6.1f} h {pk['change']:+5.1f}" + (" recharged" if pk["recharged"] else "")
        acc = "" if d["acceptance"] is None else f"{d['acceptance']:+.2f}"
        ceil = "" if d["ceiling"] is None else f"{d['ceiling']:.0f}"
        print(f"{fmt_t(d['start']):16} {d['minutes']:5.0f} {d['v_start']:6.1f} {d['v_end']:6.1f} {d['change']:+6.1f} {acc:>7} {ceil:>5}  {pks}")

    status, reasons = assess(drives, parks)
    print(f"\nStatus: {status}")
    for r in reasons:
        print(f"  - {r}")

    if a.csv:
        keys = list(drives[0].keys())
        with open(a.csv, "w", newline="") as f:
            w = csv.DictWriter(f, keys + ["parked_hours_before", "parked_change_before"])
            w.writeheader()
            for i, d in enumerate(drives):
                row = dict(d)
                row["start"], row["end"] = fmt_t(d["start"]), fmt_t(d["end"])
                if i > 0:
                    row["parked_hours_before"], row["parked_change_before"] = parks[i - 1]["hours"], parks[i - 1]["change"]
                w.writerow(row)
        print(f"\nwrote {a.csv}")

    if a.plot:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except ImportError:
            print("--plot needs matplotlib (pip install matplotlib)")
            return 0
        import datetime as dt
        fig, ax = plt.subplots(3, 1, figsize=(11, 9), sharex=True)
        ts = [dt.datetime.fromtimestamp(r[0]) for r in rows]
        ax[0].plot(ts, [r[2] for r in rows], ".", ms=3)
        ax[0].set_ylabel("12V %")
        ax[0].set_title(f"Battery Watchdog analysis: {status}")
        dts = [dt.datetime.fromtimestamp(d["start"]) for d in drives]
        ax[1].plot(dts, [d["acceptance"] for d in drives], "o", label="acceptance pts/10 min")
        ax[1].plot(dts, ewma([d["acceptance"] for d in drives]), "-", label="trend")
        ax[1].legend(); ax[1].set_ylabel("first 15 min")
        pts = [dt.datetime.fromtimestamp(p["start"]) for p in parks]
        ax[2].plot(pts, [p["drain_per_day"] for p in parks], "o", label="parked drain pts/day")
        ax[2].plot(pts, ewma([p["drain_per_day"] for p in parks]), "-", label="trend")
        ax[2].legend(); ax[2].set_ylabel("parked")
        fig.tight_layout()
        fig.savefig(a.plot, dpi=110)
        print(f"wrote {a.plot}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
