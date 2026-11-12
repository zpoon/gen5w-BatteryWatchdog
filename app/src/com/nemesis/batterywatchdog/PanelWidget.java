package com.nemesis.batterywatchdog;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.RemoteViews;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Battery Watchdog's page in the car's right-hand split-screen panel. HMG-API shows
 * it there because hmgapi-mod/mod.json lists it under provides.panel (an ordinary
 * Android app widget). It shows what the service last wrote to its status file:
 * the 12V %, main battery and outside temperature, the parked drop before this
 * drive and any warning. The service sends REFRESH after each reading; a tap opens
 * the app. Under HMG-API the status file is this app's own, so no root is needed.
 */
public class PanelWidget extends AppWidgetProvider {
    static final String REFRESH = "com.nemesis.batterywatchdog.PANEL_REFRESH";

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (REFRESH.equals(i.getAction())) refresh(c);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refresh(c); }

    static void refresh(Context c) {
        final Context app = c.getApplicationContext();
        new Thread("bwd-panel") {   // reading the state may run a process: off the main thread
            @Override public void run() {
                try { show(app); } catch (Exception e) { /* keep the last picture */ }
            }
        }.start();
    }

    static void show(Context app) {
        MainActivity.detectMode();
        String status = read("status");
        int warnPct = MainActivity.parse(read("warn_pct").trim(), 60);
        int dropPct = MainActivity.parse(read("drop_pct").trim(), 15);
        int night = app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean day = night == Configuration.UI_MODE_NIGHT_NO;
        float density = app.getResources().getDisplayMetrics().density;
        RemoteViews r = new RemoteViews(app.getPackageName(), R.layout.panel);
        fill(r, status, warnPct, dropPct, day, density);
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        r.setOnClickPendingIntent(R.id.panel_root, PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager.getInstance(app).updateAppWidget(new ComponentName(app, PanelWidget.class), r);
    }

    /** the page, in the app's design and colours (MainActivity.DAY / NIGHT) */
    static void fill(RemoteViews r, String status, int warnPct, int dropPct, boolean day, float density) {
        MainActivity.Pal pal = day ? MainActivity.DAY : MainActivity.NIGHT;
        String state = MainActivity.kv(status, "state");
        int p = MainActivity.parse(MainActivity.kv(status, "pct10"), -1);
        String warn = MainActivity.kv(status, "warn");
        long t = MainActivity.parseL(MainActivity.kv(status, "updated"), 0);

        // the dial and its label
        String text, pill = null, upd = "";
        int colour = pal.muted, value = -1;
        boolean live = false;
        if (state.length() == 0) { text = "--"; upd = "No reading yet"; }
        else if ("waiting".equals(state)) { text = "\u2026"; pill = "Starting"; upd = "Waiting for the first reading"; }
        else if ("unverified".equals(state)) {
            text = "Paused"; colour = pal.amber; pill = "Paused";
            upd = MainActivity.hm() ? "HMG-API gives no 12V reading yet" : "Firmware not verified";
        } else if ("disabled".equals(state)) { text = "Off"; pill = "Monitor off"; upd = "Monitoring is turned off"; }
        else if ("other_install".equals(state)) { text = "Idle"; pill = "Idle"; upd = "The HMG-API mod is monitoring"; }
        else {
            live = true;
            if (p >= 0) {
                boolean low = p < warnPct * 10;
                value = p; text = MainActivity.fmt10(p) + "%"; colour = low ? pal.amber : pal.green; pill = low ? "Low" : "OK";
            } else { text = "--"; pill = "No reading"; }
            upd = t > 0 ? "Read " + MainActivity.time(t) : ("no_reading".equals(state) ? "The 12V value couldn't be read" : "");
        }
        r.setImageViewBitmap(R.id.panel_dial, dial(pal, value, warnPct * 10, text, colour, pill, density));
        r.setInt(R.id.panel_root, "setBackgroundColor", pal.bg);
        text(r, R.id.panel_upd, upd);
        r.setTextColor(R.id.panel_upd, pal.muted);

        // warning banner
        text(r, R.id.panel_warn, live && warn.length() > 0 ? "\u26A0  " + warn : "");
        r.setTextColor(R.id.panel_warn, pal.amber);
        r.setInt(R.id.panel_warn, "setBackgroundResource", day ? R.drawable.panel_banner_day : R.drawable.panel_banner_night);

        // tiles: parked before this drive; main battery and outside temperature
        r.setViewVisibility(R.id.panel_tiles, live ? View.VISIBLE : View.GONE);
        String v1 = "--", s1 = "Shows after a park";
        int c1 = pal.text;
        boolean flag1 = false;
        int pMin = MainActivity.parse(MainActivity.kv(status, "parked_min"), -1);
        int pDrop = MainActivity.parse(MainActivity.kv(status, "parked_drop10"), Integer.MIN_VALUE);
        if (pMin >= 0 && pDrop != Integer.MIN_VALUE) {
            v1 = pDrop > 0 ? "\u2212" + MainActivity.fmt10(pDrop) + " pts" : pDrop < 0 ? "+" + MainActivity.fmt10(-pDrop) + " pts" : "No change";
            s1 = MainActivity.duration(pMin);
            flag1 = pDrop >= dropPct * 10;
            if (flag1) c1 = pal.amber;
        }
        tile(r, R.id.panel_tile1, R.id.panel_t1_cap, R.id.panel_t1_val, R.id.panel_t1_sub, v1, s1, c1, flag1, pal, day);
        int hv = MainActivity.parse(MainActivity.kv(status, "hv10"), -1);
        int tc = MainActivity.parse(MainActivity.kv(status, "temp_c"), Integer.MIN_VALUE);
        tile(r, R.id.panel_tile2, R.id.panel_t2_cap, R.id.panel_t2_val, R.id.panel_t2_sub,
             hv >= 0 ? MainActivity.fmt10(hv) + "%" : "--",
             tc != Integer.MIN_VALUE ? "Outside " + Math.round(tc * 9 / 5f + 32) + "\u00B0F" : "",
             pal.text, false, pal, day);
    }

    static void tile(RemoteViews r, int box, int cap, int val, int sub, String v, String s, int colour,
                     boolean flag, MainActivity.Pal pal, boolean day) {
        r.setInt(box, "setBackgroundResource", flag ? (day ? R.drawable.panel_tile_warn_day : R.drawable.panel_tile_warn_night)
                                                    : (day ? R.drawable.panel_tile_day : R.drawable.panel_tile_night));
        r.setTextColor(cap, pal.muted);
        r.setTextViewText(val, v);
        r.setTextColor(val, colour);
        text(r, sub, s);
        r.setTextColor(sub, pal.muted);
    }

    /** the app's dial (MainActivity.Dial.paint) with its OK/Low label under it, as a picture */
    static Bitmap dial(MainActivity.Pal pal, int value10, int warn10, String text, int colour, String pill, float density) {
        int w = 520, dh = 440, h = dh + 80;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG),
              small = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeCap(Paint.Cap.ROUND);
        big.setTextAlign(Paint.Align.CENTER); small.setTextAlign(Paint.Align.CENTER);
        tick.setStrokeCap(Paint.Cap.ROUND);
        MainActivity.Dial.paint(c, w, dh, pal, value10, warn10, text, colour, 1.5f, ring, big, small, tick, new RectF());
        if (pill != null) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextSize(30); t.setTextAlign(Paint.Align.CENTER); t.setColor(colour);
            float tw = t.measureText(pill), cx = w / 2f, top = dh + 14;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(MainActivity.mix(colour, pal.card, 0.18f));
            c.drawRoundRect(new RectF(cx - tw / 2 - 26, top, cx + tw / 2 + 26, top + 52), 26, 26, bg);
            c.drawText(pill, cx, top + 37, t);
        }
        return b;
    }

    static void text(RemoteViews r, int id, String s) {
        r.setTextViewText(id, s);
        r.setViewVisibility(id, s.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** a file from the service's folder: directly under HMG-API, through nemesis root otherwise */
    static String read(String name) {
        StringBuilder out = new StringBuilder();
        try {
            BufferedReader r;
            Process p = null;
            if (MainActivity.hm()) {
                File f = new File(MainActivity.STATE, name);
                if (!f.isFile()) return "";
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            } else {
                p = Runtime.getRuntime().exec(new String[] { MainActivity.NEM, "root", "-c",
                        "cat " + MainActivity.STATE + "/" + name + " 2>/dev/null" });
                r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            }
            try {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            } finally { r.close(); }
            if (p != null) p.waitFor();
        } catch (Exception e) { return ""; }
        return out.toString();
    }
}
