package com.nemesis.batterywatchdog;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips for Export to USB. Plain Java (no Android classes), so it's tested on the
 * host against tools/analyze.py.
 *
 * A "share" zip has the same layout and date shift as `analyze.py --share`: only
 * raw/log.csv, raw/log.old.csv, raw/sessions, raw/warnings and a few settings,
 * with every epoch moved back by one random whole number of weeks (time of day
 * and day of week kept), plus info.txt. Entry times are fixed, so the zip itself
 * doesn't carry the export date either.
 */
final class ShareZip {
    private ShareZip() {}

    /** files kept in a share zip; the rest of the state dir stays private */
    static final String[] SHARE_FILES = { "log.old.csv", "log.csv", "sessions", "warnings", "dips", "interval_s", "warn_pct", "drop_pct" };
    /** 1980-01-02 12:00 UTC: just after the earliest time a zip entry can hold, in any time zone */
    static final long FIXED_TIME = 315662400000L;

    static long randomShift() {
        int weeks = 8 + new SecureRandom().nextInt(153);   // 8..160 weeks, as analyze.py
        return weeks * 7L * 86400L;
    }

    /** Move the epoch in the first field of each line back by shift seconds. */
    static String shiftLines(String text, long shift, char sep) {
        if (text == null || text.length() == 0) return "";
        StringBuilder b = new StringBuilder(text.length() + 16);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i == lines.length - 1 && l.length() == 0) break;   // trailing newline
            String t = l.trim();
            int cut = t.indexOf(sep);
            String head = cut < 0 ? t : t.substring(0, cut);
            long v = -1;
            try { v = Long.parseLong(head); } catch (NumberFormatException e) { /* header or junk: keep */ }
            if (v > 0) b.append(v - shift).append(cut < 0 ? "" : t.substring(cut));
            else b.append(sep == ',' ? l : t);
            b.append('\n');
        }
        return b.toString();
    }

    /**
     * The share zip. raw holds state-file contents by name (missing files are
     * skipped); info is written as info.txt.
     */
    static byte[] share(Map<String, String> raw, String info, long shift) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        try {
            for (String name : SHARE_FILES) {
                String c = raw.get(name);
                if (c == null) continue;
                if (name.startsWith("log")) c = shiftLines(c, shift, ',');
                else if (name.equals("sessions") || name.equals("warnings") || name.equals("dips")) c = shiftLines(c, shift, ' ');
                put(z, "raw/" + name, c);
            }
            put(z, "info.txt", "dates shifted back by an undisclosed number of whole weeks\n" + (info == null ? "" : info));
        } finally {
            z.close();
        }
        return bo.toByteArray();
    }

    /** A plain zip of the export: name -> content, written as given. */
    static byte[] plain(Map<String, String> files) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        try {
            for (Map.Entry<String, String> e : files.entrySet()) put(z, e.getKey(), e.getValue());
        } finally {
            z.close();
        }
        return bo.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws java.io.IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        z.putNextEntry(e);
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    /** A short random name part, so a share zip's file name holds no date. */
    static String randomId() {
        return Integer.toHexString(0x10000 + new SecureRandom().nextInt(0xF0000)).substring(1);
    }
}
