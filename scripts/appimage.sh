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
#   -> app-desktop/build/compose/binaries/main/app/Valen-<version>-x86_64.AppImage
#
# The version is $VALEN_VERSION when given (the release workflow passes the tag's), else
# gradle.properties' valen.version: the same one the build put inside the app.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
appdir="$root/build/appimage/Valen.AppDir"
image="$root/app-desktop/build/compose/binaries/main/app/Valen"
version="${VALEN_VERSION:-$(sed -n 's/^valen\.version=\(.*\)$/\1/p' "$root/gradle.properties")}"
[ -n "$version" ] || { echo "no version: set VALEN_VERSION or valen.version in gradle.properties" >&2; exit 1; }
out="$root/app-desktop/build/compose/binaries/main/app/Valen-$version-x86_64.AppImage"

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
