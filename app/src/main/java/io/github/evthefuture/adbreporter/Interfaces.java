package io.github.evthefuture.adbreporter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every network interface with an address, labelled the way a person would name it.
 *
 * adbd listens on all interfaces, so the wireless debugging port answers on each of these.
 * Only the Wi-Fi client address is reported to the server; hotspot, USB tethering and the
 * rest are shown for reference.
 */
final class Interfaces {

    static final String WIFI = "Wi-Fi";
    static final String HOTSPOT = "Hotspot";
    static final String USB = "USB tethering";
    static final String BLUETOOTH_TETHER = "Bluetooth tethering";
    static final String ETHERNET = "Ethernet";
    static final String WIFI_DIRECT = "Wi-Fi Direct";
    static final String VPN = "VPN";
    static final String MOBILE = "Mobile";

    // Sort order, most useful for adb first
    private static final String[] ORDER = {
        WIFI, HOTSPOT, USB, ETHERNET, BLUETOOTH_TETHER, WIFI_DIRECT, VPN, MOBILE,
    };

    private static final String TAG = "AdbReporter";

    static final class Entry {
        final String label;
        final String name;
        final String address;

        Entry(String label, String name, String address) {
            this.label = label;
            this.name = name;
            this.address = address;
        }

        boolean isWifi() {
            return WIFI.equals(label);
        }

        /**
         * Reachable from other devices on a local link, unlike mobile data or a VPN.
         */
        boolean isLocal() {
            return !MOBILE.equals(label) && !VPN.equals(label);
        }

        /**
         * ip:port for adb connect, IPv6 in brackets.
         */
        String endpoint(String port) {
            String host = address.indexOf(':') >= 0 ? "[" + address + "]" : address;
            return port == null || port.isEmpty() ? host : host + ":" + port;
        }
    }

    private Interfaces() {
    }

    /**
     * All interfaces that are up, one entry per address. IPv4 where an interface has it,
     * otherwise its global IPv6 addresses (mobile data is often IPv6 only).
     */
    @SuppressWarnings("deprecation")
    static List<Entry> list(Context context) {
        Map<String, String> known = new HashMap<>();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Interfaces Android knows as networks get their transport as label. Tethering and
        // the hotspot are not networks, they are named from the interface name below
        if (connectivity != null) {
            for (Network network : connectivity.getAllNetworks()) {
                LinkProperties link = connectivity.getLinkProperties(network);
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);

                if (link == null || caps == null || link.getInterfaceName() == null) {
                    continue;
                }

                known.put(link.getInterfaceName(), transportLabel(caps));
            }
        }

        List<Entry> result = new ArrayList<>();

        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {

                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }

                String name = nif.getName();
                String label = known.containsKey(name) ? known.get(name) : labelFromName(name);

                if (label == null) {
                    continue;
                }

                addAddresses(result, label, name, Collections.list(nif.getInetAddresses()));
            }
        } catch (Exception e) {
            Log.w(TAG, "interfaces", e);
        }

        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byLabel = rank(a.label) - rank(b.label);
                return byLabel != 0 ? byLabel : a.name.compareTo(b.name);
            }
        });

        return result;
    }

    private static void addAddresses(List<Entry> result, String label, String name, List<InetAddress> addresses) {
        boolean hasIpv4 = false;

        for (InetAddress address : addresses) {

            if (address instanceof Inet4Address) {
                hasIpv4 = true;
                result.add(new Entry(label, name, address.getHostAddress()));
            }
        }

        if (hasIpv4) {
            return;
        }

        for (InetAddress address : addresses) {

            if (address instanceof Inet6Address && !address.isLinkLocalAddress()) {
                String text = address.getHostAddress();
                int scope = text.indexOf('%');
                result.add(new Entry(label, name, scope >= 0 ? text.substring(0, scope) : text));
            }
        }
    }

    private static String transportLabel(NetworkCapabilities caps) {

        // A VPN also lists the transport it runs over, so it is checked first
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return VPN;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WIFI;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return MOBILE;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return ETHERNET;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return BLUETOOTH_TETHER;
        }

        return "Other";
    }

    /**
     * Names for interfaces that are not Android networks. Returns null for internal
     * interfaces that never carry user traffic.
     */
    static String labelFromName(String name) {

        if (name.startsWith("dummy") || name.startsWith("ifb") || name.startsWith("sit")
                || name.startsWith("ip6tnl") || name.startsWith("ip_vti") || name.startsWith("gre")) {
            return null;
        }

        if (name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")) {
            return USB;
        }

        if (name.startsWith("bt-pan")) {
            return BLUETOOTH_TETHER;
        }

        if (name.startsWith("p2p")) {
            return WIFI_DIRECT;
        }

        // A wlan interface that is not the Wi-Fi client network is the hotspot
        if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
                || name.startsWith("wlan") || name.startsWith("wigig")) {
            return HOTSPOT;
        }

        if (name.startsWith("eth")) {
            return ETHERNET;
        }

        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-")
                || name.startsWith("seth") || name.startsWith("pdp")) {
            return MOBILE;
        }

        if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec")) {
            return VPN;
        }

        return name;
    }

    private static int rank(String label) {

        for (int i = 0; i < ORDER.length; i++) {

            if (ORDER[i].equals(label)) {
                return i;
            }
        }

        return ORDER.length;
    }

    /**
     * The port part of a stored ip:port endpoint, empty when there is none.
     */
    static String portOf(String endpoint) {
        int colon = endpoint == null ? -1 : endpoint.lastIndexOf(':');
        return colon < 0 ? "" : endpoint.substring(colon + 1);
    }
}
