package com.nemesis.batterywatchdog;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
    static final int AMBER = Color.rgb(255, 170, 60), GREEN = Color.rgb(110, 210, 140), MUTED = Color.rgb(150, 160, 170);

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
        RemoteViews r = new RemoteViews(app.getPackageName(), R.layout.panel);
        fill(r, status, warnPct, dropPct);
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        r.setOnClickPendingIntent(R.id.panel_root, PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager.getInstance(app).updateAppWidget(new ComponentName(app, PanelWidget.class), r);
    }

    static void fill(RemoteViews r, String status, int warnPct, int dropPct) {
        String state = MainActivity.kv(status, "state");
        int p = MainActivity.parse(MainActivity.kv(status, "pct10"), -1);
        String big, ctx = "", park = "", warn = MainActivity.kv(status, "warn"), upd = "";
        int bigColour = Color.WHITE, parkColour = Color.WHITE;
        if (state.length() == 0) { big = "--"; upd = "No reading yet"; }
        else if ("unverified".equals(state)) {
            big = "Paused"; bigColour = AMBER;
            upd = MainActivity.hm() ? "HMG-API has no 12V reading for this car yet" : "Firmware not verified";
        } else if ("disabled".equals(state)) { big = "Off"; bigColour = MUTED; upd = "Monitoring is turned off"; }
        else {
            big = p >= 0 ? MainActivity.fmt10(p) + "%" : "--";
            if (p >= 0) bigColour = p < warnPct * 10 ? AMBER : GREEN;
            int hv = MainActivity.parse(MainActivity.kv(status, "hv10"), -1);
            int tc = MainActivity.parse(MainActivity.kv(status, "temp_c"), Integer.MIN_VALUE);
            StringBuilder cx = new StringBuilder();
            if (hv >= 0) cx.append("Main battery ").append(MainActivity.fmt10(hv)).append("%");
            if (tc != Integer.MIN_VALUE) {
                if (cx.length() > 0) cx.append("  ·  ");
                cx.append("Outside ").append(Math.round(tc * 9 / 5f + 32)).append("°F");
            }
            ctx = cx.toString();
            int pMin = MainActivity.parse(MainActivity.kv(status, "parked_min"), -1);
            int pDrop = MainActivity.parse(MainActivity.kv(status, "parked_drop10"), Integer.MIN_VALUE);
            if (pMin >= 0 && pDrop != Integer.MIN_VALUE) {
                park = "Parked " + MainActivity.duration(pMin) + ": "
                    + (pDrop > 0 ? "−" + MainActivity.fmt10(pDrop) + " pts" : pDrop < 0 ? "+" + MainActivity.fmt10(-pDrop) + " pts" : "no change");
                if (pDrop >= dropPct * 10) parkColour = AMBER;
            }
            long t = MainActivity.parseL(MainActivity.kv(status, "updated"), 0);
            upd = t > 0 ? "Updated " + MainActivity.time(t) : ("no_reading".equals(state) ? "The 12V value couldn't be read" : "");
        }
        r.setTextViewText(R.id.panel_big, big);
        r.setTextColor(R.id.panel_big, bigColour);
        text(r, R.id.panel_ctx, ctx);
        text(r, R.id.panel_park, park);
        r.setTextColor(R.id.panel_park, parkColour);
        text(r, R.id.panel_warn, warn.length() > 0 ? "⚠ " + warn : "");
        text(r, R.id.panel_upd, upd);
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
