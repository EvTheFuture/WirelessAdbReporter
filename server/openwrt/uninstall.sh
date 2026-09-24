#!/bin/sh
#
# Removes the Wireless ADB Reporter receiver from OpenWrt and leaves the router
# as it was before the install. On the router:
#
#   /usr/share/adb-reporter/uninstall.sh
#
# or from the workstation, which also works when the copy on the router is gone:
#
#   make openwrt-uninstall ROUTER=root@192.168.1.1
#
# What goes:
#   - every file, symlink and directory listed in /usr/share/adb-reporter/manifest,
#     directories only when empty, so nothing that was added to them later is lost
#   - the reports in /tmp/adb-reporter
#   - leftovers of the first installer version (a uhttpd section in /etc/config/uhttpd
#     and /www-adb-reporter). /etc/config/uhttpd is put back byte for byte from /rom
#     when its settings match the factory file, comments included
#
# Nothing else is touched: no packages, no firewall, no other configuration and no
# pending uci changes. --legacy only does the leftover cleanup, install.sh uses it.
#
# IPKG_INSTROOT, the OpenWrt convention, works on another root for testing.

R="${IPKG_INSTROOT:-}"
MODE="${1:-all}"

NAME=adb-reporter
BASE=/usr/share/adb-reporter
MANIFEST="$BASE/manifest"
CONFIG=/etc/config/adb_reporter
INIT=/etc/init.d/adb-reporter
KEEP=/lib/upgrade/keep.d/adb-reporter
STATE_DIR=/tmp/adb-reporter

# Marker every file installed by this project carries
MARKER="Wireless ADB Reporter"

# Paths that must be gone afterwards, checked at the end
KNOWN="$INIT $KEEP $BASE /etc/rc.d/S95$NAME /etc/rc.d/K10$NAME $CONFIG /www-adb-reporter"

FAILED=0
LEGACY_FOUND=0

say() {
    echo "adb-reporter: $*"
}

# ----------------------------------------------------------------------
# First installer version
# ----------------------------------------------------------------------

# uci export of a config, read without any pending changes
export_config() {
    local dir="$1" empty

    empty="$(mktemp -d)"
    uci -q -c "$dir" -t "$empty" -p "$empty" export uhttpd 2>/dev/null
    rm -rf "$empty"
}

legacy_cleanup() {
    local save

    if uci -q -c "$R/etc/config" get uhttpd.adb_reporter >/dev/null 2>&1; then
        LEGACY_FOUND=1

        # A private save dir: pending uci changes you made yourself stay pending
        save="$(mktemp -d)"
        uci -c "$R/etc/config" -t "$save" delete uhttpd.adb_reporter
        uci -c "$R/etc/config" -t "$save" commit uhttpd
        rm -rf "$save"
        say "removed the uhttpd section adb_reporter of the old installer"

        # uci commit dropped the comments. With the same settings as the factory
        # file, the factory file itself is the exact original
        if [ -f "$R/rom/etc/config/uhttpd" ] \
            && [ "$(export_config "$R/rom/etc/config")" = "$(export_config "$R/etc/config")" ]; then
            cp -p "$R/rom/etc/config/uhttpd" "$R/etc/config/uhttpd"
            say "restored /etc/config/uhttpd from /rom, comments included"
        else
            say "/etc/config/uhttpd has your own changes, left as is (settings restored, comments not)"
        fi

        if [ -z "$R" ]; then
            /etc/init.d/uhttpd reload
        fi
    fi

    if [ -d "$R/www-adb-reporter" ]; then
        LEGACY_FOUND=1
        rm -rf "$R/www-adb-reporter"
        say "removed /www-adb-reporter"
    fi
}

# ----------------------------------------------------------------------
# Current installer
# ----------------------------------------------------------------------

remove_path() {
    local path="$1"

    if [ -L "$R$path" ] || [ -f "$R$path" ]; then
        rm -f "$R$path" && say "removed $path"
    fi
}

remove_from_manifest() {
    local list kind path

    list="$(mktemp)"
    cp "$R$MANIFEST" "$list"

    # Files and links first, the manifest itself is one of them
    while read -r kind path; do

        case "$kind" in
            f|l) remove_path "$path" ;;
        esac
    done < "$list"

    # Directories deepest first, and only when nothing else was put in them
    grep '^d ' "$list" | cut -c3- | sort -r | while read -r path; do

        if [ -d "$R$path" ]; then

            if rmdir "$R$path" 2>/dev/null; then
                say "removed $path/"
            else
                say "kept $path/, it holds files that are not ours"
            fi
        fi
    done

    rm -f "$list"
}

# Without a manifest only paths that carry our marker are removed
remove_marked() {
    local path

    for path in "$INIT" "$KEEP" "$CONFIG" "$BASE/uninstall.sh" "$BASE/www/api/adb"; do

        if [ -f "$R$path" ] && grep -q "$MARKER" "$R$path" 2>/dev/null; then
            remove_path "$path"
        fi

        # Temporary copy of an install that was cut off
        if [ -f "$R$path.new" ] && grep -q "$MARKER" "$R$path.new" 2>/dev/null; then
            remove_path "$path.new"
        fi
    done

    for path in "/etc/rc.d/S95$NAME" "/etc/rc.d/K10$NAME"; do

        if [ -L "$R$path" ] && [ "$(readlink "$R$path")" = "../init.d/$NAME" ]; then
            remove_path "$path"
        fi
    done

    for path in "$BASE/www/api" "$BASE/www" "$BASE"; do
        rmdir "$R$path" 2>/dev/null && say "removed $path/"
    done
}

uninstall() {
    local had_manifest=0

    # procd drops the instance on stop, the init script has to exist for that
    if [ -z "$R" ] && [ -x "$INIT" ]; then
        "$INIT" stop 2>/dev/null
    fi

    if [ -f "$R$MANIFEST" ]; then
        had_manifest=1
        remove_from_manifest
    elif [ -e "$R$INIT" ] || [ -e "$R$BASE" ]; then
        say "no manifest, removing marked files only"
    fi

    # Also after the manifest: catches what an interrupted install had not recorded
    remove_marked

    if [ -d "$R$STATE_DIR" ]; then
        rm -rf "$R$STATE_DIR"
        say "removed $STATE_DIR (reports)"
    fi

    legacy_cleanup

    # The old installer created /etc/config/adb_reporter without a marker
    if [ "$had_manifest" = 0 ] && [ "$LEGACY_FOUND" = 1 ]; then
        remove_path "$CONFIG"
    fi
}

verify() {
    local path

    for path in $KNOWN; do

        if [ -e "$R$path" ] || [ -L "$R$path" ]; then
            say "still present: $path"
            FAILED=1
        fi
    done

    if [ -z "$R" ] && pgrep -f "uhttpd.*$BASE" >/dev/null 2>&1; then
        say "a uhttpd for $BASE is still running"
        FAILED=1
    fi

    if [ "$FAILED" = 0 ]; then
        say "removed completely, nothing else was changed"
    fi
}

case "$MODE" in
    --legacy)
        legacy_cleanup
        ;;
    all)
        uninstall
        verify
        exit "$FAILED"
        ;;
    *)
        echo "usage: $0 [--legacy]" >&2
        exit 2
        ;;
esac
