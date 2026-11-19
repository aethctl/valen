# Valen — TODO

## P1 — Go All In
- [ ] Wait for Spotishit to open source so that we can protect Spotify playback from DMCA and fix Android's Spotify.
- [ ] Device sync: One profile across Android and desktop.
- [ ] [NoteBurner](https://www.noteburner.com/en1/) as a playback source (said to have a HUGE database).
- [ ] Supabase backend + online accounts (? maybe), optional for sure.
- [ ] Fix the bottom island.
- [ ] Floating lyrics like Mochy.
- [ ] An anime mascot.
- [ ] Search bar for the playlist import page.
- [x] Fix some songs skipping.
- [ ] A better lyrics API, or fix Lyperia drifting.
- [ ] Better UI/UX and much cooler animations.
- [ ] Ad blocker for Spotify (maybe with a Go daemon?).
- [ ] Look for bugs in the backend.
- [x] Discord RPC settings.
- [ ] Youtube playlist imports limited to 100 tracks.
- [x] Fix the algorithm throwing random slop into the queue.
  - The engine is `core/Taste.kt` + `core/MixEngine.kt` now (2026-11-19): entity-exact related and
    seed artists, corroborated discoveries, cover-by-title and derivative gates, discovery/familiar
    quotas, trust-gated seeds, and generated-list listening counted apart so a mix cannot seed the
    next one. The old "365MUSIC" rows were one name query merging two unrelated services' artists;
    SoundCloud fan uploads and one-session artists cannot enter any more. See HANDOFF.md.
  - Known limits: a noisy related list can still name a genuinely unrelated real artist (now only
    one whose page returns songs and which is corroborated or top-ranked); the already-saved log
    keeps its old listening as "real" (a generated mix played before this change is not marked);
    and a `durationMs=0` upload skips the length gate (the cover-by-title gate still catches the
    common case). "Less like this" and "Don't recommend this song" in the song menu are the manual
    fix, kept in `AppSettings.dislikedArtists`/`hiddenTracks`.
