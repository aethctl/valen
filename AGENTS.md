# AGENTS.md — working on Valen

Valen is a cross-platform music player (Linux desktop + Android) built from one Kotlin codebase:
Jetpack Compose Multiplatform UI, one app-owned queue, three services (Spotify, YouTube Music,
SoundCloud) behind a single provider contract. Package name `app.valen`. Not a git repo: everything
is uncommitted files on disk, so treat every file as possibly hand-edited.

This file is the durable contract: how to build, what must not break, where things live, and the
rules the project is being held to. `HANDOFF.md` is the current state and next steps.

---

## 1. Ground rules

- Work only inside `/home/dodo986/Projects/valen`. Reading reference repos (`Orpheon`, `Mochy`) is
  allowed; editing them is not.
- Ask before deleting files outside the project, installing system packages, or logging into a real
  account.
- Never hardcode API keys, tokens, cookies or credentials. SoundCloud's `client_id` is scraped at
  runtime from `soundcloud.com`; Spotify uses the user's Web Player session; nothing is committed.
- Every Kotlin file, page script and vendored bridge file carries `/* SPDX-License-Identifier: GPL-3.0-only */`
  (or the JS comment equivalent). Keep that header on new files — `NOTICE` claims it per file.
- Vendored Orpheon/Mochy code keeps its licence and credit in `NOTICE`. Do not relicense.
- After each milestone print exactly one line: `✅ [what was completed]`.
- Log a failure after 3 attempts and move on; do not loop forever.

---

## 2. Build, run, verify

```bash
cd /home/dodo986/Projects/valen
source scripts/env.sh          # project-local JDK 21, Android SDK, Gradle home, Chrome under .tools/
./gradlew :app-desktop:run     # normal app
```

Tools are project-local and git-ignored under `.tools/`; `scripts/env.sh` is mandatory for every
Gradle call in a fresh shell.

| What | Command |
| --- | --- |
| Compile everything that matters for UI work | `./gradlew :core:compileKotlinJvm :ui:compileKotlinJvm :app-desktop:compileKotlin` |
| Distributable binary | `./gradlew :app-desktop:createDistributable` → `app-desktop/build/compose/binaries/main/app/Valen/bin/Valen` |
| Core tests | `./gradlew :core:jvmTest` |
| UI click-through tests (fake services, no network, ~1 min) | `./gradlew :ui:jvmTest` |
| Audio/providers tests | `./gradlew :libs:audio:test :providers:soundcloud:test :host:desktop:test` |
| Android compile check (no device here) | `./gradlew :app-android:compileDebugKotlin` |
| APK | `./gradlew :app-android:assembleDebug` |
| Deb | `./gradlew :app-desktop:packageDeb` (needs `dpkg-deb`, `fakeroot`) |
| AppImage | `./gradlew :app-desktop:packageAppImage && ./scripts/appimage.sh` (needs `appimagetool`) |

Runtime dependencies that packages do **not** bundle: `ffplay` (FFmpeg) for native YouTube Music and
SoundCloud playback (`$VALEN_FFPLAY_BIN` overrides), and a Chrome/Chromium for Spotify
(`$VALEN_CHROME_BIN` overrides; `.tools/chrome` is used in development).

### Development flags (desktop binary)

```
valen [--width N] [--height N] [--route home|search|now-playing|queue|settings]
      [--query TEXT] [--play PROVIDER:ID] [--screenshot PATH]
```

- `--play 'spotify:get lucky'` searches the service and starts the first result. Without a provider
  prefix it uses the unified search.
- `--screenshot` renders offscreen (5 s settle so artwork loads), writes a PNG, exits. This is the
  only reliable way to check layout here.
- `--screenshot` with `--play` really plays the song for those seconds. To keep it silent and leave
  the user's settings alone, point `XDG_CONFIG_HOME` at a scratch directory whose
  `valen/settings.json` has `"volume": 0.0` (ffplay is started at that level). Prefer
  `--play 'soundcloud:…'` or `'ytmusic:…'` for screenshots: neither needs an account.
- `--route` + `--query` open a screen directly, e.g.
  `--route search --query "daft punk" --screenshot /tmp/opencode/search.png`.

### Environment variables

| Variable | Effect |
| --- | --- |
| `VALEN_FFPLAY_BIN` | ffplay used for native playback |
| `VALEN_CHROME_BIN` | Chrome/Chromium for Spotify |
| `VALEN_CHROME_PROFILE` | private Chrome profile dir (default `$XDG_DATA_HOME/valen/chrome`) |
| `VALEN_CHROME_VISIBLE=1` | force a windowed Spotify browser (normally headless) |
| `VALEN_SPOTIFY_LOG=1` | Web Player bridge lines (refusals, device join, timeouts) to stderr |
| `VALEN_CHROME_LOG=1` | Chrome launcher lines |
| `VALEN_MPRIS_LOG=1` | MPRIS lifecycle lines |

