/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.ui.Route
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Navigation for wide layouts, as a panel that floats over the page rather than a column fixed to
 * its edge: a screen's wash runs underneath it. With labels at full width; [condensed] to an icon
 * rail in between phone and desktop.
 *
 * Where you are is a lighter pill behind the item, nothing more.
 */
@Composable
fun Sidebar(
    current: Route,
    onNavigate: (Route) -> Unit,
    sessions: Map<ProviderId, SessionState>,
    modifier: Modifier = Modifier,
    condensed: Boolean = false,
) {
    val side = if (condensed) Space.step3 else Space.step3
    Column(
        horizontalAlignment = if (condensed) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier
            .width(if (condensed) Layout.railWidth else Layout.sidebarWidth)
            .fillMaxHeight()
            .glass(Radii.artworkShape)
            .padding(horizontal = side, vertical = Space.step4),
    ) {
        Box(
            contentAlignment = if (condensed) Alignment.Center else Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(Layout.hitTarget)
                .padding(horizontal = if (condensed) 0.dp else Space.step3),
        ) {
            Text(
                text = if (condensed) "V" else "Valen",
                style = MaterialTheme.typography.headlineLarge,
                color = ValenColors.Text,
            )
        }
        Spacer(Modifier.height(Space.step5))

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(Route.Home, Route.Search, Route.NowPlaying, Route.Queue).forEach { route ->
                SidebarItem(
                    route = route,
                    selected = current == route,
                    condensed = condensed,
                    onClick = { onNavigate(route) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (!condensed) {
            Text(
                text = "Services",
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text3,
                modifier = Modifier.padding(start = Space.step3, bottom = Space.step1),
            )
        }
        Column {
            ProviderId.entries.forEach { provider ->
                val state = sessions[provider] ?: SessionState.SignedOut
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (condensed) Arrangement.Center else Arrangement.Start,
                    modifier = Modifier
                        .then(if (condensed) Modifier.size(Layout.hitTarget) else Modifier.fillMaxWidth().height(Layout.hitTarget))
                        .tap(onClick = { onNavigate(Route.Settings) }, shape = Radii.pillShape)
                        .padding(horizontal = if (condensed) 0.dp else Space.step3),
                ) {
                    ServiceStatusDot(state)
                    if (!condensed) {
                        Text(
                            text = provider.short,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ValenColors.Text,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Space.step3),
                        )
                        Text(
                            text = state.word(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ValenColors.Text3,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.step2))
        SidebarItem(
            route = Route.Settings,
            selected = current == Route.Settings,
            condensed = condensed,
            onClick = { onNavigate(Route.Settings) },
        )
    }
}

@Composable
private fun SidebarItem(
    route: Route,
    selected: Boolean,
    condensed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (condensed) Arrangement.Center else Arrangement.spacedBy(Space.step3),
        modifier = Modifier
            .then(if (condensed) Modifier.size(Layout.hitTarget) else Modifier.fillMaxWidth().height(Layout.hitTarget))
            .tap(
                onClick = onClick,
                shape = Radii.pillShape,
                rest = if (selected) ValenColors.Fill else Color.Transparent,
                role = Role.Tab,
            )
            .padding(horizontal = if (condensed) 0.dp else Space.step3),
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = if (condensed) route.label else null,
            tint = if (selected) ValenColors.Text else ValenColors.Text2,
            modifier = Modifier.size(22.dp),
        )
        if (!condensed) {
            Text(
                text = route.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) ValenColors.Text else ValenColors.Text2,
                maxLines = 1,
            )
        }
    }
}
