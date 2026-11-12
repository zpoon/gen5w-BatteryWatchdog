package com.nemesis.batterywatchdog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.database.ContentObserver;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * Installed as an HMG-API mod instead, the service runs as this app's own user,
 * so the app reads and writes the service's folder directly (see detectMode()).
 * Kotlin/lambdas avoided on purpose: built with legacy dx for KitKat (DEX 035).
 */
public class MainActivity extends Activity {

    static final String NEM_STATE = "/data/nemesis/state/battery.watchdog";
    static String STATE = NEM_STATE;
    static final String NEM = "/system/xbin/nemesis";

    // ---- HMG-API mode ----------------------------------------------------------
    // As an HMG-API mod (hmgapi-mod/mod.json) the service "watch" runs as this
    // app's user, with its folder at /data/hmgapi/run/<mod>/watch. `hmgapid whoami`
    // answers the mod's id only to a mod's own app, so that decides the mode.
    static final String HMGAPID = "/system/bin/hmgapid";
    static final String HM_SVC = "watch";
    static final String HM_READINGS = "battery12v.soc hv.soc hv.health hv.ready charging outside_temp";
    static String HM_MOD = null;
    static boolean hm() { return HM_MOD != null; }

    static void detectMode() {
        HM_MOD = null; STATE = NEM_STATE;
        if (!new File(HMGAPID).exists()) return;
        try {
            Process p = Runtime.getRuntime().exec(new String[] { HMGAPID, "whoami" });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String id = r.readLine();
            if (p.waitFor() != 0 || id == null) return;
            id = id.trim();
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}") || "root".equals(id)) return;
            HM_MOD = id;
            STATE = "/data/hmgapi/run/" + id + "/" + HM_SVC;
        } catch (Exception e) { HM_MOD = null; STATE = NEM_STATE; }
    }

    /** `hmgapid <args>` as this app (HMG-API mode only) */
    String hmgapid(String args) { return runRoot(HMGAPID + " " + args + " 2>&1"); }
    static final String[] DECODE = { "Auto", "Integer", "Float" };
    static final int[] INTERVALS = { 60, 300, 900 };

    // ---- colours ------------------------------------------------------------
    // Two palettes, picked from the car's day/night display mode (the head unit
    // sets Android's night mode with it; found by the Launchpad /
    // gen5w-preconditioning project). Anything other than "day" gets the dark one.
    static final class Pal {
        final int bg, card, line, text, muted, accent, onAccent, amber, green, red, grid, park;
        Pal(int bg, int card, int line, int text, int muted, int accent, int onAccent,
            int amber, int green, int red, int grid, int park) {
            this.bg = bg; this.card = card; this.line = line; this.text = text; this.muted = muted;
            this.accent = accent; this.onAccent = onAccent; this.amber = amber; this.green = green;
            this.red = red; this.grid = grid; this.park = park;
        }
    }
    static final Pal NIGHT = new Pal(Color.rgb(16, 18, 22), Color.rgb(28, 32, 38), Color.rgb(54, 60, 68),
            Color.WHITE, Color.rgb(150, 160, 170), Color.rgb(120, 180, 255), Color.rgb(12, 24, 40),
            Color.rgb(255, 170, 60), Color.rgb(110, 210, 140), Color.rgb(255, 120, 110),
            Color.rgb(48, 53, 60), Color.rgb(110, 118, 128));
    static final Pal DAY = new Pal(Color.rgb(234, 237, 241), Color.WHITE, Color.rgb(206, 212, 219),
            Color.rgb(26, 30, 36), Color.rgb(90, 100, 110), Color.rgb(28, 104, 200), Color.WHITE,
            Color.rgb(176, 100, 0), Color.rgb(26, 128, 70), Color.rgb(190, 54, 44),
            Color.rgb(214, 219, 225), Color.rgb(136, 145, 154));
    Pal pal = NIGHT;

    boolean dayMode() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_NO;
    }

    boolean enabled = true, toastStart = false;
    int warnPct = 60, dropPct = 15, decode = 1, scaleX10 = 10, interval = 300;

    // home
    View home;
    LinearLayout banner, tiles, tileRowA, tileRowB, dialCard;
    TextView bannerText, pill, updLabel, ctxLabel;
    Dial dial;
    Tile tParked, tDrain, tLowest, tSoh;
    Chart chart;
    TextView legend;
    // settings
    View settings;
    TextView savedLabel, rawLabel, toolOut;
    Seg enSeg, popSeg, intSeg, decSeg, scaleSeg;
    Stepper warnStep, dropStep;
    TextView overrideBtn;

    // auto-refresh while the screen is open (the service samples every few minutes)
    final Handler handler = new Handler();
    final Runnable tick = new Runnable() {
        public void run() { refreshData(); handler.postDelayed(this, 30000); }
    };

    /*
     * Two pages for the 1920x720 Gen5W screen, no page scrolling:
     *   home     : warning banner (only while there is one); a dial for the 12V
     *              beside four stat tiles and the drives chart
     *   settings : three cards (Warnings, Sensor, Tools), opened from home.
     *              Changes save by themselves.
     */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // drop the system title bar: the app draws its own
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        detectMode();
        pal = dayMode() ? DAY : NIGHT;
        loadConfig();

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(pal.bg);
        home = buildHome();
        settings = buildSettings();
        settings.setVisibility(View.GONE);
        root.addView(home);
        root.addView(settings);

        setContentView(root);
        refreshButtons();
        startVerification();
        handler.postDelayed(askShare, 2000);
    }

    View buildHome() {
        LinearLayout page = column();

        LinearLayout head = row();
        TextView t = new TextView(this);
        t.setText("Battery Watchdog"); t.setTextColor(pal.text); t.setTextSize(22);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = chip("Settings ›", new View.OnClickListener() { public void onClick(View v) { showSettings(true); } });
        gear.setPadding(dp(22), 0, dp(22), 0);
        head.addView(gear, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));
        page.addView(head);

        banner = row();
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        bannerText = new TextView(this);
        bannerText.setTextSize(17);
        banner.addView(bannerText);
        banner.setVisibility(View.GONE);
        banner.setClickable(true);
        banner.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showWarnings(); } });
        page.addView(banner, margins(LinearLayout.LayoutParams.MATCH_PARENT, 0, dp(10), 0, 0));

        LinearLayout body = row();
        body.setGravity(android.view.Gravity.TOP);
        // left: the 12V dial and what goes with it
        dialCard = column();
        dialCard.setGravity(android.view.Gravity.CENTER);
        dialCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        dialCard.setBackgroundDrawable(box(pal.card, pal.line));
        dial = new Dial(this, dp(1));
        dial.pal = pal;
        // the dial takes whatever height the text under it leaves (the car's status
        // bar and screen density leave less than 720 px), so that text always shows
        dialCard.addView(dial, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        pill = new TextView(this);
        pill.setTextSize(16);
        pill.setPadding(dp(16), dp(4), dp(16), dp(4));
        dialCard.addView(pill, margins(LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(4), 0, dp(8)));
        updLabel = hint("");
        updLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        dialCard.addView(updLabel);
        ctxLabel = new TextView(this);
        ctxLabel.setTextColor(pal.text); ctxLabel.setTextSize(15);
        ctxLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        dialCard.addView(ctxLabel);
        body.addView(dialCard, new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT));

        // right: tiles over the chart
        LinearLayout right = column();
        tiles = row();
        tileRowA = row(); tileRowB = row();
        tParked = new Tile("Parked before this drive");
        tDrain  = new Tile("Parked drain, last 7 days");
        tLowest = new Tile("Lowest start, last 7 days");
        tSoh    = new Tile("HV battery health");
        tileRowA.addView(tParked.v, tileLp(false)); tileRowA.addView(tDrain.v, tileLp(true));
        tileRowB.addView(tLowest.v, tileLp(false)); tileRowB.addView(tSoh.v, tileLp(true));
        tiles.addView(tileRowA); tiles.addView(tileRowB);
        right.addView(tiles);

        LinearLayout chartCard = column();
        chartCard.setPadding(dp(14), dp(10), dp(14), dp(8));
        chartCard.setBackgroundDrawable(box(pal.card, pal.line));
        legend = new TextView(this);
        legend.setTextSize(14);
        chartCard.addView(legend);
        chart = new Chart(this, dp(1), pal);
        chartCard.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        right.addView(chartCard, vfill(0, dp(10), 0, 0));
        body.addView(right, hfill(dp(14), 0, 0, 0));
        page.addView(body, vfill(0, dp(12), 0, 0));
        layoutTiles(false);
        return page;
    }

    View buildSettings() {
        LinearLayout page = column();

        LinearLayout head = row();
        TextView back = chip("‹ Back", new View.OnClickListener() { public void onClick(View v) { showSettings(false); } });
        back.setPadding(dp(22), 0, dp(22), 0);
        head.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));
        TextView t = new TextView(this);
        t.setText("Settings"); t.setTextColor(pal.text); t.setTextSize(22);
        t.setPadding(dp(18), 0, 0, 0);
        head.addView(t);
        savedLabel = hint(hm() ? "Changes save by themselves (also on HMG-API's page for this mod) and apply from the next drive."
                : "Changes save by themselves and apply from the next drive.");
        savedLabel.setPadding(0, 0, 0, 0);
        savedLabel.setGravity(android.view.Gravity.RIGHT);
        head.addView(savedLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(head);

        LinearLayout cards = row();
        cards.setGravity(android.view.Gravity.TOP);

        // ---- warnings
        LinearLayout w = card("Warnings");
        w.addView(caption("Monitor the 12V battery"));
        enSeg = new Seg(new String[] { "On", "Off" }, new Setter() { public void set(int i) { enabled = i == 0; changed(); } });
        w.addView(enSeg.row);
        w.addView(caption("Warn when it starts below"));
        warnStep = new Stepper(30, 90, warnPct, "%", new Setter() { public void set(int v) { warnPct = v; changed(); } });
        w.addView(warnStep.row);
        w.addView(caption("Warn when it drops this much while parked"));
        dropStep = new Stepper(5, 40, dropPct, " pts", new Setter() { public void set(int v) { dropPct = v; changed(); } });
        w.addView(dropStep.row);
        w.addView(caption("Pop-up when the car starts"));
        popSeg = new Seg(new String[] { "Warnings only", "Every start" }, new Setter() { public void set(int i) { toastStart = i == 1; changed(); } });
        w.addView(popSeg.row);

        // ---- sensor
        LinearLayout s = card("Sensor");
        s.addView(caption("Read every (screen on)"));
        intSeg = new Seg(new String[] { "1 min", "5 min", "15 min" }, new Setter() { public void set(int i) { interval = INTERVALS[i]; changed(); } });
        s.addView(intSeg.row);
        // decode and scale only matter for the raw value the NEMESIS package reads;
        // HMG-API gives the 12V as a percentage
        LinearLayout raw = column();
        raw.addView(caption("Decode the value as"));
        decSeg = new Seg(DECODE, new Setter() { public void set(int i) { decode = i; changed(); } });
        raw.addView(decSeg.row);
        raw.addView(caption("Scale"));
        scaleSeg = new Seg(new String[] { "1 raw = 1 %", "1 raw = 0.5 %" }, new Setter() { public void set(int i) { scaleX10 = i == 0 ? 10 : 5; changed(); } });
        raw.addView(scaleSeg.row);
        if (hm()) raw.setVisibility(View.GONE);
        s.addView(raw);
        s.addView(tool("Read sensor now", new View.OnClickListener() { public void onClick(View v) { readNow(); } }));
        rawLabel = hint("");
        s.addView(rawLabel);

        // ---- tools
        LinearLayout tl = card("Tools");
        tl.addView(tool("Battery report\u2026", new View.OnClickListener() { public void onClick(View v) { showReport(); } }));
        tl.addView(tool("Warning history", new View.OnClickListener() { public void onClick(View v) { showWarnings(); } }));
        tl.addView(tool("Diagnostics", new View.OnClickListener() { public void onClick(View v) { showDiagnostics(); } }));
        tl.addView(tool("Export to USB", new View.OnClickListener() { public void onClick(View v) { exportToUsb(); } }));
        tl.addView(tool("Share data…", new View.OnClickListener() { public void onClick(View v) { showShareSteps(); } }));
        LinearLayout pair = row();
        pair.addView(tool("Restart monitor", new View.OnClickListener() { public void onClick(View v) { restartService(); } }), tileLp(false));
        // HMG-API runs the service itself; no direct test runs from the app
        if (!hm()) pair.addView(tool("Test run (10 s)", new View.OnClickListener() { public void onClick(View v) { testRun(); } }), tileLp(true));
        pair.setPadding(0, dp(10), 0, 0);
        tl.addView(pair);
        TextView clear = tool("Clear history…", new View.OnClickListener() { public void onClick(View v) { confirmClear(); } });
        clear.setTextColor(pal.red);
        tl.addView(clear);
        overrideBtn = tool("Unverified firmware: use built-in numbers…", new View.OnClickListener() { public void onClick(View v) { confirmOverride(); } });
        overrideBtn.setVisibility(View.GONE);
        tl.addView(overrideBtn);
        toolOut = hint("");
        tl.addView(toolOut);

        cards.addView(scroll(w), hfill(0, 0, 0, 0));
        cards.addView(scroll(s), hfill(dp(14), 0, 0, 0));
        cards.addView(scroll(tl), hfill(dp(14), 0, 0, 0));
        page.addView(cards, vfill(0, dp(12), 0, 0));
        return page;
    }

    void showSettings(boolean on) {
        if (!on) flushSave();
        settings.setVisibility(on ? View.VISIBLE : View.GONE);
        home.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) refreshData();
    }

    @Override
    public void onBackPressed() {
        if (settings.getVisibility() == View.VISIBLE) showSettings(false);
        else super.onBackPressed();
    }

    // ---- saving: every change saves by itself, shortly after the last tap ------
    boolean dirty;
    final Runnable saveLater = new Runnable() { public void run() { flushSave(); } };

    void changed() {
        refreshButtons();
        dirty = true;
        handler.removeCallbacks(saveLater);
        handler.postDelayed(saveLater, 800);
    }

    void flushSave() {
        handler.removeCallbacks(saveLater);
        if (!dirty) return;
        dirty = false;
        String r = saveConfig();
        if (r.length() > 0) { toast("Couldn't save: " + r); savedLabel.setTextColor(pal.red); savedLabel.setText("Not saved: " + r); }
        else { savedLabel.setTextColor(pal.muted); savedLabel.setText("Saved. Applies from the next drive."); }
    }

    void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Clear history?")
            .setMessage("This deletes every logged reading, drive and warning on this car. Settings are kept. "
                + "If you might share your data later, export it to USB first.")
            .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { clearHistory(); } })
            .setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            getContentResolver().registerContentObserver(Settings.System.getUriFor(SPLIT_SETTING), false, splitObserver);
            splitObserved = true;
        } catch (Exception e) { splitObserved = false; }
        applySplit();
        handler.removeCallbacks(tick); tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        flushSave();
        handler.removeCallbacks(tick);
        if (splitObserved) { getContentResolver().unregisterContentObserver(splitObserver); splitObserved = false; }
    }

    // ---- split screen -----------------------------------------------------------
    // Hyundai's split view draws the map panel over the right of the screen without
    // resizing this app. Settings.System "split_screen_status" is 1 while it's open
    // (found by the Launchpad / gen5w-preconditioning project). The panel's width
    // isn't reported (about 29 % in photos), so a third is kept clear and the
    // tiles go two by two to fit.
    static final String SPLIT_SETTING = "split_screen_status";
    View rootView;
    boolean compact, splitObserved;
    final ContentObserver splitObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { applySplit(); }
    };

    boolean splitOn() {
        try { return Settings.System.getInt(getContentResolver(), SPLIT_SETTING, 0) == 1; }
        catch (Exception e) { return false; }
    }

    void applySplit() {
        compact = splitOn();
        int pad = dp(16);
        int right = compact ? Math.round(getResources().getDisplayMetrics().widthPixels / 3f) : pad;
        rootView.setPadding(pad, pad, right, pad);
        layoutTiles(compact);
    }

    /** one row of four tiles, or two rows of two when the screen is narrowed */
    void layoutTiles(boolean twoRows) {
        tiles.setOrientation(twoRows ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tileRowA.setLayoutParams(twoRows ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lb = twoRows ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (twoRows) lb.topMargin = dp(10); else lb.leftMargin = dp(10);
        tileRowB.setLayoutParams(lb);
    }

    // ---- data ---------------------------------------------------------------
    void refreshData() {
        // the split-screen panel page (HMG-API) redraws too, so it never lags the app
        // when both are on screen, whatever happens to the monitor's own refresh
        if (hm()) PanelWidget.refresh(this);
        String status = runRoot("cat " + STATE + "/status 2>/dev/null");
        String state = kv(status, "state"), pct = kv(status, "pct10"), soh = kv(status, "soh_raw");
        String upd = kv(status, "updated"), warn = kv(status, "warn");
        int p = parse(pct, -1);
        hmReading = hm() && state.length() > 0 && !"unverified".equals(state);
        boolean paused = "unverified".equals(state) || "unverified".equals(verifyState) || "interface_mismatch".equals(verifyState);
        if (overrideBtn != null) overrideBtn.setVisibility("unverified".equals(verifyState) ? View.VISIBLE : View.GONE);
        dial.warn10 = warnPct * 10;
        List<long[]> drives = loadDrives(status);
        legend.setText(legendText());
        if (hm() && "waiting".equals(state)) {
            // just started or updated: HMG-API's readings can take a minute to come up
            dial.show(-1, "\u2026", pal.muted);
            setPill("Starting", pal.muted);
            updLabel.setText("Waiting for the first reading from HMG-API. This can take a minute after an install or update.");
            ctxLabel.setText("");
            setBanner(null);
            chart.setData(lastN(drives, 30), warnPct * 10, dropPct * 10);
            return;
        }
        if (!hm() && "other_install".equals(state)) {
            // the HMG-API mod is installed and on: this NEMESIS copy steps aside (svc/watchdog.sh)
            dial.show(-1, "Idle", pal.muted);
            setPill("Idle", pal.muted);
            updLabel.setText("The HMG-API mod is monitoring.");
            ctxLabel.setText("");
            setBanner("Battery Watchdog is also installed as an HMG-API mod, so this NEMESIS copy is idle. "
                + "Keep one: uninstall this package in NEMESIS Manager, or remove the mod in HMG-API.");
            chart.setData(lastN(drives, 30), warnPct * 10, dropPct * 10);
            return;
        }
        if (paused && !verified()) {
            dial.show(-1, "Paused", pal.amber);
            setPill("Paused", pal.amber);
            updLabel.setText("Nothing is read from the car.");
            ctxLabel.setText("");
            String hmErr = kv(status, "hm_error");
            setBanner(hm() ? "HMG-API gives no 12V reading on this car, so nothing is logged"
                    + (hmErr.length() > 0 ? " (HMG-API says: " + hmErr + ")" : "") + ". See Settings \u2192 Tools \u2192 Diagnostics."
                : "interface_mismatch".equals(verifyState)
                ? "The car's telematics service isn't the one this package was built for, so nothing is read."
                : "This firmware isn't verified yet, so nothing is read from the car. See Settings → Tools → Diagnostics.");
            tParked.set("--", "", pal.text, false); tDrain.set("--", "", pal.text, false);
            tLowest.set("--", "", pal.text, false); tSoh.set("--", "", pal.text, false);
            chart.setData(lastN(drives, 30), warnPct * 10, dropPct * 10);
            return;
        }
        boolean low = p >= 0 && p < warnPct * 10;
        if ("disabled".equals(state)) { dial.show(-1, "Off", pal.muted); setPill("Monitor off", pal.muted); }
        else if (p >= 0) { dial.show(p, fmt10(p) + "%", low ? pal.amber : pal.green); setPill(low ? "Low" : "OK", low ? pal.amber : pal.green); }
        else if (state.length() == 0) { dial.show(-1, "…", pal.muted); setPill(null, 0); }
        else { dial.show(-1, "--", pal.muted); setPill("No reading", pal.muted); }
        updLabel.setText(upd.length() > 0 ? "Read " + time(parse(upd, 0))
                : "No reading yet. If this lasts, see Settings → Tools → Diagnostics.");
        // context: main battery charge and outside temperature
        int hv = parse(kv(status, "hv10"), -1);
        int tc = parse(kv(status, "temp_c"), Integer.MIN_VALUE);
        StringBuilder cx = new StringBuilder();
        if (hv >= 0) cx.append("Main battery ").append(fmt10(hv)).append("%");
        if (tc != Integer.MIN_VALUE) {
            if (cx.length() > 0) cx.append("\n");
            cx.append("Outside ").append(Math.round(tc * 9 / 5f + 32)).append("°F");
        }
        ctxLabel.setText(cx.toString());

        // banner: the latest warning, else a decode problem, else nothing
        boolean noRead = "no_reading".equals(state);
        if (warn.length() > 0) setBanner("⚠  " + warn);
        else if (noRead) setBanner("The 12V value couldn't be decoded. Check Settings → Sensor.");
        else setBanner(null);

        // tile: parked time before this drive, and how the 12V changed meanwhile
        int pMin = parse(kv(status, "parked_min"), -1);
        int pDrop = parse(kv(status, "parked_drop10"), Integer.MIN_VALUE);
        int pRate = parse(kv(status, "parked_rate10_day"), Integer.MIN_VALUE);
        if (pMin >= 0 && pDrop != Integer.MIN_VALUE) {
            boolean bad = pDrop >= dropPct * 10;
            String sub = duration(pMin) + (pRate != Integer.MIN_VALUE && pDrop > 0 ? "  ·  −" + fmt10(pRate) + "/day" : "");
            tParked.set(pDrop > 0 ? "−" + fmt10(pDrop) + " pts" : pDrop < 0 ? "+" + fmt10(-pDrop) + " pts" : "No change",
                    sub, bad ? pal.amber : pal.text, bad);
        } else {
            tParked.set("--", "Shows after a park", pal.text, false);
        }

        // tiles: the last 7 days
        int[] wk = week(drives);   // {drives, lowest start, drain/day (or MIN), recharges}
        if (wk[0] == 0) {
            tDrain.set("--", "No drives yet", pal.text, false);
            tLowest.set("--", "No drives yet", pal.text, false);
        } else {
            String rech = wk[3] + (wk[3] == 1 ? " recharge" : " recharges") + " while parked";
            if (wk[2] == Integer.MIN_VALUE) tDrain.set("--", "Not enough parked time yet", pal.text, false);
            else tDrain.set((wk[2] > 0 ? "−" + fmt10(wk[2]) : wk[2] < 0 ? "+" + fmt10(-wk[2]) : "0.0") + " /day",
                    rech, pal.text, false);
            boolean lowStart = wk[1] < warnPct * 10;
            tLowest.set(fmt10(wk[1]) + "%", wk[0] + (wk[0] == 1 ? " drive" : " drives"), lowStart ? pal.amber : pal.text, lowStart);
        }

        int sohNow = readSoh();
        if (sohNow < 0) sohNow = parse(soh, -1);   // fall back to the service's last value
        showSoh(sohNow);

        chart.setData(lastN(drives, 30), warnPct * 10, dropPct * 10);
    }

    static List<long[]> lastN(List<long[]> l, int n) { return l.size() > n ? l.subList(l.size() - n, l.size()) : l; }

    CharSequence legendText() {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append("Last 30 drives      ");
        legendItem(b, "●", pal.text, " start   ");
        legendItem(b, "●", pal.accent, " end   ");
        legendItem(b, "- -", pal.park, " parked   ");
        legendItem(b, "- -", pal.green, " recharged   ");
        legendItem(b, "—", pal.amber, " warn below " + warnPct + "%");
        return b;
    }

    void legendItem(android.text.SpannableStringBuilder b, String mark, int color, String label) {
        int s = b.length();
        b.append(mark);
        b.setSpan(new android.text.style.ForegroundColorSpan(color), s, b.length(), 0);
        int s2 = b.length();
        b.append(label);
        b.setSpan(new android.text.style.ForegroundColorSpan(pal.muted), s2, b.length(), 0);
    }

    void setBanner(String text) {
        if (text == null) { banner.setVisibility(View.GONE); return; }
        bannerText.setText(text);
        bannerText.setTextColor(pal.amber);
        banner.setBackgroundDrawable(box(mix(pal.amber, pal.card, 0.16f), pal.amber));
        banner.setVisibility(View.VISIBLE);
    }

    void setPill(String text, int color) {
        if (text == null) { pill.setVisibility(View.INVISIBLE); return; }
        pill.setVisibility(View.VISIBLE);
        pill.setText(text);
        pill.setTextColor(color);
        GradientDrawable d = new GradientDrawable();
        d.setColor(mix(color, pal.card, 0.18f));
        d.setCornerRadius(dp(20));
        pill.setBackgroundDrawable(d);
    }

    /**
     * Drives as {start_epoch, end_epoch, start_pct10, end_pct10}, oldest first:
     * finished drives from 'sessions' plus the current one. Drives separated
     * by a stop shorter than min_off_s (default 10 min) are merged, which also
     * tidies history recorded before short stops were ignored.
     */
    List<long[]> loadDrives(String status) {
        List<long[]> out = new ArrayList<long[]>();
        long minOff = Math.max(60, readInt("min_off_s", 600));
        for (String line : runRoot("cat " + STATE + "/sessions 2>/dev/null").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) continue;
            long st = parseL(f[0], 0); int min = parse(f[1], -1), sp = parse(f[2], -1), ep = parse(f[3], -1);
            if (st <= 0 || min < 0 || sp < 0 || ep < 0) continue;
            add(out, new long[] { st, st + min * 60L, sp, ep }, minOff);
        }
        String[] ss = kv(status, "session_start").split(" ");
        long cst = ss.length > 0 ? parseL(ss[0], 0) : 0;
        int csp = ss.length > 1 ? parse(ss[1], -1) : -1;
        int cur = parse(kv(status, "pct10"), -1);
        long upd = parseL(kv(status, "updated"), 0);
        if (cst > 0 && csp >= 0 && cur >= 0 && upd >= cst
                && (out.isEmpty() || cst > out.get(out.size() - 1)[0])) {
            add(out, new long[] { cst, upd, csp, cur }, minOff);
        }
        return out;
    }

    static void add(List<long[]> l, long[] d, long minOff) {
        if (!l.isEmpty()) {
            long[] p = l.get(l.size() - 1);
            if (d[0] - p[1] < minOff && d[0] >= p[0]) { p[1] = Math.max(p[1], d[1]); p[3] = d[3]; return; }
        }
        l.add(d);
    }

    /**
     * Last 7 days: {drives, lowest start (tenths), parked drain per day (tenths,
     * positive = fell; MIN_VALUE if under an hour parked), parked recharges}.
     */
    static int[] week(List<long[]> drives) {
        long now = System.currentTimeMillis() / 1000, from = now - 7 * 86400L;
        int lowest = Integer.MAX_VALUE, n = 0, recharges = 0;
        long parkedMin = 0, parkedDrop = 0;
        for (int i = 0; i < drives.size(); i++) {
            long[] d = drives.get(i);
            if (d[0] < from) continue;
            n++;
            lowest = (int) Math.min(lowest, d[2]);
            if (i == 0) continue;
            long[] p = drives.get(i - 1);
            long gap = (d[0] - p[1]) / 60;
            if (gap < 60) continue;                         // ignore short parks for the rate
            long drop = p[3] - d[2];                        // positive = fell while parked
            if (drop <= -10) recharges++;                   // rose 1+ pt: the car topped it up
            parkedMin += gap; parkedDrop += drop;
        }
        int rate = parkedMin >= 60 ? (int) (parkedDrop * 1440 / parkedMin) : Integer.MIN_VALUE;
        return new int[] { n, n == 0 ? -1 : lowest, rate, recharges };
    }

    // SOH: tm_hscan_service 214 (0xd6) returns one raw byte, a health index (100 = new).
    int readSoh() {
        if (hm()) return -1;                             // the service logs hv.health; shown from its status
        if (txSoh <= 0) return -1;                       // not verified on this firmware: don't call
        String r = runRoot("service call tm_hscan_service " + txSoh).trim();
        int i = r.indexOf("Parcel(");
        if (i < 0) return -1;
        String[] w = r.substring(i + 7).trim().split("\\s+");
        if (w.length < 2 || !"00000000".equals(w[0])) return -1;
        try { long v = Long.parseLong(w[1], 16); return v > 0 && v <= 100 ? (int) v : -1; } catch (Exception e) { return -1; }
    }

    // green >= 90, amber 75-89, red < 75, plain if unknown
    void showSoh(int v) {
        if (v < 0)        tSoh.set("--", "Not available", pal.text, false);
        else if (v >= 90) tSoh.set(v + "%", "Good", pal.green, false);
        else if (v >= 75) tSoh.set(v + "%", "Fair", pal.amber, true);
        else              tSoh.set(v + "%", "Low", pal.red, true);
    }

    void readNow() {
        if (hm()) {
            String r = hmgapid("vehicle battery12v.soc").trim();
            String[] w = r.split("\\s+");
            rawLabel.setText(w.length == 2 && !"-".equals(w[1]) ? "Sensor now: " + w[1] + "%   (HMG-API battery12v.soc)"
                : "HMG-API: " + (r.length() > 0 ? r : "(no answer)"));
            return;
        }
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
    /**
     * Read-only getters logged alongside the 12V reading, raw, until their values
     * are understood: the 12V battery sensor's state, invalid and error flags, the
     * Auxiliary Battery Saver option, and HV charging status. Only ever taken from
     * a lookup by name on this firmware (never from known_firmware.txt or an
     * override), so a number from another build can't be called by mistake.
     */
    static final String[][] EXTRAS = {
        { "sns_state", "getBATSNSRState" }, { "sns_invalid", "getBATSNSRInvalid" },
        { "sns_error", "getBATSNSRError" }, { "aux_opt", "get_BAT_AuxBattOpt" },
        { "chg_status", "getChargingStatus" } };
    final Map<String, Integer> txExtra = new HashMap<String, Integer>();

    void startVerification() {
        new Thread(new Runnable() { public void run() {
            final String result = verify();
            handler.post(new Runnable() { public void run() { verifyState = result; refreshData(); } });
        } }).start();
    }

    /** Returns resolved | saved | known_firmware | override | interface_mismatch | unverified. */
    String verify() {
        txSoc = txHv = txSoh = -1; txExtra.clear();
        if (hm()) {   // HMG-API maps the readings for this car; nothing to look up here
            fw = runRoot("getprop ro.build.version.incremental").trim();
            return "hmgapi";
        }
        fw = runRoot("getprop ro.build.version.incremental").trim();
        String svcList = runRoot("service list 2>/dev/null");
        if (!svcList.contains("tm_hscan_service: [" + IFACE + "]")) return "interface_mismatch";

        // 1. numbers already resolved (or overridden) for exactly this firmware
        String saved = runRoot("cat " + STATE + "/txn 2>/dev/null");
        if (fw.length() > 0 && fw.equals(kv(saved, "fw")) && parse(kv(saved, "soc"), -1) > 0) {
            txSoc = parse(kv(saved, "soc"), -1); txHv = parse(kv(saved, "hv"), -1); txSoh = parse(kv(saved, "soh"), -1);
            verifySource = kv(saved, "source");
            if ("manual-override".equals(verifySource)) return "override";
            for (String[] x : EXTRAS) { int n = parse(kv(saved, x[0]), -1); if (n > 0) txExtra.put(x[0], n); }
            // saved by a version that didn't look up the extra getters: look them up once
            if (kv(saved, "ext").length() == 0 && verifySource.startsWith("/")) {
                int soc = txSoc, hv = txHv, soh = txSoh;
                String r = resolveByName(new StringBuilder());
                if (r != null && txSoc == soc) { verifySource = r; saveTxn(r); }
                else { txSoc = soc; txHv = hv; txSoh = soh; saveTxn(verifySource); }   // keep the old numbers, mark as checked
            }
            return "resolved (saved earlier)";
        }

        // 2. resolve by name from the firmware's own interface definition.
        //    Skipped if this app version already searched this firmware and found nothing.
        String missKey = fw + " " + appVersion();
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

    /** This build's version name (stamped into the manifest at build time). */
    String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
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
        txExtra.clear();
        for (String[] x : EXTRAS) { Integer n = m.get(x[1]); if (n != null && n > 0) txExtra.put(x[0], n); }
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
        writeRoot(STATE + "/" + name, content);
    }

    void saveTxn(String source) {
        // plain echo: KitKat has no printf binary and mksh R43 has no printf builtin
        String f = STATE + "/txn";
        runRoot("mkdir -p " + STATE
            + "; echo 'fw=" + fw + "' > " + f
            + "; echo 'soc=" + txSoc + "' >> " + f
            + "; echo 'hv=" + (txHv > 0 ? txHv : "") + "' >> " + f
            + "; echo 'soh=" + (txSoh > 0 ? txSoh : "") + "' >> " + f
            + extrasEcho(f, source)
            + "; echo 'source=" + source + "' >> " + f);
    }

    /** the extra getters' lines for the txn file; none for an override (and ext=1: looked up) */
    String extrasEcho(String f, String source) {
        if ("manual-override".equals(source)) return "";
        StringBuilder b = new StringBuilder();
        for (String[] x : EXTRAS) {
            Integer n = txExtra.get(x[0]);
            b.append("; echo '").append(x[0]).append('=').append(n == null ? "" : String.valueOf(n)).append("' >> ").append(f);
        }
        return b.append("; echo 'ext=1' >> ").append(f).toString();
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

    boolean verified() { return hm() ? hmReading : txSoc > 0; }
    boolean hmReading;

    // ---- one-time, opt-in request to share data ---------------------------------
    // Shown once, the first time the app opens with verified readings (on an
    // unverified firmware nothing is collected, so it waits). Nothing is ever sent
    // from the car: it only explains how to export and share, if the owner wants to.
    static final String PREFS = "ui", ASKED_SHARE = "share_asked";
    int shareTries;
    final Runnable askShare = new Runnable() { public void run() { maybeAskToShare(); } };

    void maybeAskToShare() {
        if (isFinishing()) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(ASKED_SHARE, false)) return;
        if (!verified()) {
            // still checking: look again shortly; paused: ask on a later start
            if (("checking".equals(verifyState) || hm()) && ++shareTries < 20) handler.postDelayed(askShare, 3000);
            return;
        }
        p.edit().putBoolean(ASKED_SHARE, true).apply();
        new AlertDialog.Builder(this).setTitle("Help improve Battery Watchdog?")
            .setMessage("The warnings are simple for now. Logs from real cars, especially ones whose 12V battery "
                + "later failed, would help build smarter checks for everyone.\n\n"
                + "This is completely optional, and nothing is ever sent from your car. If you'd like to help, "
                + "after a few weeks of driving you can export your data to a USB drive and share it on GitHub.\n\n"
                + "You won't be asked again. The steps are always in Settings \u2192 Tools \u2192 Share data.")
            .setPositiveButton("Show me how", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { showShareSteps(); } })
            .setNegativeButton("No thanks", null).show();
    }

    void showShareSteps() {
        new AlertDialog.Builder(this).setTitle("Share your data (optional)")
            .setMessage("1. Drive as usual for a few weeks. The longer the log, the more useful it is.\n"
                + "2. Insert a USB drive and tap Export below (or Settings \u2192 Tools \u2192 Export to USB). Choose "
                + "\"For sharing, dates hidden\" if you'd rather not show when you drive.\n"
                + "3. On a computer or phone, go to github.com/zpoon/gen5w-BatteryWatchdog, open Issues \u2192 "
                + "New issue \u2192 Share battery data, fill in what you know about your 12V battery, and attach "
                + "the .zip from the drive.\n\n"
                + "If your battery fails or is replaced later, a comment on that issue helps a lot.\n\n"
                + "An export has no location, VIN or account details.")
            .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { exportToUsb(); } })
            .setNegativeButton("Close", null).show();
    }

    static final String SVC_ID = "battery.watchdog/watch";
    static final String SVC_LOG = "/data/nemesis/logs/battery.watchdog/watch.log";
    static final String SVC_SCRIPT = "/data/nemesis/apps/battery.watchdog/svc/watchdog.sh";

    void showDiagnostics() {
        new AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(buildDiagnostics())
            .setPositiveButton("Close", null).show();
    }

    /** Diagnostics text, shown in the dialog and saved by the USB export. */
    String buildDiagnostics() {
        if (hm()) return buildHmDiagnostics();
        StringBuilder d = new StringBuilder();
        d.append("service: ").append(runRoot(NEM + " service status " + SVC_ID + " 2>&1").trim()).append("\n");
        d.append("app: ").append(appVersion()).append("   firmware: ").append(fw).append("\n");
        d.append("verification: ").append(verifyState).append(verifySource.length() > 0 ? " (" + verifySource + ")" : "")
            .append("  soc=").append(txSoc).append(" hv=").append(txHv).append(" soh=").append(txSoh).append("\n");
        d.append("logged too (by name): ").append(txExtra.isEmpty() ? "none" : txExtra.toString()).append("\n");
        d.append("file writes: ").append(lastWriteMethod.length() > 0 ? lastWriteMethod : "(none yet)").append("\n");
        d.append("telematics battery-related functions (names only, nothing is called):\n")
            .append(firstLines(batteryFunctions(), 40)).append("\n");
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

    /** Diagnostics under HMG-API: its view of the mod and the service, plus our files. */
    String buildHmDiagnostics() {
        StringBuilder d = new StringBuilder();
        d.append("mode: HMG-API mod ").append(HM_MOD).append("\n");
        d.append("app: ").append(appVersion()).append("   firmware: ").append(fw).append("\n");
        d.append("services:\n").append(firstLines(hmgapid("services"), 6));
        d.append("readings now:\n").append(firstLines(hmgapid("vehicle " + HM_READINGS), 8));
        d.append("file writes: ").append(lastWriteMethod.length() > 0 ? lastWriteMethod : "(none yet)").append("\n");
        d.append("USB folders (HMG-API):\n").append(firstLines(hmgapid("usb"), 4)).append("\n");
        String log = hmgapid("service-log " + HM_SVC);
        String[] lines = log.trim().split("\n");
        d.append("service log, newest first:\n");
        if (log.trim().length() == 0) d.append("(empty)\n");
        for (int i = lines.length - 1, n = 0; i >= 0 && n < 15; i--, n++) d.append(lines[i]).append("\n");
        d.append("\n");
        d.append("shell: ").append(runRoot("echo $KSH_VERSION").trim()).append("\n");
        d.append("date: ").append(runRoot("date; date +%s").trim().replace("\n", "  |  ")).append("\n\n");
        d.append("status file:\n").append(runRoot("cat " + STATE + "/status 2>&1").trim()).append("\n\n");
        d.append("state dir (").append(STATE).append("):\n").append(runRoot("ls -l " + STATE + " 2>&1").trim()).append("\n\n");
        String st = runRoot("cat " + STATE + "/status 2>/dev/null");
        d.append("temperature: ").append(kv(st, "temp_c").length() > 0 ? kv(st, "temp_c") + " C via " + kv(st, "temp_src") : "none").append("\n");
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
        if (hm()) {
            String r = hmgapid("service-restart " + HM_SVC) + runRoot("sleep 3") + hmgapid("services");
            toolOut.setText("Restart: " + r.trim().replace("\n", "  |  "));
            toast("Monitor restarted. Check Diagnostics in 30 seconds.");
            return;
        }
        // empty the old log first so anything shown afterwards is from this run
        String r = runRoot(": > " + SVC_LOG + " 2>/dev/null; "
                + NEM + " service stop " + SVC_ID + " 2>&1; "
                + NEM + " service start " + SVC_ID + " 2>&1; sleep 3; "
                + NEM + " service status " + SVC_ID + " 2>&1");
        toolOut.setText("Restart: " + r.trim().replace("\n", "  |  "));
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
        new AlertDialog.Builder(this).setTitle("Export to USB")
            .setMessage("Export writes a folder you can open in Excel or Google Sheets, and a zip of the same "
                + "files, ready to attach to a \"Share battery data\" issue.\n\n"
                + "\"For sharing, dates hidden\" writes only a zip with the battery log, with every date moved back "
                + "by the same random number of weeks. Use it if you'd rather not show when you drive.")
            .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { runExport(false); } })
            .setNeutralButton("For sharing, dates hidden", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { runExport(true); } })
            .setNegativeButton("Cancel", null).show();
    }

    void runExport(final boolean hideDates) {
        toast("Exporting\u2026");
        new Thread(new Runnable() { public void run() {
            final String msg = hideDates ? doShareExport() : doExport();
            handler.post(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Export to USB").setMessage(msg)
                    .setPositiveButton("Close", null).show();
            } });
        } }).start();
    }

    /** the first writable USB root, or null */
    String usbRoot() {
        StringBuilder probe = new StringBuilder();
        java.util.List<String> roots = new ArrayList<String>();
        if (hm()) {
            // HMG-API's BatteryWatchdog folder on a stick first: the likeliest place this app may write
            for (String l : hmgapid("usb").split("\n")) if (l.trim().startsWith("/")) roots.add(l.trim());
        }
        for (String r : USB_ROOTS) roots.add(r);
        for (String r : roots) {
            probe.append("[ -d " + r + " ] && touch " + r + "/.bwd_w 2>/dev/null && rm " + r + "/.bwd_w && echo " + r + " && exit 0; ");
        }
        String usb = runRoot("( " + probe + " ) 2>/dev/null").trim();
        if (usb.length() == 0 || !usb.startsWith("/")) return null;
        return usb.split("\n")[0].trim();
    }

    static final String NO_USB = "No USB drive found. Insert a FAT32 or exFAT drive and try again.";

    String noUsb() {
        return hm() ? "No USB drive this app can write to. Insert a FAT32 or exFAT drive with a folder named "
            + "BatteryWatchdog at the top and try again." : NO_USB;
    }

    /** state files read through root (the app can't read the state dir itself) */
    Map<String, String> readState(String[] names) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String f : names) {
            String q = shq(STATE + "/" + f);
            if (runRoot("[ -f " + q + " ] && echo y").trim().equals("y")) m.put(f, runRoot("cat " + q + " 2>/dev/null"));
        }
        return m;
    }

    /** Export for sharing: only a zip, dates shifted, no file name or entry time that shows the date. */
    String doShareExport() {
        String usb = usbRoot();
        if (usb == null) return noUsb();
        Map<String, String> raw = readState(ShareZip.SHARE_FILES);
        if (raw.get("log.csv") == null && raw.get("log.old.csv") == null) return "There are no readings to export yet.";
        String info = "app: " + appVersion() + "\nfirmware: " + fw + "\nverification: " + verifyState + "\n";
        String name = "BatteryWatchdog-share-" + ShareZip.randomId() + ".zip";
        String err;
        try { err = writeRootBytes(usb + "/" + name, ShareZip.share(raw, info, ShareZip.randomShift())); }
        catch (Exception e) { err = e.toString(); }
        runRoot("sync");
        if (err.length() > 0) return "Couldn't write " + name + " to " + usb + ":\n" + err;
        return "Wrote " + name + " to " + usb + ".\n\nIt has the battery log, drives and warnings, with every date moved back "
            + "by the same random number of weeks. Attach it to a \"Share battery data\" issue on GitHub. Thank you!";
    }

    String doExport() {
        String usb = usbRoot();
        if (usb == null) return noUsb();

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
        StringBuilder rd = new StringBuilder("time,event,v12_pct,main_battery_pct,outside_f,hv_soh,raw,sensor_state,sensor_invalid,sensor_error,aux_saver_opt,charging_status\n");
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
              .append(f.length > 4 ? f[4] : "").append(',').append(f[2]);
            for (int k = 7; k <= 11; k++) rd.append(',').append(f.length > k ? f[k] : "");
            rd.append('\n');
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
        String diag = "Battery Watchdog export " + stamp + "\n\n" + buildDiagnostics();
        err += writeToUsb("diagnostics.txt", diag, dir);
        String report = buildReport();
        err += writeToUsb("report.txt", report, dir);
        // the same files as one zip, ready to attach to an issue
        try {
            Map<String, String> z = new java.util.LinkedHashMap<String, String>();
            z.put("readings.csv", rd.toString()); z.put("drives.csv", dv.toString());
            z.put("warnings.txt", wr.toString()); z.put("diagnostics.txt", diag); z.put("report.txt", report);
            for (Map.Entry<String, String> e : readState(STATE_FILES).entrySet()) z.put("raw/" + e.getKey(), e.getValue());
            String zr = writeRootBytes(dir + ".zip", ShareZip.plain(z));
            if (zr.length() > 0) err += "zip: " + zr + "\n";
        } catch (Exception e) { err += "zip: " + e + "\n"; }
        runRoot("sync");
        String rawList = runRoot("ls " + shq(dir + "/raw") + " 2>&1").trim().replace("\n", " ");
        if (err.length() > 0) return "Some files couldn't be written to " + dir + ":\n" + err
            + "\nraw/ on the drive: " + (rawList.length() > 0 ? rawList : "(empty)");
        return "Exported " + rows + " readings and " + drives + " drives (written via " + lastWriteMethod + ") to\n" + dir
            + "\n\nreport.txt is the battery report. readings.csv and drives.csv open in Excel or Google Sheets. raw/ has the original files. "
            + "The .zip beside the folder has the same files, ready to attach to a \"Share battery data\" issue.";
    }

    String writeToUsb(String name, String content, String dir) {
        String r = writeRoot(dir + "/" + name, content);
        return r.length() == 0 ? "" : name + ": " + r + "\n";
    }

    static String fmtSigned10(int v) { return (v > 0 ? "+" : v < 0 ? "-" : "") + fmt10(Math.abs(v)); }

    // ---- battery report and warning history ------------------------------------

    /** The report text (Report.java), from the files the service keeps. Runs root commands: not on the UI thread. */
    String buildReport() {
        String status = runRoot("cat " + STATE + "/status 2>/dev/null");
        int soh = readSoh();
        if (soh < 0) soh = parse(kv(status, "soh_raw"), -1);
        return Report.build(loadDrives(status), runRoot("cat " + STATE + "/warnings 2>/dev/null"), warnPct, dropPct,
                parse(kv(status, "pct10"), -1), parseL(kv(status, "updated"), 0), soh,
                fw.length() > 0 ? fw : "?", appVersion(), System.currentTimeMillis() / 1000, java.util.TimeZone.getDefault());
    }

    void showReport() {
        toast("Making the report\u2026");
        new Thread(new Runnable() { public void run() {
            final String text = buildReport();
            handler.post(new Runnable() { public void run() {
                if (isFinishing()) return;
                TextView t = new TextView(MainActivity.this);
                t.setText(text);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                t.setTextSize(14);
                t.setTextIsSelectable(true);
                t.setPadding(dp(20), dp(12), dp(20), dp(12));
                android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(MainActivity.this);
                h.addView(t);
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(h);
                new AlertDialog.Builder(MainActivity.this).setTitle("Battery report").setView(sv)
                    .setPositiveButton("Save to USB", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface di, int w) { saveReport(text); } })
                    .setNegativeButton("Close", null).show();
            } });
        } }).start();
    }

    void saveReport(final String text) {
        new Thread(new Runnable() { public void run() {
            String usb = usbRoot();
            final String msg;
            if (usb == null) msg = noUsb();
            else {
                String name = "BatteryWatchdog-report-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".txt";
                String err = writeRoot(usb + "/" + name, text.replace("\n", "\r\n"));   // CRLF: opens cleanly in any editor
                runRoot("sync");
                msg = err.length() > 0 ? "Couldn't write " + name + " to " + usb + ":\n" + err
                    : "Wrote " + name + " to " + usb + ".\n\nIt's a plain text file that opens on any computer or phone.";
            }
            handler.post(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Battery report").setMessage(msg)
                    .setPositiveButton("Close", null).show();
            } });
        } }).start();
    }

    void showWarnings() {
        List<Report.Warning> ws = Report.warnings(runRoot("cat " + STATE + "/warnings 2>/dev/null"));
        StringBuilder b = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US);
        for (Report.Warning w : ws) b.append(f.format(new Date(w.at * 1000))).append("\n").append(w.text).append("\n\n");
        new AlertDialog.Builder(this)
            .setTitle(ws.isEmpty() ? "Warning history" : "Warning history (" + ws.size() + ")")
            .setMessage(ws.isEmpty() ? "No warnings so far." : b.toString().trim())
            .setPositiveButton("Close", null).show();
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
            Process p = Runtime.getRuntime().exec(hm() ? new String[] { "/system/bin/sh", "-c", cmd }
                    : new String[] { NEM, "root", "-c", cmd });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
            p.waitFor();
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
        return out.toString();
    }

    /**
     * Writes {@code content} to {@code path} as root. Getting text from this app
     * to a root shell isn't straightforward on the head unit, so it tries several
     * ways in turn and checks each by reading the file back:
     *   1. stage it in the app's external files dir (shared storage, readable by
     *      root), then `cat` it into place;
     *   2. stage it in the app's cache, world-readable, then `cat`;
     *   3. pipe it to `cat > path` on the root shell's stdin;
     *   4. append it in small chunks with mksh's `print -r` (no escape handling).
     * Returns "" on success, otherwise what each attempt did. lastWriteMethod
     * records which one worked (shown in Diagnostics).
     */
    /** Write bytes to a root-only path (stage then cat, or stdin); verified by size. */
    String writeRootBytes(String path, byte[] bytes) {
        StringBuilder why = new StringBuilder();
        String r = writeRootStaged(path, bytes, null, why);
        if (r != null) return r;
        lastWriteMethod = "failed";
        return why.toString();
    }

    /**
     * "" when written, null when every binary-safe way failed (reasons in why).
     * text, when given, is the same content as a string: short files are then also
     * checked by content if the size can't be read.
     */
    String writeRootStaged(String path, byte[] bytes, String text, StringBuilder why) {
        String q = shq(path);
        if (hm()) {   // the service's folder is this app's own: write it directly
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(path);
                try { out.write(bytes); } finally { out.close(); }
                if (written(path, bytes, text)) { lastWriteMethod = "direct"; return ""; }
                why.append("direct: mismatch; ");
            } catch (Exception e) { why.append("direct: ").append(e.getMessage()).append("; "); }
            return null;
        }

        // 1 + 2: stage a file, root copies it
        File[] stages = { stageDir(true), stageDir(false) };
        String[] names = { "external", "cache" };
        for (int i = 0; i < stages.length; i++) {
            File dir = stages[i];
            if (dir == null) { why.append(names[i]).append(": no dir; "); continue; }
            File tmp = new File(dir, "bwd-stage.tmp");
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try { out.write(bytes); } finally { out.close(); }
                tmp.setReadable(true, false);
                dir.setExecutable(true, false);
                String r = runRoot("cat " + shq(tmp.getAbsolutePath()) + " > " + q + " 2>&1").trim();
                boolean ok = written(path, bytes, text);
                tmp.delete();
                if (ok) { lastWriteMethod = names[i]; return ""; }
                why.append(names[i]).append(": ").append(r.length() > 0 ? r : "mismatch").append("; ");
            } catch (Exception e) {
                tmp.delete();
                why.append(names[i]).append(": ").append(e.getMessage()).append("; ");
            }
        }

        // 3: stdin
        try {
            Process p = Runtime.getRuntime().exec(new String[] { NEM, "root", "-c", "cat > " + q });
            java.io.OutputStream o = p.getOutputStream();
            o.write(bytes);
            o.close();
            p.waitFor();
            if (written(path, bytes, text)) { lastWriteMethod = "stdin"; return ""; }
            why.append("stdin: mismatch; ");
        } catch (Exception e) { why.append("stdin: ").append(e.getMessage()).append("; "); }
        return null;
    }

    String writeRoot(String path, String content) {
        String q = shq(path);
        StringBuilder why = new StringBuilder();
        byte[] bytes;
        try { bytes = content.getBytes("UTF-8"); } catch (Exception e) { return e.toString(); }
        String staged = writeRootStaged(path, bytes, content, why);
        if (staged != null) return staged;

        // 4: small chunks, raw print (mksh's echo would interpret backslashes)
        runRoot(": > " + q);
        StringBuilder cmd = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            cmd.append("print -r -- ").append(shq(line)).append(" >> ").append(q).append("; ");
            if (cmd.length() > 1500) { runRoot(cmd.toString()); cmd.setLength(0); }
        }
        if (cmd.length() > 0) runRoot(cmd.toString());
        if (sameContent(path, content)) { lastWriteMethod = "chunks"; return ""; }
        why.append("chunks: mismatch");
        lastWriteMethod = "failed";
        return why.toString();
    }

    String lastWriteMethod = "";

    boolean written(String path, byte[] bytes, String text) {
        return text != null ? sameContent(path, text) : fileSize(path) == bytes.length;
    }

    static String shq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    File stageDir(boolean external) {
        try {
            File d = external ? getExternalFilesDir(null) : getCacheDir();
            if (d == null) return null;
            d.mkdirs();
            return d.isDirectory() ? d : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Was the file written completely? Compares its size on disk with the bytes
     * we meant to write. (Reading the whole file back through the root shell
     * wasn't reliable on the car: an export that worked was reported as failed,
     * most likely because long output gets cut off on the way back.)
     */
    boolean sameContent(String path, String content) {
        long want;
        try { want = content.getBytes("UTF-8").length; } catch (Exception e) { return false; }
        long got = fileSize(path);
        if (got >= 0) return got == want;
        // size unknown: fall back to comparing short files by content
        if (want > 4096) return false;
        String back = runRoot("cat " + shq(path) + " 2>/dev/null");
        return back.trim().equals(content.trim());
    }

    /**
     * Battery-related function names in the telematics service's own interface
     * definition (the file the lookup found), with their transaction numbers.
     * Read from the firmware file only: nothing is called. This is how to find
     * out whether the firmware exposes more 12V data than getBatSoc.
     */
    String batteryFunctions() {
        String src = kv(runRoot("cat " + STATE + "/txn 2>/dev/null"), "source");
        if (!src.startsWith("/")) return "(no interface file recorded yet: open the app on a verified firmware first)";
        try {
            File f = new File(src);
            Map<String, Integer> m;
            String n = src.toLowerCase(Locale.US);
            if (n.endsWith(".apk") || n.endsWith(".jar")) m = TxnResolver.transactionsInZip(f, TxnResolver.STUB, NEEDLE);
            else {
                try { m = TxnResolver.transactions(f, TxnResolver.STUB); }
                catch (java.io.IOException e) { File c = viaRoot(f); m = TxnResolver.transactions(c, TxnResolver.STUB); c.delete(); }
            }
            java.util.TreeMap<Integer, String> hits = new java.util.TreeMap<Integer, String>();
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey().matches("(?i).*(bat|ibs|volt|soh|soc|12v|aux|ldc|charg).*")) hits.put(e.getValue(), e.getKey());
            }
            StringBuilder b = new StringBuilder(m.size() + " functions in " + src + "; matching:\n");
            for (Map.Entry<Integer, String> e : hits.entrySet()) b.append("  ").append(e.getKey()).append("  ").append(e.getValue()).append("\n");
            if (hits.isEmpty()) b.append("  (none)\n");
            return b.toString();
        } catch (Throwable t) {
            return "couldn't read " + src + ": " + t;
        }
    }

    /** File size from `ls -l` as root, or -1. Works with toolbox and busybox formats. */
    long fileSize(String path) {
        String l = runRoot("ls -l " + shq(path) + " 2>/dev/null").trim();
        if (l.length() == 0 || l.startsWith("ls:")) return -1;
        String[] t = l.split("\\s+");
        // the size is the last plain number before the date (2026-10-30 or Oct)
        for (int i = 1; i < t.length; i++) {
            if (t[i].matches("\\d{4}-\\d\\d-\\d\\d") || t[i].matches("[A-Z][a-z]{2}")) {
                for (int j = i - 1; j > 0; j--) if (t[j].matches("\\d+")) return Long.parseLong(t[j]);
                return -1;
            }
        }
        return -1;
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
        if (hm()) loadHmSettings();
    }

    // ---- settings kept by HMG-API (the mod) ------------------------------------
    // HMG-API stores them (its page for the mod comes from hmgapi-mod/settings.json);
    // the monitor copies them into its files once a minute. The app reads them from
    // HMG-API and saves to both, so either place can change them.

    void loadHmSettings() {
        String j = hmgapid("setting-get").trim();
        org.json.JSONObject o;
        try { o = new org.json.JSONObject(j.startsWith("{") ? j : "{}"); } catch (Exception e) { return; }
        if (!o.has("warn_pct")) {
            // never saved in HMG-API yet: give it the values in use, so its page shows them
            saveHmSettings();
            return;
        }
        if (o.has("enabled"))     enabled = o.optBoolean("enabled", enabled);
        if (o.has("warn_pct"))    warnPct = clamp(o.optInt("warn_pct", warnPct), 30, 90);
        if (o.has("drop_pct"))    dropPct = clamp(o.optInt("drop_pct", dropPct), 5, 40);
        if (o.has("toast_start")) toastStart = "every".equals(o.optString("toast_start"));
        if (o.has("interval_s"))  interval = clamp(parse(o.optString("interval_s"), interval), 60, 900);
    }

    /** "" when saved, else what HMG-API said */
    String saveHmSettings() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("warn_pct", warnPct);
            o.put("drop_pct", dropPct);
            o.put("toast_start", toastStart ? "every" : "warnings");
            o.put("interval_s", String.valueOf(interval));
        } catch (Exception e) { return e.toString(); }
        // the JSON has no single quotes, so it goes through the shell as one word
        String r = hmgapid("setting-set '" + o.toString() + "'").trim();
        return r.startsWith("saved") ? "" : (r.length() > 0 ? r : "no answer from HMG-API");
    }

    /** "" when saved, else the error */
    String saveConfig() {
        String cmd = "mkdir -p " + STATE
            + "; echo " + (enabled ? 1 : 0) + " > " + STATE + "/enabled"
            + "; echo " + (toastStart ? 1 : 0) + " > " + STATE + "/toast_start"
            + "; echo " + warnPct + " > " + STATE + "/warn_pct"
            + "; echo " + dropPct + " > " + STATE + "/drop_pct"
            + "; echo " + decode + " > " + STATE + "/decode"
            + "; echo " + scaleX10 + " > " + STATE + "/scale_x10"
            + "; echo " + interval + " > " + STATE + "/interval_s";
        String r = runRoot(cmd);
        if (r.startsWith("ERR")) return r;
        return hm() ? saveHmSettings() : "";
    }

    void refreshButtons() {
        enSeg.select(enabled ? 0 : 1);
        popSeg.select(toastStart ? 1 : 0);
        int i = 0; while (i < INTERVALS.length && INTERVALS[i] != interval) i++;
        intSeg.select(i);                   // none lit if the file holds another value
        decSeg.select(decode);
        scaleSeg.select(scaleX10 == 10 ? 0 : 1);
        warnStep.show(warnPct);
        dropStep.show(dropPct);
    }

    // ---- widgets ----------------------------------------------------------------

    /** The 12V level as a 270-degree ring, with a tick at the warning level. */
    static class Dial extends View {
        Pal pal = NIGHT;
        int value10 = -1, warn10 = 600, color;
        String text = "…";
        final float d;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG),
                small = new Paint(Paint.ANTI_ALIAS_FLAG), tickP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF oval = new android.graphics.RectF();

        Dial(Context c, float density) {
            super(c);
            d = density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            big.setTextAlign(Paint.Align.CENTER);
            small.setTextAlign(Paint.Align.CENTER);
            tickP.setStrokeCap(Paint.Cap.ROUND);
        }

        void show(int v10, String t, int col) { value10 = v10; text = t; color = col; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            paint(c, getWidth(), getHeight(), pal, value10, warn10, text, color, d,
                  ring, big, small, tickP, oval);
        }

        /** draws the dial into w x h: shared with the split-screen panel (PanelWidget) */
        static void paint(Canvas c, float w, float h, Pal pal, int value10, int warn10, String text, int color, float d,
                          Paint ring, Paint big, Paint small, Paint tickP, android.graphics.RectF oval) {
            float size = Math.min(w, h), cx = w / 2f, cy = h / 2f;
            float sw = size * 0.085f, r = size / 2f - sw;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            ring.setStrokeWidth(sw);
            ring.setColor(pal.line);
            c.drawArc(oval, 135, 270, false, ring);
            if (value10 >= 0) {
                ring.setColor(color);
                c.drawArc(oval, 135, Math.max(1f, 270f * Math.min(value10, 1000) / 1000f), false, ring);
            }
            // warning tick, across the ring
            double a = Math.toRadians(135 + 270.0 * warn10 / 1000.0);
            float ri = r - sw * 0.9f, ro = r + sw * 0.9f;
            tickP.setColor(pal.amber);
            tickP.setStrokeWidth(3 * d);
            c.drawLine(cx + ri * (float) Math.cos(a), cy + ri * (float) Math.sin(a),
                       cx + ro * (float) Math.cos(a), cy + ro * (float) Math.sin(a), tickP);
            big.setColor(value10 >= 0 ? pal.text : color);
            big.setTextSize(size * (text.length() > 5 ? 0.17f : 0.21f));
            c.drawText(text, cx, cy + size * 0.06f, big);
            small.setColor(pal.muted);
            small.setTextSize(size * 0.075f);
            c.drawText("12V battery", cx, cy + size * 0.2f, small);
        }
    }

    /** A small card: a caption, one value, a line under it. */
    class Tile {
        final LinearLayout v;
        final TextView value, sub;
        Tile(String caption) {
            v = column();
            v.setPadding(dp(14), dp(10), dp(14), dp(10));
            TextView t = new TextView(MainActivity.this);
            t.setText(caption); t.setTextColor(pal.muted); t.setTextSize(14);
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.addView(t);
            value = new TextView(MainActivity.this);
            value.setTextSize(26); value.setIncludeFontPadding(false);
            value.setPadding(0, dp(4), 0, dp(2));
            v.addView(value);
            sub = new TextView(MainActivity.this);
            sub.setTextColor(pal.muted); sub.setTextSize(14);
            sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.addView(sub);
            set("--", "", pal.text, false);
        }
        void set(String val, String line, int color, boolean flag) {
            value.setText(val); value.setTextColor(color);
            sub.setText(line);
            v.setBackgroundDrawable(box(pal.card, flag ? color : pal.line));
        }
    }

    /** A row of options, one lit. */
    class Seg {
        final LinearLayout row;
        final TextView[] opts;
        Seg(String[] labels, final Setter s) {
            row = row();
            opts = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                opts[i] = chip(labels[i], new View.OnClickListener() { public void onClick(View v) { s.set(k); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
                if (i > 0) lp.leftMargin = dp(6);
                row.addView(opts[i], lp);
            }
        }
        void select(int sel) {
            for (int j = 0; j < opts.length; j++) styleChip(opts[j], j == sel);
        }
    }

    /** − value + ; holding a button keeps stepping. */
    class Stepper {
        final LinearLayout row;
        final TextView value;
        final int min, max;
        final String unit;
        final Setter s;
        int v;
        Stepper(int min, int max, int start, String unit, Setter s) {
            this.min = min; this.max = max; this.unit = unit; this.s = s; v = start;
            row = row();
            row.addView(button(-1), new LinearLayout.LayoutParams(dp(76), dp(52)));
            value = new TextView(MainActivity.this);
            value.setTextColor(pal.text); value.setTextSize(24);
            value.setGravity(android.view.Gravity.CENTER);
            row.addView(value, new LinearLayout.LayoutParams(0, dp(52), 1f));
            row.addView(button(1), new LinearLayout.LayoutParams(dp(76), dp(52)));
        }
        void show(int n) { v = n; value.setText(n + unit); }
        TextView button(final int dir) {
            final TextView b = chip(dir < 0 ? "−" : "+", null);
            b.setTextSize(24);
            final Runnable repeat = new Runnable() {
                public void run() { if (step(dir)) handler.postDelayed(this, 90); }
            };
            b.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View view, android.view.MotionEvent e) {
                    int a = e.getAction();
                    if (a == android.view.MotionEvent.ACTION_DOWN) {
                        b.setPressed(true);
                        step(dir);
                        handler.postDelayed(repeat, 450);
                    } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                        b.setPressed(false);
                        handler.removeCallbacks(repeat);
                    }
                    return true;
                }
            });
            return b;
        }
        boolean step(int dir) {
            int n = v + dir;
            if (n < min || n > max) return false;
            s.set(n);
            return true;
        }
    }

    // ---- chart ----------------------------------------------------------------
    static class Chart extends View {
        List<long[]> drives = new ArrayList<long[]>();    // {start, end, start_pct10, end_pct10}
        int warn10 = 600, drop10 = 150;
        final float d;
        final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), startDot = new Paint(Paint.ANTI_ALIAS_FLAG),
                endDot = new Paint(Paint.ANTI_ALIAS_FLAG), park = new Paint(Paint.ANTI_ALIAS_FLAG),
                parkBad = new Paint(Paint.ANTI_ALIAS_FLAG), parkUp = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(), warnP = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Chart(Context c, float density, Pal p) {
            super(c);
            d = density;
            bar.setColor(p.accent); bar.setStrokeWidth(4 * d);
            startDot.setColor(p.text);
            endDot.setColor(p.accent);
            park.setColor(p.park); park.setStrokeWidth(2 * d);
            parkBad.setColor(p.amber); parkBad.setStrokeWidth(2 * d);
            parkUp.setColor(p.green); parkUp.setStrokeWidth(2 * d);
            grid.setColor(p.grid); grid.setStrokeWidth(d);
            warnP.setColor(p.amber); warnP.setStrokeWidth(2 * d);
            text.setColor(p.muted); text.setTextSize(13 * d);
        }

        void setData(List<long[]> dr, int w, int drop) { drives = dr; warn10 = w; drop10 = drop; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), left = 44 * d, top = 10 * d, bottom = h - 26 * d;
            // scale: from 5 points below the lowest value (or the warning line) up to 100 %
            long lo10 = warn10;
            for (long[] r : drives) lo10 = Math.min(lo10, Math.min(r[2], r[3]));
            int lo = (int) Math.max(0, ((lo10 - 50) / 100) * 10);
            float span = 100 - lo;
            for (int pct = lo; pct <= 100; pct += 10) {
                float y = bottom - (bottom - top) * (pct - lo) / span;
                c.drawLine(left, y, w, y, grid);
                c.drawText(pct + "%", 0, y + 5 * d, text);
            }
            float wy = bottom - (bottom - top) * (warn10 / 10f - lo) / span;
            c.drawLine(left, wy, w, wy, warnP);
            if (drives.isEmpty()) { c.drawText("No drives yet. They appear here as you drive.", left + 12 * d, h / 2, text); return; }

            int n = drives.size();
            float slot = (w - left - 8 * d) / Math.max(n, 6);
            SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.US);
            String lastLabel = "";
            float lastLabelX = -1000, px = 0, py = 0;
            for (int i = 0; i < n; i++) {
                long[] r = drives.get(i);
                float x = left + slot * (i + 0.5f);
                float ys = bottom - (bottom - top) * (r[2] / 10f - lo) / span;
                float ye = bottom - (bottom - top) * (r[3] / 10f - lo) / span;
                if (i > 0) {   // parked: previous end -> this start, dashed
                    long change = r[2] - drives.get(i - 1)[3];
                    Paint pp = change <= -drop10 ? parkBad : change >= 10 ? parkUp : park;
                    dashed(c, px, py, x, ys, pp);
                }
                c.drawLine(x, ys, x, ye, bar);
                c.drawCircle(x, ys, 5 * d, startDot);
                c.drawCircle(x, ye, 5 * d, endDot);
                px = x; py = ye;
                String lab = day.format(new java.util.Date(r[0] * 1000));
                if (!lab.equals(lastLabel) && x - lastLabelX > 60 * d) {
                    c.drawText(lab, x - 18 * d, h - 6 * d, text);
                    lastLabel = lab; lastLabelX = x;
                }
            }
        }

        static void dashed(Canvas c, float x1, float y1, float x2, float y2, Paint p) {
            float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            float step = 8, on = 5;
            for (float t = 0; t < len; t += step) {
                float a = t / len, b = Math.min(t + on, len) / len;
                c.drawLine(x1 + dx * a, y1 + dy * a, x1 + dx * b, y1 + dy * b, p);
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

    LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }
    TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(pal.muted); t.setTextSize(15);
        t.setPadding(0, dp(4), 0, dp(6)); return t;
    }
    TextView caption(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(pal.text); t.setTextSize(16);
        t.setPadding(0, dp(14), 0, dp(6)); return t;
    }

    /** a settings card with its heading */
    LinearLayout card(String heading) {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(12), dp(16), dp(16));
        c.setBackgroundDrawable(box(pal.card, pal.line));
        TextView h = new TextView(this);
        h.setText(heading); h.setTextColor(pal.accent); h.setTextSize(18);
        c.addView(h);
        return c;
    }

    /** a card in its own scroll view, in case a long setting ever overflows the 720 px height */
    ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(false);
        s.addView(v);
        s.setPadding(0, 0, 0, dp(40));   // keep the last item clear of the screen's rounded corner
        s.setClipToPadding(false);
        return s;
    }

    /** a tappable label (the app draws its own buttons so day and night look alike) */
    TextView chip(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(17);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight(dp(52));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setClickable(true);
        if (l != null) t.setOnClickListener(l);
        styleChip(t, false);
        return t;
    }

    void styleChip(TextView t, boolean on) {
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_pressed }, box(mix(pal.accent, pal.card, 0.35f), pal.accent));
        sl.addState(new int[0], on ? box(pal.accent, pal.accent) : box(pal.card, pal.line));
        t.setBackgroundDrawable(sl);
        t.setTextColor(on ? pal.onAccent : pal.text);
    }

    /** a full-width action in a settings card */
    TextView tool(String s, View.OnClickListener l) {
        TextView t = chip(s, l);
        t.setLayoutParams(margins(LinearLayout.LayoutParams.MATCH_PARENT, 0, dp(10), 0, 0));
        return t;
    }

    GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(12));
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    /** a over b, a's share = f */
    static int mix(int a, int b, float f) {
        return Color.rgb(Math.round(Color.red(a) * f + Color.red(b) * (1 - f)),
                Math.round(Color.green(a) * f + Color.green(b) * (1 - f)),
                Math.round(Color.blue(a) * f + Color.blue(b) * (1 - f)));
    }

    LinearLayout.LayoutParams margins(int width, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b); return p;
    }
    /** takes the rest of a vertical parent */
    LinearLayout.LayoutParams vfill(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        p.setMargins(l, t, r, b); return p;
    }
    /** an equal share of a horizontal parent */
    LinearLayout.LayoutParams hfill(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(l, t, r, b); return p;
    }
    LinearLayout.LayoutParams tileLp(boolean gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) p.leftMargin = dp(10);
        return p;
    }
}
