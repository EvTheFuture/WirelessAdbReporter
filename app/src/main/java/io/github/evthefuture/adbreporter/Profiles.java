package io.github.evthefuture.adbreporter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Server profiles: each one has its own server URL, bearer token, optional device ID
 * override and the SSIDs it is used on. An SSID belongs to at most one profile, so the
 * current network always picks exactly one server or none.
 *
 * Stored as one JSON array under Prefs.PROFILES in the settings file, so the service sees
 * every change through a single key.
 */
final class Profiles {

    static final String DEFAULT_NAME = "Default";

    private Profiles() {
    }

    /**
     * One server profile. Plain fields, callers edit a copy and save the whole list.
     */
    static final class Profile {
        String name = "";
        String url = "";
        String token = "";
        String deviceId = "";
        final List<String> ssids = new ArrayList<>();

        Profile(String name) {
            this.name = name;
        }

        /**
         * Device ID this profile reports, the profile override or the global one.
         */
        String effectiveDeviceId(Context context) {

            if (!deviceId.trim().isEmpty()) {
                return deviceId.trim();
            }

            return Prefs.deviceId(context);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("url", url);
            json.put("token", token);
            json.put("device_id", deviceId);
            json.put("ssids", new JSONArray(ssids));
            return json;
        }

        static Profile fromJson(JSONObject json) {
            Profile profile = new Profile(json.optString("name", ""));
            profile.url = json.optString("url", "");
            profile.token = json.optString("token", "");
            profile.deviceId = json.optString("device_id", "");
            JSONArray ssids = json.optJSONArray("ssids");

            if (ssids != null) {

                for (int i = 0; i < ssids.length(); i++) {
                    String ssid = ssids.optString(i, "");

                    if (!ssid.isEmpty() && !profile.ssids.contains(ssid)) {
                        profile.ssids.add(ssid);
                    }
                }
            }

            return profile;
        }
    }

    // ------------------------------------------------------------------
    // Load and save
    // ------------------------------------------------------------------

    /**
     * All profiles in the stored order. Settings from before profiles existed are migrated
     * into a profile named "Default" the first time this is called.
     */
    static List<Profile> load(Context context) {
        SharedPreferences settings = Prefs.settings(context);

        if (!settings.contains(Prefs.PROFILES)) {
            return migrate(context);
        }

        List<Profile> result = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(settings.getString(Prefs.PROFILES, "[]"));

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    result.add(Profile.fromJson(json));
                }
            }
        } catch (JSONException e) {
            // A broken value is treated as no profiles rather than crashing the service
            return new ArrayList<>();
        }

        return result;
    }

    static void save(Context context, List<Profile> profiles) {
        JSONArray array = new JSONArray();

        try {

            for (Profile profile : profiles) {
                array.put(profile.toJson());
            }
        } catch (JSONException e) {
            return;
        }

        Prefs.settings(context).edit().putString(Prefs.PROFILES, array.toString()).apply();
    }

    /**
     * Turns the old single URL, token and SSID list into the "Default" profile and removes
     * the old keys. The global device ID stays global.
     */
    private static List<Profile> migrate(Context context) {
        SharedPreferences settings = Prefs.settings(context);
        List<Profile> result = new ArrayList<>();
        String url = settings.getString(Prefs.URL, "");
        String token = settings.getString(Prefs.TOKEN, "");
        String rawSsids = settings.getString(Prefs.SSIDS, "");

        if (!url.isEmpty() || !token.isEmpty() || !rawSsids.isEmpty()) {
            Profile profile = new Profile(DEFAULT_NAME);
            profile.url = url;
            profile.token = token;

            for (String line : rawSsids.split("\n")) {

                if (!line.isEmpty() && !profile.ssids.contains(line)) {
                    profile.ssids.add(line);
                }
            }

            result.add(profile);
        }

        save(context, result);
        settings.edit().remove(Prefs.URL).remove(Prefs.TOKEN).remove(Prefs.SSIDS).apply();
        return result;
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    static Profile byName(List<Profile> profiles, String name) {

        for (Profile profile : profiles) {

            if (profile.name.equals(name)) {
                return profile;
            }
        }

        return null;
    }

    /**
     * Profile that owns this SSID, null when the SSID is not configured. SSIDs are case
     * sensitive, so match exactly.
     */
    static Profile forSsid(Context context, String ssid) {

        if (ssid == null) {
            return null;
        }

        for (Profile profile : load(context)) {

            if (profile.ssids.contains(ssid)) {
                return profile;
            }
        }

        return null;
    }

    /**
     * Every configured SSID across all profiles.
     */
    static List<String> allSsids(Context context) {
        List<String> result = new ArrayList<>();

        for (Profile profile : load(context)) {

            for (String ssid : profile.ssids) {

                if (!result.contains(ssid)) {
                    result.add(ssid);
                }
            }
        }

        return result;
    }

    /**
     * True when at least one profile has a server URL.
     */
    static boolean anyUrl(Context context) {

        for (Profile profile : load(context)) {

            if (!profile.url.trim().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Name of the other profile that already owns this SSID, or null.
     */
    static String ownerOf(List<Profile> profiles, String ssid, Profile except) {

        for (Profile profile : profiles) {

            if (profile != except && profile.ssids.contains(ssid)) {
                return profile.name;
            }
        }

        return null;
    }

    /**
     * A name that is not taken yet: "Profile 2", "Profile 3", ...
     */
    static String freeName(List<Profile> profiles) {
        String base = profiles.isEmpty() ? DEFAULT_NAME : "Profile";

        if (byName(profiles, base) == null) {
            return base;
        }

        int n = 2;

        while (byName(profiles, base + " " + n) != null) {
            n++;
        }

        return base + " " + n;
    }
}
