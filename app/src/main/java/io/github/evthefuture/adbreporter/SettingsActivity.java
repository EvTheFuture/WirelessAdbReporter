package io.github.evthefuture.adbreporter;

import android.app.Activity;
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

import java.util.List;

/**
 * Automatic mode switch, the SSID allow list and the server settings.
 *
 * The switch and SSID changes are saved immediately, text fields when leaving the screen.
 */
public class SettingsActivity extends Activity {

    private Switch autoSwitch;
    private LinearLayout ssidList;
    private EditText ssidField;
    private Button addCurrentButton;
    private EditText urlField;
    private EditText tokenField;
    private EditText deviceIdField;

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
                + "are reported whenever the phone is connected to one of the networks below."));

        root.addView(Ui.heading(this, "Networks (SSID)"));
        ssidList = new LinearLayout(this);
        ssidList.setOrientation(LinearLayout.VERTICAL);
        root.addView(ssidList);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        ssidField = new EditText(this);
        ssidField.setHint("SSID");
        ssidField.setSingleLine(true);
        addRow.addView(ssidField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(Ui.button(this, "Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSsid(ssidField.getText().toString().trim());
                ssidField.setText("");
            }
        }));
        root.addView(addRow);

        addCurrentButton = Ui.button(this, "Add current network", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ssid = currentSsid();

                if (ssid == null) {
                    toast("SSID unknown, grant location permission and turn Location on");
                    return;
                }

                addSsid(ssid);
            }
        });
        root.addView(addCurrentButton);

        root.addView(Ui.heading(this, "Server"));
        root.addView(Ui.label(this, "URL (JSON is POSTed here)"));
        urlField = new EditText(this);
        urlField.setHint("http://192.168.1.10:8765/api/adb");
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlField);

        root.addView(Ui.label(this, "Bearer token (optional)"));
        tokenField = new EditText(this);
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenField);

        root.addView(Ui.label(this, "Device ID (used to look the device up on the server)"));
        deviceIdField = new EditText(this);
        deviceIdField.setSingleLine(true);
        deviceIdField.setHint(Prefs.defaultDeviceId(this));
        deviceIdField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(deviceIdField);

        return scroll;
    }

    private void renderSsids() {
        ssidList.removeAllViews();
        List<String> ssids = Prefs.ssids(this);

        if (ssids.isEmpty()) {
            ssidList.addView(Ui.label(this, "No networks yet, nothing will be activated."));
            return;
        }

        for (final String ssid : ssids) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(ssid);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.button(this, "Remove", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeSsid(ssid);
                }
            }));
            ssidList.addView(row);
        }
    }

    private void refreshCurrentSsid() {
        String ssid = currentSsid();
        addCurrentButton.setText(ssid == null ? "Add current network (unknown)" : "Add current network: " + ssid);
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
     */
    private void addSsid(String ssid) {

        if (ssid.isEmpty()) {
            return;
        }

        List<String> ssids = Prefs.ssids(this);

        if (!ssids.contains(ssid)) {
            ssids.add(ssid);
            Prefs.setSsids(this, ssids);
        }

        renderSsids();
    }

    private void removeSsid(String ssid) {
        List<String> ssids = Prefs.ssids(this);
        ssids.remove(ssid);
        Prefs.setSsids(this, ssids);
        renderSsids();
    }

    private void loadFields() {
        urlField.setText(Prefs.settings(this).getString(Prefs.URL, ""));
        tokenField.setText(Prefs.settings(this).getString(Prefs.TOKEN, ""));
        deviceIdField.setText(Prefs.settings(this).getString(Prefs.DEVICE_ID, ""));
        autoSwitch.setChecked(Prefs.isAuto(this));
        renderSsids();

        // Attached after loading so the initial state does not count as a toggle
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                saveTextFields();
                Prefs.settings(SettingsActivity.this).edit().putBoolean(Prefs.AUTO, checked).apply();
                ReporterService.sync(SettingsActivity.this);

                if (checked && Prefs.ssids(SettingsActivity.this).isEmpty()) {
                    toast("Add at least one network, nothing happens until then");
                }
            }
        });
    }

    private void saveTextFields() {
        Prefs.settings(this).edit()
                .putString(Prefs.URL, urlField.getText().toString().trim())
                .putString(Prefs.TOKEN, tokenField.getText().toString().trim())
                .putString(Prefs.DEVICE_ID, deviceIdField.getText().toString().trim())
                .apply();
    }
}
