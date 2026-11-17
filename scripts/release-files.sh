#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Gathers a release's files under their release names and writes valen-release.json, the manifest
# Valen's updater reads (core/Updates.kt, libs/update/GithubUpdater.kt): for each file the platform
# it is for, its size and its SHA-256, and for the APK the SHA-256 of its signing certificate.
#
#   scripts/release-files.sh VERSION CODE OUT_DIR
#
# Run after `:app-android:assembleRelease`, `:app-desktop:packageDeb` and scripts/appimage.sh, all
# built with -Pvalen.version=VERSION. A file that is missing fails the script: a release that would
# lack a platform's file is not made. With $VALEN_REPO set (owner/name) and `gh` signed in, the APK's
# signer is also compared with the newest published release's: an APK signed with another key would
# not install over the Valen people have, so it is never published.
set -euo pipefail

version=${1:?version}
code=${2:?code}
out=${3:?out dir}
root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$out"

apk="$root/app-android/build/outputs/apk/release/app-android-release.apk"
appimage="$root/app-desktop/build/compose/binaries/main/app/Valen-$version-x86_64.AppImage"
deb=$(ls "$root"/app-desktop/build/compose/binaries/main/deb/*.deb 2>/dev/null | head -n1 || true)

for f in "$apk" "$appimage" "$deb"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "missing release file: ${f:-the .deb}" >&2; exit 1; }
done

cp "$apk" "$out/Valen-$version.apk"
cp "$appimage" "$out/Valen-$version-x86_64.AppImage"
cp "$deb" "$out/Valen-$version-amd64.deb"

# The APK's signing certificate, as apksigner prints it (SHA-256 of the DER certificate, which is
# what ApkInstaller compares on the phone).
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1 || true)
[ -x "$apksigner" ] || { echo "apksigner not found under \$ANDROID_HOME/build-tools" >&2; exit 1; }
signer=$("$apksigner" verify --print-certs "$out/Valen-$version.apk" \
  | sed -n 's/^\(Signer #1\|V[0-9][0-9.]* Signer\): certificate SHA-256 digest: \([0-9a-f]*\)$/\2/p; s/^Signer #1 certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' | head -n1)
[ ${#signer} -eq 64 ] || { echo "could not read the APK's signing certificate" >&2; exit 1; }

if [ -n "${VALEN_REPO:-}" ] && command -v gh >/dev/null; then
  previous=$(gh api "repos/$VALEN_REPO/releases?per_page=30" \
    --jq '[.[] | select(.draft | not) | .assets[] | select(.name == "valen-release.json") | .browser_download_url][0] // empty' || true)
  if [ -n "$previous" ]; then
    before=$(curl -fsSL "$previous" | jq -r '.assets[] | select(.platform == "android") | .signer // empty')
    if [ -n "$before" ] && [ "$before" != "$signer" ]; then
      echo "the APK is signed by $signer, the last release by $before: phones would refuse it" >&2
      exit 1
    fi
  fi
fi

entry() { # platform file [signer]
  local size sha
  size=$(stat -c %s "$out/$2")
  sha=$(sha256sum "$out/$2" | cut -d' ' -f1)
  jq -n --arg p "$1" --arg n "$2" --argjson s "$size" --arg h "$sha" --arg g "${3:-}" \
    '{platform: $p, name: $n, size: $s, sha256: $h} + (if $g == "" then {} else {signer: $g} end)'
}

jq -n --arg v "$version" --argjson c "$code" \
  --argjson a "$(entry linux-appimage "Valen-$version-x86_64.AppImage")" \
  --argjson b "$(entry linux-deb "Valen-$version-amd64.deb")" \
  --argjson d "$(entry android "Valen-$version.apk" "$signer")" \
  '{version: $v, code: $c, assets: [$a, $b, $d]}' > "$out/valen-release.json"

echo "release files for $version ($code) in $out:"
ls -l "$out"
