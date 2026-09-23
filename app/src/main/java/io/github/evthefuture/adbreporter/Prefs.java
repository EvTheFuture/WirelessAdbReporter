package io.github.evthefuture.adbreporter;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings and status storage plus helpers around the adb secure settings and permissions.
 *
 * User settings and service status live in separate files so the service can listen for
 * settings changes without seeing its own status writes.
 */
final class Prefs {

    static final String SETTINGS_FILE = "settings";
    static final String STATUS_FILE = "status";

    // User settings
    static final String AUTO = "auto";
    static final String URL = "url";
    static final String TOKEN = "token";
    static final String DEVICE_ID = "device_id";
    static final String SSIDS = "ssids";

    // Status written by the service and shown by the activities
    static final String ST_RUNNING = "running";
    static final String ST_SSID = "ssid";
    static final String ST_ENDPOINT = "endpoint";
    static final String ST_REPORT = "report";
    static final String ST_EVENT = "event";

    // Global settings keys used by the system for adb
    static final String ADB_ENABLED = "adb_enabled";
    static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    private Prefs() {
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE);
    }

    static SharedPreferences status(Context context) {
        return context.getSharedPreferences(STATUS_FILE, Context.MODE_PRIVATE);
    }

    static boolean isAuto(Context context) {
        return settings(context).getBoolean(AUTO, false);
    }

    // ------------------------------------------------------------------
    // SSIDs
    // ------------------------------------------------------------------

    static List<String> ssids(Context context) {
        String raw = settings(context).getString(SSIDS, "");
        List<String> result = new ArrayList<>();

        for (String line : raw.split("\n")) {

            if (!line.isEmpty() && !result.contains(line)) {
                result.add(line);
            }
        }

        return result;
    }

    static void setSsids(Context context, List<String> ssids) {
        settings(context).edit().putString(SSIDS, TextUtils.join("\n", ssids)).apply();
    }

    /**
     * SSIDs are case sensitive, so match exactly.
     */
    static boolean isSsidAllowed(Context context, String ssid) {
        return ssid != null && ssids(context).contains(ssid);
    }

    /**
     * WifiInfo quotes UTF-8 SSIDs. Anything unquoted is either the placeholder shown when
     * location access is missing or a hex dump of a non UTF-8 SSID, neither can match.
     * The content between the quotes is kept as is, spaces included.
     */
    static String normalizeSsid(String raw) {

        if (raw == null || raw.length() < 3 || !raw.startsWith("\"") || !raw.endsWith("\"")) {
            return null;
        }

        return raw.substring(1, raw.length() - 1);
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    static String deviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);

        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }

        return Build.MODEL;
    }

    /**
     * Id the server stores the device under, defaults to a slug of the device name.
     */
    static String deviceId(Context context) {
        String id = settings(context).getString(DEVICE_ID, "");

        if (id != null && !id.trim().isEmpty()) {
            return id.trim();
        }

        return defaultDeviceId(context);
    }

    static String defaultDeviceId(Context context) {
        String slug = deviceName(context).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.isEmpty()) {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            slug = "android-" + androidId;
        }

        return slug;
    }

    // ------------------------------------------------------------------
    // Permissions and system state
    // ------------------------------------------------------------------

    static boolean hasSecureSettingsPermission(Context context) {
        return granted(context, Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    static boolean hasFineLocation(Context context) {
        return granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    static boolean hasBackgroundLocation(Context context) {
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    /**
     * Runtime permission from Android 13, always allowed before that.
     */
    static boolean hasNotifications(Context context) {
        return Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS);
    }

    static boolean isLocationEnabled(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return manager != null && manager.isLocationEnabled();
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isUsbDebuggingEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), ADB_ENABLED, 0) == 1;
    }

    static boolean isWirelessDebuggingEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), ADB_WIFI_ENABLED, 0) == 1;
    }

    /**
     * Turns wireless debugging on or off. Returns false if the permission is missing.
     */
    static boolean setWirelessDebugging(Context context, boolean enabled) {

        try {
            return Settings.Global.putInt(context.getContentResolver(), ADB_WIFI_ENABLED, enabled ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }
}
