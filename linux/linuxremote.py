#!/usr/bin/env python3
"""Tiny LAN key-injection listener for the tvremote app.

The Android app POSTs a button name; we inject the matching key via /dev/uinput,
so it works under any Wayland compositor (GNOME included) or X11.

    sudo dnf install python3-evdev
    sudo python3 linuxremote.py           # needs root for /dev/uinput

Then from the app (or to test): curl -s localhost:8009/press?b=up

No pairing/crypto: this trusts your LAN. Bind to a specific interface or
firewall the port if that's not good enough for you.
"""
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from evdev import UInput, ecodes as e

PORT = 8009

# Button name (matches core/Companion.kt Button enum, lowercased) -> evdev key.
KEYS = {
    "up": e.KEY_UP, "down": e.KEY_DOWN, "left": e.KEY_LEFT, "right": e.KEY_RIGHT,
    "select": e.KEY_ENTER, "menu": e.KEY_ESC, "home": e.KEY_HOMEPAGE,
    "volumeup": e.KEY_VOLUMEUP, "volumedown": e.KEY_VOLUMEDOWN,
    "playpause": e.KEY_PLAYPAUSE, "sleep": e.KEY_SLEEP, "wake": e.KEY_WAKEUP,
    # Siri/Screensaver have no desktop equivalent; left out on purpose.
}

ui = None  # opened in main(), after the key map is validated


def tap(name, ms=0):
    key = KEYS.get(name)
    if key is None:
        return False
    ui.write(e.EV_KEY, key, 1)   # down
    ui.syn()
    if ms > 0:
        import time
        time.sleep(ms / 1000.0)  # held long enough for the app's key autorepeat
    ui.write(e.EV_KEY, key, 0)   # up
    ui.syn()
    return True


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/name":  # app shows this instead of the IP
            self.send_response(200)
            self.end_headers()
            self.wfile.write(socket.gethostname().encode() + b"\n")
            return
        q = parse_qs(parsed.query)
        name = (q.get("b", [""])[0]).lower()
        ms = int(q.get("ms", ["0"])[0] or 0)
        ok = tap(name, ms)
        self.send_response(200 if ok else 400)
        self.end_headers()
        self.wfile.write(b"ok\n" if ok else b"unknown button\n")

    do_POST = do_GET
    def log_message(self, *a): pass


def selftest():
    assert all(isinstance(v, int) for v in KEYS.values()), "bad key map"
    assert "up" in KEYS and "select" in KEYS
    print("ok:", ", ".join(sorted(KEYS)))


def main():
    if "--selftest" in sys.argv:
        return selftest()
    global ui
    ui = UInput({e.EV_KEY: list(KEYS.values())}, name="tvremote")
    print(f"listening on :{PORT}  (buttons: {', '.join(sorted(KEYS))})")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
