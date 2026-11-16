# HANDOFF — Valen

**As of** 2026-11-16, end of Pass 29 (§0n: Settings in parts, the mini player over artist pages, "Your week"). Before it: Pass 28 (§0m: the Offline list, songs looked up ahead). Before it: Pass 27 (§0l: single-song downloads, faster downloads). Before it: Pass 26 (§0k: this device and downloads on Android). Before it: Pass 25 (§0j: this device, downloads, Discord). Earlier the same day: Pass 23 (Lyprio's endpoint in both builds, AppImage and APK rebuilt).
Before it: Pass 22 (§0g: lyrics). Before it: Pass 21 (§0f: artist pages). Before it: Pass 20 (§0e: battery/lag,
playback that stops, songs kept on disk).
Before it: Pass 19 (§0d, Android plays YouTube Music and SoundCloud natively), Pass 14 (playlists). The passes before it were the
correctness pass (Pass 13), the login/backend audit (Pass 12), the Web Player user-agent pass
(Pass 11), the sign-in window pass (Pass 10), the window-scaling pass (Pass 9), the design rework
(Pass 7) and the AppImage rebuild (Pass 8).
**Next focus:** one signed-in Spotify session to verify playback end to end (§2) and one signed-in
account to verify playlist reading (§3), then the visual follow-ups in §4. Read `AGENTS.md` first; it is the durable contract. This file is only the state
you are inheriting.

Not a git repo. No branch, no commits: every change below is uncommitted files on disk.

---

## 0n. Pass 29 — Settings in parts, the mini player over artist pages, "Your week" (2026-11-16)

Asked for: organise the settings menu (both apps); the music pill (mini player) was missing on an
artist, playlist or single page on Android and on a narrow Linux window; a weekly recap, "visually
beautiful like Mochy Music's", turned on from Settings and watchable any time, that plays the songs
it mentions while it shows them, with great care for design and motion.

- **Settings** — `screens/SettingsScreen.kt` is seven parts (`SettingsPart`: Services, Playback,
  Colour, Your week, Downloads, Discord, About), each with a line of live state. Wide: index beside
  the open part (droplet on the two droplet springs; the part scrolls by itself, its title rises,
  its groups `arrive`). Phone: the parts as one `Group`; a part is its own page with Back, system
  back to the list. The open part is kept in `PageMemory` (`settings:part`, by name). Tokens:
  `Layout.settingsMaxWidth/IndexWidth/IndexRow`.
- **Mini player** — `ValenApp.PhoneApp` shows `Route.Artist` in the wall instead of a
  `FullScreenSheet`, so the mini player and tab bar float over the catalogue's pages (see AGENTS
  §3); sheets opened before the pages step aside (`sheetShown`). Wide and rail windows already kept
  the dock.
- **Your week** — core: `Listened` keeps length/uri/page/first heard; `ListeningLog.daily`
  (`DayListening`: ms, songs, plays, hours, by local day, 63 days); `core/Recap.kt`
  (`WeekRecap`, `ListeningLog.week`); `PlaybackCoordinator.lend/playLent/giveBack/keepLent`;
  `AppSettings.weeklyRecap`. UI: `ui/recap/` (`RecapState`/`LocalRecap`, `WeekStory` host over
  everything in `ValenApp`, `WeekPages`, `WeekCard` on Home and the profile). Tokens: `RecapType`,
  `Motion.Story*`, `Layout.storyDesignWidth/storyAspect`. Design spec "Your week".
- **Dev** — `VALEN_CAPTURE_RECAP=N`, `VALEN_CAPTURE_RECAP_TURN=1`, `VALEN_CAPTURE_SETTINGS=part`.
- **Follow-up, same day** — the card is no longer a setting: it shows by itself on Sunday and
  Monday (`core` `recapDue`, local days), and Settings gained an eighth part, **Test**, whose
  "Your week on Home" switch (`AppSettings.recapAlways`, replacing `weeklyRecap`) shows it every
  day. Settings → Your week keeps "Watch your week". `RecapTest` +1 (the days), the UI click-through
  goes through Test. Packages below predate this follow-up.
- **Packages** — rebuilt after the pass: `Valen-1.0.0-x86_64.AppImage` (115.9 MB; the story rendered
  from the packaged AppImage itself with `--appimage-extract-and-run`) and the release APK
  (10.5 MB, its dex carries the new screens). Neither was installed or run on a phone.

Verified: core `RecapTest` (7: local days and hours, pruning, the whole-log week, an older log's
week with new songs and the week before, a quiet week, lend → play → give back while playing and
while paused, nothing counted or saved while lent); UI click-through (settings parts; the phone
artist page keeps the playing song's mini player through an album and back; Settings → Your week →
Weekly recap → Watch → lent → pause → close → the queue and song given back; at 1600×900 and
390×844). Stills of every page at 390×844, 860×700, 1600×900 and 2200×1200, and frame dumps of the
pour-in and of a page slapped on, with a copy of the owner's listening plus invented per-day hours
in scratch folders (volume 0). **Not verified**: the story on an Android device or the emulator
(compiled only), the sound of the snippets by ear (volume 0 here), Mochy Music's own recap (no
reference could be found, so the story is in Valen's own language: the wall, the pen, paper and
tape), a real week of `daily` data (it starts filling from this build).

## 0m. Pass 28 — the Offline list, songs looked up ahead (2026-11-16)

Asked for: a playlist of every song fully downloaded or fully cached (there only when it has
songs, switchable in Settings), and caching smart enough that a song's start is barely noticed,
without costing much.

- **Offline** — `core/OfflineList.kt` + `PlaylistLibrary.setOffline` + `AppSettings.offlineList`
  (Settings → Downloads → Offline playlist). Built in both apps beside the library. Songs come from
  the listening log (most listened first), the shelf's singles, the library and the queue.
- **Ahead** — `MusicProvider.warm`/`isCached`, `NativePlayback.warm`/`isCached` and shared
  look-ups, `StreamCache.has`, the coordinator's two-song window and `warm` budget,
  `Modifier.tap(onIntent)` + `LocalWarm` on song rows, playlist pages warming Play's and
  Shuffle's first songs (Shuffle's start is now picked when the page opens).
- Tests: core `OfflineListTest` (4: the list follows downloads and cache and the switch; none
  when empty; next prepared and the one after warmed; the warm budget), audio
  `NativePlaybackTest` +1 (a warm-up and a tap share one look-up). Emulator: a SoundCloud song
  fetched ahead whole put "Offline" (1 song, its note, Play/Download, no rename/delete) in the
  library; Settings' switch removed and restored it. Not measured: tap-to-sound times before and
  after on a real network. Note for tests: `advanceUntilIdle` does not run work that lives only in
  `backgroundScope`; use `runCurrent()`, and hand `OfflineList` the test dispatcher.

## 0l. Pass 27 — a song's own Download, faster downloads (2026-11-16)

Asked for: download buttons for single songs in the song menu (desktop and Android), and downloads
sped up as far as is safe.

- **Song menu** — `MenuPages.DownloadRow`: Download → Downloading (Waiting / NN %) → Remove download
  ("On this device"); "Downloaded · With a playlist" when a kept list holds it; "Not found" when no
  copy matched (a press tries again). Not shown for this device's own songs. `OfflineShelf`
  gained `keepSong`/`forgetSong`; `OfflineState` gained `singles`, `fetching`, `waiting`,
  `unavailable`.
- **Speed** — `DiskShelf`: two lanes; lookups paced 0.7–1.4 s across lanes instead of a 1.5–4 s
  pause after every song; 8 MB ranges (`StreamFetcher(rangeBytes)`); a copy already kept is
  aliased with no fetch; a 429/403 rests every lane and drops to one lane for the run.
- Tests: `DiskShelfTest` +2 (single song kept/released around a kept list; at most and exactly two
  requests in flight, a Spotify song whose copy is kept costs no request — the test server needs a
  thread pool, the JDK's default answers one request at a time), UI click-through +1 step (menu →
  Download → Waiting). On the emulator: menu Download of a SoundCloud song → "Downloading 98 %" →
  "Remove download" in ~2 s; Remove deleted the file. Not measured: a timed before/after run of a
  long list against the live services.

## 0k. Pass 26 — this device and downloads on Android (2026-11-16)

Asked for: finish the Android half of Pass 25 (the owner finished Discord themselves).

