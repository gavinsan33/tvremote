# tvremote

An Apple TV remote for Android, speaking the same Companion protocol as the
iPhone's built-in Apple TV remote. No Apple account or iPhone needed.

**Status:** early. The protocol core pairs with an Apple TV and sends remote
buttons; the Android app is in progress.

## Layout

- `core/` — the Companion protocol in plain Kotlin (no Android dependencies):
  HomeKit pair-setup/pair-verify, encrypted framing, OPACK, and remote commands.
  It also has a small command-line tool for development.
- `app/` — the Android app (coming).

## Command-line tool

Requires JDK 17+.

```sh
./gradlew :core:installDist
core/build/install/core/bin/core pair 192.168.0.73:49692          # PIN appears on the TV
core/build/install/core/bin/core 192.168.0.73:49692 up select menu
```

The Apple TV's Companion port is advertised over mDNS as `_companion-link._tcp`
(commonly 49153). Credentials are saved in `~/.config/tvremote/`. Set
`TVREMOTE_DEBUG=1` for protocol logs.

Buttons: `up down left right menu select home volumeup volumedown siri
screensaver sleep wake playpause`.

## Credits

The protocol was reverse-engineered by others. This implementation follows
[pyatv](https://github.com/postlund/pyatv) (MIT) for the Companion protocol and
OPACK, and [doubletake](https://github.com/omarroth/doubletake) for the HomeKit
SRP pairing details.

## License

[GNU Lesser General Public License v3.0 or later](LICENSE) (`LGPL-3.0-or-later`).
See [COPYING.GPL](COPYING.GPL) for the incorporated GPLv3 terms.
