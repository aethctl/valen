# HANDOFF — Valen

**As of** 2026-11-14, end of the sign-in window pass (Pass 10). The passes before it were the
window-scaling pass (Pass 9), the design rework (Pass 7), the AppImage rebuild (Pass 8), and before
those headless Spotify, unified search and source priority.
**Next focus:** the Spotify recovery bug in §2, which this pass did not touch, then the visual
follow-ups in §4. Read `AGENTS.md` first; it is the durable contract. This file is only the state
you are inheriting.

Not a git repo. No branch, no commits: every change below is uncommitted files on disk.

---

## 1. Where things stand

### Working and verified

- Desktop app builds and runs; UI draws at any window size (sidebar ≥1000dp, rail 700–999, phone
  layout <700) and now fills a window the compositor resizes. Tiling Wayland compositors used to
  leave AWT's content window at its creation size; `Main.kt` sets AWT's non-reparenting switch for
  niri/sway/Hyprland/i3 and the packaged launcher opens `java.desktop/sun.awt.X11` for it. Verified
  by X geometry on the distributable and from the rebuilt AppImage: frame, `Content window` and
  Skia canvas all measure the same after `niri msg action expand-column-to-available-width`.
- **YouTube Music and SoundCloud play natively with no browser**: `libs/audio` + `providers/native`.
  Play/pause/resume/seek/auto-advance verified; `playerctl` drives both; zero Chrome processes.
  YouTube URLs resolved with NewPipeExtractor, SoundCloud with a runtime-scraped `client_id`.
- **Spotify plays headless**: Valen's private Chrome starts on first Spotify use with
  `--headless=new` and a synthesized normal-Chrome UA. Verified from a cold start with no Chrome
  running: real account, song playing, position advancing, MPRIS correct, CDP reports
  `HeadlessChrome`. `VALEN_CHROME_VISIBLE=1` forces a window.
- Spotify sign-in opens a visible window through `DesktopHosts.ensureVisible()`; replacing the
  browser bumps `generation` and invalidates the attached adapter. The browser's mode is read from
  its process, and a headless one is replaced even when this run did not start it. A sign-in that
  cannot get a window publishes `SessionState.Unavailable` with the launcher's reason instead of
  doing nothing. Verified live: a leaked headless Chrome from a killed run → Connect → the old
  browser stopped, a windowed Chrome on the Spotify login page in its place (`ChromeLauncherTest`
  covers the process reading and the stop).
- **Chrome-died watchdog works**: SIGKILL the Spotify Chrome → within ~10 s MPRIS flips to
  Paused and stderr prints
  `valen: Spotify: Spotify's browser was closed. Press play to reopen it.`
- Unified search is implemented and unit-tested: one merged, de-duplicated list; "also on" labels;
  source priority decides which copy plays; per-service errors shown inline.
- `AppSettings.priority` persists; Settings has a "Sources" card with up/down reordering.
- `Artwork.kt` no longer launches work from a remembered scope after leaving composition (the known
  `rememberCoroutineScope left the composition` freeze cause).
- **Design rework is in** (Mochy Music + Orpheon Immersive language; `docs/design-spec.md` is
  rewritten to match): black page, artwork wash and player tint, floating side panel / dock /
  phone capsules, superellipse shapes, two-voice type. Screenshots in `docs/screens/` are from
  this pass (1600×900 and 390×844), and 2200×1200 and the 860×700 rail were checked by eye.
- **Unified search on screen** and the **Settings → Sources card** now have screenshots
  (`search-*.png`, `settings-desktop.png`).
- **`./gradlew :ui:jvmTest`** composes every screen at four window sizes, empty and with a queue,
  then clicks through search → play → dock transport → queue → stage → close → settings on a wide
  window and the two sheets on a phone, against fake services. Green.
- Screens send commands on `LocalAppScope` (remembered at the root) instead of their own scope,
  which removes the freeze's cause for every screen, not only `Artwork`.
