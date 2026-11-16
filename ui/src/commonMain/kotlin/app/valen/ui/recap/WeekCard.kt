/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.localEpochDay
import app.valen.core.recapDue
import app.valen.core.week
import app.valen.ui.components.Artwork
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.hoverLift
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Where "Your week" waits, on Home and in the profile, by itself at the end of each week
 * ([recapDue]: Sunday and Monday) — or always, with Settings → Test — when the week has something to tell: its covers stacked like flyers, what it adds up to, and Watch. The
 * whole card is the press. Left out otherwise, as an empty section is.
 */
@Composable
fun WeekCard(coordinator: PlaybackCoordinator, modifier: Modifier = Modifier) {
    val settings by coordinator.settings.collectAsState()
    if (!settings.recapAlways && !recapDue(localEpochDay())) return
    val log by coordinator.stats.log.collectAsState()
    // Made again once a minute of listening has been added, not at every slice.
    val week = remember(log.totalMs / 60_000) { log.week(localEpochDay()) } ?: return
    val recap = LocalRecap.current
    val compact = LocalChrome.current.compact
    val source = rememberInteraction()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // A card, not a band: on a wide window it stops at the reading width.
            .widthIn(max = Layout.readingMaxWidth)
            .fillMaxWidth()
            .hoverLift(source)
            .clip(Radii.cardShape)
            .tap(onClick = { recap.open() }, rest = ValenColors.Surface, source = source)
            .padding(horizontal = Space.step4, vertical = Space.step4),
    ) {
        // The week's three most played covers fanned like flyers, the first in front.
        val covers = week.songs.take(3)
        Box(Modifier.size(width = 56.dp + 26.dp * (covers.size - 1).coerceAtLeast(0), height = 64.dp), contentAlignment = Alignment.CenterStart) {
            covers.indices.reversed().forEach { i ->
                // The first stands in the middle when there are three, turned least.
                val place = if (covers.size == 3) listOf(1, 0, 2)[i] else i
                val turn = listOf(-7f, 1.5f, 8f)[if (covers.size == 3) place else i.coerceAtMost(2)]
                Artwork(
                    url = covers[i].track.artworkUrl,
                    shape = Radii.coverShape,
                    modifier = Modifier
                        .offset(x = 26.dp * place)
                        .size(56.dp)
                        .graphicsLayer { rotationZ = turn },
                )
            }
        }
        Spacer(Modifier.width(Space.step4))
        Column(Modifier.weight(1f)) {
            Text("Your week", style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text, maxLines = 1)
            Text(
                listenedFor(week.totalMs) + (week.artists.firstOrNull()?.let { "  ·  ${it.name} on top" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(daysOf(week), style = MaterialTheme.typography.bodySmall, color = ValenColors.Text3, maxLines = 1)
        }
        if (!compact) {
            Spacer(Modifier.width(Space.step3))
            PrimaryButton(label = "Watch", icon = Icons.Rounded.PlayArrow, onClick = { recap.open() }, small = true)
        }
    }
}
