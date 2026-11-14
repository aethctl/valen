/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The one filled action in an area: white with black ink, never an accent colour, so it reads the
 * same over the black page and over any artwork wash.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
) {
    PillButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        small = small,
        rest = ValenColors.Text,
        lifted = ValenColors.Text.copy(alpha = 0.86f),
        content = ValenColors.OnLight,
    )
}

/** A second action beside a primary one: a translucent white fill, white ink. */
@Composable
fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    contentColor: Color = ValenColors.Text,
) {
    PillButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        small = small,
        rest = ValenColors.Fill,
        lifted = ValenColors.FillStrong,
        content = contentColor,
    )
}

/**
 * Both buttons. The small one draws a 34dp pill but still answers across a 44dp target, so a
 * compact control on a desktop row is no harder to hit on a phone.
 */
@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    small: Boolean,
    rest: Color,
    lifted: Color,
    content: Color,
) {
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> ValenColors.Fill
            hovered || focused -> lifted
            else -> rest
        },
        animationSpec = Motion.press(),
        label = "buttonFill",
    )
    val ink = if (enabled) content else ValenColors.Text3
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minHeight = Layout.hitTarget)
            .pressScale(source)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.step2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(if (small) 34.dp else Layout.hitTarget)
                .clip(Radii.pillShape)
                .background(fill)
                .padding(
                    start = if (icon != null) (if (small) Space.step3 else Space.step4) else (if (small) Space.step4 else Space.step5),
                    end = if (small) Space.step4 else Space.step5,
                ),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (small) 16.dp else 20.dp))
            }
            Text(
                text = label,
                style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = ink,
                maxLines = 1,
            )
        }
    }
}