- Navigation state and the search query/results live above the width switch: resizing across a
  breakpoint keeps the route, and leaving Search and coming back keeps the results.

### Implemented but **not yet verified** (verify before trusting)

1. **Spotify recovery after the browser dies.** `playerctl play` does restart Chrome, but the song
   does not resume: status stays Paused, position stays at the old value, and no failure line is
   printed. Two changes are built but unproven:
   - `RetryingProvider.resume()` now decides by adapter identity: if `ready()` had to build a new
     adapter, it replays `lastTrack` at `lastPosition` instead of calling `resume()` on an empty page.
   - `buildSpotify()` now re-injects the page bridge up to 40 times (1.5 s apart, 3 s wait each)
     until `api.ready` is true, instead of injecting once into a page that may still be navigating.
   The last observed run still failed, and the run that would have shown the bridge log was
   interrupted. **This is the first thing to finish.**
2. **Source reordering by hand** — the Sources card renders and its buttons call `setPriority`,
   but up/down has not been clicked in a real window and the restart persistence has not been
   re-checked since the rework.
3. **Pointer feel in a real window** — hover fills, the seek and volume rails, queue drag and the
   page cross-fade were built and compile-checked, and the click-through test exercises taps, but
   nobody has used them with a mouse or a finger yet. `--screenshot` cannot show hover or drag.
4. **Window-freeze fix** — the structural cause is removed and the route-switch test passes; the
   user's original case was never reproduced, so a real resize/route-switch session is still owed.
5. **Android** — unchanged hidden-WebView adapters; no device/emulator on this machine (no `adb`).
   Only the compile check runs here.

---

## 2. Finish the Spotify recovery bug

### Reproduce (safe process handling)

```bash
cd /home/dodo986/Projects/valen
source scripts/env.sh
./gradlew :app-desktop:createDistributable

# start clean, one instance only
DISPLAY=:0 VALEN_SPOTIFY_LOG=1 nohup \
  app-desktop/build/compose/binaries/main/app/Valen/bin/Valen \
  --play 'spotify:get lucky' > /tmp/opencode/recover.log 2>&1 &

sleep 45
playerctl -p valen status && playerctl -p valen position
```

Then SIGKILL only the Chrome whose cmdline contains
`--user-data-dir=/home/dodo986/.local/share/valen/chrome`, by scanning `/proc/*/cmdline` (never
`pkill -f`). Wait 10 s, confirm Paused + the watchdog line, then:

```bash
playerctl -p valen play
sleep 40
playerctl -p valen status; playerctl -p valen position
grep -n "Spotify" /tmp/opencode/recover.log | tail -40
```

### What the log will tell you

- Nothing after the watchdog line → `resume()` never reached the replay path, or `ready()` returned
  null silently. Check `MPRIS Play` gate → `coordinator.toggle()` → `_active` → `provider.resume()`.
- `bridge is not in this page` / repeated `media.state` + `bridge.version` timeouts → injection is
  landing in a page that is not the Web Player tab, or the tab is `about:blank`. Compare
  `curl -s http://127.0.0.1:9333/json/list` (page URL) with what `CdpHost.connect` attached to.
- `not ready` at ~1 ms → `Bridge.gone` is true; on desktop nothing calls `onPageStarting()` today,
  so this would mean the host wired it somewhere new.
- `timeout after … : media.state` after `Ready` → page booted but the player is not initialized;
  wait on `api.ready` only guards injection, not player start.

### Then close it out

- If replay works: confirm the same for `next` after a kill (queue moves on) and for a *pause* then
  browser kill then play (position should not jump).
- If it cannot be made reliable in this pass: make the failure visible instead of silent — emit
  `ProviderEvent.Failed` from `resume()` when `ready()` returns null, and say "Spotify's browser was
  closed; open Spotify to reconnect" in the UI. Silence is the bug.

