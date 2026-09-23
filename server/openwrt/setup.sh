#!/bin/sh
#
# Runs on the router, piped over ssh by make openwrt-install:
#
#   ssh root@router "sh -s -- TOKEN PORT" < server/openwrt/setup.sh
#
# Expects the CGI script already at /www-adb-reporter/api/adb. Adds a separate
# uhttpd instance on its own port, plain HTTP, so LuCI's HTTPS redirect never
# gets in the way of the phones. Safe to run again, it replaces its own
# settings only.

set -e

TOKEN="$1"
PORT="${2:-8765}"
DOCROOT=/www-adb-reporter

if [ ! -x /usr/sbin/uhttpd ]; then
    echo "uhttpd is missing, install it first: apk add uhttpd (25.x) or opkg install uhttpd"
    exit 1
fi

for tool in jsonfilter uci logger; do

    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is missing, this does not look like OpenWrt"
        exit 1
    fi
done

if [ ! -x "$DOCROOT/api/adb" ]; then
    echo "$DOCROOT/api/adb is missing, copy the CGI script first"
    exit 1
fi

# The receiver's own settings, existing values are kept
touch /etc/config/adb_reporter

if ! uci -q get adb_reporter.main >/dev/null; then
    uci set adb_reporter.main=adb_reporter
    uci set adb_reporter.main.state_dir=/tmp/adb-reporter
    uci set adb_reporter.main.stale_after=900
    uci set adb_reporter.main.open_get=0
    uci set adb_reporter.main.hook_always=0
fi

if [ -n "$TOKEN" ]; then
    uci set adb_reporter.main.token="$TOKEN"
fi

uci commit adb_reporter

# A dedicated uhttpd instance, rebuilt from scratch every run
uci -q delete uhttpd.adb_reporter || true
uci set uhttpd.adb_reporter=uhttpd
uci add_list uhttpd.adb_reporter.listen_http="0.0.0.0:$PORT"
uci add_list uhttpd.adb_reporter.listen_http="[::]:$PORT"
uci set uhttpd.adb_reporter.home="$DOCROOT"
uci set uhttpd.adb_reporter.cgi_prefix=/api
uci set uhttpd.adb_reporter.no_dirlists=1
uci set uhttpd.adb_reporter.max_requests=5
uci set uhttpd.adb_reporter.script_timeout=15
uci set uhttpd.adb_reporter.network_timeout=15
uci commit uhttpd

/etc/init.d/uhttpd reload

echo "Receiver on http://$(uci -q get network.lan.ipaddr || echo router):$PORT/api/adb"
