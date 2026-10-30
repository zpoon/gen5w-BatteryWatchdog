package com.nemesis.batterywatchdog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.DialogInterface;
import java.io.BufferedReader;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Battery Watchdog. Shows what the battery.watchdog service has logged and
 * edits its one-value config files. Reads/writes /data/nemesis/state through
 * the privileged `nemesis root -c` channel (this app runs as an ordinary uid).
 * Kotlin/lambdas avoided on purpose: built with legacy dx for KitKat (DEX 035).
 */
public class MainActivity extends Activity {

    static final String STATE = "/data/nemesis/state/battery.watchdog";
    static final String NEM = "/system/xbin/nemesis";
    static final String[] DECODE = { "Auto", "Integer", "Float" };
    static final int[] INTERVALS = { 60, 300, 900 };
    static final int BG = Color.rgb(18, 20, 24), AMBER = Color.rgb(255, 170, 60),
            GREEN = Color.rgb(110, 210, 140), MUTED = Color.rgb(150, 160, 170), ACCENT = Color.rgb(120, 180, 255);

    boolean enabled = true, toastStart = false, advanced = false;
    int warnPct = 60, dropPct = 15, decode = 1, scaleX10 = 10, interval = 300;

    LinearLayout sohBox;
    TextView sohTitle, sohValue;
    TextView parkLabel, ctxLabel;
    TextView bigLabel, updLabel, sohLabel, warnLabel, warnThrLabel, dropThrLabel, rawLabel;
    Button enBtn, toastBtn, decBtn, scaleBtn, intBtn, advBtn;
    LinearLayout advBox;
    Button overrideBtn;
    Chart chart;

    // auto-refresh while the screen is open (the service samples every few minutes)
    final Handler handler = new Handler();
    final Runnable tick = new Runnable() {
        public void run() { refreshData(); handler.postDelayed(this, 30000); }
    };

