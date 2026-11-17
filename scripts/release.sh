#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Releases Valen in one command: sets the version, commits, pushes main and the tag. GitHub's
# release workflow then builds the APK, AppImage and .deb, publishes them, and points flake.nix
# at the new AppImage. Apps already installed pick the release up by themselves.
#
#   scripts/release.sh              the next version: 1.0.0-alpha.3 -> 1.0.0-alpha.4, 1.2.0 -> 1.2.1
#   scripts/release.sh 1.1.0-beta.1 a version of your choosing (MAJOR.MINOR.PATCH[-alpha|beta|rc.N])
#   scripts/release.sh --watch      also wait for the build and print the release's address
#
# It refuses when not on main, when there are uncommitted changes, when main is behind or ahead
# of GitHub's in a way it cannot simply push, or when the tag already exists.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
repo=dodo986/valen
watch=false
want=""
for arg in "$@"; do
  case "$arg" in
    --watch) watch=true ;;
    -h|--help) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) want=${arg#v} ;;
  esac
done

die() { echo "release: $*" >&2; exit 1; }

# The remote that is GitHub's dodo986/valen, whatever it is called here.
remote=$(git remote -v | awk -v r="$repo" '$2 ~ r"(\\.git)?$" && $3 == "(push)" {print $1; exit}')
[ -n "$remote" ] || die "no git remote points at github.com/$repo"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "switch to main first"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "commit or stash your changes first"

git fetch -q "$remote" main --tags
behind=$(git rev-list --count "HEAD..$remote/main")
[ "$behind" = 0 ] || die "main is $behind commit(s) behind GitHub: run 'git pull --rebase $remote main' first"

current=$(sed -n 's/^valen\.version=//p' gradle.properties)
pattern='^([0-9]{1,2})\.([0-9]{1,2})\.([0-9]{1,2})(-(alpha|beta|rc)(\.([0-9]{1,2}))?)?$'
[[ "$current" =~ $pattern ]] || die "gradle.properties has no usable valen.version ($current)"

if [ -z "$want" ]; then
  if [ -n "${BASH_REMATCH[5]}" ]; then
    want="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}-${BASH_REMATCH[5]}.$(( 10#${BASH_REMATCH[7]:-0} + 1 ))"
  else
    want="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$(( 10#${BASH_REMATCH[3]} + 1 ))"
  fi
fi
[[ "$want" =~ $pattern ]] || die "$want is not MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]]"
tag="v$want"
git rev-parse -q --verify "refs/tags/$tag" > /dev/null && die "$tag already exists"
git ls-remote --exit-code --tags "$remote" "refs/tags/$tag" > /dev/null 2>&1 && die "$tag already exists on GitHub"

echo "Releasing $want (was $current) from $(git rev-parse --short HEAD) to $repo"
if [ "$want" != "$current" ]; then
  sed -i "s/^valen\.version=.*/valen.version=$want/" gradle.properties
  git commit -q -m "Release $want" gradle.properties
fi
git push -q "$remote" main
git tag -a "$tag" -m "Valen $want"
git push -q "$remote" "$tag"
echo "Pushed $tag. The build takes about 10 minutes: https://github.com/$repo/actions/workflows/release.yml"

if $watch; then
  command -v gh > /dev/null || die "gh is needed for --watch"
  sleep 10
  run=$(gh run list -R "$repo" --workflow release.yml -L 5 --json databaseId,headBranch \
    --jq ".[] | select(.headBranch == \"$tag\") | .databaseId" | head -n1)
  [ -n "$run" ] || die "the release run did not show up; see the Actions page"
  if gh run watch "$run" -R "$repo" --exit-status --interval 30 > /dev/null; then
    echo "Released: https://github.com/$repo/releases/tag/$tag"
  else
    die "the build failed: https://github.com/$repo/actions/runs/$run"
  fi
fi
