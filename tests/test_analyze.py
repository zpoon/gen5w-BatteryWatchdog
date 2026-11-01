#!/usr/bin/env python3
"""Synthetic check for tools/analyze.py: a healthy car stays Normal, a
battery that stops accepting charge or drains fast is flagged."""
import os
import random
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "..", "tools", "analyze.py")


def simulate(path, days=28, fail_from=None, mode=None, seed=1):
    """Two drives a day. Parked, the 12V settles a few points below where it
    stopped; driving, the LDC pulls it back up towards a ceiling. A worn
    battery climbs slowly and tops out lower; a parasitic drain empties it."""
    rnd = random.Random(seed)
    t0 = 1_780_000_000
    v = 88.0
    lines = []
    for day in range(days):
        failing = fail_from is not None and day >= fail_from
        for start_h, mins, parked_h in ((8, 40, 14), (17, 25, 9)):
            t = t0 + day * 86400 + start_h * 3600
            drain = 6 + rnd.uniform(-1, 1)                       # pts/day, healthy
            if failing and mode == "drain":
                drain = 25 + rnd.uniform(-2, 2)
            v = max(30, v - 4 - drain * parked_h / 24 * rnd.uniform(0.8, 1.2))
            lines.append(f"{t},start,0,{int(v*10)},100,700,15")
            slow = failing and mode == "accept"
            rate, ceil = (0.12, 83) if slow else (0.6, 90)        # pts/min
            for m in range(1, mins + 1):
                v = min(ceil, v + rate * rnd.uniform(0.8, 1.2) * (1 if m <= 15 else 0.4))
                if m <= 15 or m % 5 == 0:
                    lines.append(f"{t + m*60},sample,0,{int(v*10)},100,700,15")
    os.makedirs(os.path.join(path, "raw"), exist_ok=True)
    with open(os.path.join(path, "raw", "log.csv"), "w") as f:
        f.write("\n".join(lines) + "\n")


def run(path):
    out = subprocess.run([sys.executable, TOOL, path], capture_output=True, text=True, check=True).stdout
    return next(l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("Status:")), out


def main():
    ok = True
    for name, kw, want in [
        ("healthy", {}, "Normal"),
        ("slow charge acceptance", {"fail_from": 21, "mode": "accept"}, "Check battery"),
        ("high parked drain", {"fail_from": 21, "mode": "drain"}, "Check battery"),
    ]:
        with tempfile.TemporaryDirectory() as d:
            simulate(d, **kw)
            got, out = run(d)
            mark = "ok" if got == want else "FAIL"
            ok &= got == want
            print(f"  {mark:4} {name}: {got}")
            if got != want:
                print(out)
    # --share: same analysis after the dates are shifted, real dates gone
    with tempfile.TemporaryDirectory() as d:
        simulate(d)
        with open(os.path.join(d, "raw", "sessions"), "w") as f:
            f.write("1780000000 40 820 900\n")
        with open(os.path.join(d, "raw", "warnings"), "w") as f:
            f.write("1780003600 12V battery low (58%)\n")
        z = os.path.join(d, "share.zip")
        subprocess.run([sys.executable, TOOL, d, "--share", z], capture_output=True, check=True)
        import zipfile
        with zipfile.ZipFile(z) as zf:
            names = sorted(zf.namelist())
            log = zf.read("raw/log.csv").decode()
            ses = zf.read("raw/sessions").decode()
            war = zf.read("raw/warnings").decode()
            ex = os.path.join(d, "x"); zf.extractall(ex)
        orig = open(os.path.join(d, "raw", "log.csv")).read().split("\n")[0].split(",")[0]
        shifted = log.split("\n")[0].split(",")[0]
        delta = int(orig) - int(shifted)
        good = (names == ["info.txt", "raw/log.csv", "raw/sessions", "raw/warnings"]
                and delta > 0 and delta % (7 * 86400) == 0
                and str(1780000000 - delta) in ses and str(1780003600 - delta) in war
                and "1780000000" not in log)
        got, _ = run(ex)
        good &= got == "Normal"
        ok &= good
        print(f"  {'ok' if good else 'FAIL':4} share: {names}, shifted {delta // 86400} days, analysis {got}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
