package com.nemesis.batterywatchdog;

import java.util.HashMap;
import java.util.Map;

/** Off-car checks for Importer.java (run by tests/test_import.sh). */
public class ImporterTest {
    static int fails;
    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) fails++;
    }
    static String row(long t, String ev, int pct10) { return t + "," + ev + ",0000004f," + pct10 + ",100,705,12,,,,,"; }

    public static void main(String[] a) {
        long t = 1793595600L;
        // the NEMESIS copy: older drives
        Map<String, String> nem = new HashMap<String, String>();
        nem.put("log.old.csv", row(t, "start", 830) + "\n" + row(t + 300, "sample", 840) + "\n");
        nem.put("log.csv", row(t + 86400, "start", 60) + "\n" + row(t + 86400 + 300, "sample", 700) + "\n");
        nem.put("sessions", t + " 60 830 850\n");
        nem.put("warnings", (t + 86400) + " 12V collapsed while parked to 6.0% (was 85.0%)\n");
        nem.put("dips", (t + 86400) + " 850 60\n");
        // the mod's own copy: one newer reading, and one line both have
        Map<String, String> mod = new HashMap<String, String>();
        mod.put("log.csv", row(t + 86400 + 300, "sample", 700) + "\n" + row(t + 2 * 86400, "start", 820) + "\n");
        mod.put("warnings", "junk line\n");

        int[] c = Importer.count(nem);
        check(c[0] == 4 && c[1] == 1 && c[2] == 1 && c[3] == 1, "count: 4 readings, 1 drive, 1 warning, 1 collapse");
        long[] sp = Importer.span(nem);
        check(sp[0] == t && sp[1] == t + 86400 + 300, "span of the source's readings");

        Importer.Result r = Importer.merge(mod, nem, 4000);
        String log = r.files.get("log.csv");
        check(r.newReadings == 3, "3 new readings (the shared one isn't doubled): " + r.newReadings);
        check(log.split("\n").length == 5 && r.logLines == 5, "all 5 readings in log.csv: " + r.logLines);
        check(log.startsWith(t + ",start,") && log.trim().endsWith(row(t + 2 * 86400, "start", 820)), "in time order");
        check(r.files.get("log.old.csv").length() == 0, "nothing rotated below max_lines");
        check(r.newDrives == 1 && r.files.get("sessions").equals(t + " 60 830 850\n"), "drives brought over");
        check(r.newWarnings == 1 && !r.files.get("warnings").contains("junk"), "warnings brought over, junk dropped");
        check(r.newCollapses == 1, "collapses brought over");
        check(r.first == t && r.last == t + 86400 + 300, "imported span");

        Importer.Result again = Importer.merge(r.files, nem, 4000);
        check(again.nothingNew(), "importing the same again adds nothing");

        // rotation: 250 rows with max_lines 100 -> 50 in log.csv, 100 in log.old.csv, oldest 100 dropped
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) big.append(row(t + i * 60, "sample", 800)).append('\n');
        Map<String, String> b = new HashMap<String, String>();
        b.put("log.csv", big.toString());
        Importer.Result rb = Importer.merge(new HashMap<String, String>(), b, 100);
        check(rb.logLines == 50 && rb.files.get("log.csv").split("\n").length == 50, "log.csv holds the newest 50");
        check(rb.files.get("log.old.csv").split("\n").length == 100, "log.old.csv the 100 before");
        check(rb.files.get("log.old.csv").startsWith((t + 100 * 60) + ","), "the oldest 100 dropped, as rotation would");

        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all passed");
    }
}
