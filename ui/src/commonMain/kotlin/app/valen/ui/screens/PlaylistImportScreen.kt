/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.background
import app.valen.ui.components.ScrollPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import app.valen.ui.rememberLocalPicker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.ImportProgress
import app.valen.core.ImportResult
import app.valen.core.PlaybackCoordinator
import app.valen.core.Playlist
import app.valen.core.PlaylistChoices
import app.valen.core.PlaylistManager
import app.valen.core.PlaylistRef
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.ui.LocalAppScope
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.FilterChips
import app.valen.ui.components.Group
import app.valen.ui.components.IconTap
import app.valen.ui.components.InlineError
import app.valen.ui.components.LinkField
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.PlaylistRow
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.ProgressRail
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SearchField
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.SkeletonRows
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

/**
 * Getting a playlist into Valen: a pasted address, or one of an account's own lists. Both end in
 * the same place, so the screen shows the result of one import and the account lists under it,
 * rather than sending the person somewhere else mid-way.
 *
 * A service is only asked about its own lists when the person picks it here. That is also where a
 * signed-out service says so, with the sign-in beside it: a list nobody can read is not an empty
 * list.
 */
@Composable
fun PlaylistImportScreen(
    coordinator: PlaybackCoordinator,
    manager: PlaylistManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalAppScope.current
    val sessions by coordinator.sessions.collectAsState()
    val readable = remember(manager) { ProviderId.services.filter { manager.canRead(it) } }
    val device = manager.canRead(ProviderId.LOCAL)
    var deviceLists by remember { mutableStateOf<PlaylistChoices?>(null) }

    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var outcome by remember { mutableStateOf<ImportResult?>(null) }

    var provider by remember { mutableStateOf(readable.firstOrNull()) }
    var choices by remember { mutableStateOf<PlaylistChoices?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // The lists under "From your accounts" can be many; their names are typed at.
    var query by remember { mutableStateOf("") }

    LaunchedEffect(provider, reload) {
        val asked = provider ?: return@LaunchedEffect
        loading = true
        choices = null
        choices = manager.accountPlaylists(asked)
        loading = false
    }

    // Listed again once a phone lets Valen see its music.
    var deviceAccess by remember { mutableStateOf(0) }
    LaunchedEffect(device, deviceAccess) {
        if (device) deviceLists = manager.accountPlaylists(ProviderId.LOCAL)
    }

    fun runImport(work: suspend () -> ImportResult) {
        if (busy) return
        busy = true
        outcome = null
        progress = ImportProgress(label = "Reading the playlist…")
        scope.launch {
            val result = work()
            progress = null
            busy = false
            outcome = result
            if (result is ImportResult.Imported) link = ""
        }
    }

    ScrollPage(key = "import", modifier = modifier
            .fillMaxSize()) {
        ContentColumn(maxWidth = Layout.readingMaxWidth) {
            PageTop()
            PageTitle(
                title = "Add a playlist",
                leading = {
                    IconTap(
                        icon = Icons.Rounded.Close,
                        label = "Close import",
                        onClick = onClose,
                        tint = ValenColors.Text,
                    )
                },
            )
            Spacer(Modifier.height(Space.step2))
            Text(
                text = "Paste a playlist link from Spotify, YouTube Music or SoundCloud. Valen finds each song on the services it can play here and keeps the list in your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = ValenColors.Text2,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.height(Space.step6))

            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkField(
                    value = link,
                    onValueChange = { link = it },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.step2))
                PrimaryButton(
                    label = "Import",
                    enabled = !busy && link.isNotBlank(),
                    onClick = { runImport { manager.importLink(link.trim()) { progress = it } } },
                )
            }

            if (busy) {
                Spacer(Modifier.height(Space.step4))
                Text(
                    text = progress?.takeIf { it.total > 0 }?.let { "Matching ${it.processed} of ${it.total} songs" }
                        ?: "Reading the playlist…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ValenColors.Text2,
                )
                Spacer(Modifier.height(Space.step2))
                ProgressRail(
                    fraction = progress?.takeIf { it.total > 0 }?.let {
                        it.processed.toFloat() / it.total
                    } ?: 0f,
                )
                progress?.label?.takeIf { it.isNotEmpty() }?.let { label ->
                    Spacer(Modifier.height(Space.step2))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = ValenColors.Text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (val result = outcome) {
                is ImportResult.Failed -> {
                    Spacer(Modifier.height(Space.step3))
                    InlineError(result.reason)
                }
                is ImportResult.Imported -> {
                    Spacer(Modifier.height(Space.step4))
                    ImportedPanel(
                        playlist = result.playlist,
                        onDone = onClose,
                        onAnother = { outcome = null },
                    )
                }
                null -> Unit
            }

            val pick = rememberLocalPicker(
                onPicked = { path -> runImport { manager.importLink(path) { progress = it } } },
                onAccess = { deviceAccess++ },
            )
            if (device && pick != null) {
                Spacer(Modifier.height(Space.step10))
                SectionHeader("From this device")
                Spacer(Modifier.height(Space.step2))
                Text(
                    text = "A playlist file (m3u, pls, xspf) or a folder of songs. Songs that are not on this device are looked for on your services, and songs from your services can be added to it after.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
                Spacer(Modifier.height(Space.step3))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                    QuietButton(label = "Choose a playlist", icon = Icons.AutoMirrored.Rounded.QueueMusic, enabled = !busy, onClick = { pick(false) })
                    QuietButton(label = "Choose a folder", icon = Icons.Rounded.FolderOpen, enabled = !busy, onClick = { pick(true) })
                }
                val found = deviceLists?.playlists.orEmpty()
                if (found.isNotEmpty()) {
                    Spacer(Modifier.height(Space.step4))
                    Group(
                        rows = found.map { ref ->
                            {
                                PlaylistRow(
                                    name = ref.name,
                                    detail = refDetail(ref),
                                    artworkUrl = ref.artworkUrl,
                                    onClick = { if (!busy) runImport { manager.importPlaylist(ref) { progress = it } } },
                                    contained = true,
                                    trailing = {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = null,
                                            tint = ValenColors.Text3,
                                            modifier = Modifier
                                                .padding(horizontal = Space.step3)
                                                .size(20.dp),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

            if (readable.isNotEmpty()) {
                Spacer(Modifier.height(Space.step10))
                SectionHeader("From your accounts")
                Spacer(Modifier.height(Space.step2))
                Text(
                    text = "Pick a list from a service you are signed in to. Its songs come into Valen; nothing is copied into the account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
                Spacer(Modifier.height(Space.step3))
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search playlists",
                    onClear = { query = "" },
                )
                Spacer(Modifier.height(Space.step3))
                if (readable.size > 1) {
                    FilterChips(
                        options = readable,
                        selected = setOfNotNull(provider),
                        onToggle = { picked -> provider = picked },
                        label = { it.short },
                    )
                    Spacer(Modifier.height(Space.step3))
                }
                AccountLists(
                    provider = provider,
                    choices = choices,
                    loading = loading,
                    busy = busy,
                    query = query,
                    session = provider?.let { sessions[it] },
                    onSignIn = { provider?.let { id -> scope.launch { coordinator.connect(id) } } },
                    onRetry = { reload++ },
                    onImport = { ref -> runImport { manager.importPlaylist(ref) { progress = it } } },
                )
            }
            Spacer(Modifier.height(Space.step8))
        }
        PageBottom()
    }
}

@Composable
private fun AccountLists(
    provider: ProviderId?,
    choices: PlaylistChoices?,
    loading: Boolean,
    busy: Boolean,
    query: String,
    session: SessionState?,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onImport: (PlaylistRef) -> Unit,
) {
    val service = provider ?: return
    val error = choices?.error
    val lists = choices?.playlists.orEmpty()
    val typed = query.trim()
    val matching = if (typed.isEmpty()) lists else lists.filter { listMatches(it, typed) }
    when {
        loading -> SkeletonRows(count = 3)
        error != null -> {
            InlineError(error)
            Spacer(Modifier.height(Space.step2))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                if (session !is SessionState.Connected) {
                    QuietButton(label = "Sign in to ${service.short}", small = true, onClick = onSignIn)
                }
                QuietButton(label = "Try again", small = true, onClick = onRetry)
            }
        }
        lists.isEmpty() -> Text(
            text = "No playlists on ${service.short}.",
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text3,
        )
        matching.isEmpty() -> Text(
            text = "No playlists match “$typed”.",
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text3,
        )
        else -> Group(
            rows = matching.map { ref ->
                {
                    PlaylistRow(
                        name = ref.name,
                        detail = refDetail(ref),
                        artworkUrl = ref.artworkUrl,
                        onClick = { if (!busy) onImport(ref) },
                        contained = true,
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = ValenColors.Text3,
                                modifier = Modifier
                                    .padding(horizontal = Space.step3)
                                    .size(20.dp),
                            )
                        },
                    )
                }
            },
        )
    }
}

/** A list is found by its name or by whose it is, whichever part of it a person remembers. */
internal fun listMatches(ref: PlaylistRef, query: String): Boolean =
    ref.name.contains(query, ignoreCase = true) ||
        ref.owner?.contains(query, ignoreCase = true) == true

/** What one import left behind, said plainly and with the way back to the lists. */
@Composable
private fun ImportedPanel(playlist: Playlist, onDone: () -> Unit, onAnother: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.rowShape)
            .background(ValenColors.Surface)
            .padding(Space.step4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = ValenColors.Success,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Space.step2))
            Text(
                text = "Added to your playlists",
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
            )
        }
        Spacer(Modifier.height(Space.step2))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleLarge,
            color = ValenColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.step1))
        Text(
            text = summary(playlist),
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text2,
        )
        playlist.note?.let { note ->
            Spacer(Modifier.height(Space.step2))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text3,
            )
        }
        Spacer(Modifier.height(Space.step4))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
            PrimaryButton(label = "Done", onClick = onDone)
            QuietButton(label = "Add another", onClick = onAnother)
        }
    }
}

private fun summary(playlist: Playlist): String {
    val songs = when (playlist.tracks.size) {
        0 -> "No songs could be played."
        1 -> "1 song is ready to play."
        else -> "${playlist.tracks.size} songs are ready to play."
    }
    val missing = playlist.unmatched.size
    val gaps = when (missing) {
        0 -> ""
        1 -> " 1 could not be found."
        else -> " $missing could not be found."
    }
    return songs + gaps
}

private fun refDetail(ref: PlaylistRef): String {
    val songs = when (ref.trackCount) {
        0 -> "No songs"
        1 -> "1 song"
        else -> "${ref.trackCount} songs"
    }
    val owner = ref.owner?.takeIf { it.isNotBlank() && it != ref.name }
    return if (owner != null) "$songs · $owner" else songs
}