Files involved:
`app-desktop/src/main/kotlin/app/valen/desktop/Providers.kt` (`RetryingProvider`,
`buildSpotify`), `providers/spotify/src/main/kotlin/app/valen/providers/spotify/SpotifyProvider.kt`
(`play` waits for `api.ready`), `host/desktop/.../DesktopHosts.kt` (`browserAlive`, `generation`),
`host/desktop/.../ChromeLauncher.kt` (`start(visible)`, `headless`, probe/reuse rules).

---

## 3. Other open verification and cleanup

| Item | How to check | Done when |
| --- | --- | --- |
| Source priority | Settings → Sources, click up/down in a real window | order changes, survives restart in `~/.config/valen/settings.json` |
| Pointer feel | real window at 1600×900: hover rows, drag seek/volume, drag a queue row, switch routes, resize across 1000dp and 700dp | no frozen content, no unpainted regions, no exception on stderr (geometry now known good; the pointer itself is still unverified) |
| Tiled windows | niri: open Valen, `niri msg action expand-column-to-available-width`, resize the column | frame, `Content window` and `sun-awt-X11-XCanvasPeer` report the same size (done in Pass 9 on the distributable and the AppImage; other compositors untested) |
| Android | `./gradlew :app-android:compileDebugKotlin` | compiles; runtime still unverified |
| Packages | `packageDeb` / `packageAppImage` | AppImage rebuilt after the scaling fix (107 MB, sha256 `c3e2a23c…`) and run from its unpacked output in a real tiled window; `.deb` not rebuilt — its 95 MB artifact predates the design rework |
| Test sweep | `:core:jvmTest`, `:ui:jvmTest`, `:libs:audio:test`, `:providers:soundcloud:test`, `:host:desktop:test` | green (all five green in Pass 8; `:core` and `:ui` re-run green in Pass 9, Android compiles) |
| Docs | `packaging/README.md`, `docs/provider-contract.md` | headless Spotify, unified search, source priority documented (README, NOTICE, TODO, design spec and AGENTS §4 were updated this pass) |

---

## 4. Design — what was built and what is left

The visual contract is `docs/design-spec.md`; the rules an agent must not break are in
`AGENTS.md` §4. Everything visual is in `ui/src/commonMain/kotlin/app/valen/ui/`.

**How it is put together**

- Tokens: `theme/Color.kt`, `Type.kt` (+ `TrackType`, `timeStyle`), `Dimens.kt` (`Space`, `Radii`,
  `Layout`, `Chrome`/`LocalChrome`), `Shapes.kt` (`Squircle`), `Motion.kt`.
- Shell: `ValenApp.kt` — `Nav` (a place plus what is open over it), the width switch, the floating
  `Sidebar`, `PlayerBar` dock, `MiniPlayer` + `BottomNav`, toast, and the dim under bottom chrome.
- Screens draw edge to edge and put content in `components/Page.kt`'s `ContentColumn`, which
  fills the width the panel leaves. Only Settings passes a reading `maxWidth`. Up next takes 1–6
  columns with the window, the Home hero cover is 248/312/360 and the stage cover grows to 720
  (Pass 9; before it the column stopped at 1400dp and centred, which read as a strip on a wide
  window).
- Artwork colour: `artwork/Palette.kt` (`ArtworkTones`, hue-binned accent, left/right halves) and
  `components/Ambient.kt` (`AmbientWash`). `Artwork(measure = true)` is what fills the cache.
- Removed this pass: `ErrorBanner`, `SignInCard`, `ProviderTile`, `ArtworkGlow`, `TrackRow`,
  `SubHeader`, `eyebrowStyle`. Replaced by `NoticeToast`, `ServiceTile`, `AmbientWash`,
  `QuickTile`/`ShelfCard`, `SectionHeader`.
- One small core addition: `NOTICE_QUEUED` (the text of the "Added to queue" notice), so the UI can
  show that one as a confirmation that leaves by itself and everything else as a message that stays.

**Left to do, in rough order of value**

