package io.github.evthefuture.adbreporter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Status screen with a checklist of what is still missing.
 */
public class MainActivity extends Activity {

    private static final long REFRESH_MS = 1000L;
    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_BACKGROUND = 2;
    private static final int REQUEST_NOTIFICATIONS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView endpointView;
    private TextView endpointHint;
    private TextView interfacesView;
    private TextView statusView;
    private Button notificationsButton;
    private Button locationButton;
    private Button backgroundButton;
    private Button locationServicesButton;
    private Button reportButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Also lets the service pick up permissions granted meanwhile
        ReporterService.sync(this);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        ReporterService.sync(this);
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        View scroll = Ui.scrollRoot(this, root);
        root.addView(Ui.title(this, "Wireless ADB Reporter"));

        // The active address first, tap either line to copy it
        View.OnClickListener copy = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AdbWidget.copyEndpoint(MainActivity.this);
            }
        };
        endpointView = Ui.endpoint(this);
        endpointView.setOnClickListener(copy);
        root.addView(endpointView);
        endpointHint = Ui.body(this, "");
        endpointHint.setTextIsSelectable(false);
        endpointHint.setOnClickListener(copy);
        root.addView(endpointHint);

        // Every interface adbd answers on, the Wi-Fi one is what goes to the server
        root.addView(Ui.heading(this, "Interfaces"));
        interfacesView = Ui.mono(this);
        root.addView(interfacesView);

        statusView = Ui.mono(this);
        root.addView(statusView);

        root.addView(Ui.button(this, "Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));

        reportButton = Ui.button(this, "Report now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ReporterService.class)
                        .setAction(ReporterService.ACTION_REPORT);
                startForegroundService(intent);
            }
        });
        root.addView(reportButton);

        // One button per missing permission, each hidden once it is granted
        notificationsButton = Ui.button(this, "Allow notifications", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(intent);
            }
        });
        root.addView(notificationsButton);

        locationButton = Ui.button(this, "Grant location (needed to read the SSID)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                }, REQUEST_LOCATION);
            }
        });
        root.addView(locationButton);

        // Android 11+ sends this request to the app's settings page, pick "Allow all the time"
        backgroundButton = Ui.button(this, "Allow location all the time", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND);
            }
        });
        root.addView(backgroundButton);

        locationServicesButton = Ui.button(this, "Turn Location on", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            }
        });
        root.addView(locationServicesButton);

        root.addView(Ui.button(this, "Developer options", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            }
        }));

        root.addView(Ui.button(this, "App info", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            }
        }));

        addPermissionHelp(root);
        return scroll;
    }

    /**
     * Step by step instructions for every permission, always shown at the bottom.
     */
    private void addPermissionHelp(LinearLayout root) {
        String pkg = getPackageName();

        root.addView(Ui.heading(this, "How to grant permissions"));

        root.addView(Ui.body(this, "1. Secure settings, needed to switch wireless debugging on. "
                + "Android only lets a computer grant it:\n"
                + "  - Turn on Developer options (tap Build number 7 times in About phone) and USB debugging.\n"
                + "  - Xiaomi, Redmi and POCO: also turn on \"USB debugging (Security settings)\".\n"
                + "  - Connect over USB, accept the prompt on the phone and run the first command below."));

        root.addView(Ui.body(this, "2. Location, needed to read the Wi-Fi name (SSID):\n"
                + "  - Tap \"Grant location\" and pick \"While using the app\" with Precise on.\n"
                + "  - Tap \"Allow location all the time\", Android opens the app's location page, "
                + "pick \"Allow all the time\".\n"
                + "  - If Android no longer asks: App info > Permissions > Location > Allow all the time."));

        root.addView(Ui.body(this, "3. Location switched on: the Location tile in quick settings, "
                + "or \"Turn Location on\" above. Without it Android hides the SSID from every app."));

        root.addView(Ui.body(this, "4. Notifications, for the status notification: allow when asked, "
                + "or \"Allow notifications\" above."));

        root.addView(Ui.body(this, "Everything at once from a computer (step 3 still has to be done on the phone):"));

        TextView commands = Ui.mono(this);
        commands.setText("adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "adb shell pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION\n"
                + "adb shell pm grant " + pkg + " android.permission.ACCESS_BACKGROUND_LOCATION\n"
                + "adb shell pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
        root.addView(commands);

        root.addView(Ui.body(this, "Or run make grant in the project folder. Grants survive app updates "
                + "but not an uninstall."));
    }

    private void refreshStatus() {
        SharedPreferences status = Prefs.status(this);
        boolean secure = Prefs.hasSecureSettingsPermission(this);
        boolean fine = Prefs.hasFineLocation(this);
        boolean background = Prefs.hasBackgroundLocation(this);
        boolean locationOn = Prefs.isLocationEnabled(this);
        boolean notifications = Prefs.hasNotifications(this);
        boolean auto = Prefs.isAuto(this);
        List<String> ssids = Prefs.ssids(this);
        String ssid = status.getString(Prefs.ST_SSID, "");
        String endpoint = status.getString(Prefs.ST_ENDPOINT, "");
        Profiles.Profile profile = Profiles.forSsid(this, ssid.isEmpty() ? null : ssid);
        StringBuilder text = new StringBuilder();

        text.append("Automatic      : ").append(auto ? "ON" : "off (enable in Settings)").append('\n');
        text.append("Device ID      : ")
                .append(profile == null ? Prefs.deviceId(this) : profile.effectiveDeviceId(this)).append('\n');
        text.append("Networks       : ").append(ssids.isEmpty() ? "none configured" : join(ssids)).append('\n');
        text.append("Current SSID   : ").append(ssid.isEmpty() ? "-" : ssid)
                .append(profile != null ? "  (match)" : "").append('\n');
        text.append("Profile        : ").append(profile == null ? "-" : profile.name).append('\n');
        text.append("Server         : ")
                .append(profile == null || profile.url.isEmpty() ? "-" : profile.url).append('\n');
        text.append("Wireless debug : ").append(onOff(Prefs.isWirelessDebuggingEnabled(this))).append('\n');
        text.append("Last report    : ").append(status.getString(Prefs.ST_REPORT, "-")).append('\n');
        text.append("Last event     : ").append(status.getString(Prefs.ST_EVENT, "-")).append("\n\n");

        text.append("Checklist\n");
        text.append(check(secure)).append("Secure settings permission (step 1 below)\n");
        text.append(check(Prefs.isUsbDebuggingEnabled(this))).append("USB debugging on\n");
        text.append(check(fine)).append("Location permission\n");
        text.append(check(background)).append("Location allowed all the time\n");
        text.append(check(locationOn)).append("Location switched on\n");
        text.append(check(notifications)).append("Notifications allowed\n");
        text.append(check(!ssids.isEmpty())).append("At least one network\n");
        text.append(check(Profiles.anyUrl(this))).append("Server URL in a profile\n");
        text.append(check(auto)).append("Activate and report automatically");

        statusView.setText(text);

        interfacesView.setText(interfaceTable(endpoint));

        if (endpoint.isEmpty()) {
            endpointView.setText("adb connect: not active");
            endpointView.setAlpha(0.6f);
            endpointHint.setText(AdbWidget.idleText(this));
        } else {
            endpointView.setText("adb connect " + endpoint);
            endpointView.setAlpha(1f);
            endpointHint.setText(ssid.isEmpty() ? "Tap to copy ip:port" : "On " + ssid + ", tap to copy ip:port");
        }
        notificationsButton.setVisibility(notifications ? View.GONE : View.VISIBLE);
        locationButton.setVisibility(fine ? View.GONE : View.VISIBLE);
        backgroundButton.setVisibility(fine && !background ? View.VISIBLE : View.GONE);
        locationServicesButton.setVisibility(locationOn ? View.GONE : View.VISIBLE);
        reportButton.setVisibility(auto ? View.VISIBLE : View.GONE);
    }

    /**
     * One row per address: label, interface, ip:port and a mark on the reported one.
     */
    private String interfaceTable(String endpoint) {
        String port = Interfaces.portOf(endpoint);
        List<Interfaces.Entry> entries = Interfaces.list(this);
        StringBuilder text = new StringBuilder();

        if (entries.isEmpty()) {
            return "No network interfaces with an address";
        }

        for (Interfaces.Entry entry : entries) {
            String address = entry.endpoint(port);
            boolean reported = !endpoint.isEmpty() && address.equals(endpoint);

            if (text.length() > 0) {
                text.append('\n');
            }

            text.append(pad(entry.label, 14)).append(pad(entry.name, 8)).append(address);

            if (reported) {
                text.append("  <- reported");
            }
        }

        if (port.isEmpty()) {
            text.append("\n\nNo adb port yet, addresses only");
        } else {
            text.append("\n\nThe port answers on every interface, only Wi-Fi is sent to the server");
        }

        return text.toString();
    }

    private static String pad(String value, int width) {
        StringBuilder out = new StringBuilder(value);

        while (out.length() < width) {
            out.append(' ');
        }

        return out.append(' ').toString();
    }

    private static String check(boolean ok) {
        return ok ? "[x] " : "[ ] ";
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();

        for (String value : values) {

            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }

        return out.toString();
    }
}