---

## 3. Architecture map

```
core/            Track, MusicProvider, ProviderSignIn, PlaybackCoordinator (the only UI-facing
                 facade), Recordings.kt (cross-service recording identity), AppSettings, settings store
ui/              Compose Multiplatform design system + 5 screens, shared by both platforms
  theme/         Color.kt, Type.kt, Dimens.kt, Shapes.kt, Motion.kt  ← all design tokens live here
  components/    one file per component (Sidebar, PlayerBar, MiniPlayer, ResultRow, Artwork,
                 Ambient, Page, …); Interactive.kt holds `tap`, `glass`, `IconTap`
  screens/       Home, Search, NowPlaying, Queue, Settings
  artwork/       Tone extraction (expect/actual pixel read), tones cache
  ValenApp.kt    the shell: width switch, navigation state, floating chrome, `LocalAppScope`
  src/jvmTest    ShellSmokeTest: every screen at every width, then a click-through
libs/api,web,engine,spotify,ytmusic   vendored Orpheon code (GPL-3.0, do not restructure)
libs/audio/      FfplayPlayer (one ffplay process per stream), NativePlayback, YoutubeAudio
                 (NewPipeExtractor), SoundcloudAudio (API + runtime client id)
providers/spotify        Spotify adapter over the vendored Web Player
providers/native         desktop YouTube Music + SoundCloud adapters (no browser at all)
providers/ytmusic,soundcloud   page-based adapters Android still uses (hidden WebView)
host/desktop     ChromeLauncher, CdpHost/CdpPageHost, DesktopHosts, desktop sign-ins, MPRIS
host/android     WebView hosts, Widevine, Android sign-ins, foreground playback service
app-desktop, app-android      entry points, CLI flags, platform wiring
docs/            design-spec.md (the visual contract), provider-contract.md, screens/*.png
packaging/       desktop entry, icon, packaging notes
```

Key invariants:

- **The queue is Valen's.** Adapters play exactly one track. `PlaybackCoordinator` owns
  queue/index/now-playing and is the only thing the UI talks to. Never let an adapter invent a queue.
- **The UI never knows which service is playing.** It reads `ProviderId` for labels only.
- **`MusicProvider` is the contract.** See `docs/provider-contract.md`. Additions go there first.
- **Desktop YouTube Music and SoundCloud never open a browser.** Chrome belongs to Spotify alone and
  starts on first use (headless by default), never at boot.
- **Spotify signs in inside Valen's private Chrome.** A sign-in is the only reason a window may
  appear (`connect()` → `DesktopHosts.ensureVisible()`), and replacing the browser bumps
  `DesktopHosts.generation`, invalidating the attached adapter.
- **Provider failures are shown, never hidden.** A failed service is named beside the other
  services' results; Spotify's own refusal wording ("Spotify refused to play this") is shown verbatim.

---

## 4. Design system — rules for any visual work

Read `docs/design-spec.md` before changing pixels. Direction: a black page, artwork-led colour and
floating chrome, in the language of Mochy Music and Orpheon's Immersive look. Not: stacked glass,
gradients unrelated to artwork, stock card grids, uppercase eyebrow labels, icons in circles as
empty states, purple-blue gradients, emoji icons, placeholder copy, shadows.

- **Tokens, not literals.** Sizes/shapes from `Dimens.kt` and `Shapes.kt` (`Space`, `Radii`,
  `Layout`), colours from `ValenColors`, motion from `Motion.kt`, type from `Type.kt`. No hex
  colour, easing curve or duration in a screen; no font size outside `Type.kt`. A component may
  state its own fixed measurements (a 48dp cover, a 52dp field); a screen may not invent spacing
  that is not a `Space` step. If a new token is genuinely needed, add it to the token file and to
  `docs/design-spec.md` in the same change.
- **One accent and at most one wash per screen**, both from the artwork that is playing
  (`ui/artwork/Palette.kt`, `ArtworkTones`). Use the tones' fixed-luminance steps (`poolLeft`,
  `chrome`, `bright`, …); never put raw artwork colour behind text. Fallback `#D9D9DE`.
- **Hierarchy = surface step on the black page.** Separators only between rows of one group.
  No shadows. Glass (`Modifier.glass`) is for floating chrome only and is never stacked.
- **Screens draw edge to edge** and keep content inside `ContentColumn`, which reads
  `LocalChrome` for what the floating panel and dock take. The column fills the width it is given:
  only reading screens (Settings, a lone paragraph) pass a `maxWidth`. Do not pad a whole screen,
  and do not cap a browsable screen — a wide window is room for more music, not more margin.
- **One shape per component**, from `Radii`: superellipses for anything that is or holds a cover
  (12 covers, 18 rows/tiles, 28 cards, 40 stage cover and side panel), pills for controls.