1. **YouTube video thumbnails are letterboxed.** A result that is a video (not a song) falls back
   to `https://i.ytimg.com/vi/<id>/hqdefault.jpg`, a 4:3 frame with black bars baked in, so its
   cover shows bars top and bottom at every size and badly on the stage. The choice is made in
   `providers/native/.../YtmusicNativeProvider.kt` and `providers/ytmusic/.../YtmusicProvider.kt`
   (`album?.images?.best(300)?.url ?: …hqdefault.jpg`). `mqdefault.jpg` has no bars but is 320×180;
   `hq720.jpg` has none and is sharp but does not exist for every video. Provider code was out of
   scope for the design pass.
2. **YouTube song covers are requested at 300px** (`best(300)`) and the stage now draws them at up
   to 720dp. A larger size from the same image list would sharpen the stage.
3. **A search row is "playing" only when it is the same copy.** Play the YouTube Music copy of a
   song and the Spotify copy in the list is not marked. Matching by `recordingKey` would fix it.
4. **Keyboard**: Tab focus shows the hover fill, but there are no shortcuts (space to play, `/` to
   search, Escape to close the stage).
5. **No scrollbar** on desktop lists.
6. **Android**: the wash dithers and the shapes clip through `expect/actual` and compile, but have
   never been seen on a device; `Squircle` clipping cost on a long list is unmeasured there.
7. `Sheet.kt`'s scrim is still a literal (`Color.Black` at 56%), from before this pass.

**Screenshot matrix** (silent, and without touching the user's settings). The five desktop
screens were re-rendered in Pass 9 after the layout change; the five phone ones predate it and
were left alone, since the phone paths did not change. The desktop renders show services as
"Connect" because the run uses a scratch config and Valen's Chrome profile had no live Spotify
session at the time — no account was touched.

```bash
mkdir -p /tmp/valen-shots/cfg/valen
printf '{"quality":"AUTO","volume":0.0}' > /tmp/valen-shots/cfg/valen/settings.json
V=app-desktop/build/compose/binaries/main/app/Valen/bin/Valen
P='soundcloud:flume never be like you'
for size in "1600 900 desktop" "390 844 phone"; do set -- $size
  XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width $1 --height $2 --route home        --play "$P" --screenshot docs/screens/home-$3.png
  XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width $1 --height $2 --route now-playing --play "$P" --screenshot docs/screens/now-playing-$3.png
  XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width $1 --height $2 --route queue       --play "$P" --screenshot docs/screens/queue-$3.png
  XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width $1 --height $2 --route search --query "daft punk" --play "$P" --screenshot docs/screens/search-$3.png
done
XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width 1600 --height 900 --route settings --screenshot docs/screens/settings-desktop.png
```

---

## 5. Machine/environment facts

- Build env: `source scripts/env.sh` (JDK 21, Android SDK 36/37, Gradle home, Chrome under `.tools/`).
- Spotify Chrome profile: `~/.local/share/valen/chrome`, signed in as the user, plays headless.
  Do not clear it. Remove `Singleton*` only when no Chrome with that profile runs.
- DevTools: `http://127.0.0.1:9333`; a live check is `curl -s .../json/version`.
- One Valen instance at a time (shared profile, port, MPRIS name `org.mpris.MediaPlayer2.valen`).
- Settings file: `~/.config/valen/settings.json`.
- Distribution binary: `app-desktop/build/compose/binaries/main/app/Valen/bin/Valen`.
- AppImage needs `--appimage-extract-and-run` without FUSE.
- `ffplay` is a documented runtime dependency, not bundled.
- Benign: Gradle Skiko version-mismatch warning.

---

## 6. First 30 minutes, suggested

1. `source scripts/env.sh && ./gradlew :core:jvmTest :ui:jvmTest` — confirm the tree is green as
   handed over.
2. Finish §2 (Spotify recovery) with `VALEN_SPOTIFY_LOG=1`; either fix or make it fail loudly.
3. Open a real window and use it for five minutes with a mouse: the "pointer feel" row in §3.
4. Take §4's list from the top; the YouTube thumbnail choice is the most visible one.
