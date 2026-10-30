#!/usr/bin/env python3
"""
check_firmware.py - read Battery Watchdog's transaction numbers from a
firmware dump, without touching the car, and print the known_firmware.txt line.

Input: the folder written by research/odex-dump (getprop.txt,
service-list.txt, framework/, app/, priv-app/, vendor/), or any folder of
.odex / .dex / .jar / .apk files plus a getprop.txt.

    python3 tools/check_firmware.py /path/to/usb/odex-dump

Exit code 0 if getBatSoc was found, 1 otherwise. Pure Python 3, no dependencies.
Don't publish the firmware files themselves; only the printed line is needed.
"""
import os
import re
import struct
import sys
import zipfile

STUB = "Lcom/hkmc/telematics/hscan/tm_interface/ITM_HSCANService$Stub;"
IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService"
WANT = [("getBatSoc", "12V state of charge"),
        ("remainBatteryPercentage", "main battery charge"),
        ("getXEV_Bms_GDS_SOH", "HV battery health")]


def u4(d, o):
    return struct.unpack_from("<I", d, o)[0]


def uleb(d, o):
    r = s = 0
    while True:
        b = d[o]; o += 1
        r |= (b & 0x7f) << s
        if not b & 0x80:
            return r, o
        s += 7


def dex_of(raw):
    if raw[:4] == b"dey\n":
        off, ln = u4(raw, 8), u4(raw, 12)
        raw = raw[off:off + ln]
    return raw if raw[:4] == b"dex\n" else None


def transactions(d, desc):
    """TRANSACTION_* constants of class `desc` in dex bytes `d`, by method name."""
    str_off, type_off, field_off = u4(d, 60), u4(d, 68), u4(d, 84)
    cls_size, cls_off = u4(d, 96), u4(d, 100)

    def string(i):
        o = u4(d, str_off + i * 4)
        _, o = uleb(d, o)
        return d[o:d.index(b"\0", o)].decode("utf-8", "replace")

    for i in range(cls_size):
        o = cls_off + i * 32
        if string(u4(d, type_off + u4(d, o) * 4)) != desc:
            continue
        cdo, svo = u4(d, o + 24), u4(d, o + 28)
        if not cdo or not svo:
            return {}
        p = cdo
        sf, p = uleb(d, p)
        for _ in range(3):
            _, p = uleb(d, p)
        fields, idx = [], 0
        for _ in range(sf):
            diff, p = uleb(d, p); _, p = uleb(d, p)
            idx += diff
            fields.append(idx)
        q = svo
        n, q = uleb(d, q)
        out = {}
        for k in range(min(n, sf)):
            arg = d[q]; q += 1
            typ, size = arg & 0x1F, (arg >> 5) + 1
            val = None
            if typ in (0x00, 0x02, 0x03, 0x04):
                v = int.from_bytes(d[q:q + size], "little", signed=(typ != 0x03))
                val = v; q += size
            elif typ in (0x1C, 0x1D):
                break
            elif typ in (0x1E, 0x1F):
                pass
            else:
                q += size
            name = string(u4(d, field_off + fields[k] * 8 + 4))
            if val is not None and name.startswith("TRANSACTION_"):
                out[name[12:]] = val
        return out
    return {}


def dexes(path):
    low = path.lower()
    if low.endswith((".jar", ".apk", ".zip")):
        try:
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if re.match(r"classes\d*\.dex$", n):
                        yield "%s!%s" % (path, n), z.read(n)
        except zipfile.BadZipFile:
            return
    elif low.endswith((".odex", ".dex")):
        with open(path, "rb") as f:
            raw = f.read()
        d = dex_of(raw)
        if d:
            yield path, d


def getprop(folder, key):
    p = os.path.join(folder, "getprop.txt")
    if not os.path.exists(p):
        return ""
    for line in open(p, errors="replace"):
        m = re.match(r"\[%s\]: \[(.*)\]" % re.escape(key), line.strip())
        if m:
            return m.group(1)
    return ""


def main():
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(__doc__.strip()); return 2
    folder = sys.argv[1]
    fw = getprop(folder, "ro.build.version.incremental") or "<unknown-firmware>"
    disp = getprop(folder, "ro.build.display.id")
    print("firmware:  %s%s" % (fw, ("  (" + disp + ")") if disp else ""))

    sl = os.path.join(folder, "service-list.txt")
    if os.path.exists(sl):
        ok = any("tm_hscan_service: [%s]" % IFACE in l for l in open(sl, errors="replace"))
        print("interface: %s" % ("tm_hscan_service is %s" % IFACE if ok else "NOT FOUND in service-list.txt"))

    found = None
    for root, _, names in os.walk(folder):
        for n in sorted(names):
            for label, d in dexes(os.path.join(root, n)):
                try:
                    tx = transactions(d, STUB)
                except Exception as e:
                    print("  skipped %s: %s" % (label, e)); continue
                if "getBatSoc" in tx:
                    found = (label, tx); break
            if found: break
        if found: break

    if not found:
        print("\nITM_HSCANService$Stub with TRANSACTION_getBatSoc not found.")
        print("Battery Watchdog can't be verified on this firmware.")
        return 1

    label, tx = found
    print("source:    %s  (%d transactions)" % (os.path.relpath(label, folder), len(tx)))
    nums = []
    for name, what in WANT:
        v = tx.get(name)
        nums.append(str(v) if v is not None else "-")
        print("  %-26s %-5s %s" % (name, v if v is not None else "-", what))
    print("\nknown_firmware.txt line:\n%s  %s  # <car, model year, region>, %s" % (fw, "  ".join(nums), disp or "<firmware version>"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
