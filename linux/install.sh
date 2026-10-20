#!/usr/bin/env bash
# Install the tvremote key listener as a systemd service that starts at boot.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

sudo dnf install -y python3-evdev
sudo install -m0755 "$here/linuxremote.py" /usr/local/bin/linuxremote.py
sudo install -m0644 "$here/tvremote-listener.service" /etc/systemd/system/tvremote-listener.service
sudo systemctl daemon-reload
sudo systemctl enable --now tvremote-listener.service

echo "Listening on :8009. Add this machine in the app by its IP:"
ip -4 -o addr show scope global | awk '{print "  " $4}'
