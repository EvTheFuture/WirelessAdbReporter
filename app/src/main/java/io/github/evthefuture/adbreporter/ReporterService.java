package io.github.evthefuture.adbreporter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TransportInfo;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground service, runs while "Activate and report automatically" is on.
 *
 * When the phone is on one of the configured SSIDs it turns wireless debugging on,
 * discovers the random TLS port adbd advertises over mDNS and reports ip:port to the
 * configured REST endpoint. On any other network it does nothing.
 *
 * All state is owned by a single worker thread, every callback hops onto it.
 */
public class ReporterService extends Service {

    static final String ACTION_RELOAD = "io.github.evthefuture.adbreporter.RELOAD";
    static final String ACTION_REPORT = "io.github.evthefuture.adbreporter.REPORT";
    static final String ACTION_STOP = "io.github.evthefuture.adbreporter.STOP";

    // Read by sync() on the main thread to decide whether a stop request is needed
    static volatile boolean running;

    private static final String TAG = "AdbReporter";

    // Service type adbd registers once wireless debugging is on
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private static final String CHANNEL_ID = "reporter";
    private static final int NOTIFICATION_ID = 1;

    private static final long HEARTBEAT_MS = 5 * 60 * 1000L;
    private static final long RETRY_MS = 30 * 1000L;
    private static final long REENABLE_MIN_INTERVAL_MS = 60 * 1000L;
    private static final long DISCOVERY_DELAY_MS = 2000L;
    private static final long RESOLVE_TIMEOUT_MS = 15 * 1000L;
    private static final int PROBE_TIMEOUT_MS = 1500;
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final long REFUSAL_WINDOW_MS = 15 * 1000L;
    private static final long APPROVAL_WINDOW_MS = 5 * 60 * 1000L;
    private static final int MAX_REJECTS = 3;
    private static final int MAX_ALREADY_ACTIVE_RETRIES = 10;

    private HandlerThread workerThread;
    private Handler worker;
    private NsdManager nsd;
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private NotificationManager notifications;

    private WifiCallback wifiCallback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private boolean replaying;
    private ContentObserver adbWifiObserver;
    private BroadcastReceiver tetherReceiver;
    private SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private int discoveryGeneration;

    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private int resolveId;
    private int alreadyActiveRetries;

    // Every Wi-Fi network currently up, in the order they appeared
    private final LinkedHashMap<Network, String> wifiSsids = new LinkedHashMap<>();
    private final HashMap<Network, String> wifiAddresses = new HashMap<>();

    private Network wifiNetwork;
    private String wifiIpv4;
    private String currentSsid;
    private boolean evaluatedOnce;
    private boolean onAllowedNetwork;
    private boolean enabledByUs;
    private int rejectCount;

    private String currentIp;
    private int currentPort;
    private String currentMdnsName;
    private String lastReportedEndpoint;
    private long lastEnableAttempt;

    // Touched from both the main and the worker thread
    private volatile String notificationText = "Starting";
    private volatile boolean destroyed;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            onHeartbeat();
            worker.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable restartDiscovery = new Runnable() {
        @Override
        public void run() {

            if (onAllowedNetwork) {
                startDiscovery();
            }
        }
    };

    private final Runnable retryReport = new Runnable() {
        @Override
        public void run() {

            if (currentPort > 0 && onAllowedNetwork) {
                report("retry");
            }
        }
    };

    private final Runnable ensureEnabledLater = new Runnable() {
        @Override
        public void run() {
            ensureEnabled(false);
        }
    };

    private final Runnable resolveTimeout = new Runnable() {
        @Override
        public void run() {

            // Some releases never call back, give up on this resolve and move on
            if (resolving) {
                resolving = false;
                resolveId++;
                resolveNext();
            }
        }
    };

    private final Runnable widgetRefresh = new Runnable() {
        @Override
        public void run() {
            AdbWidget.updateAll(ReporterService.this);
        }
    };

    private final Runnable wifiFallback = new Runnable() {
        @Override
        public void run() {
            replaying = false;
            recompute();

            // Same network as before, still re-check permissions and USB debugging granted meanwhile
            evaluate(false);
        }
    };

