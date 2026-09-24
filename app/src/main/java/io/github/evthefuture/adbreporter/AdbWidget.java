package io.github.evthefuture.adbreporter;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * Home screen widget with the current adb connect address.
 *
 * Tapping the address copies it, the arrow reports now and the title opens the app.
 * The service pushes every change, so the widget has no update period of its own.
 */
public class AdbWidget extends AppWidgetProvider {

    static final String ACTION_COPY = "io.github.evthefuture.adbreporter.COPY";

    private static final int COLOR_ACTIVE = 0xFFFFFFFF;
    private static final int COLOR_IDLE = 0x99FFFFFF;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_COPY.equals(intent.getAction())) {
            copyEndpoint(context);
        }
    }

    /**
     * Redraws every placed widget, cheap enough to call on each status change.
     */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);

        if (manager == null) {
            return;
        }

        int[] ids = manager.getAppWidgetIds(new ComponentName(context, AdbWidget.class));

        if (ids != null && ids.length > 0) {
            render(context, manager, ids);
        }
    }

    /**
     * The address to connect to, or an empty string while nothing is active.
     */
    static String endpoint(Context context) {
        return Prefs.status(context).getString(Prefs.ST_ENDPOINT, "");
    }

    /**
     * Why there is no address right now, in a few words.
     */
    static String idleText(Context context) {
        SharedPreferences status = Prefs.status(context);

        if (!Prefs.isAuto(context)) {
            return "Automatic mode is off";
        }

        String state = status.getString(Prefs.ST_STATE, "");

        if (!state.isEmpty()) {
            return state;
        }

        return status.getString(Prefs.ST_RUNNING, "").isEmpty() ? "Service not running" : "Waiting";
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids) {
        String endpoint = endpoint(context);
        String ssid = Prefs.status(context).getString(Prefs.ST_SSID, "");
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

        views.setTextViewText(R.id.widget_title, Prefs.deviceId(context));

        // Hotspot, USB tethering and other local interfaces under the Wi-Fi address
        String others = otherInterfaces(context, endpoint);
        views.setTextViewText(R.id.widget_others, others);
        views.setViewVisibility(R.id.widget_others, others.isEmpty() ? View.GONE : View.VISIBLE);

        if (endpoint.isEmpty()) {
            views.setTextViewText(R.id.widget_endpoint, "not active");
            views.setTextColor(R.id.widget_endpoint, COLOR_IDLE);
            views.setTextViewText(R.id.widget_status, idleText(context));
        } else {
            views.setTextViewText(R.id.widget_endpoint, endpoint);
            views.setTextColor(R.id.widget_endpoint, COLOR_ACTIVE);
            views.setTextViewText(R.id.widget_status, ssid.isEmpty() ? "Tap to copy" : ssid + ", tap to copy");
        }

        Intent copy = new Intent(context, AdbWidget.class).setAction(ACTION_COPY);
        views.setOnClickPendingIntent(R.id.widget_endpoint,
                PendingIntent.getBroadcast(context, 0, copy, PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.widget_status,
                PendingIntent.getBroadcast(context, 0, copy, PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widget_header,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE));

        // Starts the service if automatic mode is on, it stops itself again otherwise
        Intent report = new Intent(context, ReporterService.class).setAction(ReporterService.ACTION_REPORT);
        views.setOnClickPendingIntent(R.id.widget_report,
                PendingIntent.getForegroundService(context, 2, report, PendingIntent.FLAG_IMMUTABLE));

        manager.updateAppWidget(ids, views);
    }

    /**
     * Local interfaces besides Wi-Fi, e.g. "Hotspot 10.107.64.1:37123". Mobile data and
     * VPN addresses are left out, nothing on the LAN can reach them.
     */
    static String otherInterfaces(Context context, String endpoint) {

        if (endpoint.isEmpty()) {
            return "";
        }

        String port = Interfaces.portOf(endpoint);
        StringBuilder text = new StringBuilder();

        for (Interfaces.Entry entry : Interfaces.list(context)) {

            if (entry.isWifi() || !entry.isLocal()) {
                continue;
            }

            if (text.length() > 0) {
                text.append(", ");
            }

            text.append(entry.label).append(' ').append(entry.endpoint(port));
        }

        return text.toString();
    }

    /**
     * Puts ip:port on the clipboard. Android 13 and later show their own confirmation.
     */
    static void copyEndpoint(Context context) {
        String endpoint = endpoint(context);

        if (endpoint.isEmpty()) {
            Toast.makeText(context, "No active adb port: " + idleText(context), Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard == null) {
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("adb connect", endpoint));

        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, "Copied " + endpoint, Toast.LENGTH_SHORT).show();
        }
    }
}
