# tvremote

An Apple TV remote for Android, speaking the same Companion protocol as the
iPhone's built-in Apple TV remote. No Apple account or iPhone needed.

## Install

Download the APK from [Releases](https://github.com/gavinsan33/tvremote/releases) on
your phone and open it (Android 10+, 64-bit). Your phone and the Apple TV must be on the
same Wi-Fi network.

## Using it

- Pick your Apple TV at the top. The first time, a 4-digit code appears on the TV; type it
  into the app. The phone then appears on the TV under Remotes and Devices.
- The round pad works like the Siri Remote: tap an edge for an arrow or the middle to
  select, or swipe to move (a long swipe keeps going). Hold the middle for context menus.
- Back, Home (hold it for Control Center), play/pause, volume and sleep buttons.
- A **TV Remote** Quick Settings tile opens the remote from the notification shade.

## Controlling a Linux device

The remote can also drive a Linux machine (e.g. a Fedora box running a TV-style app)
by injecting key presses. On the Linux machine:

```sh
./linux/install.sh   # installs python3-evdev and a systemd service on port 8009
```

Then in the app, open the device menu and pick **Add Linux device…**, entering the
machine's IP. Buttons map to arrow keys, Enter (select), Esc (back), Home, media and
volume keys. The listener runs as root for `/dev/uinput`, so it works under Wayland
(GNOME included) or X11. It trusts your LAN — there's no pairing.

## Layout

- `core/` — the Companion protocol in plain Kotlin (no Android dependencies):
  HomeKit pair-setup/pair-verify, encrypted framing, OPACK, and remote commands.
  It also has a small command-line tool for development.
- `app/` — the Android app (Jetpack Compose, Material 3).

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

## Building the app

Needs the Android SDK and JDK 17+.

```sh
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # signed, minified: app/build/outputs/apk/release/app-release.apk
```

A release build needs a keystore described by `keystore.properties` at the repo root
(git-ignored):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Credits

The protocol was reverse-engineered by others. This implementation follows
[pyatv](https://github.com/postlund/pyatv) (MIT) for the Companion protocol and
OPACK, and [doubletake](https://github.com/omarroth/doubletake) for the HomeKit
SRP pairing details.

## License

[GNU Lesser General Public License v3.0 or later](LICENSE) (`LGPL-3.0-or-later`).
See [COPYING.GPL](COPYING.GPL) for the incorporated GPLv3 terms.