- **Downloads without FFmpeg** — `libs/audio` `SongFinisher` (probe + re-wrap): `FfmpegFinisher`
  (the desktop's old code, moved) and `PlainFinisher` (pure Kotlin, also the desktop's fallback
  without FFmpeg): `AudioSniff` (container/codec from the first bytes), `OggOpus` (WebM/Matroska
  Opus → Ogg Opus with OpusTags and the cover; EBML reader with all lacings, Ogg pages and CRC,
  granules from each packet's TOC), `Mp4Tags` (`moov/udta/meta/ilst`, chunk offsets moved when moov
  is first), `Id3Tags` (ID3v2.3, UTF-16, APIC). A stream nothing can re-wrap is kept in its own
  container's name (`.webm`, `.ts`), never a wrong one. `DiskShelf` gained `finisher`, `work` (the
  scratch folder) and `onWritten` (the media scanner).
- **Android** — `app-android` `AndroidFinisher` (MediaExtractor → MediaMuxer for HLS AAC, then
  `Mp4Tags`), `AndroidTags` (MediaMetadataRetriever, covers in `cacheDir/covers`),
  `AndroidStorage` (shared `Music/Valen`, checked; Android 10 falls back to app storage),
  `ValenRuntime` builds the shelf (on an IO scope) and `LocalProvider` (ExoPlayer sink).
  `host/android` `DownloadService` (data-sync foreground service + notification while a list runs).
  Manifest: `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` ≤32, `WRITE_EXTERNAL_STORAGE` 29,
  `FOREGROUND_SERVICE_DATA_SYNC`, legacy storage on 10. `core` `downloadableCopy` is shared now.
- **UI** — `rememberLocalPicker(onPicked, onAccess)` on Android: permission, then the Files chooser
  (`OpenDocument`/`OpenDocumentTree`), mapped to a path by `PhoneFiles` (external-storage ids, media
  and downloads providers); an unreadable list is copied as an absolute-path m3u8. `openFolder` is
  now `rememberFolderOpener(path)` (null where it cannot open: Android app storage). The playlist
  page's buttons wrap (a phone cut Download off), paths read `Music/Valen`, and Settings counts
  kept *files* (`OfflineState.files`; it counted a Spotify song and its copy twice).

Verified on the API 35 emulator: the permission question, Files chooser → `Music/Shore/Shore
mix.m3u8` imported (2 device songs with tags and embedded cover, the missing one matched on a
service), Choose a folder (2 songs), a device song playing through ExoPlayer with effects and the
lock screen's tags, Download of a list with a Spotify song → a real YouTube Music stream kept as
`Music/Valen/Daft Punk/Discovery/Something About Us.opus` (Ogg Opus 145 kb/s, tags, cover,
decodes cleanly), the song playing in airplane mode, a SoundCloud song added later fetched by
itself (MP3 with ID3 + cover), the `.m3u8`, the media scanner, Settings → Open in the Files app.
Tests: `PlainFinisherTest` (5, each read back by FFmpeg), a phone-layout `DiskShelfTest`; audio
62, core 136, native 5, desktop 28. **Not verified on a device**: SoundCloud HLS AAC through
MediaMuxer, Android 10, the notification seen by eye (the download finished in 5 s).

## 0j. Pass 25 — this device, downloads, Discord (2026-11-16)

Asked for: import local playlists from the device and mix them with cloud ones; download playlists
at the highest quality without being rate-limited by Spotify; a configurable Discord presence on
Linux that makes people curious.

