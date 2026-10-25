package io.github.evthefuture.adbreporter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures the app from a computer in one command, every extra is optional:
 *
 *   adb shell am start -n io.github.evthefuture.adbreporter/.ProvisionActivity \
 *       --es profile lab --es url http://host:8765/api/adb --es token secret \
 *       --es ssids "LabNet,LabNet-5G" --ez auto true
 *
 * profile         server profile to create or update, "Default" when left out
 * url, token      server settings of that profile
 * ssids           comma separated, replaces the profile's list; an SSID listed here is
 *                 removed from every other profile, so it moves to this one
 * device_id       with profile: an override for that profile only (empty clears it);
 *                 without profile: the global device ID, as before profiles existed
 * remove_profile  deletes a profile by name, applied before everything else
 * auto            the automatic mode switch
 *
 * The manifest protects this activity with WRITE_SECURE_SETTINGS, which the adb shell
 * holds and normal apps cannot get, so other apps cannot reconfigure the reporter.
 */
public class ProvisionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();

        if (extras != null) {
            apply(extras);
        }

        // MainActivity only resumes once the phone is unlocked
        ReporterService.sync(this);

        // Show the status screen, it also starts or stops the service
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void apply(Bundle extras) {
        List<Profiles.Profile> profiles = Profiles.load(this);
        boolean changed = false;

        if (extras.containsKey("remove_profile")) {
            Profiles.Profile gone = Profiles.byName(profiles, extras.getString("remove_profile", "").trim());

            if (gone != null) {
                profiles.remove(gone);
                changed = true;
            }
        }

        boolean named = extras.containsKey("profile");
        boolean touchesProfile = named || extras.containsKey("url") || extras.containsKey("token")
                || extras.containsKey("ssids");

        if (touchesProfile) {
            String name = named ? extras.getString("profile", "").trim() : "";

            if (name.isEmpty()) {
                name = Profiles.DEFAULT_NAME;
            }

            Profiles.Profile profile = Profiles.byName(profiles, name);

            if (profile == null) {
                profile = new Profiles.Profile(name);
                profiles.add(profile);
            }

            if (extras.containsKey("url")) {
                profile.url = extras.getString("url", "").trim();
            }

            if (extras.containsKey("token")) {
                profile.token = extras.getString("token", "").trim();
            }

            if (named && extras.containsKey("device_id")) {
                profile.deviceId = extras.getString("device_id", "").trim();
            }

            if (extras.containsKey("ssids")) {
                setSsids(profiles, profile, extras.getString("ssids", ""));
            }

            changed = true;
        }

        if (changed) {
            Profiles.save(this, profiles);
        }

        // Without a profile name the device ID stays global
        if (!named && extras.containsKey("device_id")) {
            Prefs.settings(this).edit().putString(Prefs.DEVICE_ID, extras.getString("device_id", "").trim()).apply();
        }

        if (extras.containsKey("auto")) {
            Prefs.settings(this).edit().putBoolean(Prefs.AUTO, extras.getBoolean("auto", false)).apply();
        }
    }

    /**
     * Replaces the profile's SSIDs and takes each of them away from the other profiles.
     */
    private static void setSsids(List<Profiles.Profile> profiles, Profiles.Profile target, String raw) {
        List<String> ssids = new ArrayList<>();

        for (String part : raw.split(",")) {
            String ssid = part.trim();

            if (!ssid.isEmpty() && !ssids.contains(ssid)) {
                ssids.add(ssid);
            }
        }

        for (Profiles.Profile other : profiles) {

            if (other != target) {
                other.ssids.removeAll(ssids);
            }
        }

        target.ssids.clear();
        target.ssids.addAll(ssids);
    }
}
