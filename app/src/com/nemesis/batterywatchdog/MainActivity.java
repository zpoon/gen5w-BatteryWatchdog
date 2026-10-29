package com.nemesis.batterywatchdog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
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

    boolean enabled = true, toastStart = false;
    int warnPct = 60, dropPct = 15, decode = 1, scaleX10 = 10, interval = 300;

    TextView bigLabel, infoLabel, warnLabel, warnThrLabel, dropThrLabel, rawLabel;
    Button enBtn, toastBtn, decBtn, scaleBtn, intBtn;
    Chart chart;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadConfig();

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 20, 24));
        sv.addView(root);

        root.addView(title("Battery Watchdog"));

        bigLabel = new TextView(this);
        bigLabel.setTextColor(Color.WHITE);
        bigLabel.setTextSize(44);
        root.addView(bigLabel);
        infoLabel = hint("");
        root.addView(infoLabel);
        warnLabel = new TextView(this);
        warnLabel.setTextColor(Color.rgb(255, 170, 60));
        warnLabel.setTextSize(15);
        root.addView(warnLabel);

        root.addView(section("Recent readings (start of each drive = dot)"));
        chart = new Chart(this);
        root.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        LinearLayout row = hrow();
        row.addView(button("Refresh", new View.OnClickListener() { public void onClick(View v) { refreshData(); } }));
        row.addView(button("Read sensor now", new View.OnClickListener() { public void onClick(View v) { readNow(); } }));
        root.addView(row);
        rawLabel = hint("");
        root.addView(rawLabel);

        root.addView(section("Warnings"));
        enBtn = button("", new View.OnClickListener() { public void onClick(View v) { enabled = !enabled; refreshButtons(); } });
        root.addView(enBtn);

        warnThrLabel = label();
        root.addView(warnThrLabel);
        root.addView(seek(30, 90, warnPct, new Setter() { public void set(int v) { warnPct = v; refreshButtons(); } }));

        dropThrLabel = label();
        root.addView(dropThrLabel);
        root.addView(seek(5, 40, dropPct, new Setter() { public void set(int v) { dropPct = v; refreshButtons(); } }));

        toastBtn = button("", new View.OnClickListener() { public void onClick(View v) { toastStart = !toastStart; refreshButtons(); } });
        root.addView(toastBtn);

        root.addView(section("Sampling / decoding (set from the probe results)"));
        intBtn = button("", new View.OnClickListener() { public void onClick(View v) {
            int i = 0; while (i < INTERVALS.length && INTERVALS[i] != interval) i++;
            interval = INTERVALS[(i + 1) % INTERVALS.length]; refreshButtons(); } });
        root.addView(intBtn);
        decBtn = button("", new View.OnClickListener() { public void onClick(View v) { decode = (decode + 1) % 3; refreshButtons(); } });
        root.addView(decBtn);
        scaleBtn = button("", new View.OnClickListener() { public void onClick(View v) { scaleX10 = scaleX10 == 10 ? 5 : 10; refreshButtons(); } });
        root.addView(scaleBtn);

        root.addView(gap());
        LinearLayout row2 = hrow();
        row2.addView(button("Save", new View.OnClickListener() { public void onClick(View v) { saveConfig(); } }));
        row2.addView(button("Clear history", new View.OnClickListener() { public void onClick(View v) { clearHistory(); } }));
        root.addView(row2);
        root.addView(hint("Settings take effect on the next start of the car."));

        setContentView(sv);
        refreshButtons();
        refreshData();
    }

    // ---- data ---------------------------------------------------------------
    void refreshData() {
        String status = runRoot("cat " + STATE + "/status 2>/dev/null");
        String state = kv(status, "state"), pct = kv(status, "pct10"), soh = kv(status, "soh_raw");
        String upd = kv(status, "updated"), warn = kv(status, "warn");
        int p = parse(pct, -1);
        if ("disabled".equals(state)) bigLabel.setText("Monitor off");
        else if (p >= 0) bigLabel.setText(fmt10(p) + " %");
        else if (state.length() == 0) bigLabel.setText("No data yet");
        else bigLabel.setText("No reading");
        StringBuilder info = new StringBuilder();
        if (upd.length() > 0) info.append("Updated ").append(time(parse(upd, 0))).append("   ");
        if (soh.length() > 0) info.append("HV battery SOH (raw) ").append(soh);
        if ("no_reading".equals(state)) info.append("\nThe 12V value couldn't be decoded. Run the probe and check the decode settings.");
        infoLabel.setText(info.toString());
        warnLabel.setText(warn.length() > 0 ? "⚠ " + warn : "");

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

    void readNow() {
        String r = runRoot("service call tm_hscan_service 137").trim();
        rawLabel.setText("getBatSoc (0x89) → " + r);
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
        enBtn.setText(enabled ? "Monitor: ON" : "Monitor: OFF");
        warnThrLabel.setText("Warn when 12V is below " + warnPct + " %");
        dropThrLabel.setText("Warn when it drops " + dropPct + "+ points while parked");
        toastBtn.setText(toastStart ? "Show reading every start: YES" : "Show reading every start: NO (warnings only)");
        intBtn.setText("Sample every " + (interval / 60) + " min");
        decBtn.setText("Decode value as: " + DECODE[decode]);
        scaleBtn.setText("Scale: " + (scaleX10 == 10 ? "1 raw = 1 %" : "1 raw = 0.5 %"));
    }

    // ---- chart ----------------------------------------------------------------
    static class Chart extends View {
        List<long[]> pts = new ArrayList<long[]>();
        int warn10 = 600;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(), warnP = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(Context c) {
            super(c);
            line.setColor(Color.rgb(120, 180, 255)); line.setStrokeWidth(3);
            dot.setColor(Color.WHITE);
            grid.setColor(Color.rgb(50, 55, 62));
            warnP.setColor(Color.rgb(255, 170, 60)); warnP.setStrokeWidth(2);
            text.setColor(Color.rgb(150, 160, 170)); text.setTextSize(22);
        }

        void setData(List<long[]> p, int w) { pts = p; warn10 = w; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), left = 60, top = 10, bottom = h - 10;
            for (int pct = 0; pct <= 100; pct += 25) {
                float y = bottom - (bottom - top) * pct / 100f;
                c.drawLine(left, y, w, y, grid);
                c.drawText(pct + "%", 0, y + 8, text);
            }
            float wy = bottom - (bottom - top) * warn10 / 1000f;
            c.drawLine(left, wy, w, wy, warnP);
            if (pts.size() < 2) { c.drawText("Not enough data yet", left + 20, h / 2, text); return; }
            // x = sample index (sessions are far apart in time, so time scaling hides detail)
            float step = (w - left - 10) / (pts.size() - 1);
            float px = 0, py = 0;
            for (int i = 0; i < pts.size(); i++) {
                long[] p = pts.get(i);
                float x = left + i * step, y = bottom - (bottom - top) * p[1] / 1000f;
                if (i > 0 && p[2] == 0) c.drawLine(px, py, x, y, line);   // break the line at each new drive
                if (p[2] == 1) c.drawCircle(x, y, 6, dot);
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
    Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b; }
    LinearLayout hrow() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    TextView label() { TextView t = new TextView(this); t.setTextColor(Color.WHITE); t.setTextSize(17); return t; }
    TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(26);
        t.setPadding(0, 0, 0, dp(8)); return t;
    }
    TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.rgb(120, 180, 255)); t.setTextSize(16);
        t.setPadding(0, dp(14), 0, dp(4)); return t;
    }
    TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.rgb(150, 160, 170)); t.setTextSize(13);
        t.setPadding(0, 0, 0, dp(8)); return t;
    }
    View gap() { View v = new View(this); v.setMinimumHeight(dp(10)); return v; }
}
