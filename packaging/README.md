# Packaging

Desktop integration for Valen on Linux. The Gradle package tasks write the real
installers; this directory holds the hand-written launcher for a checkout or a
distro that prefers its own packaging.

## What is here

- `valen.desktop` — a launcher entry for a checkout install. It points at the
  Debian package's launcher (`/opt/valen/bin/Valen`); for a checkout install,
  change `Exec=` to `valen` and put a wrapper on `PATH`, and install the icon as
  `valen.png` under `hicolor/512x512/apps/`. The packages built by Gradle write
  their own entry (`Categories=AudioVideo`, `Icon=/opt/valen/lib/Valen.png`), so
  this file is only needed when packaging by hand.

## What Valen needs on a Linux desktop

- **FFmpeg (`ffplay`).** YouTube Music and SoundCloud play natively: Valen
  resolves the stream itself and hands it to one `ffplay` process per song, so
  no browser is involved. `$VALEN_FFPLAY_BIN` overrides the binary, otherwise
  `ffplay` is taken from `PATH`. Without FFmpeg those two services report it on
  the first play and Spotify still works.
- **A Chrome or Chromium binary, for Spotify only.** Spotify plays through the
  Web Player in a Chrome Valen starts itself with a private profile, so no other
  browser session is touched. It is started when Spotify is first used, never at
  boot. The binary is found in this order:
  1. `$VALEN_CHROME_BIN`
  2. `<checkout>/.tools/chrome/opt/google/chrome/chrome`
  3. `google-chrome`, `google-chrome-stable`, `chromium`, `chromium-browser`,
     `/opt/google/chrome/chrome`, `/usr/bin/chromium` on `PATH`

  A missing Chrome is not fatal: the window opens, YouTube Music and SoundCloud
  play, and Spotify's tile says why it cannot start.
- **A session bus.** MPRIS (`org.mpris.MediaPlayer2.valen`) is what the media
  widget, the lock screen, the volume/media keys and `playerctl` talk to. Without
  a session bus the app still runs and says so on stderr.
- **No root, no service, no setuid helper.**

## Profile and settings

| What | Path (default) | Override |
| --- | --- | --- |
| Chrome profile (the sign-ins) | `~/.local/share/valen/chrome` | `$XDG_DATA_HOME`, `$VALEN_CHROME_PROFILE` |
| Chrome log | `<profile>/chrome.log` | — |
| Settings (`settings.json`) | `~/.config/valen/settings.json` | `$XDG_CONFIG_HOME` |

The Chrome profile holds the user's sessions for all three services (YouTube Music and SoundCloud
only use it for a sign-in: playback never goes through it). Treat it like a password store: never
copy, log or commit it. Signing out in the app deletes that service's cookies from it.

Chrome listens on `127.0.0.1:9333` (`--remote-debugging-port`) for the app's own
DevTools connection. If a Chrome is already listening there, Valen attaches to it
instead of starting another; only a Chrome Valen started is closed on exit.

## Building a package

```bash
cd valen && source scripts/env.sh
./gradlew :app-desktop:packageDeb
# -> app-desktop/build/compose/binaries/main/deb/valen_1.0.0_amd64.deb

./gradlew :app-desktop:packageAppImage
./scripts/appimage.sh
# -> app-desktop/build/compose/binaries/main/app/Valen-1.0.0-x86_64.AppImage
```

`packageDeb` needs `fakeroot` and `dpkg-deb` on the machine. The Debian package
installs to `/opt/valen`, registers the launcher itself, and uses the icon at
`app-desktop/src/main/resources/valen.png` (512×512, already in the repository),
the same name `packaging/valen.desktop` gives as `Icon=valen`.

`packageAppImage` alone leaves a jpackage app-image directory at
`app-desktop/build/compose/binaries/main/app/Valen/`; jpackage has no AppImage
type and the Compose plugin does not wrap it. `scripts/appimage.sh` copies the
image into `build/appimage/Valen.AppDir`, adds the desktop entry, icon and
`AppRun`, and calls `appimagetool --no-appstream` (it reads the version from
`app-desktop/build.gradle.kts`). Where FUSE is unavailable, run the result with
`--appimage-extract-and-run`.

Both packaged runtimes carry `jdk.security.auth` and `java.net.http`
(`app-desktop/build.gradle.kts`): without the first, MPRIS fails with
`No session bus: com/sun/security/auth/module/UnixSystem`; without the second,
the Chrome launcher reports Chrome as dead while it is in fact listening
(`NoClassDefFoundError: java/net/http/HttpClient`).

## Development flags

Documented for the screenshot and integration passes only; never part of the
packaged launcher.

```
--width N --height N   window size in px (desktop 900x640, phone 390x844)
--route ROUTE          home | search | now-playing | queue | settings
--query TEXT           text the search screen starts with (pair it with --route search)
--play PROVIDER:ID     start a song found by that provider's own search:
                       spotify:<id>, ytmusic:<id>, soundcloud:<id>
                       (a bare TEXT searches every provider and plays the first result)
--screenshot PATH      draw the app at --width×--height for about two seconds, write PATH as PNG, exit
--help                 the flags above
```

`--screenshot` renders one offscreen Skia scene of `ValenApp` (not a screen
capture: on Wayland a compositor does not give a window's pixels to an X11
client, so `java.awt.Robot` returns a black frame). It still starts Chrome and
builds the providers, so a screenshot can show real state; there is no window
and no MPRIS name while it runs. The scene keeps drawing for about two seconds
while the real clock moves, so a `--query` search and its artwork reach the
frame.

Examples:

```bash
./gradlew :app-desktop:run --args="--width 900 --height 640 --route home --screenshot /tmp/valen-home.png"
./gradlew :app-desktop:run --args="--width 900 --height 640 --route search \
  --query \"daft punk\" --screenshot /tmp/valen-search.png"
./gradlew :app-desktop:run --args="--width 390 --height 844 --route now-playing \
  --play ytmusic:dQw4w9WgXcQ --screenshot /tmp/valen-phone.png"
```

## MPRIS

- Bus name: `org.mpris.MediaPlayer2.valen`
- Object: `/org/mpris/MediaPlayer2`
- Interfaces: `org.mpris.MediaPlayer2`, `org.mpris.MediaPlayer2.Player` and the
  standard `org.freedesktop.DBus.Properties` (Get, GetAll, Set).
- `Identity` is "Valen"; `CanQuit`/`CanRaise` are false, `CanControl` true.

Check it by hand:

```bash
playerctl --player=valen status
playerctl --player=valen metadata
gdbus call --session --dest org.mpris.MediaPlayer2.valen \
  --object-path /org/mpris/MediaPlayer2 \
  --method org.freedesktop.DBus.Properties.Get org.mpris.MediaPlayer2.Player PlaybackStatus
```
