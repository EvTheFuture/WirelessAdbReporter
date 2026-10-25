# Wireless ADB Reporter

Android app (Android 11+). When **Activate and report automatically** is on and the phone
is connected to one of the configured SSIDs, it switches **Wireless debugging** on, finds
the random TLS port adbd listens on, and POSTs `ip:port` to a REST endpoint whenever it
changes (plus a heartbeat every 5 minutes). Each SSID belongs to a server profile, so home
networks can report to one server and lab networks to another. On any other network it does nothing, and if
it was the one that turned wireless debugging on, it turns it off again.

A small receiver stores the reports, answers "what is the ip and port of device X", and can
run `adb connect` for you.

How it works:

- Toggles `Settings.Global.adb_wifi_enabled` (needs `WRITE_SECURE_SETTINGS`, granted once over adb).
- Reads the SSID from the Wi-Fi network callback. Android only reveals SSIDs to apps with
  location access, so it needs location permission "Allow all the time" and Location switched on.
- Discovers its own `_adb-tls-connect._tcp` mDNS service with `NsdManager`, ignores other phones
  on the LAN, and verifies the port with a TCP probe.
- Reports the Wi-Fi client address only. adbd listens on every interface, so with the hotspot,
  USB tethering or a VPN up the same port answers on several addresses, and mDNS may resolve to
  any of them. The app takes the port from mDNS and the address from the Wi-Fi network, and
  follows the Wi-Fi address when it changes (DHCP renew, roaming).
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
   adb install -r wireless-adb-reporter-1.5.apk
   make grant
   ```

4. Configure under **Settings** in the app, or in one command from the host:

   ```sh
   adb shell am start -n io.github.evthefuture.adbreporter/.ProvisionActivity \
       --es url http://192.168.1.10:8765/api/adb --es token change-me \
       --es device_id pixel8 --es ssids "HomeNet,HomeNet-5G" --ez auto true
   ```

   Every extra is optional. Without `profile` the settings go to the profile `Default`. A second
   server for other networks is one more call with its own profile name:

   ```sh
   adb shell am start -n io.github.evthefuture.adbreporter/.ProvisionActivity \
       --es profile lab --es url http://10.0.0.1/cgi-bin/adb --es token lab-secret \
       --es ssids "LabNet"
   ```

   | Extra | Meaning |
   | --- | --- |
   | `profile` | Profile to create or update, `Default` when left out |
   | `url`, `token` | Server of that profile |
   | `ssids` | Comma separated, replaces the profile's list. The SSIDs are taken away from every other profile |
   | `device_id` | With `profile`: override for that profile only (empty clears it). Without: the global device ID |
   | `remove_profile` | Deletes a profile by name |
   | `auto` | The automatic mode switch |

   `make provision PROFILE=lab URL=... TOKEN=... SSIDS=LabNet` does the same. The provisioning
   screen only accepts callers holding `WRITE_SECURE_SETTINGS` (the adb shell), so other apps
   cannot reconfigure it.

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

- **Main:** the active address in large type, `adb connect 192.168.1.50:37123`, or why there is
  none (not on a configured network, wireless debugging off, automatic mode off). Tap it to copy
  `ip:port`. Under it every interface with its address and the port, the Wi-Fi one marked as
  the reported one:

  ```
  Wi-Fi          wlan0    192.168.1.50:37123  <- reported
  Hotspot        ap0      10.107.64.1:37123
  USB tethering  rndis0   192.168.42.129:37123
  Mobile         rmnet1   [2001:db8::5]:37123
  ```

  Below that: status (automatic mode, device ID, networks, current SSID, profile and server,
  last report, last event) and a checklist of what is still missing, with buttons to fix each item.
- **Settings:**
  - *Activate and report automatically* switch
  - Device ID (defaults to a slug of the phone's name)
  - Server profiles, as many as needed, each with a name, server URL, bearer token, an optional
    device ID override and its networks (SSID): add by name, **Add current network**, remove.
    An SSID belongs to one profile only, adding it to a second one is refused.

  Settings from before profiles existed become the profile `Default` on first start.

  When the phone moves to a network of another profile, the endpoint is reported to that
  profile's server right away. A pending retry always goes to the server of the network the
  phone is on now, never to the one it left.

The notification has a **Report now** action.

## Widget

Long press the home screen > Widgets > Wireless ADB Reporter. A 3x1 card, resizable, also
allowed on the lock screen where the launcher supports it:

- the device ID, tap it to open the app
- the active `ip:port` in large type, tap it to copy, or "not active" with the reason below
- the SSID it is on
- other local interfaces with the same port, e.g. `Hotspot 10.107.64.1:37123`, when the hotspot
  or USB tethering is on (mobile data and VPN addresses are left out, the LAN cannot reach them).
  Make the widget two cells high to see this line
- the circular arrow reports now

The service pushes every change to the widget, including the hotspot and tethering being
switched on or off, so it has no polling of its own and costs no battery.

## Receiver

Two receivers with the same API (the Python one under `/api/adb`, the OpenWrt one under
`/cgi-bin/adb`), pick one:

- `server/adb-reporter-server.py`, Python 3 standard library only, for a workstation or server.
  It can also run `adb connect` itself.
- `server/openwrt/adb`, a shell CGI script any CGI capable web server runs per request, see
  [OpenWrt](#openwrt) and [Any web server](#any-web-server-alpine).

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

The receiver is one CGI script, `server/openwrt/adb`, busybox ash with `jsonfilter`, `jshn` and
`uci`, which every OpenWrt image ships (or `jq` when installed). It is not a daemon: the
router's own uhttpd (the one serving LuCI) starts it for each request, it answers and exits.
Nothing runs in between, nothing extra is installed and no existing file is edited.

### Install

Check first that the web server does not redirect HTTP to HTTPS, the phones post plain HTTP and
Android does not follow a redirect from http to https:

```sh
#!/bin/sh
ssh root@192.168.1.1 uci -q get uhttpd.main.redirect_https   # 0 or empty is fine
```

If it prints `1`, see [HTTPS redirect](#https-redirect) below. Otherwise copy the script, it is
the only file needed. Dropbear has no sftp, so `cat` over ssh instead of scp:

```sh
#!/bin/sh
ssh root@192.168.1.1 'cat > /www/cgi-bin/adb && chmod 755 /www/cgi-bin/adb' < server/openwrt/adb
```

Optionally the settings, for a token (without the file: no token, reports in `/tmp/adb-reporter`):

```sh
#!/bin/sh
ssh root@192.168.1.1 'cat > /etc/config/adb_reporter && chmod 600 /etc/config/adb_reporter' < server/openwrt/adb_reporter.config
ssh root@192.168.1.1 "uci set adb_reporter.main.token=change-me && uci commit adb_reporter"
```

That is all, no restart. The API lives under `/cgi-bin/adb` on the router's normal port 80:

| Method | Path | Result |
| --- | --- | --- |
| POST | `/cgi-bin/adb` | Store a report |
| GET | `/cgi-bin/adb` | All devices as JSON |
| GET | `/cgi-bin/adb/<device_id>` | One device as JSON |
| GET | `/cgi-bin/adb/<device_id>/connect` | Plain `ip:port` |

Server URL in the app: `http://192.168.1.1/cgi-bin/adb`. From any machine on the LAN:

