package io.github.evthefuture.adbreporter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures the app from a computer in one command, every extra is optional:
 *
 *   adb shell am start -n io.github.evthefuture.adbreporter/.ProvisionActivity \
 *       --es url http://host:8765/api/adb --es token secret --es device_id pixel8 \
 *       --es ssids "HomeNet,LabNet" --ez auto true
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
        SharedPreferences.Editor editor = Prefs.settings(this).edit();

        if (extras.containsKey("url")) {
            editor.putString(Prefs.URL, extras.getString("url", "").trim());
        }

        if (extras.containsKey("token")) {
            editor.putString(Prefs.TOKEN, extras.getString("token", "").trim());
        }

        if (extras.containsKey("device_id")) {
            editor.putString(Prefs.DEVICE_ID, extras.getString("device_id", "").trim());
        }

        if (extras.containsKey("auto")) {
            editor.putBoolean(Prefs.AUTO, extras.getBoolean("auto", false));
        }

        editor.apply();

        // Comma separated, replaces the whole list
        if (extras.containsKey("ssids")) {
            List<String> ssids = new ArrayList<>();

            for (String part : extras.getString("ssids", "").split(",")) {

                if (!part.trim().isEmpty()) {
                    ssids.add(part.trim());
                }
            }

            Prefs.setSsids(this, ssids);
        }
    }
}
