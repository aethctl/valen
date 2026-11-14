/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay

/**
 * What the app has to say right now, floating at the top of the content.
 *
 * Two kinds, and they behave differently on purpose. A [confirmation] ("Added to queue") is a nod:
 * a tick, and it leaves by itself. Anything else is a provider's own message, in its own words,
 * and it stays until it is dismissed, because a failure that fades away has been hidden.
 */
@Composable
fun NoticeToast(
    message: String,
    confirmation: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (confirmation) {
        LaunchedEffect(message) {
            delay(Motion.ToastMs)
            onDismiss()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 560.dp)
            .heightIn(min = 48.dp)
            .glass(Radii.cardShape)
            .padding(start = Space.step4, end = if (confirmation) Space.step5 else Space.step1),
    ) {
        Icon(
            imageVector = if (confirmation) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (confirmation) ValenColors.Success else ValenColors.Danger,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = Space.step3, vertical = Space.step3),
        )
        if (!confirmation) {
            IconTap(icon = Icons.Rounded.Close, label = "Dismiss", onClick = onDismiss, iconSize = 18.dp)
        }
    }
}

/** One service's failure, inside the results it failed to add to: compact, and named. */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier.heightIn(min = Layout.hitTarget - Space.step2),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = ValenColors.Danger,
            modifier = Modifier.size(16.dp),
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2)
    }
}
