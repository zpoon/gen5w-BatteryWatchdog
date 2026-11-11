package com.nemesis.batterywatchdog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The 12V battery report: a plain-text summary of everything logged, written
 * for a dealer or service visit. Plain Java (no Android classes) so it can be
 * tested off the car (tests/test_report.sh).
 *
 * Drives are {start_epoch, end_epoch, start_pct10, end_pct10}, oldest first,
 * as MainActivity.loadDrives returns them. Warnings are the service's
 * "warnings" file: one "<epoch> <text>" per line.
 */
final class Report {
    private Report() {}

    /** One parsed warning. */
    static final class Warning {
        final long at; final String text;
        Warning(long at, String text) { this.at = at; this.text = text; }
    }

    /** Warnings from the service's file, newest first. Lines it can't read are skipped. */
    static List<Warning> warnings(String file) {
        List<Warning> out = new ArrayList<Warning>();
        if (file == null) return out;
        for (String line : file.split("\n")) {
            String l = line.trim();
            int sp = l.indexOf(' ');
            if (sp <= 0) continue;
            long at;
            try { at = Long.parseLong(l.substring(0, sp)); } catch (NumberFormatException e) { continue; }
            if (at <= 0) continue;
            out.add(new Warning(at, l.substring(sp + 1).trim()));
        }
        Collections.sort(out, new Comparator<Warning>() {
            public int compare(Warning a, Warning b) { return a.at < b.at ? 1 : a.at > b.at ? -1 : 0; }
        });
        return out;
    }

    /** A parked stop shorter than this doesn't count towards the drain rate. */
    static final long MIN_PARK_MIN = 60;

    /** Per-week numbers; weeks start on Monday, local time. */
    static final class Week {
        final long start;
        int drives, lowest = Integer.MAX_VALUE, below, recharges;
        long sumStart, parkedMin, parkedDrop;
        Week(long start) { this.start = start; }
        /** average start, tenths of a point */
        int avgStart() { return drives == 0 ? -1 : (int) Math.round((double) sumStart / drives); }
        /** parked drain per day, tenths of a point (positive = fell); MIN_VALUE if under an hour parked */
        int drainPerDay() { return parkedMin >= MIN_PARK_MIN ? (int) (parkedDrop * 1440 / parkedMin) : Integer.MIN_VALUE; }
    }

    static List<Week> weeks(List<long[]> drives, int warn10, TimeZone tz) {
        List<Week> out = new ArrayList<Week>();
        for (int i = 0; i < drives.size(); i++) {
            long[] d = drives.get(i);
            long ws = weekStart(d[0], tz);
            Week w = out.isEmpty() ? null : out.get(out.size() - 1);
            if (w == null || w.start != ws) { w = new Week(ws); out.add(w); }
            w.drives++;
            w.sumStart += d[2];
            w.lowest = (int) Math.min(w.lowest, d[2]);
            if (d[2] < warn10) w.below++;
            if (i == 0) continue;
            long[] p = drives.get(i - 1);
            long gap = (d[0] - p[1]) / 60;
            if (gap < MIN_PARK_MIN) continue;
            long drop = p[3] - d[2];                       // positive = fell while parked
            if (drop <= -10) w.recharges++;                // rose 1+ pt: the car topped it up
            w.parkedMin += gap; w.parkedDrop += drop;
        }
        return out;
    }

    static long weekStart(long epoch, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(epoch * 1000);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday 0 ... Sunday 6
        c.add(Calendar.DAY_OF_MONTH, -back);
        return c.getTimeInMillis() / 1000;
    }