    /*
     * Layout for the 1920x720 Gen5W screen: three columns, no page scrolling.
     *   left  (3) : live 12V reading, warning, SOH, read-sensor button
     *   middle(5) : chart, full height
     *   right (3) : settings (scrolls on its own if it ever overflows)
     */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // drop the system title bar: the app draws its own title in the left column
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        loadConfig();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // ---- left: status --------------------------------------------------
        // Compact so the SOH box always fits on the 720 px-tall screen; the
        // column also scrolls on its own as a fallback. The read-sensor button
        // lives under Advanced now.
        LinearLayout left = column();
        left.setPadding(0, 0, dp(12), 0);
        TextView t = title("Battery Watchdog");
        t.setTextSize(20);
        t.setPadding(0, 0, 0, dp(2));
        left.addView(t);
        left.addView(hint("12V battery"));
        bigLabel = new TextView(this);
        bigLabel.setTextColor(Color.WHITE);
        bigLabel.setTextSize(52);
        bigLabel.setIncludeFontPadding(false);
        left.addView(bigLabel);
        updLabel = hint("");
        left.addView(updLabel);
        parkLabel = new TextView(this);
        parkLabel.setTextColor(Color.WHITE);
        parkLabel.setTextSize(15);
        left.addView(parkLabel);
        ctxLabel = hint("");
        ctxLabel.setPadding(0, dp(2), 0, dp(2));
        left.addView(ctxLabel);
        warnLabel = new TextView(this);
        warnLabel.setTextColor(AMBER);
        warnLabel.setTextSize(16);
        warnLabel.setPadding(0, dp(2), 0, dp(6));
        left.addView(warnLabel);
        sohLabel = hint("");
        sohLabel.setVisibility(View.GONE);
        left.addView(sohLabel);
        // HV battery health box: read live, not logged or monitored
        sohBox = new LinearLayout(this);
        sohBox.setOrientation(LinearLayout.VERTICAL);
        sohBox.setPadding(dp(14), dp(8), dp(14), dp(10));
        sohTitle = new TextView(this);
        sohTitle.setText("HV battery health (SOH)");
        sohTitle.setTextSize(14);
        sohBox.addView(sohTitle);
        sohValue = new TextView(this);
        sohValue.setTextSize(26);
        sohValue.setIncludeFontPadding(false);
        sohBox.addView(sohValue);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(4), 0, dp(4));
        left.addView(sohBox, sp);
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);

        // ---- middle: chart -------------------------------------------------
        LinearLayout mid = column();
        mid.setPadding(dp(16), 0, dp(16), 0);
        mid.addView(section("Recent readings · dot = start of a drive"));
        chart = new Chart(this, dp(1));
        mid.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- right: settings -----------------------------------------------
        ScrollView rightScroll = new ScrollView(this);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(right);

        right.addView(section("Warnings"));
        enBtn = button("", new View.OnClickListener() { public void onClick(View v) { enabled = !enabled; refreshButtons(); } });
        right.addView(enBtn);
        warnThrLabel = label();
        right.addView(warnThrLabel);
        right.addView(seek(30, 90, warnPct, new Setter() { public void set(int v) { warnPct = v; refreshButtons(); } }));
        dropThrLabel = label();
        right.addView(dropThrLabel);
        right.addView(seek(5, 40, dropPct, new Setter() { public void set(int v) { dropPct = v; refreshButtons(); } }));
        toastBtn = button("", new View.OnClickListener() { public void onClick(View v) { toastStart = !toastStart; refreshButtons(); } });
        right.addView(toastBtn);

        advBtn = button("", new View.OnClickListener() { public void onClick(View v) { advanced = !advanced; refreshButtons(); } });
        right.addView(advBtn);
        advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        intBtn = button("", new View.OnClickListener() { public void onClick(View v) {
            int i = 0; while (i < INTERVALS.length && INTERVALS[i] != interval) i++;
            interval = INTERVALS[(i + 1) % INTERVALS.length]; refreshButtons(); } });
        advBox.addView(intBtn);
        decBtn = button("", new View.OnClickListener() { public void onClick(View v) { decode = (decode + 1) % 3; refreshButtons(); } });
        advBox.addView(decBtn);
        scaleBtn = button("", new View.OnClickListener() { public void onClick(View v) { scaleX10 = scaleX10 == 10 ? 5 : 10; refreshButtons(); } });
        advBox.addView(scaleBtn);
        advBox.addView(button("Read sensor now", new View.OnClickListener() { public void onClick(View v) { readNow(); } }));
        rawLabel = hint("");
        advBox.addView(rawLabel);
        advBox.addView(button("Diagnostics", new View.OnClickListener() { public void onClick(View v) { showDiagnostics(); } }));
        advBox.addView(button("Restart monitor", new View.OnClickListener() { public void onClick(View v) { restartService(); } }));
        advBox.addView(button("Test run (10 s)", new View.OnClickListener() { public void onClick(View v) { testRun(); } }));
        advBox.addView(button("Export to USB", new View.OnClickListener() { public void onClick(View v) { exportToUsb(); } }));
        advBox.addView(button("Clear history", new View.OnClickListener() { public void onClick(View v) { clearHistory(); } }));
        overrideBtn = button("Unverified firmware: use built-in numbers\u2026", new View.OnClickListener() { public void onClick(View v) { confirmOverride(); } });
        advBox.addView(overrideBtn);
        right.addView(advBox);

        right.addView(button("Save", new View.OnClickListener() { public void onClick(View v) { saveConfig(); } }));
        right.addView(hint("Applies on the next start of the car."));

        root.addView(leftScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        root.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5f));
        root.addView(rightScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));

        setContentView(root);
        refreshButtons();
        startVerification();
    }

    @Override
    protected void onResume() { super.onResume(); handler.removeCallbacks(tick); tick.run(); }

    @Override
    protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    // ---- data ---------------------------------------------------------------
    void refreshData() {
        String status = runRoot("cat " + STATE + "/status 2>/dev/null");
        String state = kv(status, "state"), pct = kv(status, "pct10"), soh = kv(status, "soh_raw");
        String upd = kv(status, "updated"), warn = kv(status, "warn");
        int p = parse(pct, -1);
        bigLabel.setTextColor(Color.WHITE);
        boolean paused = "unverified".equals(state) || "unverified".equals(verifyState) || "interface_mismatch".equals(verifyState);
        if (overrideBtn != null) overrideBtn.setVisibility("unverified".equals(verifyState) ? View.VISIBLE : View.GONE);
        if (paused && !verified()) {
            bigLabel.setText("Paused");
            bigLabel.setTextColor(AMBER);
            updLabel.setText("interface_mismatch".equals(verifyState)
                ? "The car's telematics service isn't the one this package was built for, so nothing is read."
                : "This firmware isn't verified, so nothing is read from the car. See Advanced \u2192 Diagnostics.");
            parkLabel.setText(""); ctxLabel.setText(""); warnLabel.setText(""); sohLabel.setVisibility(View.GONE);
            showSoh(-1);
            return;
        }
        if ("disabled".equals(state)) bigLabel.setText("Off");
        else if (p >= 0) {
            bigLabel.setText(fmt10(p) + "%");
            bigLabel.setTextColor(p < warnPct * 10 ? AMBER : GREEN);
        }
        else if (state.length() == 0) bigLabel.setText("Waiting…");
        else bigLabel.setText("No reading");
        updLabel.setText(upd.length() > 0 ? "Updated " + time(parse(upd, 0))
                : "No reading yet. If this lasts, see Advanced \u2192 Diagnostics.");
        // parked time before this drive, and how the 12V changed meanwhile
        int pMin = parse(kv(status, "parked_min"), -1);
        int pDrop = parse(kv(status, "parked_drop10"), Integer.MIN_VALUE);
        int pRate = parse(kv(status, "parked_rate10_day"), Integer.MIN_VALUE);
        if (pMin >= 0 && pDrop != Integer.MIN_VALUE) {
            StringBuilder pk = new StringBuilder("Parked ").append(duration(pMin)).append(" before this drive: ");
            pk.append(pDrop > 0 ? "\u2212" + fmt10(pDrop) + " pts" : pDrop < 0 ? "+" + fmt10(-pDrop) + " pts" : "no change");
            if (pRate != Integer.MIN_VALUE && pDrop > 0) pk.append("  (\u2212").append(fmt10(pRate)).append("/day)");
            parkLabel.setText(pk.toString());
            parkLabel.setTextColor(pDrop >= dropPct * 10 ? AMBER : Color.WHITE);
        } else {
            parkLabel.setText("");
        }
        // context: main battery charge and outside temperature
        int hv = parse(kv(status, "hv10"), -1);
        int tc = parse(kv(status, "temp_c"), Integer.MIN_VALUE);
        StringBuilder cx = new StringBuilder();
        if (hv >= 0) cx.append("Main battery ").append(fmt10(hv)).append("%");
        if (tc != Integer.MIN_VALUE) {
            if (cx.length() > 0) cx.append("  \u00B7  ");
            cx.append("Outside ").append(Math.round(tc * 9 / 5f + 32)).append("\u00B0F");
        }
        ctxLabel.setText(cx.toString());
        boolean noRead = "no_reading".equals(state);
        sohLabel.setText(noRead ? "The 12V value couldn't be decoded. Check Advanced settings." : "");
        sohLabel.setVisibility(noRead ? View.VISIBLE : View.GONE);
        int sohNow = readSoh();
        if (sohNow < 0) sohNow = parse(soh, -1);   // fall back to the service's last value
        showSoh(sohNow);
        warnLabel.setText(warn.length() > 0 ? "\u26A0 " + warn : "No warnings");
        warnLabel.setTextColor(warn.length() > 0 ? AMBER : MUTED);

        // last ~300 log rows: epoch,event,raw,pct10,soh
        String log = runRoot("cat " + STATE + "/log.csv 2>/dev/null");
        List<long[]> pts = new ArrayList<long[]>();
        for (String line : log.split("\n")) {
            String[] f = line.split(",");
            if (f.length < 4) continue;
            long t = parseL(f[0], 0); int v = parse(f[3], -1);
            if (t <= 0 || v < 0) continue;
            pts.add(new long[] { t, v, "start".equals(f[1]) ? 1 : 0 });
        }
        if (pts.size() > 300) pts = pts.subList(pts.size() - 300, pts.size());
        chart.setData(pts, warnPct * 10);
    }

    // SOH: tm_hscan_service 214 (0xd6) returns one raw byte, a health index (100 = new).
    int readSoh() {
        if (txSoh <= 0) return -1;                       // not verified on this firmware: don't call
        String r = runRoot("service call tm_hscan_service " + txSoh).trim();
        int i = r.indexOf("Parcel(");
        if (i < 0) return -1;
        String[] w = r.substring(i + 7).trim().split("\\s+");
        if (w.length < 2 || !"00000000".equals(w[0])) return -1;
        try { long v = Long.parseLong(w[1], 16); return v > 0 && v <= 100 ? (int) v : -1; } catch (Exception e) { return -1; }
    }

    // green >= 90, amber 75-89, red < 75, gray if unknown
    void showSoh(int v) {
        int bg, fg; String text;
        if (v < 0)       { bg = Color.rgb(42, 47, 54);  fg = MUTED;                text = "--"; }
        else if (v >= 90){ bg = Color.rgb(22, 60, 38);  fg = GREEN;                text = v + "%  Good"; }
        else if (v >= 75){ bg = Color.rgb(70, 50, 18);  fg = AMBER;                text = v + "%  Fair"; }
        else             { bg = Color.rgb(80, 28, 28);  fg = Color.rgb(255, 120, 110); text = v + "%  Low"; }
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(2), fg);
        sohBox.setBackgroundDrawable(d);
        sohTitle.setTextColor(fg);
        sohValue.setTextColor(fg);
        sohValue.setText(text);
    }

    void readNow() {
        if (txSoc <= 0) { rawLabel.setText("Not verified on this firmware, so nothing was read."); return; }
        String r = runRoot("service call tm_hscan_service " + txSoc).trim();
        String shown = r;
        int i = r.indexOf("Parcel(");
        if (i >= 0) {
            String[] w = r.substring(i + 7).trim().split("\\s+");
            if (w.length >= 2 && "00000000".equals(w[0])) {
                try { shown = "Sensor now: " + Long.parseLong(w[1], 16) + "%   (raw " + w[1] + ")"; } catch (Exception e) { }
            }
        }
        rawLabel.setText(shown);
    }

    // ---- firmware verification ------------------------------------------------
    // Transaction numbers can shift between firmware builds, so they're looked
    // up BY NAME in this firmware's own ITM_HSCANService definition and saved
    // (with the firmware id) for the service. Same rules as verify() in
    // svc/watchdog.sh; hand-verified builds are listed in known_firmware.txt.
    static final String IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    static final String KNOWN_FILE = "/data/nemesis/apps/battery.watchdog/known_firmware.txt";
    String fw = "", verifyState = "checking", verifySource = "";
    int txSoc = -1, txHv = -1, txSoh = -1;

    void startVerification() {
        new Thread(new Runnable() { public void run() {
            final String result = verify();
            handler.post(new Runnable() { public void run() { verifyState = result; refreshData(); } });
        } }).start();
    }

    /** Returns resolved | saved | known_firmware | override | interface_mismatch | unverified. */
    String verify() {
        txSoc = txHv = txSoh = -1;
        fw = runRoot("getprop ro.build.version.incremental").trim();
        String svcList = runRoot("service list 2>/dev/null");
        if (!svcList.contains("tm_hscan_service: [" + IFACE + "]")) return "interface_mismatch";

        // 1. numbers already resolved (or overridden) for exactly this firmware
        String saved = runRoot("cat " + STATE + "/txn 2>/dev/null");
        if (fw.length() > 0 && fw.equals(kv(saved, "fw")) && parse(kv(saved, "soc"), -1) > 0) {
            txSoc = parse(kv(saved, "soc"), -1); txHv = parse(kv(saved, "hv"), -1); txSoh = parse(kv(saved, "soh"), -1);
            verifySource = kv(saved, "source");
            return "manual-override".equals(verifySource) ? "override" : "saved";
        }

        // 2. resolve by name from the firmware's own interface definition.
        //    Skipped if this app version already searched this firmware and found nothing.
        String missKey = fw + " " + APP_VERSION;
        if (!missKey.equals(runRoot("cat " + STATE + "/resolve_miss 2>/dev/null").trim())) {
            StringBuilder lg = new StringBuilder();
            String r = resolveByName(lg);
            runRoot("mkdir -p " + STATE);
            writeStateFile("resolve.log", lg.toString());
            if (r != null) { verifySource = r; saveTxn(r); return "resolved"; }
            writeStateFile("resolve_miss", missKey + "\n");
        }

        // 3. hand-verified firmware
        // one line per build: <fw> <soc> <hv> <soh>  # comment ("-" = not available)
        for (String line : runRoot("cat " + KNOWN_FILE + " 2>/dev/null").split("\n")) {
            String l = line.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            String[] f = l.split("\\s+");
            if (f.length < 2 || !f[0].equals(fw) || parse(f[1], -1) <= 0) continue;
            txSoc = parse(f[1], -1);
            txHv = f.length > 2 ? parse(f[2], -1) : -1;
            txSoh = f.length > 3 ? parse(f[3], -1) : -1;
            verifySource = "known_firmware.txt";
            return "known_firmware";
        }
        return "unverified";
    }

    static final String APP_VERSION = "0.5.0";
    static final String[] SEARCH_DIRS = { "/system/framework", "/system/app", "/system/priv-app",
            "/vendor/framework", "/vendor/app", "/system/vendor/app", "/system/vendor/framework" };
    static final byte[] NEEDLE = "tm_interface/ITM_HSCANService$Stub;".getBytes();

    /**
     * Finds ITM_HSCANService$Stub in the firmware and reads its transaction codes.
     * Pass 1: .odex/.dex files, searched in 1 MiB chunks, parsed only on a hit.
     * Pass 2: classes*.dex inside .jar/.apk (builds that aren't odexed).
     * Returns the source path, or null. Everything it tries goes into {@code lg}.
     */
    String resolveByName(StringBuilder lg) {
        java.util.List<File> plain = new java.util.ArrayList<File>(), zips = new java.util.ArrayList<File>();
        for (String dname : SEARCH_DIRS) collect(new File(dname), plain, zips, 2);
        lg.append("searched ").append(plain.size()).append(" odex/dex and ").append(zips.size()).append(" jar/apk files\n");
        for (File f : plain) {
            try {
                boolean hit;
                try { hit = TxnResolver.fileContains(f, NEEDLE); }
                catch (java.io.IOException e) { File c = viaRoot(f); hit = c != null && TxnResolver.fileContains(c, NEEDLE); if (!hit && c != null) c.delete(); }
                if (!hit) continue;
                lg.append("found Stub in ").append(f).append("\n");
                Map<String, Integer> m;
                try { m = TxnResolver.transactions(f, TxnResolver.STUB); }
                catch (java.io.IOException e) { File c = viaRoot(f); m = TxnResolver.transactions(c, TxnResolver.STUB); c.delete(); }
                lg.append("  ").append(m.size()).append(" transactions; getBatSoc=").append(m.get("getBatSoc")).append("\n");
                if (useMap(m)) return f.getPath();
            } catch (Throwable t) {
                lg.append("  ").append(f).append(": ").append(t).append("\n");
            }
        }
        for (File f : zips) {
            try {
                Map<String, Integer> m = TxnResolver.transactionsInZip(f, TxnResolver.STUB, NEEDLE);
                if (m.isEmpty()) continue;
                lg.append("found Stub in ").append(f).append(": ").append(m.size()).append(" transactions; getBatSoc=").append(m.get("getBatSoc")).append("\n");
                if (useMap(m)) return f.getPath();
            } catch (Throwable t) {
                lg.append("  ").append(f).append(": ").append(t).append("\n");
            }
        }
        lg.append("not found\n");
        return null;
    }

    boolean useMap(Map<String, Integer> m) {
        Integer soc = m.get("getBatSoc");
        if (soc == null) return false;
        Integer hv = m.get("remainBatteryPercentage"), soh = m.get("getXEV_Bms_GDS_SOH");
        txSoc = soc; txHv = hv == null ? -1 : hv; txSoh = soh == null ? -1 : soh;
        return true;
    }

    static void collect(File dir, java.util.List<File> plain, java.util.List<File> zips, int depth) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory()) { if (depth > 1) collect(f, plain, zips, depth - 1); }
            else if (n.endsWith(".odex") || n.endsWith(".dex")) plain.add(f);
            else if (n.endsWith(".jar") || n.endsWith(".apk")) zips.add(f);
        }
    }

    /** Copy a file this app can't read into its cache via root; null on failure. */
    File viaRoot(File f) {
        File tmp = new File(getCacheDir(), "resolve.bin");
        runRoot("cat '" + f.getPath() + "' > '" + tmp.getAbsolutePath() + "' && chmod 644 '" + tmp.getAbsolutePath() + "'");
        return tmp.length() > 0 ? tmp : null;
    }

    void writeStateFile(String name, String content) {
        try {
            File tmp = new File(getCacheDir(), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try { out.write(content.getBytes("UTF-8")); } finally { out.close(); }
            runRoot("cat '" + tmp.getAbsolutePath() + "' > " + STATE + "/" + name);
            tmp.delete();
        } catch (Exception e) { /* best effort */ }
    }

    void saveTxn(String source) {
        // plain echo: KitKat has no printf binary and mksh R43 has no printf builtin
        String f = STATE + "/txn";
        runRoot("mkdir -p " + STATE
            + "; echo 'fw=" + fw + "' > " + f
            + "; echo 'soc=" + txSoc + "' >> " + f
            + "; echo 'hv=" + (txHv > 0 ? txHv : "") + "' >> " + f
            + "; echo 'soh=" + (txSoh > 0 ? txSoh : "") + "' >> " + f
            + "; echo 'source=" + source + "' >> " + f);
    }

    void confirmOverride() {
        new AlertDialog.Builder(this).setTitle("Use built-in numbers on this firmware?")
            .setMessage("This firmware (" + fw + ") hasn't been verified, and Battery Watchdog couldn't confirm the "
                + "transaction numbers by name. On a different firmware the same numbers can reach a different "
                + "function, including ones that reset or change settings.\n\nOnly continue if you've run the USB "
                + "probe on this car and its txn 137 / 74 / 214 values look like a 12V %, a main battery %, and a "
                + "health index (see docs/TECHNICAL.md).")
            .setPositiveButton("I've checked, use them", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) {
                    txSoc = 137; txHv = 74; txSoh = 214; saveTxn("manual-override");
                    verifyState = "override"; verifySource = "manual-override";
                    toast("Saved. The monitor starts within a minute."); refreshData();
                } })
            .setNegativeButton("Cancel", null).show();
    }

    boolean verified() { return txSoc > 0; }

    static final String SVC_ID = "battery.watchdog/watch";
    static final String SVC_LOG = "/data/nemesis/logs/battery.watchdog/watch.log";
    static final String SVC_SCRIPT = "/data/nemesis/apps/battery.watchdog/svc/watchdog.sh";

    void showDiagnostics() {
        new AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(buildDiagnostics())
            .setPositiveButton("Close", null).show();
    }

    /** Diagnostics text, shown in the dialog and saved by the USB export. */
    String buildDiagnostics() {
        StringBuilder d = new StringBuilder();
        d.append("service: ").append(runRoot(NEM + " service status " + SVC_ID + " 2>&1").trim()).append("\n");
        d.append("firmware: ").append(fw).append("\n");
        d.append("verification: ").append(verifyState).append(verifySource.length() > 0 ? " (" + verifySource + ")" : "")
            .append("  soc=").append(txSoc).append(" hv=").append(txHv).append(" soh=").append(txSoh).append("\n");
        d.append("lookup by name:\n").append(firstLines(runRoot("cat " + STATE + "/resolve.log 2>/dev/null"), 8)).append("\n");
        // newest log lines first, so the latest error is at the top
        String log = runRoot("cat " + SVC_LOG + " 2>&1");
        if (log.trim().length() == 0 || log.contains("No such file"))
            log = runRoot(NEM + " log service " + SVC_ID + " 2>&1");
        String[] lines = log.trim().split("\n");
        d.append("service log, newest first:\n");
        if (log.trim().length() == 0) d.append("(empty)\n");
        for (int i = lines.length - 1, n = 0; i >= 0 && n < 15; i--, n++) d.append(lines[i]).append("\n");
        d.append("\n");
        // NEMESIS's own log says why it marked the service failed
        String nl = runRoot("cat /data/nemesis/nemesis.log 2>&1");
        String[] nlines = nl.split("\n");
        d.append("nemesis.log, battery.watchdog lines, newest first:\n");
        int shown = 0;
        for (int i = nlines.length - 1; i >= 0 && shown < 12; i--) {
            if (nlines[i].contains("battery.watchdog") || nlines[i].contains("watchdog.sh")) { d.append(nlines[i]).append("\n"); shown++; }
        }
        if (shown == 0) d.append(nl.contains("No such file") ? "(no /data/nemesis/nemesis.log)\n" : "(no matching lines)\n");
        d.append("\n");
        d.append("shell: ").append(runRoot("echo $KSH_VERSION").trim()).append("\n");
        d.append("script file: ").append(runRoot("ls -l " + SVC_SCRIPT + " 2>&1").trim()).append("\n");
        String chk = runRoot("/system/bin/sh -n " + SVC_SCRIPT + " 2>&1 && echo OK").trim();
        d.append("script syntax check: ").append(chk.length() == 0 ? "(no output)" : chk).append("\n");
        d.append("date: ").append(runRoot("date; date +%s").trim().replace("\n", "  |  ")).append("\n");
        d.append("display: ").append(runRoot("dumpsys power | grep -E 'mWakefulness=|mScreenOn=|policy=' 2>/dev/null").trim()).append("\n\n");
        d.append("status file:\n").append(runRoot("cat " + STATE + "/status 2>&1").trim()).append("\n\n");
        d.append("state dir:\n").append(runRoot("ls -l " + STATE + " 2>&1").trim()).append("\n\n");
        // outside temperature: which source the monitor used, and what each source shows now
        String st = runRoot("cat " + STATE + "/status 2>/dev/null");
        d.append("temperature: ").append(kv(st, "temp_c").length() > 0 ? kv(st, "temp_c") + " C via " + kv(st, "temp_src") : "none found").append("\n");
        d.append("climate dump, OutTemp lines:\n").append(firstLines(runRoot("dumpsys com.hkmc.micom.datc 2>&1 | grep -i outtemp"), 8)).append("\n");
        d.append("logcat temperature lines, newest:\n")
            .append(lastLines(runRoot("logcat -d 2>/dev/null | grep -E 'outTemperature|setOutsideTempUIAdvanced'"), 3)).append("\n");
        return d.toString();
    }

    static String firstLines(String text, int n) {
        String t = text.trim();
        if (t.length() == 0) return "(none)\n";
        String[] l = t.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.length && i < n; i++) b.append(l[i]).append("\n");
        if (l.length > n) b.append("... (" + (l.length - n) + " more)\n");
        return b.toString();
    }

    static String lastLines(String text, int n) {
        String t = text.trim();
        if (t.length() == 0) return "(none)\n";
        String[] l = t.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.length - n); i < l.length; i++) b.append(l[i]).append("\n");
        return b.toString();
    }

    void restartService() {
        // empty the old log first so anything shown afterwards is from this run
        String r = runRoot(": > " + SVC_LOG + " 2>/dev/null; "
                + NEM + " service stop " + SVC_ID + " 2>&1; "
                + NEM + " service start " + SVC_ID + " 2>&1; sleep 3; "
                + NEM + " service status " + SVC_ID + " 2>&1");
        rawLabel.setText("Restart: " + r.trim().replace("\n", "  |  "));
        toast("Monitor restarted. Check Diagnostics in 30 seconds.");
    }

    // Runs the installed monitor script directly as root for a couple of loops
    // (BWD_TEST shortens the timings), bypassing NEMESIS. Real readings are saved.
    void testRun() {
        if (!verified()) { toast("Not verified on this firmware, so the test run is disabled."); return; }
        toast("Running the monitor for about 10 seconds\u2026");
        String out = runRoot("cd " + STATE + " && BWD_TEST=1 BWD_TEST_LOOPS=3 /system/bin/sh " + SVC_SCRIPT
                + " > " + STATE + "/testrun.log 2>&1; echo \"exit code $?\"; cat " + STATE + "/testrun.log");
        new AlertDialog.Builder(this).setTitle("Test run").setMessage(out.trim().length() == 0 ? "(no output)" : out.trim())
            .setPositiveButton("Close", null).show();
        refreshData();
    }

    // ---- export to USB ---------------------------------------------------------
    static final String[] USB_ROOTS = { "/storage/usb0", "/storage/usb1", "/storage/usb2",
            "/mnt/media_rw/usb0", "/mnt/media_rw/usb1" };
    static final String[] STATE_FILES = { "log.csv", "log.old.csv", "sessions", "warnings", "status", "txn",
            "enabled", "warn_pct", "drop_pct", "interval_s", "toast_start", "decode", "scale_x10" };

    void exportToUsb() {
        toast("Exporting\u2026");
        new Thread(new Runnable() { public void run() {
            final String msg = doExport();
            handler.post(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Export to USB").setMessage(msg)
                    .setPositiveButton("Close", null).show();
            } });
        } }).start();
    }

    String doExport() {
        // first writable USB root
        StringBuilder probe = new StringBuilder();
        for (String r : USB_ROOTS) {
            probe.append("[ -d " + r + " ] && touch " + r + "/.bwd_w 2>/dev/null && rm " + r + "/.bwd_w && echo " + r + " && exit 0; ");
        }
        String usb = runRoot("( " + probe + " ) 2>/dev/null").trim();
        if (usb.length() == 0 || !usb.startsWith("/")) return "No USB drive found. Insert a FAT32 or exFAT drive and try again.";
        usb = usb.split("\n")[0].trim();

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        String dir = usb + "/BatteryWatchdog-export-" + stamp;
        runRoot("mkdir -p '" + dir + "/raw'");

        // raw copies (cat: KitKat's toolbox has no cp)
        StringBuilder cp = new StringBuilder();
        for (String f : STATE_FILES) cp.append("[ -f " + STATE + "/" + f + " ] && cat " + STATE + "/" + f + " > '" + dir + "/raw/" + f + "'; ");
        runRoot(cp.toString());

        // readable files built here, then copied over by root
        SimpleDateFormat t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        int rows = 0;
        StringBuilder rd = new StringBuilder("time,event,v12_pct,main_battery_pct,outside_f,hv_soh,raw\n");
        String log = runRoot("cat " + STATE + "/log.old.csv " + STATE + "/log.csv 2>/dev/null");
        for (String line : log.split("\n")) {
            String[] f = line.split(",", -1);
            if (f.length < 4) continue;
            long ts = parseL(f[0], 0);
            if (ts <= 0) continue;
            int p = parse(f[3], -1);
            String hv = f.length > 5 && parse(f[5], -1) >= 0 ? fmt10(parse(f[5], -1)) : "";
            String tf = f.length > 6 && f[6].trim().length() > 0 ? String.valueOf(Math.round(parse(f[6], 0) * 9 / 5f + 32)) : "";
            rd.append(t.format(new Date(ts * 1000))).append(',').append(f[1]).append(',')
              .append(p >= 0 ? fmt10(p) : "").append(',').append(hv).append(',').append(tf).append(',')
              .append(f.length > 4 ? f[4] : "").append(',').append(f[2]).append('\n');
            rows++;
        }

        // one row per finished drive, with the parked time and change before it
        StringBuilder dv = new StringBuilder("drive_start,drive_minutes,v12_start_pct,v12_end_pct,change_during_drive_pts,parked_before_minutes,change_while_parked_pts\n");
        long prevEnd = -1; int prevEndPct = -1;
        int drives = 0;
        for (String line : runRoot("cat " + STATE + "/sessions 2>/dev/null").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) continue;
            long st = parseL(f[0], 0); int min = parse(f[1], -1), sp = parse(f[2], -1), ep = parse(f[3], -1);
            if (st <= 0 || min < 0 || sp < 0 || ep < 0) continue;
            String parked = prevEnd > 0 && st > prevEnd ? String.valueOf((st - prevEnd) / 60) : "";
            String pchg = prevEnd > 0 && st > prevEnd ? fmtSigned10(sp - prevEndPct) : "";
            dv.append(t.format(new Date(st * 1000))).append(',').append(min).append(',').append(fmt10(sp)).append(',')
              .append(fmt10(ep)).append(',').append(fmtSigned10(ep - sp)).append(',').append(parked).append(',').append(pchg).append('\n');
            prevEnd = st + min * 60L; prevEndPct = ep; drives++;
        }

        StringBuilder wr = new StringBuilder();
        for (String line : runRoot("cat " + STATE + "/warnings 2>/dev/null").split("\n")) {
            String l = line.trim();
            int sp = l.indexOf(' ');
            if (sp <= 0) continue;
            long ts = parseL(l.substring(0, sp), 0);
            wr.append(ts > 0 ? t.format(new Date(ts * 1000)) : l.substring(0, sp)).append("  ").append(l.substring(sp + 1)).append('\n');
        }
        if (wr.length() == 0) wr.append("No warnings recorded.\n");

        String err = "";
        err += writeToUsb("readings.csv", rd.toString(), dir);
        err += writeToUsb("drives.csv", dv.toString(), dir);
        err += writeToUsb("warnings.txt", wr.toString(), dir);
        err += writeToUsb("diagnostics.txt", "Battery Watchdog export " + stamp + "\n\n" + buildDiagnostics(), dir);
        runRoot("sync");
        if (err.length() > 0) return "Some files couldn't be written to " + dir + ":\n" + err;
        return "Exported " + rows + " readings and " + drives + " drives to\n" + dir
            + "\n\nreadings.csv and drives.csv open in Excel or Google Sheets. raw/ has the original files.";
    }

    String writeToUsb(String name, String content, String dir) {
        try {
            File tmp = new File(getCacheDir(), name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try { out.write(content.getBytes("UTF-8")); } finally { out.close(); }
            String r = runRoot("cat '" + tmp.getAbsolutePath() + "' > '" + dir + "/" + name + "' && echo ok").trim();
            tmp.delete();
            return r.endsWith("ok") ? "" : name + ": " + r + "\n";
        } catch (Exception e) {
            return name + ": " + e.getMessage() + "\n";
        }
    }

    static String fmtSigned10(int v) { return (v > 0 ? "+" : v < 0 ? "-" : "") + fmt10(Math.abs(v)); }

    void clearHistory() {
        runRoot("rm -f " + STATE + "/log.csv " + STATE + "/log.old.csv " + STATE + "/sessions "
                + STATE + "/warnings " + STATE + "/lines");
        toast("History cleared");
        refreshData();
    }

    // ---- config I/O via `nemesis root -c` -------------------------------------
    String runRoot(String cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[] { NEM, "root", "-c", cmd });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
            p.waitFor();
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
        return out.toString();
    }

    int readInt(String name, int def) {
        return parse(runRoot("cat " + STATE + "/" + name + " 2>/dev/null").trim(), def);
    }

    void loadConfig() {
        enabled    = readInt("enabled", 1) == 1;
        toastStart = readInt("toast_start", 0) == 1;
        warnPct    = clamp(readInt("warn_pct", 60), 30, 90);
        dropPct    = clamp(readInt("drop_pct", 15), 5, 40);
        decode     = clamp(readInt("decode", 1), 0, 2);
        scaleX10   = readInt("scale_x10", 10) == 5 ? 5 : 10;
        interval   = readInt("interval_s", 300);
    }

    void saveConfig() {
        String cmd = "mkdir -p " + STATE
            + "; echo " + (enabled ? 1 : 0) + " > " + STATE + "/enabled"
            + "; echo " + (toastStart ? 1 : 0) + " > " + STATE + "/toast_start"
            + "; echo " + warnPct + " > " + STATE + "/warn_pct"
            + "; echo " + dropPct + " > " + STATE + "/drop_pct"
            + "; echo " + decode + " > " + STATE + "/decode"
            + "; echo " + scaleX10 + " > " + STATE + "/scale_x10"
            + "; echo " + interval + " > " + STATE + "/interval_s";
        String r = runRoot(cmd);
        toast(r.startsWith("ERR") ? "Save failed: " + r : "Saved");
    }

    void refreshButtons() {
        enBtn.setText(enabled ? "Monitor: on" : "Monitor: off");
        warnThrLabel.setText("Warn below " + warnPct + "%");
        dropThrLabel.setText("Warn if it drops " + dropPct + "+ pts while parked");
        toastBtn.setText(toastStart ? "Pop-up every start: on" : "Pop-up every start: warnings only");
        intBtn.setText("Sample every " + (interval / 60) + " min");
        advBtn.setText(advanced ? "Advanced \u25B4" : "Advanced \u25BE");
        advBox.setVisibility(advanced ? View.VISIBLE : View.GONE);
        decBtn.setText("Decode as: " + DECODE[decode]);
        scaleBtn.setText("Scale: " + (scaleX10 == 10 ? "1 raw = 1 %" : "1 raw = 0.5 %"));
    }

    // ---- chart ----------------------------------------------------------------
    static class Chart extends View {
        List<long[]> pts = new ArrayList<long[]>();
        int warn10 = 600;
        final float d;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(), warnP = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(Context c, float density) {
            super(c);
            d = density;
            line.setColor(ACCENT); line.setStrokeWidth(3 * d);
            dot.setColor(Color.WHITE);
            grid.setColor(Color.rgb(50, 55, 62)); grid.setStrokeWidth(d);
            warnP.setColor(AMBER); warnP.setStrokeWidth(2 * d);
            text.setColor(MUTED); text.setTextSize(14 * d);
        }

        void setData(List<long[]> p, int w) { pts = p; warn10 = w; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), left = 44 * d, top = 10 * d, bottom = h - 10 * d;
            // y range: 30-100 % (a healthy 12V lives up there); drop the floor if data goes lower
            int lo = 30;
            for (long[] p : pts) while (p[1] < lo * 10 && lo > 0) lo -= 10;
            float span = 100 - lo;
            for (int pct = lo; pct <= 100; pct += 10) {
                float y = bottom - (bottom - top) * (pct - lo) / span;
                c.drawLine(left, y, w, y, grid);
                if ((pct - lo) % 20 == 0 || pct == 100) c.drawText(pct + "%", 0, y + 5 * d, text);
            }
            float wy = bottom - (bottom - top) * (warn10 / 10f - lo) / span;
            c.drawLine(left, wy, w, wy, warnP);
            if (pts.size() < 2) { c.drawText("Not enough data yet. Readings appear after a drive or two.", left + 12 * d, h / 2, text); return; }
            // x = sample index (drives are far apart in time, so time scaling hides detail)
            float step = (w - left - 10 * d) / (pts.size() - 1);
            float px = 0, py = 0;
            for (int i = 0; i < pts.size(); i++) {
                long[] p = pts.get(i);
                float x = left + i * step, y = bottom - (bottom - top) * (p[1] / 10f - lo) / span;
                if (i > 0 && p[2] == 0) c.drawLine(px, py, x, y, line);   // break the line at each new drive
                if (p[2] == 1) c.drawCircle(x, y, 5 * d, dot);
                px = x; py = y;
            }
        }
    }

    // ---- helpers --------------------------------------------------------------
    interface Setter { void set(int v); }

    static String kv(String text, String key) {
        for (String l : text.split("\n")) if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
        return "";
    }
    static int parse(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    static long parseL(String s, long d) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return d; } }
    static String fmt10(int v) { return (v / 10) + "." + (v % 10); }
    static String duration(int min) {
        if (min < 60) return min + " min";
        if (min < 48 * 60) return (min / 60) + " h " + (min % 60) + " min";
        return (min / 1440) + " d " + ((min % 1440) / 60) + " h";
    }
    static String time(long epoch) {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(epoch * 1000));
    }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    SeekBar seek(final int min, int max, int val, final Setter s) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { s.set(p + min); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        return sb;
    }
    Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setTextSize(17); b.setMinHeight(dp(56)); b.setOnClickListener(l); return b;
    }
    LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f)); return v; }
    TextView label() { TextView t = new TextView(this); t.setTextColor(Color.WHITE); t.setTextSize(17); t.setPadding(0, dp(10), 0, 0); return t; }
    TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(26);
        t.setPadding(0, 0, 0, dp(8)); return t;
    }
    TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(ACCENT); t.setTextSize(17);
        t.setPadding(0, dp(14), 0, dp(4)); return t;
    }
    TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(MUTED); t.setTextSize(15);
        t.setPadding(0, 0, 0, dp(8)); return t;
    }
}
