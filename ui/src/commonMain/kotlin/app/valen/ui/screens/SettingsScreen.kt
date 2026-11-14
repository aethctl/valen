/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.valen.core.AudioQuality
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.ui.LocalAppScope
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.SegmentedFilter
import app.valen.ui.components.ServiceTile
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.launch

/**
 * Four groups, nothing else: the services, the order they are preferred in, the one audio
 * preference, and what this app is. Every row reports real state; none of the buttons are
 * decorative. Each group is one raised surface with hairlines between its rows.
 */
@Composable
fun SettingsScreen(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val scope = LocalAppScope.current
    val sessions by coordinator.sessions.collectAsState()
    val settings by coordinator.settings.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ValenColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        ContentColumn(maxWidth = Layout.readingMaxWidth) {
            PageTop()
            PageTitle(
                title = "Settings",
                leading = if (onBack != null) {
                    {
                        IconTap(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            label = "Back",
                            onClick = onBack,
                            tint = ValenColors.Text,
                        )
                    }
                } else {
                    null
                },
            )

            Spacer(Modifier.height(Space.step6))
            SectionHeader(title = "Services")
            Note("Sign-in opens the service's own page. Valen never sees your password and never asks for it.")
            Group(
                ProviderId.entries.map { provider ->
                    {
                        ServiceTile(
                            provider = provider,
                            state = sessions[provider] ?: SessionState.SignedOut,
                            onConnect = { scope.launch { coordinator.connect(provider) } },
                            onDisconnect = { scope.launch { coordinator.disconnect(provider) } },
                            contained = true,
                        )
                    }
                },
            )

            Spacer(Modifier.height(Space.step8))
            SectionHeader(title = "Sources")
            Note(
                "A song that more than one service has appears once in search. It plays from the " +
                    "first service here, and songs the services rank equally are listed in this order.",
            )
            val priority = coordinator.priorityOrder(settings.priority)
            Group(
                priority.mapIndexed { index, provider ->
                    {
                        SourceRow(
                            position = index + 1,
                            provider = provider,
                            canRise = index > 0,
                            canFall = index < priority.lastIndex,
                            onRise = { coordinator.setPriority(priority.moved(index, index - 1)) },
                            onFall = { coordinator.setPriority(priority.moved(index, index + 1)) },
                        )
                    }
                },
            )

            Spacer(Modifier.height(Space.step8))
            SectionHeader(title = "Audio quality")
            Note(
                when (settings.quality) {
                    AudioQuality.AUTO -> "Each service picks the best stream it can currently deliver."
                    AudioQuality.STANDARD -> "Asks every service for its standard stream."
                    AudioQuality.HIGH -> "Asks every service for the highest stream your account allows."
                },
            )
            SegmentedFilter(
                options = AudioQuality.entries,
                selected = settings.quality,
                onSelect = { quality -> scope.launch { coordinator.setQuality(quality) } },
                label = { it.label },
            )

            Spacer(Modifier.height(Space.step8))
            SectionHeader(title = "About")
            Spacer(Modifier.height(Space.step2))
            Group(
                listOf(
                    { AboutRow("Version", "1.0.0") },
                    { AboutRow("Licence", "GPL-3.0") },
                    { AboutRow("Playback built on", "Orpheon") },
                    { AboutRow("Interface after", "Mochy Music, Orpheon") },
                ),
            )
        }
        PageBottom()
    }
}

/** The line under a group's title that says what the group does. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = ValenColors.Text2,
        modifier = Modifier.padding(bottom = Space.step4),
    )
}

/** One raised surface holding a group's rows, a hairline between each, inset to the text. */
@Composable
private fun Group(rows: List<@Composable () -> Unit>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.cardShape)
            .background(ValenColors.Surface)
            .padding(vertical = Space.step1),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.step4)
                        .height(1.dp)
                        .background(ValenColors.Hairline),
                )
            }
            row()
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = Space.step4),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
        )
    }
}

/** A service in the preference order, moved a place at a time; no drag gesture to misread. */
@Composable
private fun SourceRow(
    position: Int,
    provider: ProviderId,
    canRise: Boolean,
    canFall: Boolean,
    onRise: () -> Unit,
    onFall: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(start = Space.step3, end = Space.step2),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ValenColors.Fill),
        ) {
            Text(text = "$position", style = timeStyle(), color = ValenColors.Text)
        }
        Text(
            text = provider.label,
            style = MaterialTheme.typography.titleMedium,
            color = ValenColors.Text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        )
        IconTap(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = "Move ${provider.label} up",
            onClick = onRise,
            enabled = canRise,
            tint = ValenColors.Text,
            iconSize = 24.dp,
        )
        IconTap(
            icon = Icons.Rounded.KeyboardArrowDown,
            label = "Move ${provider.label} down",
            onClick = onFall,
            enabled = canFall,
            tint = ValenColors.Text,
            iconSize = 24.dp,
        )
    }
}

private fun List<ProviderId>.moved(from: Int, to: Int): List<ProviderId> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}
