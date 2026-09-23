#!/usr/bin/env python3
"""
Receiver for Wireless ADB Reporter.

POST /api/adb                      JSON report from the phone, optionally runs adb connect
GET  /api/adb                      all known devices as JSON
GET  /api/adb/<device_id>          ip and port of one device as JSON
GET  /api/adb/<device_id>/connect  plain "ip:port", handy in scripts

Lookups match device_id first, then the adb serial, then the name (case insensitive).

Example:
    adb connect "$(curl -s http://localhost:8765/api/adb/pixel8/connect)"
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

# Guards the device table and the state file
STATE_LOCK = threading.Lock()

# Serialises adb invocations so connect/disconnect never interleave
ADB_LOCK = threading.Lock()

DEVICES = {}
CONFIG = None


def log(message):
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"{stamp} {message}", flush=True)


def load_state(path):

    if not path or not os.path.exists(path):
        return {}

    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError) as err:
        log(f"could not read state file {path}: {err}")
        return {}


def save_state(path):

    if not path:
        return

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = f"{path}.tmp"

    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(DEVICES, handle, indent=2, sort_keys=True)

    os.replace(tmp, path)


def run_adb(*args):
    command = [CONFIG.adb, *args]

    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
        output = (result.stdout + result.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as err:
        output = str(err)

    log(f"{' '.join(command)}: {output}")
    return output


def on_endpoint(device, previous):
    """Runs in a background thread after a report has been stored."""
    endpoint = device["connect"]

    with ADB_LOCK:

        # Drop the stale entry so it does not linger as offline in adb devices
        if previous and previous != endpoint:
            run_adb("disconnect", previous)

        run_adb("connect", endpoint)

    if CONFIG.hook:
        env = dict(os.environ)
        env.update({
            "ADB_DEVICE_ID": device["device_id"],
            "ADB_NAME": device["name"],
            "ADB_IP": device["ip"],
            "ADB_PORT": str(device["port"]),
            "ADB_CONNECT": endpoint,
            "ADB_PREVIOUS": previous or "",
            "ADB_SERIAL": device.get("serial", ""),
        })
        subprocess.run(CONFIG.hook, shell=True, env=env, check=False)


def find_device(key):
    key = key.lower()

    with STATE_LOCK:
        devices = list(DEVICES.values())

    # Most specific field wins, so a device_id never loses to another phone's name
    for field in ("device_id", "serial", "name"):

        for device in devices:

            if str(device.get(field, "")).lower() == key:
                return dict(device)

    return None


def describe(device):
    """Device record plus age and a stale flag (no report for three heartbeats)."""
    result = dict(device)
    result["age_seconds"] = max(0, int(time.time()) - int(device.get("reported_at", 0)))
    result["stale"] = result["age_seconds"] > CONFIG.stale_after
    return result


class Handler(BaseHTTPRequestHandler):
    server_version = "adb-reporter/1.0"

    def log_message(self, fmt, *args):

        if CONFIG.verbose:
            log(f"{self.address_string()} {fmt % args}")

    def reply(self, code, body, content_type="application/json"):

        if isinstance(body, (dict, list)):
            body = json.dumps(body, indent=2, sort_keys=True) + "\n"

        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):

        if not CONFIG.token:
            return True

        return self.headers.get("Authorization", "") == f"Bearer {CONFIG.token}"

    def route(self):
        path = self.path.split("?", 1)[0].rstrip("/")
        base = CONFIG.path.rstrip("/")

        if path == base:
            return ""

        if path.startswith(base + "/"):
            return unquote(path[len(base) + 1:])

        return None

    def do_GET(self):
        sub = self.route()

        if sub is None:
            self.reply(404, {"error": "not found"})
            return

        if not self.authorized() and not CONFIG.open_get:
            self.reply(401, {"error": "unauthorized"})
            return

        if sub == "":

            with STATE_LOCK:
                snapshot = {key: describe(device) for key, device in DEVICES.items()}
            self.reply(200, snapshot)
            return

        # /<device_id> or /<device_id>/connect
        device_id, _, action = sub.partition("/")
        device = find_device(device_id)

        if action not in ("", "connect"):
            self.reply(404, {"error": "not found"})
            return

        if device is None:

            if action == "connect":
                self.reply(404, "unknown device\n", "text/plain")
            else:
                self.reply(404, {"error": "unknown device", "device_id": device_id})
            return

        if action == "connect":
            self.reply(200, device["connect"] + "\n", "text/plain")
            return

        self.reply(200, describe(device))

    def do_POST(self):

        if self.route() != "":
            self.reply(404, {"error": "not found"})
            return

        if not self.authorized():
            log(f"rejected report from {self.client_address[0]}: bad token")
            self.reply(401, {"error": "unauthorized"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            report = json.loads(self.rfile.read(min(length, 65536)).decode("utf-8"))
            ip = str(report["ip"])
            port = int(report["port"])

            if not 0 < port < 65536:
                raise ValueError("port out of range")
        except (KeyError, TypeError, ValueError) as err:
            self.reply(400, {"error": f"bad report: {err}"})
            return

        name = str(report.get("name") or report.get("serial") or ip)
        device_id = str(report.get("device_id") or report.get("serial") or name).strip()
        device = {
            "device_id": device_id,
            "name": name,
            "model": str(report.get("model", "")),
            "serial": str(report.get("serial", "")),
            "ip": ip,
            "port": port,
            "connect": f"{ip}:{port}",
            "ssid": str(report.get("ssid") or ""),
            "android": str(report.get("android", "")),
            "reason": str(report.get("reason", "")),
            "reported_at": int(time.time()),
            "remote_addr": self.client_address[0],
        }

        with STATE_LOCK:
            previous = DEVICES.get(device_id, {}).get("connect")
            DEVICES[device_id] = device

            try:
                save_state(CONFIG.state)
            except OSError as err:
                log(f"could not write state file: {err}")

        changed = previous != device["connect"]

        if changed:
            log(f"{device_id}: {previous or '-'} -> {device['connect']} ({device['reason']})")

        # Heartbeats also reconnect, cheap and heals dropped connections
        if CONFIG.auto_connect and (changed or CONFIG.reconnect_on_heartbeat):
            threading.Thread(target=on_endpoint, args=(device, previous), daemon=True).start()

        self.reply(200, {"ok": True, "changed": changed})


def parse_args():
    default_state = os.path.join(
        os.environ.get("XDG_STATE_HOME", os.path.expanduser("~/.local/state")),
        "adb-reporter",
        "devices.json",
    )

    parser = argparse.ArgumentParser(description="Receiver for Wireless ADB Reporter")
    parser.add_argument("--listen", default="0.0.0.0", help="bind address (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="bind port (default 8765)")
    parser.add_argument("--path", default="/api/adb", help="API path (default /api/adb)")
    parser.add_argument("--token", default=os.environ.get("ADB_REPORTER_TOKEN", ""),
                        help="required bearer token (or ADB_REPORTER_TOKEN)")
    parser.add_argument("--open-get", action="store_true", help="allow GET without the token")
    parser.add_argument("--state", default=default_state, help="state file, empty string disables")
    parser.add_argument("--auto-connect", action="store_true", help="run adb connect on new endpoints")
    parser.add_argument("--reconnect-on-heartbeat", action="store_true",
                        help="with --auto-connect, also run adb connect on every heartbeat")
    parser.add_argument("--adb", default="adb", help="adb binary (default adb from PATH)")
    parser.add_argument("--hook", default="", help="shell command run after each connect, gets ADB_* env vars")
    parser.add_argument("--stale-after", type=int, default=900,
                        help="seconds without a report before a device is flagged stale (default 900)")
    parser.add_argument("-v", "--verbose", action="store_true", help="log every HTTP request")
    return parser.parse_args()


def main():
    global CONFIG
    CONFIG = parse_args()
    DEVICES.update(load_state(CONFIG.state))

    server = ThreadingHTTPServer((CONFIG.listen, CONFIG.port), Handler)
    log(f"listening on http://{CONFIG.listen}:{CONFIG.port}{CONFIG.path} "
        f"(auto-connect {'on' if CONFIG.auto_connect else 'off'}, token {'set' if CONFIG.token else 'none'})")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
