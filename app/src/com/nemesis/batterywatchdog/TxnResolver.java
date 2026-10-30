package com.nemesis.batterywatchdog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads AIDL transaction codes by NAME from a DEX or ODEX file, so the monitor
 * never calls a transaction number that hasn't been checked against this
 * firmware's own interface definition.
 *
 * An AIDL Stub class holds one constant per method:
 *     static final int TRANSACTION_getBatSoc = 137;
 * Those live in the class's static_values array. This is a Java port of the
 * relevant parts of hkmc-coldstart-heat's tools/dexodex.py. Pure Java, no
 * Android dependencies, so it can be unit-tested on a desktop JVM.
 */
public final class TxnResolver {

    public static final String STUB = "Lcom/hkmc/telematics/hscan/tm_interface/ITM_HSCANService$Stub;";

    private final byte[] raw;
    private final int base;                      // dex start inside raw (ODEX wrapper offset, else 0)
    private final int strOff, typeOff, fieldOff, clsSize, clsOff;

    private TxnResolver(byte[] file) {
        raw = file;
        int b = 0, len = file.length;
        if (file.length > 16 && file[0] == 'd' && file[1] == 'e' && file[2] == 'y' && file[3] == '\n') {
            b = u4(file, 8);                     // ODEX wrapper: dex offset / length
            len = u4(file, 12);
        }
        if (b < 0 || len <= 0 || (long) b + len > file.length) throw new IllegalArgumentException("bad odex header");
        base = b;
        if (!(at(0) == 'd' && at(1) == 'e' && at(2) == 'x' && at(3) == '\n')) throw new IllegalArgumentException("not a dex");
        strOff = r4(60);
        typeOff = r4(68);
        fieldOff = r4(84);
        clsSize = r4(96);
        clsOff = r4(100);
    }

    // dex-relative accessors
    private int at(int o) { return raw[base + o] & 0xff; }
    private int r4(int o) { return u4(raw, base + o); }

    /** Transaction codes of {@code classDesc} in this file, keyed by method name (without TRANSACTION_). Empty if absent. */
    public static Map<String, Integer> transactions(byte[] file, String classDesc) {
        return new TxnResolver(file).find(classDesc);
    }

    public static Map<String, Integer> transactions(File f, String classDesc) throws IOException {
        long n = f.length();
        if (n <= 0 || n > 64L * 1024 * 1024) throw new IOException("unexpected size " + n);
        byte[] b = new byte[(int) n];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException("short read");
                off += r;
            }
        } finally {
            in.close();
        }
        return transactions(b, classDesc);
    }

    /** True if the file contains {@code needle}, read in 1 MiB chunks (no full load). */
    public static boolean fileContains(File f, byte[] needle) throws IOException {
        byte[] buf = new byte[(1 << 20) + needle.length];
        FileInputStream in = new FileInputStream(f);
        try {
            int keep = 0, n;
            while ((n = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + n;
                if (indexOf(buf, len, needle) >= 0) return true;
                keep = Math.min(needle.length - 1, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
            return false;
        } finally {
            in.close();
        }
    }

    static int indexOf(byte[] h, int hlen, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= hlen; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Transaction codes from every classes*.dex inside a .jar/.apk (for builds that aren't odexed). */
    public static Map<String, Integer> transactionsInZip(File f, String classDesc, byte[] needle) throws IOException {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(f);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                java.util.zip.ZipEntry en = e.nextElement();
                if (!en.getName().matches("classes\\d*\\.dex")) continue;
                java.io.InputStream in = z.getInputStream(en);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try {
                    byte[] b = new byte[65536]; int r;
                    while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                } finally {
                    in.close();
                }
                byte[] dex = bo.toByteArray();
                if (indexOf(dex, dex.length, needle) < 0) continue;
                Map<String, Integer> m = transactions(dex, classDesc);
                if (!m.isEmpty()) return m;
            }
            return new HashMap<String, Integer>();
        } finally {
            z.close();
        }
    }

    private Map<String, Integer> find(String classDesc) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (int i = 0; i < clsSize; i++) {
            int o = clsOff + i * 32;
            if (!classDesc.equals(type(r4(o)))) continue;
            int classData = r4(o + 24), staticValues = r4(o + 28);
            if (classData == 0 || staticValues == 0) return out;
            // static field indices, in declaration order
            int[] p = { classData };
            int sf = uleb(p), inf = uleb(p), dm = uleb(p), vm = uleb(p);
            int[] fields = new int[sf];
            int idx = 0;
            for (int k = 0; k < sf; k++) { idx += uleb(p); uleb(p); fields[k] = idx; }
            // static values array: one encoded value per leading static field
            int[] q = { staticValues };
            int n = uleb(q);
            for (int k = 0; k < n && k < sf; k++) {
                int arg = at(q[0]++), type = arg & 0x1f, size = (arg >> 5) + 1;
                Integer val = null;
                if (type == 0x04 || type == 0x00 || type == 0x02 || type == 0x03) {      // int, byte, short, char
                    long v = 0;
                    for (int b = 0; b < size; b++) v |= (long) at(q[0] + b) << (8 * b);
                    if (type != 0x03 && size < 4 && (v & (1L << (8 * size - 1))) != 0) v -= 1L << (8 * size);   // sign-extend
                    val = (int) v;
                    q[0] += size;
                } else if (type == 0x1c || type == 0x1d) {
                    return out;                    // array/annotation: not expected in a Stub; stop safely
                } else if (type == 0x1e || type == 0x1f) {
                    // null / boolean: no payload
                } else {
                    q[0] += size;
                }
                String name = fieldName(fields[k]);
                if (val != null && name.startsWith("TRANSACTION_")) out.put(name.substring(12), val);
            }
            return out;
        }
        return out;
    }

    private String fieldName(int fidx) { return string(r4(fieldOff + fidx * 8 + 4)); }

    private String type(int tidx) { return string(r4(typeOff + tidx * 4)); }

    private String string(int sidx) {
        int[] p = { r4(strOff + sidx * 4) };
        uleb(p);                                       // utf16 length (unused)
        int start = base + p[0], end = start;
        while (raw[end] != 0) end++;
        try { return new String(raw, start, end - start, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private int uleb(int[] p) {
        int r = 0, shift = 0;
        while (true) {
            int b = at(p[0]++);
            r |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return r;
            shift += 7;
        }
    }

    private static int u4(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
