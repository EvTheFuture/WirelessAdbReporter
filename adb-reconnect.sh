#!/bin/sh
# Reconnect ADB using connect info from the reporter API.
# Usage: adb-reconnect.sh [api_endpoint]
# If .<scriptname> exists next to this script, that endpoint is used.
# Otherwise the endpoint is taken from the argument or asked interactively.
# The config file is written after a successful API fetch, even if a device
# is already connected. adb connect is skipped when a device is present.

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SCRIPT_NAME="$(basename -- "$0")"
CONFIG_FILE="${SCRIPT_DIR}/.${SCRIPT_NAME}"
API_URL=""
SAVE_CONFIG=0

# Return 0 if at least one device is in state "device".
has_adb_device()
{
    adb devices | awk '
        NR > 1 && $2 == "device" {
            found = 1
        }
        END {
            exit !found
        }
    '
}

# Persist the endpoint only after a successful API response.
save_config_if_needed()
{
    if [ "$SAVE_CONFIG" -eq 1 ]
    then
        printf '%s\n' "$API_URL" > "$CONFIG_FILE"
        chmod 600 "$CONFIG_FILE"
        echo "Saved endpoint to $CONFIG_FILE"
    fi
}

if [ -f "$CONFIG_FILE" ]
then
    API_URL="$(sed -n '/^[[:space:]]*#/d; /^[[:space:]]*$/d; s/[[:space:]]*$//; p; q' "$CONFIG_FILE")"
fi

if [ -z "$API_URL" ]
then
    if [ -n "$1" ]
    then
        API_URL="$1"
    else
        printf "API endpoint: "
        IFS= read -r API_URL
    fi

    SAVE_CONFIG=1
fi

if [ -z "$API_URL" ]
then
    echo "No API endpoint given" >&2
    exit 1
fi

echo "Fetching connect info from ${API_URL}"

JSON="$(wget -qO- "$API_URL")"
if [ -z "$JSON" ]
then
    echo "Failed to fetch $API_URL" >&2
    exit 1
fi

# Extract "connect": "host:port" from the JSON body.
CONNECT="$(printf '%s\n' "$JSON" | sed -n 's/.*"connect"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [ -z "$CONNECT" ]
then
    echo "No connect field in API response" >&2
    printf '%s\n' "$JSON" >&2
    exit 1
fi

if has_adb_device
then
    echo "ADB device already connected"
    adb devices
    save_config_if_needed
    exit 0
fi

echo "Connecting to $CONNECT"
adb connect "$CONNECT"

if has_adb_device
then
    echo "ADB connect succeeded"
    adb devices
    save_config_if_needed
    exit 0
fi

echo "ADB connect failed" >&2
adb devices >&2
exit 1
