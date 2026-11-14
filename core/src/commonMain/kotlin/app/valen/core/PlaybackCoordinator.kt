/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProviderSearchResult(
    val provider: ProviderId,
    val tracks: List<Track> = emptyList(),
    val error: String? = null,
)

/** One song in the unified list, with the other services that list the same recording. */
data class SearchHit(
    val track: Track,
    val alsoOn: List<ProviderId> = emptyList(),
)

/** The merged answer to one query: each recording once, plus whichever services failed. */
data class UnifiedSearch(
    val hits: List<SearchHit> = emptyList(),
    val errors: Map<ProviderId, String> = emptyMap(),
) {
    val tracks: List<Track> get() = hits.map { it.track }
}

/**
 * The one notice that is a confirmation rather than a provider's message: the UI shows it as a
 * brief nod and lets it leave, where a failure stays until it is dismissed.
 */
const val NOTICE_QUEUED = "Added to queue"

/**
 * Owns the one queue and the one visible player. Providers are interchangeable behind it: the
 * queue asks the active adapter to play a single song and never cares which service that is.
 */
class PlaybackCoordinator(
    private val providers: List<MusicProvider>,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    initial: AppSettings = settingsStore.load(),
) {
    private val byId = providers.associateBy { it.id }

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _active = MutableStateFlow<ProviderId?>(null)
    val active: StateFlow<ProviderId?> = _active.asStateFlow()

    private val _snapshot = MutableStateFlow<PlayerSnapshot?>(null)
    val snapshot: StateFlow<PlayerSnapshot?> = _snapshot.asStateFlow()

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val sessions: StateFlow<Map<ProviderId, SessionState>> =
        combine(providers.map { it.session }) { states ->
            providers.mapIndexed { i, p -> p.id to states[i] }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, providers.associate { it.id to SessionState.SignedOut })

    private var lastRaw: PlayerSnapshot? = null
    private var lastRawAt = 0L

    init {
        providers.forEach { provider ->
            scope.launch {
                provider.events.collect { event ->
                    if (_active.value != provider.id) return@collect
                    when (event) {
                        ProviderEvent.TrackEnded -> next(auto = true)
                        is ProviderEvent.Failed -> _notice.value = event.message
                        ProviderEvent.SessionLost -> _notice.value = "${provider.id.label} session expired"
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                val id = _active.value ?: continue
                val provider = byId[id] ?: continue
                val raw = provider.player.value ?: continue
                val now = System.currentTimeMillis()
                if (raw != lastRaw) {
                    lastRaw = raw
                    lastRawAt = now
                }
                val elapsed = if (raw.isPlaying && !raw.isBuffering) (now - lastRawAt).coerceAtLeast(0) else 0L
                val position = (raw.positionMs + elapsed).coerceAtMost(raw.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                _snapshot.value = raw.copy(positionMs = position, volume = _settings.value.volume)
            }
        }
    }

    fun provider(id: ProviderId): MusicProvider? = byId[id]

    suspend fun connect(id: ProviderId) {
        runCatching { byId[id]?.connect() }.onFailure { _notice.value = it.message ?: "Could not connect" }
    }

    suspend fun disconnect(id: ProviderId) {
        byId[id]?.let { provider ->
            if (_active.value == id) runCatching { provider.pause() }
            runCatching { provider.disconnect() }
        }
    }

    suspend fun search(query: String, enabled: Set<ProviderId> = byId.keys): List<ProviderSearchResult> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            enabled.mapNotNull { byId[it] }.map { provider ->
                async {
                    runCatching { provider.search(query, 25) }
                        .fold(
                            // A service often lists the same recording twice (an album and a
                            // single upload): one row per title and artist keeps the list honest.
                            onSuccess = { ProviderSearchResult(provider.id, it.distinctRecordings()) },
                            onFailure = { ProviderSearchResult(provider.id, error = it.message ?: "Unavailable") },
                        )
                }
            }.awaitAll()
        }
    }

    /**
     * One query, one list. Every enabled service answers in parallel; the answers are merged into
     * a single list where a recording appears once, no matter how many services have it. The copy
     * that is kept — and therefore played — comes from the service highest in [priority], and the
     * others are reported as [SearchHit.alsoOn] so the row can say so.
     *
     * Rows are ordered by how well they matched (a song both services put first beats one only one
     * service found deep in its list), with the user's order breaking ties. A service that fails
     * is left out of the list and reported in [UnifiedSearch.errors]: one refusal never hides the
     * other services' answers.
     */
    suspend fun unifiedSearch(
        query: String,
        enabled: Set<ProviderId> = byId.keys,
        priority: List<ProviderId> = _settings.value.priority,
    ): UnifiedSearch {
        if (query.isBlank()) return UnifiedSearch()
        val order = priorityOrder(priority).filter { it in enabled }
        if (order.isEmpty()) return UnifiedSearch()

        val answers = coroutineScope {
            order.map { id ->
                async { id to runCatching { byId.getValue(id).search(query, 25) } }
            }.awaitAll()
        }

        val errors = LinkedHashMap<ProviderId, String>()
        // recording key -> every service's copy of it, with where that service ranked it
        val copies = LinkedHashMap<String, MutableList<Copy>>()
        for ((id, answer) in answers) {
            val tracks = answer.getOrElse { error ->
                errors[id] = error.message ?: "Unavailable"
                emptyList()
            }
            val rank = order.indexOf(id)
            tracks.distinctRecordings().forEachIndexed { position, track ->
                copies.getOrPut(recordingKey(track.title, track.artist)) { mutableListOf() }
                    .add(Copy(track, position, rank))
            }
        }

        val ranked = copies.values.mapNotNull { group ->
            val preferred = group.minWith(compareBy({ it.providerRank }, { it.position }))
            // The best place any service put the song, so a great match is not buried because the
            // preferred service ranked its own copy lower.
            val position = group.minOf { it.position }
            val alsoOn = group.map { it.track.provider }
                .filter { it != preferred.track.provider }
                .distinct()
            position to SearchHit(preferred.track, alsoOn)
        }
        val hits = ranked
            .sortedWith(compareBy({ it.first }, { order.indexOf(it.second.track.provider) }))
            .map { it.second }
            .take(MAX_UNIFIED_RESULTS)
        return UnifiedSearch(hits, errors)
    }

    /** The service order actually used: the user's, cleaned, then anything missing appended. */
    fun priorityOrder(priority: List<ProviderId> = _settings.value.priority): List<ProviderId> {
        val seen = LinkedHashSet<ProviderId>(ProviderId.entries.size)
        priority.forEach { if (it in byId) seen += it }
        ProviderId.entries.forEach { if (it in byId) seen += it }
        return seen.toList()
    }

    /** Reorders the services: the first one wins a song that more than one of them has. */
    fun setPriority(priority: List<ProviderId>) {
        _settings.value = _settings.value.copy(priority = priorityOrder(priority))
        settingsStore.save(_settings.value)
    }

    /** One service's copy of a recording: the track, its place in that service's list, and rank. */
    private class Copy(val track: Track, val position: Int, val providerRank: Int)

    /** Starts [track] inside [context] (the whole list becomes the queue). */
    suspend fun play(track: Track, context: List<Track> = listOf(track)) {
        val list = if (context.any { it.key == track.key }) context else context + track
        _queue.value = list
        startAt(list.indexOfFirst { it.key == track.key }.coerceAtLeast(0))
    }

    private suspend fun startAt(position: Int) {
        val list = _queue.value
        val track = list.getOrNull(position) ?: return
        val target = byId[track.provider] ?: run {
            _notice.value = "${track.provider.label} is not available"
            return
        }
        byId.values.filter { it.id != track.provider }.forEach { runCatching { it.pause() } }
        _index.value = position
        _active.value = track.provider
        _settings.value = _settings.value.copy(lastProvider = track.provider)
        settingsStore.save(_settings.value)
        lastRaw = null
        runCatching {
            target.setVolume(_settings.value.volume)
            target.play(track)
        }.onFailure { _notice.value = it.message ?: "${track.provider.label} could not play this" }
    }

    suspend fun toggle() {
        val id = _active.value ?: run {
            _queue.value.firstOrNull()?.let { play(it) }
            return
        }
        val provider = byId[id] ?: return
        val snap = provider.player.value
        if (snap?.isPlaying == true) provider.pause() else {
            if (snap?.isBuffering == true) return
            provider.resume()
        }
    }

    suspend fun next(auto: Boolean = false) {
        val next = _index.value + 1
        if (next in _queue.value.indices) {
            startAt(next)
        } else {
            _active.value?.let { byId[it]?.pause() }
            if (auto) {
                _index.value = _queue.value.lastIndex.coerceAtLeast(0)
                _snapshot.value = _snapshot.value?.copy(isPlaying = false, positionMs = 0)
            }
        }
    }

    suspend fun previous() {
        val snap = _snapshot.value
        if (snap != null && snap.positionMs > 3_000) {
            seek(0)
        } else if (_index.value > 0) {
            startAt(_index.value - 1)
        } else {
            seek(0)
        }
    }

    suspend fun seek(positionMs: Long) {
        val id = _active.value ?: return
        runCatching { byId[id]?.seek(positionMs) }
    }

    suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _settings.value = _settings.value.copy(volume = v)
        settingsStore.save(_settings.value)
        _active.value?.let { byId[it]?.setVolume(v) }
    }

    suspend fun setQuality(quality: AudioQuality) {
        _settings.value = _settings.value.copy(quality = quality)
        settingsStore.save(_settings.value)
        providers.forEach { runCatching { it.setQuality(quality) } }
    }

    fun enqueue(track: Track) {
        if (_queue.value.isEmpty()) {
            scope.launch { play(track) }
            return
        }
        if (_queue.value.any { it.key == track.key }) return
        _queue.value = _queue.value + track
        _notice.value = NOTICE_QUEUED
    }

    fun removeAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        _queue.value = list.toMutableList().also { it.removeAt(position) }
        when {
            position < _index.value -> _index.value -= 1
            position == _index.value -> {
                if (_queue.value.isEmpty()) {
                    _index.value = -1
                    _active.value = null
                } else {
                    _index.value = position.coerceAtMost(_queue.value.lastIndex)
                }
            }
        }
    }

    fun move(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val current = list.getOrNull(_index.value)
        list.add(to, list.removeAt(from))
        _queue.value = list
        if (current != null) _index.value = list.indexOfFirst { it.key == current.key }
    }

    fun clearQueue() {
        _queue.value = emptyList()
        _index.value = -1
        _active.value = null
    }

    fun clearNotice() { _notice.value = null }

    private companion object {
        /** One page of merged answers; enough to scroll, not so many that a search never ends. */
        const val MAX_UNIFIED_RESULTS = 50
    }
}
