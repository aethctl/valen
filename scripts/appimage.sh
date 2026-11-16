#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Turns the app-image jpackage produces into a single .AppImage file.
#
# `./gradlew :app-desktop:packageAppImage` only asks jpackage for an app image
# directory (`build/compose/binaries/main/app/Valen`): jpackage has no AppImage
# type, and the Compose Gradle plugin does not wrap it. appimagetool does.
#
#   scripts/appimage.sh
#   -> app-desktop/build/compose/binaries/main/app/Valen-1.0.0-x86_64.AppImage
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
appdir="$root/build/appimage/Valen.AppDir"
image="$root/app-desktop/build/compose/binaries/main/app/Valen"
out="$root/app-desktop/build/compose/binaries/main/app/Valen-1.0.0-x86_64.AppImage"
version=$(sed -n 's/^[[:space:]]*packageVersion = "\(.*\)"/\1/p' "$root/app-desktop/build.gradle.kts")

command -v appimagetool >/dev/null || {
  echo "appimagetool is not installed" >&2
  exit 1
}
[ -x "$image/bin/Valen" ] || {
  echo "run ./gradlew :app-desktop:packageAppImage first ($image/bin/Valen is missing)" >&2
  exit 1
}

rm -rf "$appdir"
mkdir -p "$appdir"
cp -a "$image/bin" "$image/lib" "$appdir/"
cp "$root/app-desktop/src/main/resources/valen.png" "$appdir/valen.png"

cat > "$appdir/valen.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Valen
Comment=A music player for Spotify, YouTube Music and SoundCloud
Exec=Valen
Icon=valen
Terminal=false
Categories=AudioVideo
EOF

cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
# The launcher resolves its lib/ next to its own binary; keep the path absolute.
# glibc gives every thread that allocates its own arena (up to 8 per core); Skia and the JVM's
# many short-lived threads left most of them half empty. Two is plenty for a player and saved
# about 30 MB of resident memory (measured with a song and its lyrics on the stage).
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
exec "$(dirname "$(readlink -f "$0")")/bin/Valen" "$@"
EOF
chmod +x "$appdir/AppRun"

ARCH=x86_64 appimagetool --no-appstream "$appdir" "$out"
echo "wrote $out for $version"
