# Wireless ADB Reporter

Android app (Android 11+). When **Activate and report automatically** is on and the phone
is connected to one of the configured SSIDs, it switches **Wireless debugging** on, finds
the random TLS port adbd listens on, and POSTs `ip:port` to a REST endpoint whenever it
changes (plus a heartbeat every 5 minutes). On any other network it does nothing, and if
it was the one that turned wireless debugging on, it turns it off again.

A small receiver stores the reports, answers "what is the ip and port of device X", and can
run `adb connect` for you.

How it works:

- Toggles `Settings.Global.adb_wifi_enabled` (needs `WRITE_SECURE_SETTINGS`, granted once over adb).
- Reads the SSID from the Wi-Fi network callback. Android only reveals SSIDs to apps with
  location access, so it needs location permission "Allow all the time" and Location switched on.
- Discovers its own `_adb-tls-connect._tcp` mDNS service with `NsdManager`, ignores other phones
  on the LAN, and verifies the port with a TCP probe.
- Foreground service while automatic mode is on. Starts again after reboot and app updates.
- If the network is not trusted for wireless debugging, Android shows an allow dialog. After three
  refusals the app pauses until the network changes or you press **Report now**.
- No AndroidX, no dependencies, ~40 KB APK.

## Phone setup (once per phone)

1. Developer options: enable **USB debugging**. Optionally enable
   **Disable adb authorization timeout** so the pairing never expires.
2. Pair with your computer once: Developer options > Wireless debugging >
   *Pair device with pairing code*, then on the host `adb pair <ip>:<pairport> <code>`.
   Tick **Always allow on this network** when asked. Pairing survives port changes.
