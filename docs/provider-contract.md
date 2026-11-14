# Provider contract

Everything below lives in `:core` (`app.valen.core`). Adapters implement `MusicProvider`; the platforms
build a host and a `ProviderSignIn` for each service. The UI only sees `PlaybackCoordinator`.

```kotlin
interface ProviderSignIn {
    val state: StateFlow<SessionState>
    suspend fun connect()      // opens the service's own sign-in page
    suspend fun disconnect()
}

interface MusicProvider {
    val id: ProviderId
    val session: StateFlow<SessionState>
    val player: StateFlow<PlayerSnapshot?>
    val events: SharedFlow<ProviderEvent>
    suspend fun connect(); suspend fun disconnect()
    suspend fun search(query: String, limit: Int = 25): List<Track>
    suspend fun play(track: Track, startMs: Long = 0)
    suspend fun pause(); suspend fun resume(); suspend fun seek(ms: Long)
    suspend fun setVolume(v: Float); suspend fun setQuality(q: AudioQuality) {}
    suspend fun close()
}
```

Rules:
- `play` replaces the adapter's current song; the queue is app-owned, so adapters never invent a queue.
- Emit `ProviderEvent.TrackEnded` when the service finishes the song (not when the user pauses).
- Report position at the moment of the snapshot; the coordinator extrapolates while playing.
- `PlayerSnapshot.canSkip` means "the transport can move to another song", which is the queue's job,
  so an adapter that plays one song at a time reports `true` whatever its service allows itself.
- `play` must not block on anything optional: set a level, start the song, let the rest settle in the
  background (a service's own volume answer can take 30 s on an idle page).
- Adapter constructors take their transport plus a `ProviderSignIn`; no Android/desktop types.
- `connect()` delegates to `ProviderSignIn.connect()`; it must not ask for credentials itself.
- Every copied Orpheon file keeps its SPDX header and package name.

## Native playback (desktop, YouTube Music and SoundCloud)

Those two adapters do not own a page. They resolve a stream and hand it to the platform's audio
output:

```kotlin
interface AudioPlayer {            // :libs:audio
    val state: StateFlow<AudioState>
    val canSeek: Boolean
    suspend fun play(stream: ResolvedStream, startMs: Long)
    suspend fun pause(); suspend fun resume(); suspend fun seek(ms: Long)
    suspend fun setVolume(v: Float); suspend fun stop(); suspend fun release()
}
```

- `YoutubeAudio` resolves a video id to a playable address through NewPipeExtractor (signatures, the
  `n` challenge and PO tokens are its job); a plain InnerTube answer serves only its first megabyte,
  which is why the extractor is not optional.
- `SoundcloudAudio` reads the API's `client_id` from `soundcloud.com` at runtime, searches, and picks
  a progressive MP3 or an HLS AAC stream. A rejected id is re-read once.
- `FfplayPlayer` runs one `ffplay` per stream: `-ss` starts part-way in, SIGSTOP/SIGCONT pause,
  a zero exit is the end of the song. Position is measured from the moment playback started, so a
  seek restarts the process.
- `NativePlayback` is the shared half: it maps `AudioState` to `PlayerSnapshot`, resolves only on
  `play`, and emits `TrackEnded` once per song so the coordinator advances exactly once.

The page-based adapters in `providers/ytmusic` and `providers/soundcloud` remain for Android, whose
WebView sink plays the same way it always has.
