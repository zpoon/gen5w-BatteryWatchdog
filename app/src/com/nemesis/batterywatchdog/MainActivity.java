package com.nemesis.batterywatchdog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
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
    static final int BG = Color.rgb(18, 20, 24), AMBER = Color.rgb(255, 170, 60),
            GREEN = Color.rgb(110, 210, 140), MUTED = Color.rgb(150, 160, 170), ACCENT = Color.rgb(120, 180, 255);

    boolean enabled = true, toastStart = false, advanced = false;
    int warnPct = 60, dropPct = 15, decode = 1, scaleX10 = 10, interval = 300;

    TextView bigLabel, updLabel, sohLabel, warnLabel, warnThrLabel, dropThrLabel, rawLabel;
    Button enBtn, toastBtn, decBtn, scaleBtn, intBtn, advBtn;
    LinearLayout advBox;
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
        LinearLayout left = column();
        left.addView(title("Battery Watchdog"));
        left.addView(hint("12V battery"));
        bigLabel = new TextView(this);
        bigLabel.setTextColor(Color.WHITE);
        bigLabel.setTextSize(64);
        left.addView(bigLabel);
        updLabel = hint("");
        left.addView(updLabel);
        warnLabel = new TextView(this);
        warnLabel.setTextColor(AMBER);
        warnLabel.setTextSize(18);
        warnLabel.setPadding(0, dp(6), 0, dp(10));
        left.addView(warnLabel);
        sohLabel = hint("");
        left.addView(sohLabel);
        left.addView(spacer());
        left.addView(button("Read sensor now", new View.OnClickListener() { public void onClick(View v) { readNow(); } }));
        rawLabel = hint("");
        left.addView(rawLabel);

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
        advBox.addView(button("Clear history", new View.OnClickListener() { public void onClick(View v) { clearHistory(); } }));
        right.addView(advBox);

        right.addView(button("Save", new View.OnClickListener() { public void onClick(View v) { saveConfig(); } }));
        right.addView(hint("Applies on the next start of the car."));

        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));
        root.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5f));
        root.addView(rightScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));

        setContentView(root);
        refreshButtons();
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
        if ("disabled".equals(state)) bigLabel.setText("Off");
        else if (p >= 0) {
            bigLabel.setText(fmt10(p) + "%");
            bigLabel.setTextColor(p < warnPct * 10 ? AMBER : GREEN);
        }
        else if (state.length() == 0) bigLabel.setText("No data yet");
        else bigLabel.setText("No reading");
        updLabel.setText(upd.length() > 0 ? "Updated " + time(parse(upd, 0)) : "");
        String sohText = soh.length() > 0 ? "HV battery health (SOH): " + soh : "";
        if ("no_reading".equals(state)) sohText += "\nThe 12V value couldn't be decoded. Check Advanced settings.";
        sohLabel.setText(sohText);
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

    void readNow() {
        String r = runRoot("service call tm_hscan_service 137").trim();
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