3. Install the APK and grant the permissions, see [Permissions](#permissions):

   ```sh
   adb install -r wireless-adb-reporter-1.3.apk
   make grant
   ```

4. Configure under **Settings** in the app, or in one command from the host:

   ```sh
   adb shell am start -n io.github.evthefuture.adbreporter/.ProvisionActivity \
       --es url http://192.168.1.10:8765/api/adb --es token change-me \
       --es device_id pixel8 --es ssids "HomeNet,LabNet" --ez auto true
   ```

   Every extra is optional, `ssids` replaces the whole list. The provisioning screen only accepts
   callers holding `WRITE_SECURE_SETTINGS` (the adb shell), so other apps cannot reconfigure it.

## Permissions

| Permission | Why | How |
| --- | --- | --- |
| `WRITE_SECURE_SETTINGS` | Switch wireless debugging on | Computer only, `adb shell pm grant` |
| `ACCESS_FINE_LOCATION` | Android hides the SSID without it | App button or adb |
| `ACCESS_BACKGROUND_LOCATION` | Read the SSID while the app is in the background | App button, then pick *Allow all the time*, or adb |
| `POST_NOTIFICATIONS` (Android 13+) | Status notification with **Report now** | Prompt on first start, app button, or adb |
| Location switched on | Android hides the SSID from every app when it is off | Quick settings tile, cannot be granted |

Everything at once from a computer (USB, or an existing wireless connection):

```sh
#!/bin/sh
PKG=io.github.evthefuture.adbreporter
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

`make grant` runs the same. Background location has to come after fine location.

Without a computer, on the phone:

1. Open the app, tap **Grant location** and pick *While using the app* with *Precise* on.
2. Tap **Allow location all the time**. Android opens the app's location page, pick
   *Allow all the time*. If Android stops asking after a refusal: App info > Permissions >
   Location > Allow all the time.
3. Tap **Allow notifications** if the prompt was dismissed.
4. Switch Location on in quick settings.

`WRITE_SECURE_SETTINGS` has no on-phone path. It needs USB debugging, and on Xiaomi, Redmi and
POCO also *USB debugging (Security settings)*, otherwise `pm grant` fails with a security
exception. Grants survive app updates signed with the same key, not an uninstall.

The main screen shows the same instructions at the bottom, a checklist of what is still
missing, and one button per missing permission.

## App screens

- **Main:** status (automatic mode, device ID, networks, current SSID, endpoint, last report,
  last event) and a checklist of what is still missing, with buttons to fix each item.
- **Settings:**
  - *Activate and report automatically* switch
  - Networks (SSID): add by name, **Add current network**, remove
  - Server URL, bearer token, Device ID (defaults to a slug of the phone's name)

The notification has a **Report now** action.

## Receiver

Two receivers with the same API, pick one:

- `server/adb-reporter-server.py`, Python 3 standard library only, for a workstation or server.
  It can also run `adb connect` itself.
- `server/openwrt/adb`, a shell CGI script for OpenWrt's uhttpd, see [OpenWrt](#openwrt).

```sh
./server/adb-reporter-server.py --token change-me --auto-connect
```

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/adb` | Store a report. With `--auto-connect`: `adb disconnect` old, `adb connect` new endpoint |
| GET | `/api/adb` | All devices as JSON |
| GET | `/api/adb/<device_id>` | One device as JSON: `ip`, `port`, `connect`, `age_seconds`, `stale`, ... |
| GET | `/api/adb/<device_id>/connect` | Plain `ip:port`, for scripts |

Lookups match `device_id` first, then the adb serial, then the name (case insensitive).
`stale` is true when there has been no report for `--stale-after` seconds (default 900).

```sh
curl -s -H 'Authorization: Bearer change-me' http://gurkdaily:8765/api/adb/pixel8
adb connect "$(curl -s -H 'Authorization: Bearer change-me' http://gurkdaily:8765/api/adb/pixel8/connect)"
```

Useful flags: `--reconnect-on-heartbeat` (also run `adb connect` on every heartbeat, heals dropped
connections), `--open-get` (GET without token), `--hook CMD` (runs after each connect with
`ADB_DEVICE_ID`, `ADB_NAME`, `ADB_IP`, `ADB_PORT`, `ADB_CONNECT`, `ADB_PREVIOUS`, `ADB_SERIAL`
in the environment), `--state ''` to disable the state file.

Run it as the user that owns `~/.android/adbkey`, otherwise adb uses a different key and the
phone rejects it. `server/adb-reporter.initd` + `adb-reporter.confd` are an OpenRC service
for Alpine.

## OpenWrt

The receiver fits on any OpenWrt router without extra packages: it is one busybox ash CGI
script that uses `jsonfilter`, `jshn` and `uci`, which every image ships. It runs in its own
uhttpd instance on port 8765, plain HTTP, so LuCI's HTTPS redirect on port 80 and 443 never
gets in the way of the phones. The URLs are the same as the Python receiver's.

```sh
#!/bin/sh
make openwrt-install ROUTER=root@192.168.1.1 TOKEN=change-me
make openwrt-uninstall ROUTER=root@192.168.1.1
```

What the install does, if you rather do it by hand:

```sh
#!/bin/sh
ssh root@192.168.1.1 'mkdir -p /www-adb-reporter/api && cat > /www-adb-reporter/api/adb \
    && chmod 755 /www-adb-reporter/api/adb' < server/openwrt/adb
ssh root@192.168.1.1 "sh -s -- change-me 8765" < server/openwrt/setup.sh
```

`setup.sh` creates `/etc/config/adb_reporter` (existing values are kept) and a uhttpd section
`adb_reporter` listening on port 8765 with `/www-adb-reporter` as document root and `/api` as
CGI prefix, then reloads uhttpd. Only uhttpd is needed, it is there on every image with LuCI,
otherwise `apk add uhttpd` (25.x) or `opkg install uhttpd`.

Point the phones at `http://<router>:8765/api/adb`, then from any machine on the LAN:

```sh
#!/bin/sh
adb connect "$(curl -s -H 'Authorization: Bearer change-me' http://192.168.1.1:8765/api/adb/pixel8/connect)"
```

Settings in `/etc/config/adb_reporter`, read on every request so no restart is needed:

| Option | Default | Meaning |
| --- | --- | --- |
| `token` | empty | Bearer token, empty accepts anyone on the LAN |
| `state_dir` | `/tmp/adb-reporter` | One JSON file per device. RAM, so no flash wear; the 5 minute heartbeat refills it after a reboot |
| `stale_after` | `900` | Seconds without a report before `stale` is true |
| `open_get` | `0` | `1` allows GET without the token |
| `hook` | empty | Shell command run when a device's ip:port changes, with the same `ADB_*` variables as the Python receiver |
| `hook_always` | `0` | `1` runs the hook on every report |

Differences from the Python receiver: the device ID is also the file name, so it may only
contain letters, digits, `.`, `-` and `_` (the app's default ID always does). There is no
built-in `adb connect`, use the hook or run it from the workstation. Changes are logged to
syslog, `logread -e adb-reporter` shows them. The firewall's default LAN input policy
already allows port 8765, WAN stays closed.

## Report payload

```json
{
  "device_id": "pixel8",
  "name": "Pixel 8",
  "model": "Pixel 8",
  "manufacturer": "Google",
  "serial": "3A1B2C...",
  "mdns_name": "adb-3A1B2C...-Xy12Ab",
  "ssid": "HomeNet",
  "ip": "192.168.1.50",
  "port": 37123,
  "connect": "192.168.1.50:37123",
  "android": "15",
  "sdk": 35,
  "reason": "changed",
  "timestamp": 1790143233
}
```

`reason` is `changed`, `heartbeat`, `manual`, `settings` or `retry`. Sent with
`Authorization: Bearer <token>` when a token is set. Any 2xx counts as success, anything else
is retried every 30 s. If the default route fails (e.g. Wi-Fi without internet so Android routes
via mobile data) it retries bound to the Wi-Fi network.

## Building

Needs a JDK 17 to 24 (Gradle 8.14 does not run on JDK 25) and the Android SDK with
platform 36. The Makefile picks the newest JDK in that range, so a JDK 25 next to it is fine.
On Ubuntu:

```sh
#!/bin/sh
sudo apt install -y openjdk-21-jdk \
    google-android-platform-36-installer \
    google-android-build-tools-36.0.0-installer \
    google-android-platform-tools-installer
```

The Makefile does the rest, it finds the JDK, writes `local.properties` and runs Gradle:

```sh
#!/bin/sh
make                  # list the targets
make all              # checks, release APK, dist/ with the APK and wireless-adb-reporter-src.zip
make android          # app/build/outputs/apk/debug/app-debug.apk
make setup URL=http://gurkdaily:8765/api/adb TOKEN=change-me DEVICE_ID=pixel8 SSIDS="HomeNet,LabNet"
make serve TOKEN=change-me
```

`make setup` is `make install grant provision`: install the debug APK, grant the
permissions and configure the app on the connected phone. Each part also runs on its own.

`make direct` (and `make dist-direct`) builds without Gradle, straight from the SDK's own
`aapt2`, `d8`, `zipalign` and `apksigner` through `build.sh`, for machines that cannot reach
Google's Maven repository. Every tool can be overridden by env var (`AAPT2`, `D8`, `DX_JAR`,
`APKSIGNER`, `UBER_SIGNER_JAR`, `ANDROID_JAR`, ...).

The version lives in `VERSION` at the root, Gradle and `build.sh` both read it and turn it
into the version code with two digits per part (1.2 is 1020000). Ubuntu's own `gradle`
package is far too old, the wrapper fetches the Gradle it needs.

Debug and release builds are signed with `keystore/adbreporter.p12` (password
`adbreporter`), created on first build if missing. Keep it, so rebuilt APKs install as
updates and keep the granted permissions.
