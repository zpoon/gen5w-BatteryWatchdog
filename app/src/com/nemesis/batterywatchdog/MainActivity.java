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
    // svc/watchdog.sh, which also has the hand-verified KNOWN_FW list.
    static final String IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    static final String KNOWN_FW = "eng.vmadmin.20260507.094910";   // 2023 Ioniq 5 SEL US, 260507
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

        // 2. resolve by name from the firmware's own interface definition
        String files = runRoot("grep -l 'ITM_HSCANService' /system/framework/*.odex /system/app/*.odex "
                + "/system/priv-app/*.odex /vendor/framework/*.odex 2>/dev/null");
        for (String path : files.split("\n")) {
            path = path.trim();
            if (path.length() == 0 || !path.startsWith("/")) continue;
            Map<String, Integer> m = readTransactions(path);
            Integer soc = m.get("getBatSoc");
            if (soc == null) continue;
            txSoc = soc;
            Integer hv = m.get("remainBatteryPercentage"), soh = m.get("getXEV_Bms_GDS_SOH");
            txHv = hv == null ? -1 : hv; txSoh = soh == null ? -1 : soh;
            verifySource = path;
            saveTxn(verifySource);
            return "resolved";
        }

        // 3. hand-verified firmware
        if (KNOWN_FW.equals(fw)) { txSoc = 137; txHv = 74; txSoh = 214; verifySource = "built-in"; return "known_firmware"; }
        return "unverified";
    }

    Map<String, Integer> readTransactions(String path) {
        try {
            return TxnResolver.transactions(new File(path), TxnResolver.STUB);
        } catch (Exception e) {
            // not readable by this app's uid: copy it via root into the app's cache first
            try {
                File tmp = new File(getCacheDir(), "resolve.odex");
                runRoot("cat '" + path + "' > '" + tmp.getAbsolutePath() + "' && chmod 644 '" + tmp.getAbsolutePath() + "'");
                Map<String, Integer> m = TxnResolver.transactions(tmp, TxnResolver.STUB);
                tmp.delete();
                return m;
            } catch (Exception e2) {
                return new HashMap<String, Integer>();
            }
        }
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
        StringBuilder d = new StringBuilder();
        d.append("service: ").append(runRoot(NEM + " service status " + SVC_ID + " 2>&1").trim()).append("\n");
        d.append("firmware: ").append(fw).append("\n");
        d.append("verification: ").append(verifyState).append(verifySource.length() > 0 ? " (" + verifySource + ")" : "")
            .append("  soc=").append(txSoc).append(" hv=").append(txHv).append(" soh=").append(txSoh).append("\n\n");
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
        d.append("state dir:\n").append(runRoot("ls -l " + STATE + " 2>&1").trim()).append("\n");
        new AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(d.toString())
            .setPositiveButton("Close", null).show();
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
