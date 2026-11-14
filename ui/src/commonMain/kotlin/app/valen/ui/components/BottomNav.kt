/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.ui.Route
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Phone navigation: a capsule floating at the bottom, as wide as its three places need and no
 * wider. The place you are in sits in a lighter pill and is the only one that spells its name.
 */
@Composable
fun BottomNav(
    current: Route,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(Route.Home, Route.Search, Route.NowPlaying)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step1),
        modifier = modifier
            .height(Layout.bottomNavHeight)
            .glass(Radii.pillShape)
            .padding(Space.step2),
    ) {
        items.forEach { route ->
            BottomNavItem(
                route = route,
                selected = current == route || (route == Route.NowPlaying && current == Route.Queue),
                onClick = { onNavigate(route) },
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    route: Route,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 64.dp)
            .tap(
                onClick = onClick,
                shape = Radii.pillShape,
                rest = if (selected) ValenColors.Fill else Color.Transparent,
                role = Role.Tab,
            )
            .padding(horizontal = Space.step4),
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = if (selected) null else route.label,
            tint = if (selected) ValenColors.Text else ValenColors.Text2,
            modifier = Modifier.size(24.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = if (reduced) fadeIn(Motion.fade()) else expandHorizontally(Motion.chrome()) + fadeIn(Motion.standard()),
            exit = if (reduced) fadeOut(Motion.fade()) else shrinkHorizontally(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
            Text(
                text = route.label,
                style = MaterialTheme.typography.labelLarge,
                color = ValenColors.Text,
                maxLines = 1,
                modifier = Modifier.padding(start = Space.step2),
            )
        }
    }
}
