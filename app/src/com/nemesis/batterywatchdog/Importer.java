package com.nemesis.batterywatchdog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges history from another copy of Battery Watchdog into this one: the NEMESIS
 * package's state folder (or its hand-over copy), or the raw/ folder of an export on
 * a USB drive. Plain Java (no Android classes) so it can be tested off the car
 * (tests/test_import.sh).
 *
 * Only history moves: the battery log, drives, warnings and collapses. Settings,
 * firmware numbers and the monitor's own bookkeeping stay as they are here.
 * Lines already here aren't doubled, everything is put back in time order, and the
 * log is split again into log.old.csv / log.csv the way the monitor rotates it.
 */
final class Importer {
    private Importer() {}

    /** the history files, as the monitor names them in its state folder */
    static final String[] FILES = { "log.old.csv", "log.csv", "sessions", "warnings", "dips" };

    /** What a merge did. */
    static final class Result {
        final Map<String, String> files = new LinkedHashMap<String, String>();
        int newReadings, newDrives, newWarnings, newCollapses;
        long first, last;          // time span of the imported readings (epoch), 0 if none
        int logLines;              // lines in the new log.csv (the monitor's "lines" counter)
        boolean nothingNew() { return newReadings + newDrives + newWarnings + newCollapses == 0; }
    }

    /** Readings, drives, warnings and collapses in a source, for "found ..." before importing. */
    static int[] count(Map<String, String> src) {
        return new int[] {
            rows(join(src.get("log.old.csv"), src.get("log.csv")), ',').size(),
            rows(src.get("sessions"), ' ').size(),
            rows(src.get("warnings"), ' ').size(),
            rows(src.get("dips"), ' ').size() };
    }

    /** Time span {first, last} of a source's readings, or {0, 0}. */
    static long[] span(Map<String, String> src) {
        List<String> r = rows(join(src.get("log.old.csv"), src.get("log.csv")), ',');
        if (r.isEmpty()) return new long[] { 0, 0 };
        sort(r, ',');
        return new long[] { epoch(r.get(0), ','), epoch(r.get(r.size() - 1), ',') };
    }

    /**
     * Merge {@code add} into {@code have}. Both map file names (FILES) to their
     * contents; a missing file is empty. {@code maxLines} is the monitor's max_lines
     * (log.csv is rotated to log.old.csv at that many lines).
     */
    static Result merge(Map<String, String> have, Map<String, String> add, int maxLines) {
        if (maxLines < 100) maxLines = 4000;
        Result res = new Result();

        List<String> mine = rows(join(have.get("log.old.csv"), have.get("log.csv")), ',');
        List<String> theirs = rows(join(add.get("log.old.csv"), add.get("log.csv")), ',');
        List<String> log = union(mine, theirs, res, 0, ',');
        sort(log, ',');
        // keep the newest 2 x maxLines, split the way the monitor rotates
        int n = log.size();
        int cur = n % maxLines == 0 && n > 0 ? maxLines : n % maxLines;
        if (n > cur + maxLines) log = log.subList(n - cur - maxLines, n);
        n = log.size();
        res.files.put("log.old.csv", lines(log.subList(0, n - cur)));
        res.files.put("log.csv", lines(log.subList(n - cur, n)));
        res.logLines = cur;
        List<String> imported = rows(join(add.get("log.old.csv"), add.get("log.csv")), ',');
        if (!imported.isEmpty()) {
            sort(imported, ',');
            res.first = epoch(imported.get(0), ',');
            res.last = epoch(imported.get(imported.size() - 1), ',');
        }

        String[][] spaced = { { "sessions", "1" }, { "warnings", "2" }, { "dips", "3" } };
        for (String[] f : spaced) {
            List<String> m = union(rows(have.get(f[0]), ' '), rows(add.get(f[0]), ' '), res, Integer.parseInt(f[1]), ' ');
            sort(m, ' ');
            res.files.put(f[0], lines(m));
        }
        return res;
    }

    /** lines of {@code a} plus those of {@code b} not already in it; counts the new ones */
    static List<String> union(List<String> a, List<String> b, Result res, int kind, char sep) {
        List<String> out = new ArrayList<String>(a);
        Set<String> seen = new HashSet<String>(a);
        for (String l : b) {
            if (!seen.add(l)) continue;
            out.add(l);
            switch (kind) {
                case 0: res.newReadings++; break;
                case 1: res.newDrives++; break;
                case 2: res.newWarnings++; break;
                default: res.newCollapses++; break;
            }
        }
        return out;
    }

    /** non-empty lines whose first field is a time (epoch > 0), trimmed */
    static List<String> rows(String text, char sep) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.length() == 0) continue;
            if (epoch(l, sep) <= 0) continue;
            out.add(l);
        }
        return out;
    }

    static long epoch(String line, char sep) {
        int i = 0;
        while (i < line.length() && line.charAt(i) != sep && !(sep == ' ' && Character.isWhitespace(line.charAt(i)))) i++;
        try { return Long.parseLong(line.substring(0, i)); } catch (NumberFormatException e) { return -1; }
    }

    /** by time; lines with the same time keep their order (Collections.sort is stable) */
    static void sort(List<String> l, final char sep) {
        Collections.sort(l, new Comparator<String>() {
            public int compare(String a, String b) {
                long x = epoch(a, sep), y = epoch(b, sep);
                return x < y ? -1 : x > y ? 1 : 0;
            }
        });
    }

    static String join(String a, String b) {
        return (a == null ? "" : a) + "\n" + (b == null ? "" : b);
    }

    static String lines(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('\n');
        return b.toString();
    }
}
