package com.nemesis.batterywatchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Off-car checks for Report.java (run by tests/test_report.sh). */
public class ReportTest {
    static int fails;
    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        // Monday 2026-11-02 00:00 New York = 1793595600
        long mon = 1793595600L;
        check(Report.weekStart(mon, ny) == mon, "Monday midnight starts its own week");
        check(Report.weekStart(mon + 6 * 86400 + 23 * 3600, ny) == mon, "Sunday night is in the same week");
        // the week before had 25 hours on Nov 1 (DST ended), so its Monday is 7 days + 1 h earlier
        check(Report.weekStart(mon - 60, ny) == mon - 7 * 86400 - 3600, "Sunday 23:59 before it is the week before (across DST)");

        List<long[]> d = new ArrayList<long[]>();
        // week 1: two drives, parked 10 h between them, fell 2.0 pts
        d.add(new long[] { mon + 8 * 3600, mon + 9 * 3600, 820, 850 });
        d.add(new long[] { mon + 19 * 3600, mon + 20 * 3600, 830, 860 });
        // week 2: parked 24 h and it rose 1.5 (recharged); then a 30-minute stop (not counted); then a low start
        long w2 = mon + 7 * 86400;
        d.add(new long[] { w2 + 9 * 3600, w2 + 10 * 3600, 875, 880 });       // parked from week 1's end: counts for week 2
        d.add(new long[] { w2 + 10 * 3600 + 1800, w2 + 11 * 3600, 870, 875 });
        d.add(new long[] { w2 + 3 * 86400, w2 + 3 * 86400 + 3600, 570, 650 });

        List<Report.Week> ws = Report.weeks(d, 600, ny);
        check(ws.size() == 2, "two weeks: " + ws.size());
        Report.Week a1 = ws.get(0), a2 = ws.get(1);
        check(a1.drives == 2 && a1.avgStart() == 825 && a1.lowest == 820, "week 1 drives/avg/lowest");
        check(a1.parkedMin == 600 && a1.drainPerDay() == 48, "week 1 drain/day 4.8 pts: " + a1.drainPerDay());
        check(a2.drives == 3 && a2.lowest == 570 && a2.below == 1, "week 2 lowest start and one below warning");
        check(a2.recharges == 1, "week 2 one recharge: " + a2.recharges);
        // week 2 parks: 9h+... from week 1 end (mon+20h) to w2+9h = 6d13h = 9420 min, rose 1.5; then the 3-day park
        // from w2+11h to w2+72h = 3660 min, fell 30.5; 30-minute stop ignored
        check(a2.parkedMin == 9420 + 3660, "week 2 parked minutes: " + a2.parkedMin);
        check(a2.drainPerDay() == (int) ((-15 + 305) * 1440L / (9420 + 3660)), "week 2 drain/day: " + a2.drainPerDay());

        String warn = "1794000000 12V battery low (57%)\njunk\n1793900000 12V dropped 16 pts while parked\n";
        List<Report.Warning> w = Report.warnings(warn);
        check(w.size() == 2 && w.get(0).at == 1794000000L, "warnings parsed, newest first, junk skipped");

        String r = Report.build(d, warn, 60, 15, 650, w2 + 3 * 86400 + 3600, 100, "eng.test", "0.7.0",
                w2 + 4 * 86400, ny);
        System.out.println(r);
        check(r.contains("5 drives)"), "covers line counts drives");
        check(r.contains("Drives that started below 60%:        1 of 5"), "below-warning count");
        check(r.indexOf("57.0%") > 0 && r.indexOf("57.0%") < r.indexOf("WEEK BY WEEK"), "lowest start listed first");
        check(r.contains("57.0% !"), "low week marked");
        check(r.contains("-4.8 pts"), "week 1 drain shown");
        check(r.contains("12V battery low (57%)"), "warnings listed");
        check(!r.contains("−"), "plain ASCII minus (prints anywhere)");

        String empty = Report.build(new ArrayList<long[]>(), "", 60, 15, -1, 0, -1, "?", "0.7.0", mon, ny);
        check(empty.contains("No drives have been recorded yet.") && empty.contains("None."), "empty report");

        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all passed");
    }
}
