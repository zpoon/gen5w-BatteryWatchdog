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

    private final byte[] d;
    private final int strOff, typeOff, fieldOff, clsSize, clsOff;

    private TxnResolver(byte[] raw) {
        int base = 0, len = raw.length;
        if (raw.length > 16 && raw[0] == 'd' && raw[1] == 'e' && raw[2] == 'y' && raw[3] == '\n') {
            base = u4(raw, 8);                 // ODEX wrapper: dex offset / length
            len = u4(raw, 12);
        }
        if (base < 0 || len <= 0 || base + len > raw.length) throw new IllegalArgumentException("bad odex header");
        byte[] x = new byte[len];
        System.arraycopy(raw, base, x, 0, len);
        d = x;
        if (!(d[0] == 'd' && d[1] == 'e' && d[2] == 'x' && d[3] == '\n')) throw new IllegalArgumentException("not a dex");
        strOff = u4(d, 60);
        typeOff = u4(d, 68);
        fieldOff = u4(d, 84);
        clsSize = u4(d, 96);
        clsOff = u4(d, 100);
    }

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

    private Map<String, Integer> find(String classDesc) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (int i = 0; i < clsSize; i++) {
            int o = clsOff + i * 32;
            if (!classDesc.equals(type(u4(d, o)))) continue;
            int classData = u4(d, o + 24), staticValues = u4(d, o + 28);
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
                int arg = d[q[0]++] & 0xff, type = arg & 0x1f, size = (arg >> 5) + 1;
                Integer val = null;
                if (type == 0x04 || type == 0x00 || type == 0x02 || type == 0x03) {      // int, byte, short, char
                    long v = 0;
                    for (int b = 0; b < size; b++) v |= (long) (d[q[0] + b] & 0xff) << (8 * b);
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

    private String fieldName(int fidx) { return string(u4(d, fieldOff + fidx * 8 + 4)); }

    private String type(int tidx) { return string(u4(d, typeOff + tidx * 4)); }

    private String string(int sidx) {
        int[] p = { u4(d, strOff + sidx * 4) };
        uleb(p);                                       // utf16 length (unused)
        int start = p[0], end = start;
        while (d[end] != 0) end++;
        try { return new String(d, start, end - start, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private int uleb(int[] p) {
        int r = 0, shift = 0;
        while (true) {
            int b = d[p[0]++] & 0xff;
            r |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return r;
            shift += 7;
        }
    }

    private static int u4(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