- **Type**: Bricolage Grotesque is the app's voice (greeting, screen and section titles, wordmark);
  Inter is the music (song titles, artists, controls), semi-bold and tight. Song titles are never
  set in Bricolage. Nothing is uppercase. Tabular numbers for times (`timeStyle`).
- **One pressable**: `Modifier.tap` (hover/press/focus fill, no ripple) or `IconTap`. Do not call
  `clickable` with a ripple in new code.
- **Responsive by width, not platform** (`ValenApp.kt`): ≥1000dp floating side panel + player
  dock; 700–999 icon rail; <700 tab bar + mini player capsules + full-screen sheets. Same
  components in all three. Navigation state lives above the width switch.
- **Motion**: 280ms standard, 320ms enter, 200ms exit, 120ms press, 160ms page cross-fade; queue
  reorder spring(520, .83). Reduced motion → 120ms fades and no drift. Curves are defined once in
  `Motion.kt`.
- **Icons**: Material Symbols Rounded only, no service logos. **Hit targets ≥44dp. Rows truncate
  at one line.**
- **Empty states are a title and a real sentence with at most one action**, on the page's left
  edge. A section with nothing to show is left out rather than shown empty.
- **Artwork** crossfades; never shimmer. Tone extraction must stay off the composition callback
  path (see `Artwork.kt`), and only covers of the playing song set `measure = true`.
- **Commands go on `LocalAppScope`**, never on a screen's own `rememberCoroutineScope()`: a scope
  that leaves the composition cancels the command, and using it afterwards throws
  `rememberCoroutineScope left the composition` and freezes the window.

Android and desktop share every screen; platform differences belong in `expect/actual`, never in
duplicated screens.

---

## 5. Debugging and machine guardrails

These cost hours before; do not relearn them.

- **Tiled windows under XWayland**: niri, sway and Hyprland resize the frame but XWayland leaves
  AWT's content window at the size it was created with, so the UI draws in the corner of a larger
  window. `Main.kt` sets AWT's own non-reparenting switch (`awtWMNonReparenting`, what
  `_JAVA_AWT_WM_NONREPARENTING=1` sets) through reflection when it sees `NIRI_SOCKET`, `SWAYSOCK`,
  `HYPRLAND_INSTANCE_SIGNATURE` or `I3SOCK`. Both halves are required: without
  `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED` in `app-desktop/build.gradle.kts`
  (`jvmArgs`, so it is in the packaged launcher too) the reflection fails and the window silently
  regresses. Reparenting desktops are deliberately left alone. Verify with
  `xwininfo`-style geometry: the `Valen` frame, `Content window` and `sun-awt-X11-XCanvasPeer`
  must report the same size after a compositor resize.
- **Never `pkill -f` / `pgrep -f`.** A pattern matches the agent's own shell and has killed the
  session three times. Kill by scanning `/proc/*/cmdline` and matching `argv[0]`:
  `.endswith('/bin/Valen')`, `== '/usr/bin/ffplay'`, or `.endswith('/chrome')` with
  `--user-data-dir=/home/dodo986/.local/share/valen/chrome`.
- **One Valen at a time.** Instances share the Chrome profile (`SingletonLock`), DevTools port 9333
  and the MPRIS bus name. A second instance steals `next` or fails to register MPRIS.
- **Stale Chrome singletons**: only delete `~/.local/share/valen/chrome/Singleton*` when no Chrome
  with that profile is running.
- **Chrome profile is signed in** as the user. Do not clear it; do not log in on their behalf.
- `playerctl -p valen status|position|next|play` is the fastest end-to-end check that playback,
  queue and MPRIS are actually connected.
- MPRIS state is derived from `PlaybackCoordinator.snapshot`, which is updated from the provider's
  `player` flow every 250 ms. If MPRIS says Paused while audio plays, the provider flow is stale.
- Spotify page needs focus emulation (`Emulation.setFocusEmulationEnabled`) or it never fetches
  media; `CdpHost.activate()` does this before a play. Keep it.
- The Gradle/Skiko "version mismatch" warning is benign.
- `--screenshot` needs ~5 s settle; a shorter settle produces half-loaded artwork in the PNG.

---

## 6. Definition of done for a change

1. Compiles for the affected modules (`:core`, `:ui`, `:app-desktop`, and `:app-android` when shared
   code or `host/android` changed).
2. Relevant tests pass (`:core:jvmTest`, and `:ui:jvmTest` for anything under `ui/`); new behavior
   gets a test in `core/src/jvmTest`, or a step in `ui/src/jvmTest/.../ShellSmokeTest.kt` when it
   is a screen or a navigation path.
3. Visually checked with `--screenshot` at 1600×900, 2200×1200 and phone size (390×844) for UI work.
4. No new hardcoded colour, size, curve, font or credential; SPDX headers intact.
5. `TODO.md` risks/log updated when a real constraint was discovered; `README.md` and
   `docs/provider-contract.md` updated when behaviour or the provider contract changed.
6. One `✅` line summarizing the milestone.
