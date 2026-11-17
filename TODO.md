# Valen — TODO

## P0 — foundation
- [x] Project skeleton, Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, Compose Multiplatform 1.12.2
- [x] Project-local JDK 21, Android SDK 36 + 37, Chrome (`.tools/`)
- [x] Core models, `MusicProvider`, `ProviderSignIn`, `PlaybackCoordinator`
- [x] Design spec written before screens
- [x] Vendored Orpheon modules compile (`api`, `web`, `engine`, `spotify`, `ytmusic`)
- [x] Spotify adapter (search/play/pause/seek/volume/session)
- [x] YouTube Music adapter (search/play/queue-next)
- [x] SoundCloud adapter (search/streams/play)
- [x] Design system + four screens + settings (responsive)
- [x] Desktop hosts (Chrome CDP) + MPRIS + app shell
- [x] Android hosts (WebView) + foreground service/notification + app shell
- [x] Integration, build desktop, audio checks (YTM/SoundCloud signed out)
- [x] Debug APK (`app-android/build/outputs/apk/debug/app-android-debug.apk`)
- [x] Screenshots in `docs/screens` (8 from the design pass; Home and Playlists re-shot in Pass 14)
- [x] README, NOTICE, env var documentation
- [x] An SPDX `GPL-3.0-only` header on every source file (all 194 Kotlin files, the page scripts and the vendored bridge included), so NOTICE's per-file claim holds