```sh
#!/bin/sh
adb connect "$(curl -s -H 'Authorization: Bearer change-me' http://192.168.1.1/cgi-bin/adb/pixel8/connect)"
```

### Remove

```sh
#!/bin/sh
ssh root@192.168.1.1 'rm -f /www/cgi-bin/adb /etc/config/adb_reporter; rm -rf /tmp/adb-reporter'
```

Those are the only three things it ever creates: the script, the optional settings file, and the
reports in RAM (`/tmp`, gone after a reboot anyway). The router is then exactly as before.

`/www/cgi-bin` and `/etc/config/adb_reporter` are not part of any package, so a sysupgrade drops
the script; `/etc/config` is kept. To keep the script too, add the line `/www/cgi-bin/adb` to
`/etc/sysupgrade.conf` (and remove it again when you uninstall).

### HTTPS redirect

With `redirect_https 1` (set by luci-ssl on many images) a second uhttpd section on its own port
serves plain HTTP next to LuCI. Add to `/etc/config/uhttpd`:

```
config uhttpd 'adb_reporter'
	list listen_http '0.0.0.0:8765'
	option home '/www'
	option cgi_prefix '/cgi-bin'
	option no_dirlists '1'
```

then `/etc/init.d/uhttpd reload`; the URL is `http://192.168.1.1:8765/cgi-bin/adb`. That is a
second uhttpd process, the script itself is still started per request. To remove it, delete the
section again by hand in the editor (`uci delete` + `uci commit` would drop the file's comments).

### Settings

`/etc/config/adb_reporter`, read on every request, so changes apply at once:

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
syslog, `logread -e adb-reporter` shows them. The firewall's default LAN input policy already
allows the web server, WAN stays closed.

## Any web server (Alpine)

The same CGI script runs outside OpenWrt under any web server that speaks CGI. Instead of
`jsonfilter`, `jshn` and `uci` it then needs `jq`, and reads its settings from
`/etc/adb-reporter.conf` (same option names, `name=value` lines, see
`server/adb-reporter.conf`). A web server that can set CGI environment variables can point
`ADB_REPORTER_CONF` at another file.

On Alpine with uhttpd as its own OpenRC instance:

```sh
#!/bin/sh
apk add jq uhttpd uhttpd-openrc

# The script under the CGI prefix, its settings and state, all readable by the uhttpd user
install -D -m 755 server/openwrt/adb /var/www/adb-reporter/api/adb
install -m 640 -g uhttpd server/adb-reporter.conf /etc/adb-reporter.conf
install -d -m 750 -o uhttpd -g uhttpd /var/lib/adb-reporter

# A second uhttpd instance with its own conf.d file
ln -s uhttpd /etc/init.d/uhttpd.adb_reporter
cat > /etc/conf.d/uhttpd.adb_reporter <<'CONF'
supervisor="supervise-daemon"
listen_http="0.0.0.0:8765 [::]:8765"
home="/var/www/adb-reporter"
cgi_prefix="/api"
no_dirlists=yes
rfc1918_filter=no
max_requests=5
script_timeout=15
network_timeout=15
http_keepalive=0
CONF

rc-update add uhttpd.adb_reporter default
rc-service uhttpd.adb_reporter start
```

Server URL in the app: `http://<host>:8765/api/adb`. Set the token in `/etc/adb-reporter.conf`.
busybox `httpd` works the same way with the script in `cgi-bin/` of its home directory. The
OpenWrt tools are used whenever `jq` is missing, and `jq` whenever it is installed.

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
