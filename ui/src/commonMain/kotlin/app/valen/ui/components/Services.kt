/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** A service's glyph. Material Symbols only: no service's own logo is drawn. */
fun ProviderId.glyph(): ImageVector = when (this) {
    ProviderId.SPOTIFY -> Icons.Rounded.GraphicEq
    ProviderId.YOUTUBE_MUSIC -> Icons.Rounded.SmartDisplay
    ProviderId.SOUNDCLOUD -> Icons.Rounded.Cloud
}

/** The status dot is the only coloured element on a service's surface. */
@Composable
fun ServiceStatusDot(state: SessionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        SessionState.SignedOut -> ValenColors.Text3
        SessionState.Connecting -> ValenColors.Text2
        is SessionState.Connected -> ValenColors.Success
        is SessionState.Unavailable -> ValenColors.Danger
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** The state as a sentence, for a tile that has room for one. */
internal fun SessionState.sentence(): String = when (this) {
    SessionState.SignedOut -> "Not connected"
    SessionState.Connecting -> "Waiting for sign-in"
    is SessionState.Connected -> accountName?.takeIf { it.isNotBlank() } ?: "Connected"
    is SessionState.Unavailable -> reason
}

/** The state as one word, for the side panel. */
internal fun SessionState.word(): String = when (this) {
    SessionState.SignedOut -> "Sign in"
    SessionState.Connecting -> "Waiting"
    is SessionState.Connected -> "Ready"
    is SessionState.Unavailable -> "Offline"
}

/**
 * One service: its glyph, its name, what the adapter really reports, and the one thing that can
 * be done about it. On Home it is a tile of its own; in Settings ([contained]) it is a row inside
 * the group that holds all three, and gains a way to disconnect.
 */
@Composable
fun ServiceTile(
    provider: ProviderId,
    state: SessionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onDisconnect: (() -> Unit)? = null,
    contained: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (contained) Modifier else Modifier.clip(Radii.rowShape).background(ValenColors.Surface))
            .padding(start = Space.step3, end = Space.step3, top = Space.step2, bottom = Space.step2),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(Radii.coverShape)
                .background(ValenColors.Fill),
        ) {
            Icon(
                imageVector = provider.glyph(),
                contentDescription = null,
                tint = ValenColors.Text,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.label,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ServiceStatusDot(state, Modifier.size(6.dp))
                Text(
                    text = state.sentence(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    maxLines = if (state is SessionState.Unavailable) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (state) {
            SessionState.SignedOut -> PrimaryButton(label = "Connect", onClick = onConnect, small = true)
            SessionState.Connecting -> QuietButton(label = "Waiting", onClick = {}, enabled = false, small = true)
            is SessionState.Connected ->
                if (onDisconnect != null) {
                    QuietButton(label = "Disconnect", onClick = onDisconnect, small = true)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Connected",
                        tint = ValenColors.Success,
                        modifier = Modifier.padding(end = Space.step2).size(22.dp),
                    )
                }
            is SessionState.Unavailable -> QuietButton(label = "Retry", onClick = onConnect, small = true)
        }
    }
}
