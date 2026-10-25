package io.github.evthefuture.adbreporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Automatic mode switch, the global device ID and the server profiles. Each profile has its
 * own server URL, token, optional device ID override and the SSIDs it is used on.
 *
 * The switch and structural changes (SSIDs, adding or deleting a profile) are saved
 * immediately, text fields when leaving the screen or before a structural change.
 */
public class SettingsActivity extends Activity {

    private Switch autoSwitch;
    private EditText deviceIdField;
    private LinearLayout profileContainer;

    // Working copy of the stored profiles and the views editing them, same order
    private List<Profiles.Profile> profiles = new ArrayList<>();
    private final List<ProfileViews> profileViews = new ArrayList<>();

    /**
     * Text fields of one rendered profile.
     */
    private static final class ProfileViews {
        Profiles.Profile profile;
        EditText name;
        EditText url;
        EditText token;
        EditText deviceId;
        EditText ssid;
        Button addCurrent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadFields();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrentSsid();
    }

    @Override
    protected void onPause() {
        saveTextFields();
        ReporterService.sync(this);
        super.onPause();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        View scroll = Ui.scrollRoot(this, root);
        root.addView(Ui.title(this, "Settings"));

        autoSwitch = new Switch(this);
        autoSwitch.setText("Activate and report automatically");
        autoSwitch.setTextSize(17);
        autoSwitch.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(autoSwitch);
        root.addView(Ui.label(this, "When on, wireless debugging is switched on and the IP and port "
                + "are reported whenever the phone is connected to a network listed in one of the "
                + "profiles below, to that profile's server."));

        root.addView(Ui.label(this, "Device ID (used to look the device up on the server, a profile "
                + "can override it)"));
        deviceIdField = new EditText(this);
        deviceIdField.setSingleLine(true);
        deviceIdField.setHint(Prefs.defaultDeviceId(this));
        deviceIdField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(deviceIdField);

        root.addView(Ui.heading(this, "Server profiles"));
        root.addView(Ui.label(this, "Each network belongs to one profile. On that network the phone "
                + "reports to the profile's server."));
        profileContainer = new LinearLayout(this);
        profileContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(profileContainer);

        root.addView(Ui.button(this, "Add profile", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTextFields();
                profiles.add(new Profiles.Profile(Profiles.freeName(profiles)));
                commit();
            }
        }));

        return scroll;
    }

    private void renderProfiles() {
        profileContainer.removeAllViews();
        profileViews.clear();

        if (profiles.isEmpty()) {
            profileContainer.addView(Ui.label(this, "No profiles yet, nothing will be activated."));
            return;
        }

        for (Profiles.Profile profile : profiles) {
            profileContainer.addView(renderProfile(profile));
        }

        refreshCurrentSsid();
    }

    private View renderProfile(final Profiles.Profile profile) {
        final ProfileViews views = new ProfileViews();
        views.profile = profile;
        profileViews.add(views);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        box.addView(Ui.heading(this, "Profile: " + profile.name));

        box.addView(Ui.label(this, "Name"));
        views.name = textField(profile.name, "Home", InputType.TYPE_CLASS_TEXT);
        box.addView(views.name);

        box.addView(Ui.label(this, "Server URL (JSON is POSTed here)"));
        views.url = textField(profile.url, "http://192.168.1.10:8765/api/adb",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(views.url);

        box.addView(Ui.label(this, "Bearer token (optional)"));
        views.token = textField(profile.token, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(views.token);

        box.addView(Ui.label(this, "Device ID for this server (optional, empty uses the one above)"));
        views.deviceId = textField(profile.deviceId, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(views.deviceId);

        box.addView(Ui.label(this, "Networks (SSID)"));

        if (profile.ssids.isEmpty()) {
            box.addView(Ui.label(this, "No networks, this profile is never used."));
        }

        for (final String ssid : profile.ssids) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(ssid);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.button(this, "Remove", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveTextFields();
                    profile.ssids.remove(ssid);
                    commit();
                }
            }));
            box.addView(row);
        }

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        views.ssid = new EditText(this);
        views.ssid.setHint("SSID");
        views.ssid.setSingleLine(true);
        addRow.addView(views.ssid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(Ui.button(this, "Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSsid(profile, views.ssid.getText().toString().trim());
            }
        }));
        box.addView(addRow);

        views.addCurrent = Ui.button(this, "Add current network", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ssid = currentSsid();

                if (ssid == null) {
                    toast("SSID unknown, grant location permission and turn Location on");
                    return;
                }

                addSsid(profile, ssid);
            }
        });
        box.addView(views.addCurrent);

        box.addView(Ui.button(this, "Delete profile " + profile.name, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(profile);
            }
        }));

        return box;
    }

    private EditText textField(String value, String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setText(value);
        return field;
    }

    private void refreshCurrentSsid() {
        String ssid = currentSsid();
        String text = ssid == null ? "Add current network (unknown)" : "Add current network: " + ssid;

        for (ProfileViews views : profileViews) {
            views.addCurrent.setText(text);
        }
    }

    private void confirmDelete(final Profiles.Profile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete profile " + profile.name + "?")
                .setMessage("Its networks will no longer activate wireless debugging.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveTextFields();
                        profiles.remove(profile);
                        commit();
                    }
                })
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    /**
     * Current SSID, needs location permission and Location switched on.
     */
    @SuppressWarnings("deprecation")
    private String currentSsid() {
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = manager == null ? null : manager.getConnectionInfo();
        String ssid = info == null ? null : Prefs.normalizeSsid(info.getSSID());

        if (ssid != null) {
            return ssid;
        }

        // Fall back to what the service saw
        ssid = Prefs.status(this).getString(Prefs.ST_SSID, "");
        return ssid.isEmpty() ? null : ssid;
    }

    /**
     * Stored exactly as given, SSIDs can legitimately contain leading or trailing spaces.
     * An SSID can only belong to one profile, so one owned by another profile is refused.
     */
    private void addSsid(Profiles.Profile profile, String ssid) {

        if (ssid.isEmpty()) {
            return;
        }

        String owner = Profiles.ownerOf(profiles, ssid, profile);

        if (owner != null) {
            toast(ssid + " already belongs to profile " + owner + ", remove it there first");
            return;
        }

        saveTextFields();

        if (!profile.ssids.contains(ssid)) {
            profile.ssids.add(ssid);
        }

        commit();
    }

    /**
     * Saves the working copy and redraws, used after every structural change.
     */
    private void commit() {
        Profiles.save(this, profiles);
        renderProfiles();
    }

    private void loadFields() {
        deviceIdField.setText(Prefs.settings(this).getString(Prefs.DEVICE_ID, ""));
        autoSwitch.setChecked(Prefs.isAuto(this));
        profiles = Profiles.load(this);
        renderProfiles();

        // Attached after loading so the initial state does not count as a toggle
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                saveTextFields();
                Prefs.settings(SettingsActivity.this).edit().putBoolean(Prefs.AUTO, checked).apply();
                ReporterService.sync(SettingsActivity.this);

                if (checked && Profiles.allSsids(SettingsActivity.this).isEmpty()) {
                    toast("Add at least one network to a profile, nothing happens until then");
                }
            }
        });
    }

    /**
     * Copies the text fields into the working copy and saves it. A renamed profile keeps
     * its old name when the new one is empty or already taken.
     */
    private void saveTextFields() {
        Prefs.settings(this).edit()
                .putString(Prefs.DEVICE_ID, deviceIdField.getText().toString().trim())
                .apply();

        for (ProfileViews views : profileViews) {
            Profiles.Profile profile = views.profile;
            String name = views.name.getText().toString().trim();
            Profiles.Profile clash = Profiles.byName(profiles, name);

            if (!name.equals(profile.name)) {

                if (name.isEmpty() || (clash != null && clash != profile)) {
                    toast("Profile name \"" + name + "\" is empty or taken, kept " + profile.name);
                    views.name.setText(profile.name);
                } else {
                    profile.name = name;
                }
            }

            profile.url = views.url.getText().toString().trim();
            profile.token = views.token.getText().toString().trim();
            profile.deviceId = views.deviceId.getText().toString().trim();
        }

        Profiles.save(this, profiles);
    }
}
