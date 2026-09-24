#!/bin/sh
#
# Installs the Wireless ADB Reporter receiver on OpenWrt. Runs on the router
# from a directory holding the files next to it, make openwrt-install does:
#
#   tar -c ... | ssh root@router 'extract to a temp dir && sh install.sh TOKEN PORT'
#
# It only adds files, it never edits one that was there before. Every file,
# symlink and directory it creates is written to /usr/share/adb-reporter/manifest,
# and uninstall.sh removes exactly those, which leaves the router as it was.
#
# Running it again upgrades in place and keeps the settings. An empty TOKEN or
# PORT keeps the current value (8765 and no token on a first install).
#
# IPKG_INSTROOT, the OpenWrt convention, installs into another root for testing.

set -e

R="${IPKG_INSTROOT:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TOKEN="${1:-}"
PORT="${2:-}"

NAME=adb-reporter
BASE=/usr/share/adb-reporter
MANIFEST="$BASE/manifest"
CONFIG=/etc/config/adb_reporter
INIT=/etc/init.d/adb-reporter
KEEP=/lib/upgrade/keep.d/adb-reporter

die() {
    echo "adb-reporter: $*" >&2
    exit 1
}

# ----------------------------------------------------------------------
# Checks, nothing is changed before they all pass
# ----------------------------------------------------------------------

for file in adb adb-reporter.init adb_reporter.config uninstall.sh; do

    if [ ! -f "$HERE/$file" ]; then
        die "$file missing next to install.sh"
    fi
done

if [ ! -x "$R/usr/sbin/uhttpd" ]; then
    die "uhttpd is not installed, add it first: apk add uhttpd (25.x) or opkg install uhttpd"
fi

if [ ! -f "$R/etc/rc.common" ] || [ ! -f "$R/usr/share/libubox/jshn.sh" ]; then
    die "this does not look like OpenWrt (no /etc/rc.common or jshn.sh)"
fi

for tool in jsonfilter uci logger; do

    if ! command -v "$tool" >/dev/null 2>&1; then
        die "$tool is missing, this does not look like OpenWrt"
    fi
done

case "$PORT" in
    '') ;;
    *[!0-9]*) die "port must be a number, got '$PORT'" ;;
    *)
        if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
            die "port must be 1 to 65535, got '$PORT'"
        fi
        ;;
esac

# Written into a quoted uci value, so only characters that need no escaping
case "$TOKEN" in
    *[!A-Za-z0-9._~+/=-]*) die "token may only contain letters, digits and . _ ~ + / = -" ;;
esac

# Marker every file installed by this project carries
MARKER="Wireless ADB Reporter"

# A file at one of our paths that is neither in the manifest nor carries our
# marker belongs to someone else, refuse instead of overwriting it
if [ ! -f "$R$MANIFEST" ]; then

    for path in "$INIT" "$KEEP" "$BASE/uninstall.sh" "$BASE/www/api/adb"; do

        if [ -e "$R$path" ] && ! grep -q "$MARKER" "$R$path" 2>/dev/null; then
            die "$path exists but was not installed by this installer, not touching it"
        fi
    done
fi

# ----------------------------------------------------------------------
# Leftovers of the first installer version, which added a section to
# /etc/config/uhttpd. uninstall.sh knows how to take that back out
# ----------------------------------------------------------------------

sh "$HERE/uninstall.sh" --legacy

# ----------------------------------------------------------------------
# Manifest bookkeeping
# ----------------------------------------------------------------------

# Written as files land, so even an interrupted install is fully removable.
# Entries of an earlier run are kept, the directories it created exist now
record() {
    echo "$1 $2" >> "$R$MANIFEST"
}

# Creates each missing directory of a path and records it, parents first
make_dir() {
    local dir="$1" parent

    if [ -d "$R$dir" ]; then
        return 0
    fi

    parent="${dir%/*}"

    if [ -n "$parent" ]; then
        make_dir "$parent"
    fi

    if [ -f "$R$MANIFEST" ] || [ "$dir" != "$BASE" ]; then
        record d "$dir"
    else
        PENDING_BASE=1
    fi

    mkdir "$R$dir"
}

# Copies a file into place with a mode and records it
put() {
    local source="$1" target="$2" mode="$3"

    make_dir "${target%/*}"
    record f "$target"
    cp "$source" "$R$target.new"
    chmod "$mode" "$R$target.new"
    mv -f "$R$target.new" "$R$target"
}

# ----------------------------------------------------------------------
# Install
# ----------------------------------------------------------------------

# An upgrade stops the running copy first, the port may change
if [ -z "$R" ] && [ -x "$INIT" ]; then
    "$INIT" stop 2>/dev/null || true
fi

# The manifest lives in BASE, so BASE is made first and both are recorded in it
PENDING_BASE=0
make_dir "$BASE"
touch "$R$MANIFEST"
chmod 644 "$R$MANIFEST"

if [ "$PENDING_BASE" = 1 ]; then
    record d "$BASE"
fi

record f "$MANIFEST"

put "$HERE/adb" "$BASE/www/api/adb" 755
put "$HERE/uninstall.sh" "$BASE/uninstall.sh" 755
put "$HERE/adb-reporter.init" "$INIT" 755

# The settings file is created once and afterwards only its token and port change
if [ -f "$R$CONFIG" ]; then

    UCI_SAVE="$(mktemp -d)"

    # A private save dir, so pending uci changes of other configs are never committed
    if [ -n "$TOKEN" ]; then
        uci -c "$R/etc/config" -t "$UCI_SAVE" set "adb_reporter.main.token=$TOKEN"
    fi

    if [ -n "$PORT" ]; then
        uci -c "$R/etc/config" -t "$UCI_SAVE" set "adb_reporter.main.port=$PORT"
    fi

    uci -c "$R/etc/config" -t "$UCI_SAVE" commit adb_reporter
    rm -rf "$UCI_SAVE"
    record f "$CONFIG"
else
    sed -e "s|@PORT@|${PORT:-8765}|" -e "s|@TOKEN@|$TOKEN|" "$HERE/adb_reporter.config" > "$HERE/config.out"
    put "$HERE/config.out" "$CONFIG" 600
    rm -f "$HERE/config.out"
fi

# Kept across sysupgrade. The directory entry keeps everything under it
make_dir "${KEEP%/*}"
printf '%s\n' "# Wireless ADB Reporter, removed by $BASE/uninstall.sh" \
    "$CONFIG" "$INIT" "/etc/rc.d/S95$NAME" "/etc/rc.d/K10$NAME" "$BASE/" > "$R$KEEP"
record f "$KEEP"

# The same two symlinks "/etc/init.d/adb-reporter enable" creates (START 95, STOP 10)
for link in "S95$NAME" "K10$NAME"; do
    record l "/etc/rc.d/$link"
    ln -sf "../init.d/$NAME" "$R/etc/rc.d/$link"
done

# Repeated runs append the same lines again, keep one of each
awk '!seen[$0]++' "$R$MANIFEST" > "$R$MANIFEST.new"
mv -f "$R$MANIFEST.new" "$R$MANIFEST"

if [ -z "$R" ]; then
    "$INIT" start
fi

PORT_NOW="$(uci -q -c "$R/etc/config" get adb_reporter.main.port || echo 8765)"
LAN="$(uci -q -c "$R/etc/config" get network.lan.ipaddr 2>/dev/null || echo router)"

echo "Installed, phones report to http://${LAN%/*}:$PORT_NOW/api/adb"
echo "Remove with: $BASE/uninstall.sh"