    /**
     * The report.
     * @param pct10   the latest 12V reading in tenths, or -1
     * @param updated when it was read (epoch), or 0
     * @param soh     HV battery health index, or -1
     */
    static String build(List<long[]> drives, String warningsFile, int warnPct, int dropPct,
                        int pct10, long updated, int soh, String firmware, String appVersion,
                        long now, TimeZone tz) {
        SimpleDateFormat day = fmt("MMM d, yyyy", tz), when = fmt("MMM d, yyyy h:mm a", tz), wk = fmt("MMM d", tz);
        int warn10 = warnPct * 10;
        List<Warning> warns = warnings(warningsFile);
        StringBuilder b = new StringBuilder();

        b.append("12V BATTERY REPORT\n");
        b.append("Battery Watchdog ").append(appVersion).append(", head unit firmware ").append(firmware).append("\n");
        b.append("Made ").append(when.format(new Date(now * 1000))).append("\n");
        if (drives.isEmpty()) {
            b.append("\nNo drives have been recorded yet.\n");
        } else {
            long first = drives.get(0)[0], last = drives.get(drives.size() - 1)[1];
            long days = Math.max(1, (last - first + 86399) / 86400);
            b.append("Covers ").append(day.format(new Date(first * 1000))).append(" to ")
             .append(day.format(new Date(last * 1000))).append(" (").append(days).append(days == 1 ? " day, " : " days, ")
             .append(drives.size()).append(drives.size() == 1 ? " drive)\n" : " drives)\n");
        }

        b.append("\nNOW\n");
        b.append("  12V battery:        ").append(pct10 >= 0 ? pct(pct10) : "no reading");
        if (pct10 >= 0 && updated > 0) b.append("  (read ").append(when.format(new Date(updated * 1000))).append(")");
        b.append("\n");
        b.append("  HV battery health:  ").append(soh > 0 ? soh + " (100 = new)" : "not available").append("\n");

        if (!drives.isEmpty()) {
            int below = 0;
            for (long[] d : drives) if (d[2] < warn10) below++;
            b.append("\nAT A GLANCE\n");
            b.append("  Warnings shown on the head unit:        ").append(warns.size()).append("\n");
            b.append("  Drives that started below ").append(warnPct).append("%:        ")
             .append(below).append(" of ").append(drives.size()).append("\n");

            List<long[]> low = new ArrayList<long[]>(drives);
            Collections.sort(low, new Comparator<long[]>() {
                public int compare(long[] a, long[] c) { return a[2] < c[2] ? -1 : a[2] > c[2] ? 1 : (a[0] < c[0] ? -1 : a[0] > c[0] ? 1 : 0); }
            });
            b.append("\nLOWEST 12V AT THE START OF A DRIVE\n");
            for (int i = 0; i < low.size() && i < 5; i++) {
                b.append("  ").append(pad(pct(low.get(i)[2]), 8)).append(when.format(new Date(low.get(i)[0] * 1000))).append("\n");
            }

            b.append("\nWEEK BY WEEK\n");
            b.append("  Week of   Drives  Avg start  Lowest start  Parked drain/day  Recharged while parked\n");
            for (Week w : weeks(drives, warn10, tz)) {
                int r = w.drainPerDay();
                b.append("  ").append(pad(wk.format(new Date(w.start * 1000)), 10))
                 .append(pad(String.valueOf(w.drives), 8))
                 .append(pad(pct(w.avgStart()), 11))
                 .append(pad(pct(w.lowest) + (w.lowest < warn10 ? " !" : ""), 14))
                 .append(pad(r == Integer.MIN_VALUE ? "-" : signed(-r) + " pts", 18))
                 .append(w.recharges).append("\n");
            }
            b.append("  ! = below the ").append(warnPct).append("% warning level\n");
        }

        b.append("\nWARNINGS");
        if (warns.isEmpty()) b.append("\n  None.\n");
        else {
            b.append(" (newest first)\n");
            for (Warning w : warns) b.append("  ").append(pad(when.format(new Date(w.at * 1000)), 23)).append(w.text).append("\n");
        }

        b.append("\nABOUT THESE NUMBERS\n");
        b.append("  The 12V figures are the car's own estimate of the 12V battery's charge, read from\n");
        b.append("  the head unit's telematics service while the screen is on. They are not a voltage\n");
        b.append("  reading or a load test. \"Parked drain\" is how much that estimate fell while the car\n");
        b.append("  was off (stops of an hour or more), per day; \"recharged while parked\" counts stops\n");
        b.append("  where it rose instead, i.e. the car topped the 12V up. Warnings: below ").append(warnPct)
         .append("% at the\n  start of a drive, or a drop of ").append(dropPct).append(" points or more while parked.\n");
        return b.toString();
    }

    static SimpleDateFormat fmt(String p, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
        f.setTimeZone(tz);
        return f;
    }
    static String pct(long v10) { return v10 < 0 ? "-" : (v10 / 10) + "." + (v10 % 10) + "%"; }
    /** tenths with a sign: +1.2, -0.4, 0.0 */
    static String signed(int v10) {
        int a = Math.abs(v10);
        return (v10 > 0 ? "+" : v10 < 0 ? "-" : "") + (a / 10) + "." + (a % 10);
    }
    static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        if (b.length() == s.length()) b.append(' ');
        return b.toString();
    }
}
