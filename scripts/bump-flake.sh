#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Points flake.nix at a release's AppImage: its version and the SRI form of the sha256 the
# release manifest lists for linux-appimage. Run by the release workflow after a release is
# published, so the flake follows the releases.
#
#   scripts/bump-flake.sh VERSION MANIFEST.json
#
# Only moves forward: when flake.nix already names this version or a newer one (a release made
# again from an older tag), it changes nothing. Exit 0 either way; "changed" or "unchanged" is
# printed so the caller knows whether to commit.
set -euo pipefail

version=${1:?version}
manifest=${2:?manifest}
root=$(cd "$(dirname "$0")/.." && pwd)
flake="$root/flake.nix"

# The same number as ValenVersion.code and the root build.gradle.kts.
code() {
  [[ "$1" =~ ^([0-9]{1,2})\.([0-9]{1,2})\.([0-9]{1,2})(-(alpha|beta|rc)(\.([0-9]{1,2}))?)?$ ]] || { echo 0; return; }
  local base=$(( (10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]}) * 1000 ))
  case "${BASH_REMATCH[5]}" in
    "") echo $(( base + 999 )) ;;
    alpha) echo $(( base + 100 + 10#${BASH_REMATCH[7]:-0} )) ;;
    beta) echo $(( base + 200 + 10#${BASH_REMATCH[7]:-0} )) ;;
    rc) echo $(( base + 300 + 10#${BASH_REMATCH[7]:-0} )) ;;
  esac
}

current=$(sed -n 's/^      version = "\(.*\)";$/\1/p' "$flake" | head -n1)
[ -n "$current" ] || { echo "flake.nix has no top-level version line" >&2; exit 1; }
[ "$(code "$version")" -gt 0 ] || { echo "not a version: $version" >&2; exit 1; }
if [ "$(code "$version")" -le "$(code "$current")" ]; then
  echo "unchanged (flake.nix is at $current)"
  exit 0
fi

hex=$(jq -r '.assets[] | select(.platform == "linux-appimage") | .sha256' "$manifest")
[[ "$hex" =~ ^[0-9a-f]{64}$ ]] || { echo "the manifest has no linux-appimage sha256" >&2; exit 1; }
sri="sha256-$(python3 -c 'import base64,sys; print(base64.b64encode(bytes.fromhex(sys.argv[1])).decode())' "$hex")"

sed -i \
  -e "s|^      version = \"$current\";\$|      version = \"$version\";|" \
  -e "s|^        hash = \"sha256-[A-Za-z0-9+/=]*\";\$|        hash = \"$sri\";|" \
  "$flake"
grep -q "version = \"$version\";" "$flake" && grep -q "hash = \"$sri\";" "$flake" \
  || { echo "flake.nix did not take the new version and hash" >&2; exit 1; }
echo "changed ($current -> $version, $sri)"