    /**
     * Starts or stops the service to match the automatic mode switch.
     *
     * Stopping goes through startForegroundService instead of stopService, because
     * stopService while a start is still pending crashes with
     * ForegroundServiceDidNotStartInTimeException.
     */
    static void sync(Context context) {
        Intent intent = new Intent(context, ReporterService.class);

        // Device id and automatic mode are on the widget too
        AdbWidget.updateAll(context);

        if (Prefs.isAuto(context)) {
            context.startForegroundService(intent.setAction(ACTION_RELOAD));
        } else if (running) {
            context.startForegroundService(intent.setAction(ACTION_STOP));
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle on the main thread
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Wireless ADB reporter", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);

        workerThread = new HandlerThread("adb-reporter");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        worker.post(new Runnable() {
            @Override
            public void run() {
                setup();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // A sticky restart from the background may not be allowed to go foreground (Android 12+)
        try {
            goForeground();
        } catch (IllegalStateException e) {
            Log.w(TAG, "not allowed to start in the foreground", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Covers ACTION_STOP and a switch turned off while a start was in flight
        if (!Prefs.isAuto(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_REPORT.equals(action)) {
            worker.post(new Runnable() {
                @Override
                public void run() {
                    reportNow();
                }
            });
        } else if (ACTION_RELOAD.equals(action)) {
            worker.post(new Runnable() {
                @Override
                public void run() {
                    registerWifiCallback();
                }
            });
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        destroyed = true;
        worker.removeCallbacksAndMessages(null);
        worker.post(new Runnable() {
            @Override
            public void run() {
                teardown();
            }
        });
        workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Setup and teardown on the worker thread
    // ------------------------------------------------------------------

    private void setup() {
        status(Prefs.ST_RUNNING, "1");
        event("Service started");

        // Watch the wireless debugging toggle itself
        Uri uri = Settings.Global.getUriFor(Prefs.ADB_WIFI_ENABLED);
        adbWifiObserver = new ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange) {
                onAdbWifiChanged();
            }
        };
        getContentResolver().registerContentObserver(uri, false, adbWifiObserver);

        // SSID list, URL, token or device id edited in the settings screen
        settingsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, final String key) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        onSettingChanged(key);
                    }
                });
            }
        };
        Prefs.settings(this).registerOnSharedPreferenceChangeListener(settingsListener);

        // Hotspot and tethering come and go without touching Wi-Fi, the widget lists them
        IntentFilter tethering = new IntentFilter();
        tethering.addAction("android.net.conn.TETHER_STATE_CHANGED");
        tethering.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        tetherReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                // Addresses are assigned a moment after the state change
                worker.removeCallbacks(widgetRefresh);
                worker.postDelayed(widgetRefresh, 1500L);
                worker.postDelayed(widgetRefresh, 5000L);
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(tetherReceiver, tethering, null, worker, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(tetherReceiver, tethering, null, worker);
        }

        registerWifiCallback();
        worker.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    private void teardown() {
        stopDiscovery();

        if (tetherReceiver != null) {
            try {
                unregisterReceiver(tetherReceiver);
            } catch (RuntimeException ignored) {
            }
            tetherReceiver = null;
        }

        unregisterWifiCallback();

        if (adbWifiObserver != null) {
            getContentResolver().unregisterContentObserver(adbWifiObserver);
            adbWifiObserver = null;
        }

        if (settingsListener != null) {
            Prefs.settings(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
            settingsListener = null;
        }

        Prefs.status(this).edit()
                .putString(Prefs.ST_RUNNING, "")
                .putString(Prefs.ST_ENDPOINT, "")
                .putString(Prefs.ST_SSID, "")
                .putString(Prefs.ST_STATE, "")
                .apply();
        event("Service stopped");
        AdbWidget.updateAll(this);

        // A late notify from this thread may have re-posted it, a foreground notification is not affected
        notifications.cancel(NOTIFICATION_ID);
    }

    // ------------------------------------------------------------------
    // Wi-Fi and SSID tracking
    // ------------------------------------------------------------------

    /**
     * Records every Wi-Fi network separately, several can exist at once while switching.
     * Separate class so the API 31 constructor with location flags is only called on API 31+.
     */
    private final class WifiCallback extends ConnectivityManager.NetworkCallback {

        WifiCallback() {
            super();
        }

        WifiCallback(int flags) {
            super(flags);
        }

        @Override
        public void onAvailable(Network network) {

            // onCapabilitiesChanged always follows and carries the SSID, decide there
            if (!wifiSsids.containsKey(network)) {
                wifiSsids.put(network, null);
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            String ssid = readSsid(capabilities);

            // A network's SSID cannot change, keep what we knew if location access drops
            if (ssid == null) {
                ssid = wifiSsids.get(network);
            }

            wifiSsids.put(network, ssid);
            recompute();
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            wifiAddresses.put(network, firstIpv4(properties));

            if (network.equals(wifiNetwork)) {
                wifiIpv4 = wifiAddresses.get(network);
                followWifiAddress();
            }

            AdbWidget.updateAll(ReporterService.this);
        }

        @Override
        public void onLost(Network network) {
            wifiSsids.remove(network);
            wifiAddresses.remove(network);
            recompute();
        }
    }

    /**
     * Re-registering replays the current networks, which also picks up newly granted location access.
     */
    private void registerWifiCallback() {
        unregisterWifiCallback();
        replaying = true;
        wifiSsids.clear();
        wifiAddresses.clear();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        if (Build.VERSION.SDK_INT >= 31) {
            wifiCallback = new WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
        } else {
            wifiCallback = new WifiCallback();
        }

        connectivity.registerNetworkCallback(request, wifiCallback, worker);

        // Picks up the default network moving between two Wi-Fi networks
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                recompute();
            }
        };
        connectivity.registerDefaultNetworkCallback(defaultCallback, worker);

        // Decide once the replay of the current networks is complete
        worker.removeCallbacks(wifiFallback);
        worker.postDelayed(wifiFallback, 1000L);
    }

    private void unregisterWifiCallback() {

        if (wifiCallback == null) {
            return;
        }

        try {
            connectivity.unregisterNetworkCallback(wifiCallback);
            connectivity.unregisterNetworkCallback(defaultCallback);
        } catch (RuntimeException ignored) {
        }
        wifiCallback = null;
        defaultCallback = null;
    }

    /**
     * The Wi-Fi network that counts: the default network if it is Wi-Fi, otherwise the newest one.
     */
    private Network primaryWifi() {
        Network active = connectivity.getActiveNetwork();

        if (active != null && wifiSsids.containsKey(active)) {
            return active;
        }

        Network newest = null;

        for (Network network : wifiSsids.keySet()) {
            newest = network;
        }

        return newest;
    }

    /**
     * Re-derives the current network and SSID and evaluates when either changed.
     */
    private void recompute() {

        if (replaying) {
            return;
        }

        Network primary = primaryWifi();
        String ssid = primary == null ? null : wifiSsids.get(primary);
        wifiIpv4 = primary == null ? null : wifiAddresses.get(primary);

        boolean changed = !evaluatedOnce
                || !Objects.equals(primary, wifiNetwork)
                || !Objects.equals(ssid, currentSsid);
        wifiNetwork = primary;
        currentSsid = ssid;

        if (!changed) {
            followWifiAddress();
            return;
        }

        evaluatedOnce = true;
        status(Prefs.ST_SSID, ssid == null ? "" : ssid);

        if (primary == null) {
            event("No Wi-Fi");
        } else {
            event("Wi-Fi: " + (ssid == null ? "SSID unknown" : ssid));
        }

        // New network: Android resets wireless debugging and any earlier refusal no longer applies
        rejectCount = 0;
        evaluate(true);
    }

    @SuppressWarnings("deprecation")
    private String readSsid(NetworkCapabilities capabilities) {

        if (Build.VERSION.SDK_INT >= 31) {
            TransportInfo info = capabilities.getTransportInfo();

            if (info instanceof WifiInfo) {
                return Prefs.normalizeSsid(((WifiInfo) info).getSSID());
            }

            return null;
        }

        WifiInfo info = wifi.getConnectionInfo();
        return info == null ? null : Prefs.normalizeSsid(info.getSSID());
    }

    /**
     * Central decision: act only while the current SSID is in the list.
     */
    private void evaluate(boolean force) {
        boolean allowed = wifiNetwork != null && Prefs.isSsidAllowed(this, currentSsid);

        if (!allowed) {
            onAllowedNetwork = false;
            stopDiscovery();
            clearEndpoint(null);

            // Only undo what this app did, never a manual toggle
            if (enabledByUs && Prefs.isWirelessDebuggingEnabled(this)) {
                Prefs.setWirelessDebugging(this, false);
                event("Disabled wireless debugging, not on a configured network");
            }

            enabledByUs = false;
            updateNotification(idleReason());
            return;
        }

        boolean entering = !onAllowedNetwork;
        onAllowedNetwork = true;
        ensureEnabled(force || entering);

        if (entering || discoveryListener == null) {
            scheduleDiscovery();
        }

        if (currentPort == 0 && rejectCount < MAX_REJECTS) {
            updateNotification("On " + currentSsid + ", waiting for wireless debugging");
        }
    }

    private String idleReason() {

        if (wifiNetwork == null) {
            return "Waiting for Wi-Fi";
        }

        if (currentSsid == null) {
            return "SSID unknown, check location permission and Location";
        }

        if (Prefs.ssids(this).isEmpty()) {
            return "No networks configured";
        }

        return currentSsid + " is not a configured network";
    }

    private void onSettingChanged(String key) {

        if (Prefs.SSIDS.equals(key)) {
            evaluate(false);
            return;
        }

        boolean serverSetting = Prefs.URL.equals(key) || Prefs.TOKEN.equals(key) || Prefs.DEVICE_ID.equals(key);

        if (serverSetting && onAllowedNetwork && currentPort > 0) {
            report("settings");
        }
    }

    // ------------------------------------------------------------------
    // Wireless debugging toggle
    // ------------------------------------------------------------------

    /**
     * Makes sure wireless debugging is on while on an allowed network. Without force the
     * attempts are rate limited and stop after repeated refusals.
     */
    private void ensureEnabled(boolean force) {

        if (!onAllowedNetwork) {
            return;
        }

        if (!Prefs.hasSecureSettingsPermission(this)) {
            event("WRITE_SECURE_SETTINGS not granted, see app screen");
            updateNotification("Permission missing, open the app");
            return;
        }

        if (!Prefs.isUsbDebuggingEnabled(this)) {
            event("USB debugging (Developer options) is off");
            updateNotification("USB debugging is off");
            return;
        }

        if (Prefs.isWirelessDebuggingEnabled(this)) {
            return;
        }

        if (!force && rejectCount >= MAX_REJECTS) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!force && now - lastEnableAttempt < REENABLE_MIN_INTERVAL_MS) {
            worker.removeCallbacks(ensureEnabledLater);
            worker.postDelayed(ensureEnabledLater, REENABLE_MIN_INTERVAL_MS);
            return;
        }

        lastEnableAttempt = now;

        if (Prefs.setWirelessDebugging(this, true)) {
            enabledByUs = true;
            event("Enabled wireless debugging on " + currentSsid);
            scheduleDiscovery();
        } else {
            event("Failed to enable wireless debugging");
        }
    }

    private void onAdbWifiChanged() {

        if (Prefs.isWirelessDebuggingEnabled(this)) {

            if (onAllowedNetwork) {

                // Approved in the system dialog that followed our attempt
                if (SystemClock.elapsedRealtime() - lastEnableAttempt < APPROVAL_WINDOW_MS) {
                    enabledByUs = true;
                }

                scheduleDiscovery();
            }
            return;
        }

        // Switched back off right after we enabled it: the network is not trusted and the dialog was declined
        boolean refused = enabledByUs && SystemClock.elapsedRealtime() - lastEnableAttempt < REFUSAL_WINDOW_MS;
        enabledByUs = false;
        clearEndpoint("Wireless debugging turned off");

        if (!onAllowedNetwork) {
            return;
        }

        if (refused) {
            rejectCount++;
        }

        if (rejectCount >= MAX_REJECTS) {
            event("Wireless debugging refused " + rejectCount + " times, paused until the network changes or Report now");
            updateNotification("Paused: allow wireless debugging on " + currentSsid);
            return;
        }

        worker.removeCallbacks(ensureEnabledLater);
        worker.postDelayed(ensureEnabledLater, 3000L);
    }

    // ------------------------------------------------------------------
    // Periodic and manual actions
    // ------------------------------------------------------------------

    private void onHeartbeat() {
        AdbWidget.updateAll(this);

        if (!onAllowedNetwork) {
            return;
        }

        ensureEnabled(false);

        if (currentPort > 0 && probe(currentIp, currentPort)) {
            report("heartbeat");
            return;
        }

        if (currentPort > 0) {
            clearEndpoint("Endpoint stopped answering");
        }

        startDiscovery();
    }

    private void reportNow() {

        if (!onAllowedNetwork) {
            event("Not reporting: " + idleReason());
            return;
        }

        // An explicit request also lifts the pause after repeated refusals
        rejectCount = 0;
        ensureEnabled(true);

        if (currentPort > 0 && probe(currentIp, currentPort)) {
            report("manual");
            return;
        }

        event("No endpoint yet, searching");
        startDiscovery();
    }

    // ------------------------------------------------------------------
    // mDNS discovery of the adb TLS port
    // ------------------------------------------------------------------

    private void scheduleDiscovery() {
        worker.removeCallbacks(restartDiscovery);
        worker.postDelayed(restartDiscovery, DISCOVERY_DELAY_MS);
    }

    private void startDiscovery() {
        stopDiscovery();
        alreadyActiveRetries = 0;
        final int generation = ++discoveryGeneration;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, final int errorCode) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {

                        if (generation == discoveryGeneration) {
                            discoveryListener = null;
                        }
                        event("mDNS discovery failed: " + errorCode);
                    }
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "stop discovery failed " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "discovery stopped");
            }

            @Override
            public void onServiceFound(final NsdServiceInfo info) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {

                        if (generation == discoveryGeneration) {
                            enqueueResolve(info);
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(final NsdServiceInfo info) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {

                        if (info.getServiceName().equals(currentMdnsName)) {
                            clearEndpoint("mDNS service lost");
                        }
                    }
                });
            }
        };

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            discoveryListener = null;
            event("mDNS discovery error: " + e.getMessage());
        }
    }

    private void stopDiscovery() {
        worker.removeCallbacks(restartDiscovery);
        resolveQueue.clear();

        if (discoveryListener == null) {
            return;
        }

        try {
            nsd.stopServiceDiscovery(discoveryListener);
        } catch (RuntimeException ignored) {
        }
        discoveryListener = null;
    }

    private void enqueueResolve(NsdServiceInfo info) {
        resolveQueue.add(info);
        resolveNext();
    }

    /**
     * NsdManager can only resolve one service at a time on older releases, so resolve serially.
     * Each resolve gets an id so a late callback after a timeout cannot clear a newer one.
     */
    @SuppressWarnings("deprecation")
    private void resolveNext() {

        if (resolving || resolveQueue.isEmpty()) {
            return;
        }

        final NsdServiceInfo info = resolveQueue.poll();
        final int id = ++resolveId;
        resolving = true;
        worker.removeCallbacks(resolveTimeout);
        worker.postDelayed(resolveTimeout, RESOLVE_TIMEOUT_MS);

        NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(final NsdServiceInfo failed, final int errorCode) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {

                        if (id != resolveId) {
                            return;
                        }

                        finishResolve();

                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                                && alreadyActiveRetries++ < MAX_ALREADY_ACTIVE_RETRIES) {
                            resolveQueue.addFirst(info);
                            worker.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    resolveNext();
                                }
                            }, 1000L);
                            return;
                        }

                        Log.w(TAG, "resolve failed " + errorCode + " for " + failed.getServiceName());
                        resolveNext();
                    }
                });
            }

            @Override
            public void onServiceResolved(final NsdServiceInfo resolved) {
                worker.post(new Runnable() {
                    @Override
                    public void run() {

                        if (id != resolveId) {
                            return;
                        }

                        finishResolve();
                        alreadyActiveRetries = 0;
                        handleResolved(resolved);
                        resolveNext();
                    }
                });
            }
        };

        try {
            nsd.resolveService(info, listener);
        } catch (RuntimeException e) {
            finishResolve();
            Log.w(TAG, "resolve error", e);
            worker.post(new Runnable() {
                @Override
                public void run() {
                    resolveNext();
                }
            });
        }
    }

    private void finishResolve() {
        resolving = false;
        worker.removeCallbacks(resolveTimeout);
    }

    @SuppressWarnings("deprecation")
    private List<InetAddress> resolvedAddresses(NsdServiceInfo info) {

        if (Build.VERSION.SDK_INT >= 34) {
            return info.getHostAddresses();
        }

        List<InetAddress> list = new ArrayList<>();

        if (info.getHost() != null) {
            list.add(info.getHost());
        }

        return list;
    }

    /**
     * Other phones on the LAN advertise the same service type, only accept our own addresses.
     */
    private void handleResolved(NsdServiceInfo info) {

        if (!onAllowedNetwork) {
            return;
        }

        int port = info.getPort();
        Set<InetAddress> local = localAddresses();
        boolean isLocal = false;

        for (InetAddress address : resolvedAddresses(info)) {

            if (local.contains(address)) {
                isLocal = true;
                break;
            }
        }

        if (!isLocal) {
            Log.i(TAG, "ignoring foreign service " + info.getServiceName());
            return;
        }

        // adbd listens on every interface and mDNS may well answer with the hotspot or USB
        // tethering address. Only the Wi-Fi client address is useful to the server
        String ip = wifiIpv4;

        if (ip == null || port <= 0) {
            Log.i(TAG, "no Wi-Fi IPv4 address yet for port " + port);
            return;
        }

        if (!probe(ip, port)) {
            Log.i(TAG, "endpoint " + ip + ":" + port + " not answering, ignoring");
            return;
        }

        setEndpoint(ip, port, info.getServiceName());
    }

    /**
     * Follows a new Wi-Fi address (DHCP renew, roaming) for the port already known.
     */
    private void followWifiAddress() {

        if (!onAllowedNetwork || currentPort <= 0 || wifiIpv4 == null || wifiIpv4.equals(currentIp)) {
            return;
        }

        if (probe(wifiIpv4, currentPort)) {
            setEndpoint(wifiIpv4, currentPort, currentMdnsName);
        } else {
            clearEndpoint("Wi-Fi address changed");
            startDiscovery();
        }
    }

    // ------------------------------------------------------------------
    // Endpoint state and reporting
    // ------------------------------------------------------------------

    private void setEndpoint(String ip, int port, String mdnsName) {
        currentIp = ip;
        currentPort = port;
        currentMdnsName = mdnsName;
        String endpoint = ip + ":" + port;
        status(Prefs.ST_ENDPOINT, endpoint);
        updateNotification("adb connect " + endpoint);

        if (!endpoint.equals(lastReportedEndpoint)) {
            event("Found " + endpoint);
            report("changed");
        }
    }

    /**
     * Forgets the endpoint, reason is logged only when there was one.
     */
    private void clearEndpoint(String reason) {

        if (currentPort > 0 && reason != null) {
            event(reason);
        }

        currentIp = null;
        currentPort = 0;
        currentMdnsName = null;
        status(Prefs.ST_ENDPOINT, "");

        if (onAllowedNetwork && rejectCount < MAX_REJECTS) {
            updateNotification("On " + currentSsid + ", waiting for wireless debugging");
        }
    }

    private void report(String reason) {
        SharedPreferences settings = Prefs.settings(this);
        String url = settings.getString(Prefs.URL, "");
        String token = settings.getString(Prefs.TOKEN, "");

        if (url == null || url.trim().isEmpty()) {
            status(Prefs.ST_REPORT, "No server URL configured");
            return;
        }

        String endpoint = currentIp + ":" + currentPort;
        JSONObject body = new JSONObject();

        try {
            body.put("device_id", Prefs.deviceId(this));
            body.put("name", Prefs.deviceName(this));
            body.put("model", Build.MODEL);
            body.put("manufacturer", Build.MANUFACTURER);
            body.put("serial", serialFromMdnsName(currentMdnsName));
            body.put("mdns_name", currentMdnsName);
            body.put("ssid", currentSsid);
            body.put("ip", currentIp);
            body.put("port", currentPort);
            body.put("connect", endpoint);
            body.put("android", Build.VERSION.RELEASE);
            body.put("sdk", Build.VERSION.SDK_INT);
            body.put("reason", reason);
            body.put("timestamp", System.currentTimeMillis() / 1000L);
        } catch (JSONException e) {
            Log.e(TAG, "json", e);
            return;
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        int code;

        try {
            code = post(url.trim(), token, payload, null);
        } catch (IOException first) {

            // Default route may be cellular when Wi-Fi has no internet, retry bound to Wi-Fi
            if (wifiNetwork == null) {
                failReport(time, first);
                return;
            }

            try {
                code = post(url.trim(), token, payload, wifiNetwork);
            } catch (IOException second) {
                failReport(time, second);
                return;
            }
        }

        if (code >= 200 && code < 300) {
            lastReportedEndpoint = endpoint;
            worker.removeCallbacks(retryReport);
            status(Prefs.ST_REPORT, time + "  HTTP " + code + "  (" + reason + ")");
            return;
        }

        status(Prefs.ST_REPORT, time + "  HTTP " + code + ", will retry");
        scheduleRetry();
    }

    private void failReport(String time, IOException e) {
        status(Prefs.ST_REPORT, time + "  " + e.getClass().getSimpleName() + ": " + e.getMessage());
        scheduleRetry();
    }

    private void scheduleRetry() {
        worker.removeCallbacks(retryReport);
        worker.postDelayed(retryReport, RETRY_MS);
    }

    private static int post(String url, String token, byte[] payload, Network network) throws IOException {
        URL target = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) (network != null
                ? network.openConnection(target)
                : target.openConnection());

        try {
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }

            OutputStream out = connection.getOutputStream();

            try {
                out.write(payload);
            } finally {
                out.close();
            }

            int code = connection.getResponseCode();
            InputStream in = code < 400 ? connection.getInputStream() : connection.getErrorStream();

            // Drain the body so the connection can be reused
            if (in != null) {
                byte[] buffer = new byte[1024];

                while (in.read(buffer) > 0) {
                    continue;
                }
                in.close();
            }

            return code;
        } finally {
            connection.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * adbd names the service adb-SERIAL-RANDOM, strip the prefix and random suffix.
     */
    static String serialFromMdnsName(String name) {

        if (name == null || !name.startsWith("adb-")) {
            return "";
        }

        String rest = name.substring(4);
        int dash = rest.lastIndexOf('-');
        return dash > 0 ? rest.substring(0, dash) : rest;
    }

    private static boolean probe(String ip, int port) {

        if (ip == null || port <= 0) {
            return false;
        }

        Socket socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(ip, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Set<InetAddress> localAddresses() {
        Set<InetAddress> result = new HashSet<>();

        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                result.addAll(Collections.list(nif.getInetAddresses()));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "interfaces", e);
        }

        return result;
    }

    private static String firstIpv4(LinkProperties properties) {

        for (LinkAddress address : properties.getLinkAddresses()) {

            if (address.getAddress() instanceof Inet4Address) {
                return address.getAddress().getHostAddress();
            }
        }

        return null;
    }

    private void event(String text) {
        Log.i(TAG, text);
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        status(Prefs.ST_EVENT, time + "  " + text);
    }

    private void status(String key, String value) {
        Prefs.status(this).edit().putString(key, value).apply();

        // The widget shows these, apply() updates the in-memory copy it reads right away
        if (Prefs.ST_ENDPOINT.equals(key) || Prefs.ST_SSID.equals(key)
                || Prefs.ST_STATE.equals(key) || Prefs.ST_RUNNING.equals(key)) {
            AdbWidget.updateAll(this);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent reportIntent = new Intent(this, ReporterService.class).setAction(ACTION_REPORT);
        PendingIntent report = PendingIntent.getForegroundService(
                this, 1, reportIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb)
                .setContentTitle("Wireless ADB")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Report now", report).build())
                .build();
    }

    private void goForeground() {
        Notification notification = buildNotification(notificationText);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {

        if (text.equals(notificationText)) {
            return;
        }

        notificationText = text;
        status(Prefs.ST_STATE, text);

        if (!destroyed) {
            notifications.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