- **This device** — `ProviderId.LOCAL` (+ `ProviderId.services` for the three services),
  `core/LocalFiles.kt` (M3U/M3U8/PLS/XSPF reader, `file://` and relative paths, local paths as
  import links), `providers/native` `LocalProvider` (plays files through ffplay, effects included;
  reads a playlist file or a folder, natural order, missing files `blocked` → matched on services;
  lists playlist files under `~/Music`; searches the library's local songs) and `FfprobeTags`
  (tags, length, embedded cover into `~/.cache/valen/covers`, else `cover.jpg`/`folder.jpg`).
  Import screen: "From this device" (Choose a playlist / Choose a folder via zenity/kdialog, and
  the lists found under ~/Music). Search has a Device chip on the desktop.
- **Downloads** — `core/Offline.kt` (`OfflineShelf`, `OfflineState`), coordinator routing of kept
  songs through the device adapter, `libs/audio` `DiskShelf` + `StreamFetcher`. Playlist page:
  Download → Downloading (progress rail, current song, rest notice) → Downloaded; Remove download
  asks once. Settings → Downloads (folder, Open, count).
- **Discord** — `core/Presence.kt`, `host/desktop` `DiscordIpc` + `DiscordPresence`, Settings →
  Discord (on, cover, time bar, the line being sung, where it plays from, song button). The card:
  title (links to the song), artist or "♪ the sung line", cover + Valen mark, "Album · from
  Spotify" / "kept on this device", "Valen · 3 of 41 in the queue", time bar. It is named after
  Valen's own Discord application (`DiscordPresence.DEFAULT_APP_ID`), so users set nothing up;
  `VALEN_DISCORD_APP_ID` is the only override. The mark is the uploaded `valen` asset, drawn as
  Discord's small image over the cover; the card's second button (Get Valen) opens the repository.

Verified: core 136 tests, `DiskShelfTest` (a real Opus file served by a local server, a 429
rested through, tags and cover in place, the m3u8, forget deletes files and folders),
`LocalProviderTest` (FFmpeg-made files), `DiscordPresenceTest` (activity JSON, sung line, a real
Unix-socket handshake and `SET_ACTIVITY` frame), UI click-through (Discord settings, Download).
**Not verified**: a live download from YouTube Music/SoundCloud (network), the card in a real
Discord client on another account (the owner's own works; the application is Valen's, so every copy
sends it). Android followed in Pass 26 (§0k).

## 0i. Pass 24 — the lyrics crash, a phone build, less memory (2026-11-16)

Asked for: optimisation. Android lagged a lot and opening the lyrics crashed it; the desktop's RAM
use was high.

- **The crash** (reproduced on the emulator, logcat): `LyricsFormats`' `LEGACY_VOICE`
  `\{agent:([^}]+)}` is fine on the JVM and a `PatternSyntaxException` in Android's ICU, inside
  the object's initialiser, so the first Lyprio answer threw `ExceptionInInitializerError` on the
  main thread. Braces escaped there and in `LyprioLyrics.MLX_JSON_ARTIST`; `LyricsBook` catches any
  `Throwable` from a source (a failed source, never a crash) and does its store read, parse and
  sources on `Dispatchers.Default` instead of the composition's main thread. After the fix the
  emulator shows Lyprio's word-timed lyrics with marks and keeps running.
- **The lag.** Only a debug APK existed: debuggable, unoptimised Compose. New `release` build type
  (R8 with `-dontobfuscate` and Valen/NewPipe/Rhino kept whole, `proguard-rules.pro`; resources
  shrunk; debug-signed; `profileinstaller` so the libraries' baseline profiles apply on a
  sideload). `core`, `ui`, `host/android` now compile against SDK 37 (the release AAR check
  demanded it). Lyrics drawing: no `saveLayer` per line, gradient-brush soft edge, words at one
  height in one draw, geometry kept per layout and built lazily (see AGENTS §3 "The lyrics draw
  without layers"). Debug build on the emulator before the drawing change, lyrics playing:
  ~20 ms a frame (p50), ~25 % CPU.
- **Desktop memory.** JVM flags in `app-desktop` (G1, 1 GB cap, idle heap returned, 2 GC
  threads, string dedup) and `MALLOC_ARENA_MAX=2` in the AppImage's `AppRun`. Same capture (song +
  lyrics, 60 s): 455 MB → 396 MB with the flags, 367 MB with the arena cap; the heap no longer
  stays at its peak. The capture loop now closes each rendered frame (it leaked ~25 MB/s of
  native images, which made any `--screenshot` memory reading meaningless). Spotify's headless
  Helium is the larger share on a signed-in machine (~1.5 GB, of which 230 MB was an extension
  process in the private profile); its flags are unchanged (see TODO risks).

- **A frame loop, found while measuring.** With the lyrics hidden the stage still drew 601 frames
  in 10 s, and so did the search page: `PlayingBars` (the playing row's equaliser) was an infinite
  transition read in composition, and the page stays composed under the stage. It steps on
  `Motion.PlayingBarsStepMs` (110 ms) now, read in the draw.

Verified (emulator, release APK, song playing, media volume 0):

| Screen | Before (debug) | After (release) |
| --- | --- | --- |
| Stage, lyrics hidden | 601 frames / 10 s, ~25 % CPU | 83 frames / 10 s, 7 % CPU |
| Search page (playing row) | 601 frames / 10 s | 66 frames / 10 s, 4 % CPU |
| Stage, word-timed lyrics | ~25 % CPU, p50 20 ms | 16 % CPU, p50 16 ms |
| App memory (PSS) | 247 MB | 144 MB |

The release APK is 10 MB (debug 85 MB): welcome, search (NewPipe after R8), YouTube Music
playback and Lyprio lyrics with marks all work. Tests green: `:core:jvmTest` 129,
`:providers:lyrics:test` 11, `:ui:jvmTest` 16. Desktop stills at 1600×900, 2200×1200 and 390×844
and a frame dump across a line change look right (soft edge, held-note crest, marks, the follow
spring). The rebuilt AppImage runs with `MALLOC_ARENA_MAX=2` and holds ~379 MB from 20 s to 60 s.

**Not verified**: a real phone (the emulator's GPU is the host's), Spotify's WebView in the
release build (needs a signed-in account), the Chrome flags question in TODO risks.

## 0h. Pass 23 — Lyprio's endpoint in both builds (2026-11-16)

Asked for: reuse Metrolist's lyrics API; they publish no key or address for other projects, so use
theirs, then rebuild the AppImage and the APK.

Metrolist bakes `LYPRIO_BASE_URL`/`LYPRIO_HMAC_KEY` into every release from CI secrets. The pair was
read out of their published v14.0.0 APK (the dex string pool; the address is
`https://ly.meowery.eu`, the key a base64 32-byte HMAC key) and checked with a signed protocol-2
search before wiring it in. It now lives in the git-ignored `local.properties`, never in the source:
`app-android`'s existing `BuildConfig` fields read it, and `app-desktop` gained the same helper — a
`generateLyprioConfig` task writes `LyprioBuild` into `build/generated/lyprio/kotlin`, and
`desktopLyrics` passes it as `LyricsSetup.lyprio(builtUrl = …, builtKey = …)`. The environment and
`~/.config/valen/lyprio.json` still win over the build.

Verified from the packaged desktop binary (a scratch `XDG_CONFIG_HOME` with volume 0 and lyrics on):
`--play 'ytmusic:get lucky daft punk'` drew word-timed lyrics on the stage and kept them, with word
timings, in the cache. A random SoundCloud upload ("Get Lucky … — DJ KB") is answered by Lyprio with
its own "none of its sources answered", which the pane shows. `providers/lyrics:test` still green.
AppImage written (`Valen-1.0.0-x86_64.AppImage`); APK rebuilt and its dex carries both strings.
**Not verified**: lyrics on Android at runtime (the build only).

## 0g. Pass 22 — lyrics, with Lyprio and the pen (2026-11-16)

Asked for: Lyprio in Valen the way Metrolist does lyrics, with a design that matches the app; when
the words are timed one by one, some get scribbled over, crossed out and so on, in many variants.

- **Core** — `core/Lyrics.kt` (model, `LyricsSource`, `LyricsBook` on `PlaybackCoordinator.lyrics`,
  `Track.lyricsQuery()` cleaning "(Official Video)"/" - Topic"/"feat."), `core/LyricsFormats.kt`
  (TTML with backing vocals and voices, MLX text and v2 JSON, LRC with `<mm:ss.xx>` word tags and
  offsets, plain; a small XML reader), `AppSettings.lyrics` + `setLyricsShown`.
- **Sources** — `providers/lyrics` (new JVM module, both apps): `LyprioLyrics` ports Metrolist's
  client (signed POST, protocol 2 polling ≤20 s with retry delays + jitter, fall back to protocol 1
  for 10 min, second search with `allSources`, artist check, sub-source credit "Lyprio ·
  Musixmatch"); `LrclibLyrics` (exact get, then search by artist and length); `DiskLyricsStore`
  (`~/.cache/valen/lyrics`, Android `cacheDir/lyrics`). Lyprio's address and key: `VALEN_LYPRIO_URL`
  / `VALEN_LYPRIO_KEY` (or `LYPRIO_BASE_URL` / `LYPRIO_HMAC_KEY`), `~/.config/valen/lyprio.json`, or
  Android build properties (`BuildConfig.LYPRIO_*`, from a Gradle property, env or `local.properties`).
  The desktop build got the same at build time in Pass 23, so both apps carry Metrolist's endpoint.
- **UI** — `ui/lyrics/`: `LyricsPane` (looking blocks, none/instrumental/failed sentences, untimed
  page, timed lines in a custom layout: anchor at 30%, follow spring with a per-line cascade,
  scroll by hand then resume after 2.6 s, tap to seek, pauses ≥4.5 s as three filling dabs, rows
  rise in), `SungText` (one masked paragraph per line: soft sung edge per word, 0.07em lift, a
  crest through held notes, marks over or under), `Marks.kt` (which words: ~1 in 6, repeats crossed
  out, held notes circled/underlined while held, shouts burst), `Scribbles.kt` (11 marks: strike,
  double, X, scratch, hatch, zigzag, box, loop, underline with drip, swipe, burst; em-sized, seeded
  jitter, overspray), `LyricsClock` (frames only while word-timed lyrics show and play). The stage:
  lyrics button beside Sound; wide — cover sits back to 80%, a 680dp column of title, words, rail,
  transport; phone — words where the cover was, under a cover/title/heart line. Tokens: `LyricType`,
  Motion `lyricsFollow`, `LyricsCascade*`, `LyricsResumeMs`, `LyricLineMs`, `LyricRestScale`,
  `WordLiftMs`, `Scribble*Ms`. Design spec "Lyrics" section.
- **Dev** — `VALEN_CAPTURE_LYRICS=<file>`, `VALEN_CAPTURE_SEEK=<ms>`, `VALEN_CAPTURE_WORDS=1`.
- **Follow-up, same day** — Spotify's own lyrics as the second source (`core` `LyricsCatalog`,
  `ServiceLyrics`, `LyricsSkipped`; `providers/spotify/SpotifyLyrics.kt`: the Spotify copy found by
  recording and length, "♪" lines turned into the end of the line before, syllables kept as word
  timing only when they spell the line, credit "Spotify · Musixmatch"), asked only signed in with
  the Web Player already up, through the attached adapter on the desktop. Settings → Lyrics: Show
  on the stage, Pen marks, Moving words (`AppSettings.lyricsMarks`, `lyricsMotion`). Tests: core
  +2, `SpotifyLyricsTest` 3, UI +1 (the three switches). **Not verified**: Spotify's lyrics against
  a real signed-in account (none here) — in particular whether Orpheon's `syllables[].words` reading
  matches what Spotify sends for syllable-synced songs.

Verified: core lyrics tests (19) and the whole core suite, `:providers:lyrics:test` (11, a local
server standing in for Lyprio v2/v1 and LRCLIB, the signature, the setup rules, the store), a UI
click-through (show lyrics, tap a line → seek to it, hide; the "none" sentence) at 1600×900 and
390×844, stills at 1600×900, 2200×1200 and 390×844 with an invented word-timed fixture, frame dumps
of a line change and of the swap, and a live LRCLIB lookup for a real SoundCloud song (64 lines,
cached). Fixed on the way: a lifted word on a wrapped line drew the row above it twice (clips are
now the row's band); the swap now lets the old layout go before the new rises. **Not verified**:
a live Lyprio search on Android, Android on a device or the emulator, a real mouse on the
pane (wheel, drag, hover), frame cost on a weak GPU.

## 0f. Pass 21 — artist pages (2026-11-15)

Artist, release and service-playlist pages, merged from Spotify and YouTube Music
(`core/Artists.kt`, `ui/artist/`). Spotify's catalogue gained the artist's playlists
("featuring"/"discovered on", from the same `queryArtistOverview`); YouTube Music's artist parser
now reads shelves by their heading (a single's card often shows only its year), its playlists and
its subscriber count (`YtmParse.count`). Verified against YouTube Music live (`LiveArtistTest`,
`VALEN_LIVE=1`: Daft Punk → 5 top songs, 20 typed releases, 20 playlists, 10 related, bio, 7.2M
subscribers, an album's full track list); Spotify's side was not exercised live here (it needs the
signed-in Web Player). `ArtistDirectoryTest` (7) pins the merge; `ShellSmokeTest` opens an artist,
its album and goes back at desktop and phone sizes (screens in `ui/build/screens`).

Before Pass 21, same day: Spotify on Android was fixed by `SpotifyRelay` (see AGENTS §5), and the
desktop prefers an unsandboxed browser (a snap/Flatpak keeps its profile in its own folder). A
NixOS tester still cannot play Spotify on the desktop; the owner plans a Nix flake for that.

## 0e. Pass 20 — lighter, steadier, kept on disk (2026-11-15)

The user reported, on Android: heavy and laggy with poor battery, "URL not accepted" on every
YouTube play, and a Spotify tap that "shows another but plays neither"; on both platforms: music
that sometimes stops, repeat that sometimes moves on anyway; and asked for heavy disk caching.

**"URL not accepted"** is NewPipe upper-casing the host in the device's language: in Turkish
"music" → "MUSİC". Reproduced on the emulator with the app in tr-TR and fixed by building the
extractor from the id (`fromId`). **The Spotify tap** replaced the queue before finding that a
signed-out Spotify cannot play; now the same recording plays from YouTube Music/SoundCloud (or the
reason is said and the queue is left alone), and a signed-out Spotify is not searched unless chosen.

**Battery and lag.** Every screen drew at the display's full rate while a song played (60 fps on
the emulator, 20–27 % of a core) because of a `withFrameNanos` loop in the wash and an infinite
transition for the glass orbit, each frame also re-blurring the glass over the page; and every
screen collected the whole snapshot, so the 4 Hz position recomposed them all. Now: the wash steps
on a 200 ms timer, the orbit goes round twice per song and rests (own layer), screens read
`nowPlaying`, positions are read inside the three widgets that show them. Measured on the emulator
while playing: about 27 fps for the first seconds of a song, then 4 fps; app CPU 6–18 % → about
3–4 % once the orbit rests; Battery Saver also draws the glass solid. Desktop steady state: the
stage 0 of 20 frames redrawn, Home 8 of 20 (the 5 fps wash). The Spotify Web Player no longer loads
at boot on Android (renderer 582 MB → 104 MB); it loads for a signed-in account 4 s after start or
on first use. Android's session no longer ends between two songs (null snapshot), which with the
screen off could stop the music for good; the wake lock is held while the next song starts.

**Music that stops / repeat that moves on** (found by a read-only audit, then fixed and tested):
a failed song never moved on; recovery usually gave up after one try (each reopen reset `started`);
a stall after the last try buffered forever; Play did nothing after an error or the end; a failure
or silent reopen at the very end was an error; a cached address that had expired was never looked
up again; Spotify reported its end once the page was already on another song, so the end was
dropped and the page's own autoplay continued whatever the repeat setting; the sleep timer's "end
of song" with repeat-one parked on the next song; settings were read-modify-write across threads.
Events now carry the song's key, failures move on (five in a row stop), NativePlayback keeps its own
"sounded" flag, retries lookups, forgets drops after 10 s of steady sound, and publishes under one
lock with the switch to a new song.

**Songs on disk.** `StreamCache`: `DiskAudioCache` on the desktop (whole files, 3 GB), ExoPlayer's
`SimpleCache` on Android (1.5 GB, kept as it streams, next song fetched ahead in 1 MB ranges). A
kept song plays with no lookup: on the emulator a prefetched next song was ready 31 ms after Next
with no network; a live desktop check kept a real song (5.2 MB) in 1.2 s and ffprobe read its full
321 s. `LiveCacheTest` (only with `VALEN_LIVE=1`) repeats that check.

Follow-up the same day: Spotify on Android "still doesn't work". Two faults in this pass's lazy
loading and one older one: `SpotifyProvider` refused before any bridge command was sent, so the
page that loads on first command never loaded ("Spotify is still starting" forever) — it now wakes
the host and waits (25/30 s); `ensureLoaded` refused after a sign-in page that ended FAILED; and the
WebView claimed Chrome 133 whatever its engine was (now the engine's real version). Verified on the
emulator signed out: a Spotify-only search loads the Web Player and returns results, bridge up,
Widevine available, page focused and visible. Signed-in playback is still the user's to test (the
agent does not sign in to their account). Android now also asks for the screen's fastest refresh
rate, and the glass orbit follows the display's frames during its laps.

Second follow-up: with the user signed in on the emulator, Spotify still did not play — and
Orpheon plays on their phone. Valen had dropped Orpheon's `ControlRelay`: WebView requests carry
`X-Requested-With`, and Spotify acknowledges Connect commands that carry it but never delivers
them. `SpotifyRelay` now sends those requests from the app; verified on the emulator with the
user's account ("Shotgun" playing from Spotify, `relay POST player/command -> 200`). A desktop
tester on a snap Chromium lost the sign-in at every start (the snap cannot write the hidden profile
folder): sandboxed browsers are now the last choice and keep the profile in their own folder.

Not done: desktop media controls (MPRIS) still report repeat/shuffle as off; a real phone was not used (emulator only); HLS
SoundCloud streams are not kept on Android.

## 0d. Pass 19 — the backend, and Android playing natively (2026-11-15)

**Spotify on Android.** The "connected before I did anything" report did not reproduce on a fresh
install of this build: the session is the `sp_dc` cookie and stays "Not connected" until a sign-in.
What did break Spotify for a signed-out person was search picking the Spotify copy of a song: it is
now ranked after a playable copy (`playsSignedOut`/`canPlayNow`), skipped when the queue moves, and a
tap says "Sign in to Spotify to play this".

**The notebook on Spotify songs** is dimmed and locked behind a taped note that says why, with "Play
it with effects" (`PlaybackCoordinator.playWithEffects` swaps in the same recording from a service
that takes effects, at the same place). The title toggle reads "Off for Spotify"; the menu row and
the stage's Sound button say it too.

**Coordinator.** A crowd stress test (`PlaybackStressTest`: 12 rounds × 300 interleaved commands,
plus late track ends) found two services sounding at once and a late `TrackEnded` skipping the next
song. Fixed by the decide-under-lock / play-outside model in AGENTS §3; removing the playing row now
starts or parks its successor, and clearing the queue silences. The restored song's stream is looked
up 1.5 s after start (`RESTORE_PREPARE_MS`).

**Native playback.** `FfplayPlayer`: one mutex around every op (a restart raced a seek and left two
ffplay processes), exit 0 is an end only after sound came out (ffplay exits 0 when a stream will not
open, which silently skipped songs), the error text is read from the status line, and a stall is
`aq=0KB` held for 12 s (its clock runs on over silence — the cause of the stall test failures noted
below, which pass now). `NativePlayback`: an address cache with `prepare`, a fresh resolve when a kept
address will not open, recovery of drops, stalls and early ends where they were (3 tries), reopening
after a long pause, Play retrying a failed song.

**Android plays YouTube Music and SoundCloud natively.** On the emulator the YouTube web player in
the hidden page stopped fetching after the first 50–70 s of every song (state "buffering", no media
requests, even after a seek); its page also never became ready because the page script announced
with its first injection's number after an in-page navigation (fixed in both page scripts). Android
now builds the same `providers/native` adapters as the desktop with `ExoAudioPlayer` (media3 1.11.1,
audio only, OkHttp data source, HLS module, audio focus, 1 s start / 2 min ahead buffering) and the
effects as `SoundDsp` inside ExoPlayer's audio chain (the Astra parametric EQ included, as the same RBJ
biquads with its own preamp). Measured on the emulator: YouTube Music plays
past 100 s at real time; SoundCloud HLS plays; Nightcore advances 12.6 s per 10 s; with the stream
already prepared, tap → stream open 55 ms and tap → sound ≈3 s (the CDN's first bytes); playback in
the background ≈3 % of one emulator core; `SoundDsp` with every effect on is ≈1 % of real time on
the desktop JVM. The media session was rewritten on every 250 ms tick (≈40 updates per 10 s); it now
updates only when its own clock could not have predicted the change (0 per 10 s while playing), and
carries the effects' speed. SoundCloud tracks offered only as encrypted HLS (DRM, e.g. ODESZA "A
Moment Apart") are left out of search and say "copy-protected" when played anyway.

**Builds.** See the end of this pass's report for the AppImage and APK paths and hashes.

Not verified: a real phone (only the API 35 emulator, silent), Spotify signed in on Android, the
YouTube Music/SoundCloud account playlists on Android (they read the sign-in pages' cookies; no
account here), battery over hours, and gapless transitions (each song opens its own stream).

## Playback interruptions follow-up (2026-11-15)

The ffplay backend used to restart on every volume change; it now coalesces volume drags with
pending effect changes, but still restarts and can audibly cut out. The old rate is used to measure
position until a new effect graph actually starts. Truly seamless changes remain open.

An early clean exit from a YouTube stream with a known resolved duration now reopens at the last
heard position at most twice, then reports a playback error instead of advancing/repeating the queue.
This uses the existing URL, so it does not repair an expired URL; streams without a trustworthy
duration or usable clock are not protected. SoundCloud does not opt in because its metadata can
describe a full track while its stream is a 30-second preview. Real intermittent YouTube failures
have not been reproduced; the check is a safeguard, not a confirmed diagnosis.

Core tests and 24 focused audio tests pass (including real ffplay truncation, preview, volume,
speed, seek and effects checks); desktop compiles. The full audio suite is not green because of
the pre-existing stall test below. Rebuilt `app-desktop/build/compose/binaries/main/app/Valen-1.0.0-x86_64.AppImage`
(SHA-256 `362a2141b15c622c78921a8b54b5b35f9016a90169a5d0f08907e2dc2ff64d01`); not yet tested
interactively or with real YouTube playback. The older running Valen instance was not stopped.

The existing FIFO stall test has failed at least three times. A diagnostic with this ffplay found
its audio clock can continue moving after input dries up, so the clock-only detector cannot be
relied upon. Stall recovery is not implemented; do not confuse it with the early-exit safeguard.

## Astra parametric EQ backend (2026-11-15)

Added `ParametricEq.kt`: Astra's five filter types, 20-band limits, adjustable shelf/pass Q,
built-in presets and JSON/AutoEQ parsing, plus saved presets and per-output profile storage.
`SoundEffects.parametricEq` is independent of the existing fixed EQ and effects, participates in
response and persistence, and Valen presets preserve it. FFmpeg uses explicit RBJ biquads;
Android's Web Audio uses IIR nodes and fades between chains, retaining native Astra shelf Q.
No notebook controls, file picker, or automatic output-device profile switching are wired yet.
The native desktop restart and Spotify DRM limitations remain; Android device playback unverified.
Core tests, focused FFmpeg tests, desktop/Android compilation and 18 headless Chrome checks pass;
the full audio suite failed its ffplay stall-detection test twice ("never noticed the stall").

## 0c. Pass 18 — saved lists take songs; Sound, as a notebook (2026-11-15)

Asked for: adding songs to saved playlists like Spotify's own, and an equalizer with a new design —
a notebook with the cover and a few facts, taking notes of the effects as they are set, with
animations — working on Android and Linux, high quality, detailed effects and presets.

- **Saved lists** — `Playlist.added/removed/renamed`, laid back over the source's songs by
  `PlaylistLibrary.add` (refresh and Make again both go through it); `takesSongs` (all but Liked)
  replaces `isOwn` as the add target in the menu and the playlist page; nothing goes to the service.
- **Model** — `core/Sound.kt`: `SoundEffects` (10 bands, preamp, bass/treble shelves, low cut,
  muffle, speed, pitch, turntable, reverb amount/room/brightness, echo amount/time/repeats, width,
  8D, even out), `SoundPresets` (16), `notes()` (plain words), `responseDb` (RBJ biquads, the curve
  drawn). `AppSettings.sound`; `PlaybackCoordinator.sound/setEffects/usePreset/effectSupport`
  (applied after 90ms of stillness, before every play; the position runs at the speed);
  `MusicProvider.effectSupport/setEffects`.
- **Linux** — `libs/audio/FfmpegEffects`: the `-af` graph (aresample → time → preamp → cuts,
  shelves, bands → compressor → echo → reverb by `afir` with a generated room WAV in
  `$XDG_CACHE_HOME/valen/rooms` beside the dry sound → width → `apulsator` → `alimiter`), rubberband
  when present. `FfplayPlayer` restarts in place 220ms after a change; native adapters report Full.
- **Android** — `core/SoundWebAudio`: the page engine (built-in nodes only; CORS reload before
  routing, never routes a tainted or not-yet-running element), appended to both page scripts;
  `YtmusicProvider`/`SoundcloudProvider` report Full and re-apply on each page load; the SoundCloud
  page asks the engine whether its next source needs CORS. Spotify stays None (DRM audio).
- **UI** — `ui/sound/`: `SoundScreen` (spread on wide, stacked on a phone), `Notebook.kt` (ruled
  and dot pages, spiral, tabs, `PageTurn`), `Notes.kt` (written / struck / folded), `SoundControls.kt`
  (`PencilSlider`, `Ruler`, `EqSketch`, `PresetSticker`). `Route.Sound` (page on wide, sheet on
  phone), the stage's equalizer button, Sound in the player's menu. Motion tokens: `WriteLetterMs`,
  `WriteMaxMs`, `StrikeMs`, `PageTurnMs`, `NoteStaggerMs`, `NoteSettleMs`, `StruckHoldMs`.
- **Dev** — `VALEN_CAPTURE_TAB/TURN/SOUND` (AGENTS §2), `tools/sound/check-webaudio.mjs` + `serve.py`.

Verified: core (library overlay, effects, presets, notes, curve accuracy), `FfmpegEffectsTest` on
FFmpeg 9.0.2 (every preset runs; +6 dB band measures +6.0; ±12 semitones, half speed, turntable;
the fallback without rubberband; the limiter; a room in a folder named with the graph's own
characters), the Chrome check of the page engine (10/10), a live desktop run (ffplay started with
the graph, the room written once), UI click-through (preset → notes, tab turn, bass slider → note,
off; phone via the menu), stills at 1600×900, 1400×800, 390×844, frame dumps of the opening notes,
the rewrite and the page turn, 0 of 20 frames still drawing once settled. **Not verified**: on an
Android device (see TODO risks), real listening (all checks are measurements), the SoundCloud
progressive CDN's CORS.

## 0b. Pass 17 — new playlists, Liked songs, one song menu (2026-11-15)

Asked for: making a normal playlist (not a mix), a menu like Mochy's player menu, and the core
features behind it, matching the frontend's design and motion, on Android and Linux.

- **Core** — `PlaylistLibrary.create`, `addTracks` (skips songs already in), `removeTrack`,
  `toggleLiked` ("Liked songs", id `liked`, newest first, made on the first like and removed with its
  last song); `Playlist.isOwn` (no source, not a mix, not Liked) is the only kind songs are added to.
  `PlaybackCoordinator.playNext`, `startRadio` (plays now; `SmartMixer` with the artist as the seed
  fills the queue after it and confirms "Radio from …"), `sleepIn(min)` / `sleepAtEndOfSong()` /
  `cancelSleep()` (`sleep: StateFlow<SleepTimer?>`; a timed one pauses on the 250 ms tick, the
  end-of-song one parks the next song paused at 0:00 through the same path a restored session uses),
  `confirm()` + `noticeIsConfirmation` (toasts that leave by themselves were keyed on the literal
  "Added to queue" before). `Track.link` (SoundCloud permalinks, both adapters) and `Track.webLink()`.
  `AppSettings.keepScreenOn`. The coordinator takes a `clock` for the timer's tests.
- **Menu** (`ui/menu/`) — `LocalSongMenu`/`SongMenuState`, opened by `MoreButton` (stage corner,
  dock, every `ResultRow`), right click (`songMenuOnRightClick`: rows, queue rows, shelf covers) or a
  long press (`tap(onLongClick = …)`, also the mini player). `SongMenuHost` draws it as chrome: on a
  wide window under `LocalBackdrop` beside the dock, as glass that pours out of the anchor (reveal by a
  layer clip on two springs, never a size animation); on a phone above the sheets, as an opaque sheet
  (glass there would see the Home page, not the stage sheet). Pages: Main (heart, four tiles, rows),
  Add to playlist (new list typed in the row; ring → tick), Sleep timer (draining ring, countdown).
  `SystemBack` (expect/actual) closes it on Android; `MainActivity` keeps the sign-in's back on top.
- **Playlists** — New playlist (filled button) slaps a taped blank sleeve onto the shelf, named in
  place (`NameField`), then opens the empty list, whose empty state leads into its own search with +
  that turns into a tick. Own lists: pencil to rename in place, Shuffle beside Play, ⋯ with "Remove
  from this playlist". `PlaylistCover` everywhere a list is shown (Liked heart; four-cover mosaic).
  The shelf's sleeve-in-progress is kept in `PageMemory` (`playlists.creating`).
- **Stage** — ⋯ top right, `LiquidHeart` at the end of the words (keyed by song, so it does not
  replay on a change), `SleepChip` above Up next, `KeepScreenOn` while shown.
- **Dev** — `VALEN_CAPTURE_MENU=main|playlist|sleep`, `VALEN_CAPTURE_PLAYLIST=ID|new` (AGENTS §2).
  `ValenApp` takes `songMenu`/`pageMemory` so the capture run can drive them.

Verified: `:core:jvmTest` (coordinator 25, library 9), `:ui:jvmTest` (9, two new click-throughs: row
⋯ → Play next, dock ⋯ → like, new list from the menu, sleep timer set/off, close; New playlist on the
shelf → add two songs from its search → rename; phone long press → sheet, stage ⋯ → sheet over the
stage), `:providers:soundcloud:test`, `:libs:audio:test`, `:app-android:compileDebugKotlin`, the
distributable. Stills at 1600×900, 1000×600, 1100×700 and 390×844; a frame dump of the pour (it
found the `drawGlassLight` ghost, AGENTS §4). **Not verified**: anything on an Android device (share
sheet, keep screen on, back order, drag-to-close), a real mouse on the dropdown, radio with real
services beyond SoundCloud. **Not built**: playback speed, equalizer, lyrics, downloads, output device.

## 0a. Pass 16 — shuffle, repeat, remembered places, resume (2026-11-15)

- **Shuffle / repeat** live in core (`PlaybackCoordinator.setShuffle`, `cycleRepeat`; `AppSettings.shuffle`,
  `repeat: RepeatMode`). Shuffle keeps the playing song first and the rest shuffled; off restores the list's
  own order (songs added meanwhile at the end). Repeat one replays only on a natural end; Next still moves on.
  Repeat all wraps at both ends. Buttons: `TransportBar(modes = PlayModes(...))` on both stages and on the
  dock when it is ≥760dp wide.
- **Resume**: `AppSettings.session` (`SavedSession`: queue, index, position, source, pre-shuffle order) is
  written on every queue/song change, on pause, every 10 s while playing, and on desktop close / Android
  `onStop`. The next start shows that song paused; Play starts it at the saved position (`resumeAtMs`).
- **Remembered places**: `components/PageMemory.kt` (`LocalPageMemory`, provided above the width switch)
  holds each page's `ScrollState`/`LazyListState` and the open playlist id. Scrolling pages use
  `ScrollPage(key)`; the playlist detail (`playlist:<id>`) and queue use `rememberPageList`. Search keys
  its scroll by query. Desktop `PageScrollbar` is Compose's `VerticalScrollbar`; Android draws none.
- Tests: 3 new in `PlaybackCoordinatorTest`; `ShellSmokeTest` presses the dock's shuffle/repeat and checks a
  playlist stays open across a page change. **Not verified**: resume against a real Spotify play (startMs
  passed to the Web Player), the scrollbar drag with a real mouse.

## 0. Pass 15 — liquid glass, motion and the street layer (2026-11-14)

The owner asked for liquid glass on the player and side panel, much more motion, and a restrained
street/graffiti feel built on their own tag (`docs/tag-source.png`). A first attempt that kept the
old layout with subtle glass and a graffiti font was rejected as generic; what is in now:

- **Liquid glass** (`components/Glass.kt`, `GlassBlur.{jvm,android}.kt`): the pages are recorded
  into a `Backdrop` by `ValenApp`'s `Wall`; `GlassSurface` draws its piece blurred, saturated and
  bent at the rim by `LensShader` (SkSL desktop / AGSL Android 13+), with rim light, a pointer
  glint, a light sweep and a jelly gulp on a new song, and light orbiting the rim while playing.
  Side panel, dock, mini player, tab bar and toasts use it. Droplets mark the current place, with
  two-spring ends (stretch then gather); the side panel has a hover lens.
- **The tag**: traced to 18 ordered strokes (`TagStrokes.kt`, tracer kept out of the repo) and
  written on by `Tag` with speckled mist and drips; rewrites when pressed; V alone on the rail;
  small on phone Home. The Sedgwick font tried first was removed.
- **Motion**: paint-pour page transition with a wet band in the cover's colour; staggered `rise`;
  `KineticText` letter rise on titles with a marker underline; player blooms from a drop; squeeze
  presses; play/pause morph; pasted + taped covers that lean to the pointer; stage cover breathes
  with play/pause; marker swipe behind the playing row.
- **Colour** (second feedback round: "avoid the neon look, colours like Material 3, sweet and just
  one colour"): `theme/Tonal.kt` builds a Material-3-style tonal scheme in OKLCH from one seed —
  the playing cover's accent, lavender `#6750A4` with nothing playing — and `ValenColors` reads it
  (snapshot state, eased over 600ms). Surfaces are dark tones of the hue (no longer `#000`), the
  filled button/chips/rails/marks are the pastel primary with dark ink, selection is the container
  tone, the wash is one hue at three depths (the left/right-half hues no longer reach the screen).
  The tag's glow halo and the saturated mark colour were removed; rim light was halved.
- **Third round** ("black and pink by default, remove the services list from the sidebar, more
  varied animations, a profile page with a 3D Japanese-style TV, a banner and a picture, then
  stats"): black-and-pink default with Settings → Colour (`AppSettings.coverColour`); the sidebar's
  services list is gone (a profile chip sits above Settings; phone Home has a profile button);
  each place has its own page arrival (`Arrivals.kt`) and items arrive three ways; `Route.Profile`
  with the pre-rendered 3D set (`tools/tv/render_tv.py`, 25 angles × 2 layers, 860 KB), the
  system picture picker on both platforms, a centred editable name, and listening stats
  (`core/Listening.kt`, counted per tick, `listening.json`). A first flat, Japanese-labelled TV
  was rejected and replaced. Tests: core 66, ui 4 (a profile step was added), Android compiles.
  **Fixed after**: clicking the set crashed the app (SIGABRT: AWT's FileDialog loaded GTK 3,
  whose accessibility bridge aborted in `g_bus_get_sync`); the desktop chooser is now `zenity`/
  `kdialog` in its own process (AGENTS §5). **Not verified**: the pickers in a real window/device
  (zenity and the Android photo picker were never opened here), the set's pointer turning with a real mouse, stats over a real
  listening session (only a seeded `listening.json` was rendered).
- **Fourth round** (remove buttons, GIFs, backend/Spotify): Remove picture / Remove banner (they
  delete Valen's copy); GIFs play on the set, banner, panel chip and phone header (desktop: Skia
  codec frames, `Picture.jvm.kt`; Android: `AnimatedImageDrawable`, `Picture.android.kt`).
  Backend: the desktop sign-in read the bridge's parked `/preferences` tab as a login page and
  reloaded the player at every start (proven on the live tab with old vs new check); the browser
  now dies with Valen (`guarded()`, 2 tests; a crashed run's headless browser was found still
  playing); Android Spotify's session is the `sp_dc` cookie; stats batch their updates and are
  flushed on quit / Android `onStop`; the browser log is capped at 4 MB. 118 tests green, Android
  compiles. **Not verified live**: a fresh-start Spotify play with these fixes (the owner's Valen
  was running and was not closed), anything on an Android device.
- **Fifth round** (welcome, taste, smart playlists): `welcome/WelcomeFlow.kt` shows once
  (`AppSettings.onboarded`), with the one 🥺; `core/Taste.kt` (taste + mixer, 4 tests); Home gets
  For you / Your artists / On repeat (`screens/HomeTaste.kt`); `smart/MixScreen.kt` and
  `Route.Mix` build smart playlists (sources incl. Spotify follows via `ArtistFollows`), saved
  with their `SmartSpec` and remade from the playlist page; covers use `PictureKind.Cover`.
  UI tests walk the welcome and save a genre mix. **Not verified**: Spotify follows against a real
  account, the mix builder on a phone, the welcome on Android.
- **Sixth round** (clear data, about): Settings → Your data → Clear data (one confirmation;
  `PlaybackCoordinator.clearData()`, `ListeningStats.clear()`, `PlaylistLibrary.clear()`, pictures
  deleted; sign-ins stay) returns to the welcome. `Route.About` (small link under Settings in the
  panel, "Who made Valen" in Settings): dodo986/erdem (GitHub picture live, GitHub button) and
  Valen/bade (`bade_profile.jpg`). "Insparation" from the brief is spelled "Inspiration" in the app.
- **Dev**: `VALEN_CAPTURE_GO`, `VALEN_CAPTURE_FRAMES`, `VALEN_CAPTURE_STEP_MS` (AGENTS §2) dump
  frames from `--screenshot` so motion can be reviewed as a contact sheet.

Verified (after the colour change too): `:ui:jvmTest` (4/4), `:app-desktop` distributable, `:app-android:compileDebugKotlin`;
stills at 1600×900, 2200×1200, 860×700, 390×844; frame dumps of the tag writing, the drop-to-pill
bloom, the letter rise and the Home→Search pour.
**Not verified**: anything on a real GPU window (the frames are Skia raster) — glass cost while
scrolling, the pointer glint, hover lens, tilt and droplet stretch with a real mouse; the jelly gulp
and the orbit were not frame-checked; Android glass has never been seen on a device (API 31/32 blur
without the rim, 29/30 opaque fallback). If glass costs too much on a weak GPU, the first lever is
`Glass.blur` and the lens shader (drop it on the side panel, which rarely has moving content under it).

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
  running: real account, song playing, position advancing, MPRIS correct; the browser process
  carries `--headless=new` (`HeadlessChrome` never appears — the UA is spoofed on purpose).
  `VALEN_CHROME_VISIBLE=1` forces a window.
- **The synthesized UA is built from the engine, not the brand** (Pass 11). A packaged install finds
  the machine's browser, which here is Helium: `--version` says "Helium 0.21.3.1 (Chromium
  157.0.8103.78)" and the old parse took 0.21.3.1, so Chrome announced `Chrome/0.0.0.0` and Spotify
  served "Unsupported browser" — the player never loaded and every bridge call timed out behind it.
  `ChromeLauncher.engineMajor` reads the `Chrome`/`Chromium` number (or the only plausible
  two-or-three-digit engine version), the UA is passed in a window too, and
  `ChromeLauncherTest` pins the four real version strings. Verified from the rebuilt AppImage: a
  real Web Player page, `token.get` and `player.play` answered by the bridge, Widevine available
  (`requestMediaKeySystemAccess` for `com.widevine.alpha` succeeds). Playback itself still needs a
  signed-in account, and this scratch profile is signed out.
- Spotify sign-in opens a visible window through `DesktopHosts.ensureVisible()`; replacing the
  browser bumps `generation` and invalidates the attached adapter. The browser's mode is read from
  its process, and a headless one is replaced even when this run did not start it. A sign-in that
  cannot get a window publishes `SessionState.Unavailable` with the launcher's reason instead of
  doing nothing. Verified live: a leaked headless Chrome from a killed run → Connect → the old
  browser stopped, a windowed Chrome on the Spotify login page in its place (`ChromeLauncherTest`
  covers the process reading and the stop). The page swap also found the adapter handoff gap: the
  Spotify adapter kept the old `CdpHost` across the replacement and every bridge call timed out, so
  Spotify is now always built behind `RetryingProvider` and `ready()` rebuilds when
  `generation` moved (the account stays, only the page is rebuilt).
- **Chrome-died watchdog works**: SIGKILL the Spotify Chrome → within ~10 s MPRIS flips to
  Paused and stderr prints
  `valen: Spotify: Spotify's browser was closed. Press play to reopen it.`
- **The login path is audited and its known stalls are fixed** (Pass 12):
  - The browser's argv is read from `/proc/<pid>/cmdline`. OpenJDK's
    `ProcessHandle.Info.commandLine()` returns argv[0] alone for this machine's Helium process, so
    every process check saw no browser: `isHeadless()` answered "could not be read" (the exact
    Connect error), `stopBrowser()` had nothing to stop, and a leaked headless engine could not be
    replaced. `ChromeLauncherTest` pins the full argv.
  - `start()`/`stopBrowser()` share one mutex, and `starting` is true while a browser comes up, so
    the port's absence during a sign-in's replacement is not a crash and a play cannot launch a
    second Chrome on the profile.
  - `RetryingProvider`'s watchdog skips an adapter whose browser generation moved: it used to close
    the adapter and publish "Spotify's browser was closed" in the middle of a login. Two tests pin
    the two halves (replacement ignored, real death noticed).
  - Spotify's account is its `sp_dc` cookie (`Network.getCookies` on the tab's target, verified on a
    live page target without `Network.enable`), not the page's markup — the "Unsupported browser"
    page read as connected before. The tab is reloaded once when a login finished elsewhere, and
    Disconnect deletes the session cookies (it only navigated to `accounts.spotify.com/logout`
    before, and claimed a sign-out it had not done).
  - YouTube Music and SoundCloud `connect()` report a page that will not open instead of throwing;
    a probe during a browser start keeps the last answer (no "Offline" flash over a working login)
    and a browser that has died is reported as "The browser is not running: press Connect to open it
    again" instead of the HTTP client's `Failed to connect to /127.0.0.1:9333`; a rebuilt adapter
    gets the volume chosen before it existed; `resume()` with no adapter emits the reason instead of
    returning silently.
  - `CdpHost`/`CdpPageHost`: pending calls fail on close (no 30 s wait), a refused `ws.send` fails
    at once, a timeout names the method, bridge messages go through an unbounded channel instead of
    a 256-slot `tryEmit` flow, and one unparseable frame no longer kills the connection.
- **Correctness pass over exactly those paths** (Pass 13). Twelve review findings in two batches, all
  confirmed against the code, and two more found while auditing:
  - The UA cache held only the tail of the string, so the launch after the first sent
    `Chrome/157.0.0.0 Safari/537.36` with no Mozilla prefix. A sign-in replaces the browser, so that
    was the launch a login depended on. The whole line is cached; two consecutive calls are pinned.
  - A rebuilt adapter's volume/quality restore now shares one mutex with `setVolume`/`setQuality` and
    re-reads the value inside it, so a slow restore cannot put an older level back after a newer one.
  - A `CdpHost`/`CdpPageHost` listener keeps "closed" and its waiter map under one guard: shutdown
    fails every waiter registered before it and refuses commands that start during it, instead of
    leaving them to the 30 s timeout. The timeout is `withTimeoutOrNull`, so a caller's own
    cancellation is not rewritten as "the browser did not answer" (`CdpListenerTest`).
  - Spotify's one reload now needs a tri-state page read. A document that was still loading answered
    "not the login page" before, so the reload was spent before the sign-in page rendered and a
    login finished in another tab left the player tab behind. `pageKindOf`/`reloadDone` are tested.
  - The `RetryingProvider` never cancelled a replaced adapter's collectors. A `StateFlow` never
    completes, so a closed page could still write its session and its old song over the new page's;
    they are cancelled on detach (`a replaced adapter can no longer publish over the new one`).
  - `NativePlayback` published the old ffplay process's ticks while the next song was still being
    resolved, so a requested song showed the previous song's position for the length of the resolve.
    The sink's states are ignored while `play` resolves and the first real one is applied after.
  - Second batch (six more findings, all confirmed): the death watchdog re-checks the browser
    generation *inside* its lock, so a replacement landing between the failed probe and the lock is
    no longer closed and reported as "Spotify's browser was closed" in the middle of a login
    (`a replacement that lands during a failed probe is not a dead browser`). YouTube Music and
    SoundCloud `connect()` report a `page.navigate(HOME)` that cannot be sent as
    `SessionState.Unavailable` and return before refreshing, instead of leaving the button dead and
    starting a poller that can never recover. `NativePlayback` stops the still-running previous
    stream before publishing a resolve failure, so its ticks can neither move the failed song nor
    clear its reason (`a song that cannot be resolved stops the previous stream and keeps its
    failure`). Three test-quality fixes: the resolved-stream test asserts the published
    `isPlaying`/`isBuffering` (it accepted the initial buffering snapshot), the gated volume fake
    records on completion rather than on arrival (it could pass with a stale restore finishing
    last), and `CdpListenerTest` gains a bounded slow-subscriber test over both pumps (two
    undispatched collectors, 300 binding frames, one blocked; both receive every payload in order).
- Unified search is implemented and unit-tested: one merged, de-duplicated list; "also on" labels;
  source priority decides which copy plays; per-service errors shown inline.
- `AppSettings.priority` persists; Settings has a "Sources" card with up/down reordering.
- `Artwork.kt` no longer launches work from a remembered scope after leaving composition (the known
  `rememberCoroutineScope left the composition` freeze cause).
- **Playlists are Valen's own library** (Pass 14). `core` holds `Playlist`, `PlaylistLibrary` (one
  mutex, persisted after each change to `$XDG_DATA_HOME/valen/playlists.json`), `PlaylistLinks`
  (Spotify, YouTube Music, SoundCloud, including short links), `TrackMatcher` (Mochy's importer
  scoring, hardened), `PlaylistImporter` and `PlaylistManager`. A pasted link is read by that
  service's catalog; a track already on a playable service is kept as-is, the rest are matched
  across services, and an entry nothing matches stays as an unmatched entry. Re-importing the same
  source replaces it in place (`PlaylistSource.key`). Account playlists are read from the browser's
  existing session (`CdpHost.cookieHeader`), so no YT/SoundCloud browser is ever launched.
- **The UI has Playlists and Import** (Pass 14): a cover grid with an in-place detail (Play,
  Refresh, Remove with one confirmation, the list's songs, and "Not on your services"), a link
  field and an account-list section on one shared `Group` surface, a `Route.Playlists` place in the
  side panel, icon rail, tab bar and `Nav.Places`, and `Route.Import` as a full page on wide
  windows and a full-screen sheet on a phone. Home is now listening-first: greeting, hero on one
  washed band (no wash at all when nothing plays), Your playlists, Recently played, Services only
  while signed out.
- **Design rework is in** (Mochy Music + Orpheon Immersive language; `docs/design-spec.md` is
  rewritten to match): black page, artwork wash and player tint, floating side panel / dock /
  phone capsules, superellipse shapes, two-voice type. Screenshots in `docs/screens/` date from
  that pass, except Home, Playlists and Phone Playlists, which Pass 14 re-shot at 1600×900 and
  390×844; 2200×1200 and the 860×700 rail are checked by eye each pass.
- **Playlist tests**: five new core suites (links, matcher, importer, library, manager) and a
  click-through step in `ShellSmokeTest` (play a list from Home, open it on Playlists, refuse a
  link no service can read). `:core:jvmTest` (63 tests), `:ui:jvmTest` (4), and the other suites
  are green; both apps compile.
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

1. **Spotify playback end to end after a sign-in.** The login path's known bugs are fixed (Pass 12,
   above) and `playerctl play` now says why when it cannot restart Chrome, but no signed-in session
   exists on this machine, so nothing past "the bridge answers" has been seen since Pass 11. Two
   changes are built but unproven:
   - `RetryingProvider.resume()` decides by adapter identity: if `ready()` had to build a new
     adapter, it replays `lastTrack` at `lastPosition` instead of calling `resume()` on an empty page.
   - `buildSpotify()` re-injects the page bridge up to 40 times (1.5 s apart, 3 s wait each) until
     `api.ready` is true, instead of injecting once into a page that may still be navigating.
   Sign in once in the real profile, then run the §2 sequence. **This is the first thing to finish.**
2. **Source reordering by hand** — the Sources card renders and its buttons call `setPriority`,
   but up/down has not been clicked in a real window and the restart persistence has not been
   re-checked since the rework.
3. **Pointer feel in a real window** — hover fills, the seek and volume rails, queue drag and the
   page cross-fade were built and compile-checked, and the click-through test exercises taps, but
   nobody has used them with a mouse or a finger yet. `--screenshot` cannot show hover or drag.
4. **Window-freeze fix** — the structural cause is removed and the route-switch test passes; the
   user's original case was never reproduced, so a real resize/route-switch session is still owed.
5. **Android** — unchanged hidden-WebView adapters; no device/emulator on this machine (no `adb`).
   Only the compile check runs here. Playlist reading there covers Spotify only: the YT Music and
   SoundCloud adapters on Android are page adapters, not `PlaylistCatalog`s, so their links and
   account lists are refused until a native catalogue exists on that platform.
6. **Reading a real account's playlists** — the Spotify, YouTube Music and SoundCloud catalogues
   compile and are called by `PlaylistManager`, but this machine has no signed-in session, so no
   account list has been read end to end. A pasted link for each service has not been exercised
   against the network either; the link parsing and the import orchestration are unit-tested.
7. **Unmatched entries are visible but not actionable** — they are listed under a playlist and
   kept across refreshes, but there is no search-and-fix action yet; the importer only matches at
   import time.

---

## 2. Verify Spotify end to end (needs one sign-in)

No Spotify account is signed in on this machine (neither `~/.local/share/valen/chrome` nor the
scratch profile holds `sp_dc`), and logging in is the user's step. The sign-in window, the browser
replacement, the account detection and the watchdog are all fixed and unit-tested; what is left is
one run with a real account.

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
- `resume()` with no adapter now emits `ProviderEvent.Failed` with the launcher's reason (Pass 12),
  so a play that cannot reopen Chrome leaves a line on stderr instead of nothing. If it still
  happens, read that line: it names the actual failure.

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
| Packages | `packageDeb` / `packageAppImage` | Both rebuilt in Pass 14 and smoke-rendered from their unpacked output (1440×900 Playlists; `--screenshot`, scratch `XDG_CONFIG_HOME`), clean exit. AppImage 112,310,776 B, sha256 `b1d818e5…`; deb 99,623,724 B, sha256 `41c5df9a…`. The full Spotify smoke (Chrome came up, the bridge answered `token.get`, cookie probe read "Not connected" on a signed-out scratch profile) is the Pass 12 one; playback code is unchanged since |
| Test sweep | `:core:jvmTest`, `:ui:jvmTest`, `:libs:audio:test`, `:providers:soundcloud:test`, `:host:desktop:test`, `:app-desktop:test` | green in Pass 14 (111 tests: core 64 — five playlist suites added; ui 4; host/desktop 19; app-desktop 10; libs/audio 8; providers/soundcloud 6); Android compiles (`:app-android:compileDebugKotlin`) |
| Docs | `packaging/README.md`, `docs/provider-contract.md` | headless Spotify, unified search, source priority documented (README, NOTICE, TODO, design spec and AGENTS §4 were updated in Pass 13); Pass 14 updated README (playlists/import, `playlists.json`, routes), `docs/design-spec.md` (Home order, Playlists and Import screens, new components) and NOTICE (Mochy importer credit) |
| Playlist reading | signed in to each service, open Import, pick a service chip, add a list; paste one public link per service | list arrives, songs play, unmatched entries are listed; then refresh the same source and confirm it replaces in place |

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

**Screenshot matrix** (silent, and without touching the user's settings). Pass 9 re-rendered the
five desktop screens after the layout change. Pass 14 re-shot Home and Playlists for both widths
from a scratch `XDG_DATA_HOME` whose `valen/playlists.json` held a small sample library (four
lists, real covers); the other phone shots predate Pass 9 and were left alone. A run without that
seed shows Home without "Your playlists" and an empty Playlists page — the layout is the same, the
shelves are simply absent. The renders show services as "Connect" because the run uses a scratch
config and no live account; no account was touched.

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
  XDG_CONFIG_HOME=/tmp/valen-shots/cfg XDG_DATA_HOME=/tmp/valen-shots/data $V --width $1 --height $2 --route playlists --screenshot docs/screens/playlists-$3.png
done
XDG_CONFIG_HOME=/tmp/valen-shots/cfg $V --width 1600 --height 900 --route settings --screenshot docs/screens/settings-desktop.png
```

---

## 5. Machine/environment facts

- Build env: `source scripts/env.sh` (JDK 21, Android SDK 36/37, Gradle home, Chrome under `.tools/`).
- Spotify Chrome profile: `~/.local/share/valen/chrome`, used by the user (signed out on this machine
  unless they sign in). Do not clear it. Remove `Singleton*` only when no Chrome with that profile runs.
- DevTools: `http://127.0.0.1:9333`; a live check is `curl -s .../json/version`.
- `ProcessHandle.Info.commandLine()` is unreliable here: for the Helium browser it answers argv[0]
  only. Read `/proc/<pid>/cmdline` (`ChromeLauncher.procCommandLine`); verify with
  `tr '\0' '\n' < /proc/<pid>/cmdline`.
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
