/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.ui.Route
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Phone navigation: a capsule of liquid glass floating at the bottom, as wide as its places need
 * and no wider. The place you are in sits in a droplet of brighter glass and is the only one that
 * spells its name.
 *
 * The droplet's two ends travel on different springs — the leading end quick, the trailing end
 * slower — so moving between places it stretches toward the new one and then gathers itself
 * there, like a drop pulled across glass.
 */
@Composable
fun BottomNav(
    current: Route,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(Route.Home, Route.Search, Route.Playlists, Route.NowPlaying)
    val selected = items.indexOfFirst { it == current || (it == Route.NowPlaying && current == Route.Queue) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val reduced = LocalReducedMotion.current
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    LaunchedEffect(selected, reduced) {
        snapshotFlow { bounds[selected] }.collect { target ->
            if (target == null) return@collect
            val first = left.value == 0f && right.value == 0f
            if (first || reduced) {
                left.snapTo(target.left)
                right.snapTo(target.right)
                return@collect
            }
            val forward = target.left > left.value
            coroutineScope {
                launch { left.animateTo(target.left, if (forward) Motion.liquidTrail() else Motion.liquidLead()) }
                launch { right.animateTo(target.right, if (forward) Motion.liquidLead() else Motion.liquidTrail()) }
            }
        }
    }
    GlassSurface(
        shape = Radii.pillShape,
        modifier = modifier.height(Layout.bottomNavHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.step1),
            modifier = Modifier
                .padding(Space.step2)
                .drawBehind {
                    if (selected >= 0 && right.value > left.value) {
                        drawDroplet(Rect(left.value, 0f, right.value, size.height), vertical = false)
                    }
                },
        ) {
            items.forEachIndexed { index, route ->
                BottomNavItem(
                    route = route,
                    selected = index == selected,
                    onClick = { onNavigate(route) },
                    // In the row's own coordinates, which are the ones its droplet is drawn in.
                    modifier = Modifier.onGloballyPositioned { bounds[index] = it.boundsInParent() },
                )
            }
        }
    }
}


@Composable
private fun BottomNavItem(
    route: Route,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val ink by animateColorAsState(
        targetValue = if (selected) ValenColors.OnContainer else ValenColors.Text2,
        animationSpec = Motion.standard(),
        label = "tabInk",
    )
    val source = rememberInteraction()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 64.dp)
            .pressScale(source)
            .tap(
                onClick = onClick,
                shape = Radii.pillShape,
                rest = Color.Transparent,
                role = Role.Tab,
                source = source,
            )
            .padding(horizontal = Space.step4),
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = if (selected) null else route.label,
            tint = ink,
            modifier = Modifier.size(24.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = if (reduced) fadeIn(Motion.fade()) else expandHorizontally(Motion.liquid()) + fadeIn(Motion.standard()),
            exit = if (reduced) fadeOut(Motion.fade()) else shrinkHorizontally(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
            Text(
                text = route.label,
                style = MaterialTheme.typography.labelLarge,
                color = ValenColors.OnContainer,
                maxLines = 1,
                modifier = Modifier.padding(start = Space.step2),
            )
        }
    }
}
