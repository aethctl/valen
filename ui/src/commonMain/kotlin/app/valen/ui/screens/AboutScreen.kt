/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.animation.core.Animatable
import app.valen.ui.components.ScrollPage
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.valen.ui.components.Artwork
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.KineticText
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTop
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.arrive
import app.valen.ui.components.hoverLift
import app.valen.ui.components.pasted
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tilt
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import org.jetbrains.compose.resources.painterResource
import valen.ui.generated.resources.Res
import valen.ui.generated.resources.bade_profile

private const val GitHub = "https://github.com/dodo986"

/** The developer's picture, as GitHub has it now; the day in the address keeps it fresh daily. */
private fun githubAvatar(): String = "$GitHub.png?size=320&d=${System.currentTimeMillis() / 86_400_000L}"

/**
 * Who made Valen: two cards side by side (stacked on a phone), each a picture, a name with a
 * second name under it like its shadow, and what they did. The developer's picture is their
 * GitHub picture, read live.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    ScrollPage(key = "about", modifier = modifier.fillMaxSize()) {
        PageTop()
        ContentColumn {
            KineticText(
                text = "About",
                style = if (LocalChrome.current.compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                color = ValenColors.Text,
                underline = ValenColors.Primary,
            )
            Spacer(Modifier.height(Space.step2))
            Text("Valen 1.0.0 · GPL-3.0", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, modifier = Modifier.arrive(1))
            Spacer(Modifier.height(Space.step8))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = maxWidth >= 820.dp
                val dodo: @Composable (Modifier) -> Unit = { m ->
                    CreditCard(
                        name = "dodo986",
                        shadow = "erdem",
                        roles = listOf("Main Developer", "Ui / Ux", "Maintainer", "Every other shit"),
                        picture = { Artwork(url = githubAvatar(), shape = CircleShape, iconSize = 40.dp, modifier = Modifier.fillMaxSize()) },
                        index = 2,
                        action = {
                            PrimaryButton(
                                label = "GitHub",
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                onClick = { openLink(uri, GitHub) },
                            )
                        },
                        modifier = m,
                    )
                }
                val valen: @Composable (Modifier) -> Unit = { m ->
                    val painter: Painter = painterResource(Res.drawable.bade_profile)
                    CreditCard(
                        name = "Valen",
                        shadow = "bade",
                        roles = listOf("Art", "Inspiration", "Existing", "Overall best person", "Motivation"),
                        picture = { Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) },
                        index = 4,
                        action = null,
                        modifier = m,
                    )
                }
                if (side) {
                    Row(verticalAlignment = Alignment.Top) {
                        dodo(Modifier.weight(1f))
                        Heart(Modifier.width(72.dp).padding(top = 120.dp))
                        valen(Modifier.weight(1f))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        dodo(Modifier.fillMaxWidth())
                        Heart(Modifier.width(56.dp).padding(vertical = Space.step4))
                        valen(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        PageBottom()
    }
}

/** Opens [url] in the browser; on a desktop that cannot hand it over, through `xdg-open`. */
internal fun openLink(handler: androidx.compose.ui.platform.UriHandler, url: String) {
    runCatching { handler.openUri(url) }.onFailure {
        runCatching { ProcessBuilder("xdg-open", url).start() }
    }
}

/**
 * One person: their picture in a ring of the one colour (it leans toward the pointer), their name
 * with a second, smaller, darker name tucked under it like a shadow, what they did, and maybe a
 * button. The card is pasted to the wall a touch crooked and straightens under the pointer.
 */
@Composable
private fun CreditCard(
    name: String,
    shadow: String,
    roles: List<String>,
    picture: @Composable () -> Unit,
    index: Int,
    action: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteraction()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .arrive(index, ItemArrival.Slap)
            .pasted(seed = name.hashCode(), source = source)
            .hoverLift(source)
            .clip(Radii.cardShape)
            .background(ValenColors.Surface)
            .padding(horizontal = Space.step6, vertical = Space.step8),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(148.dp)
                .tilt()
                .border(3.dp, ValenColors.Primary, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(ValenColors.Container),
        ) {
            picture()
        }
        Spacer(Modifier.height(Space.step6))
        // The name, and under it its shadow: the second name, smaller and darker, tucked in.
        Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.arrive(index + 1)) {
            Text(
                text = shadow,
                style = MaterialTheme.typography.headlineMedium,
                color = ValenColors.Container,
                modifier = Modifier.offset(x = 10.dp, y = 34.dp),
            )
            Text(text = name, style = MaterialTheme.typography.displaySmall, color = ValenColors.Text)
        }
        Spacer(Modifier.height(Space.step10))
        Column(verticalArrangement = Arrangement.spacedBy(Space.step3), modifier = Modifier.width(IntrinsicWidth)) {
            roles.forEachIndexed { i, role ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.arrive(index + 2 + i, ItemArrival.Swing)) {
                    Box(
                        Modifier
                            .size(width = 18.dp, height = 6.dp)
                            .clip(Radii.pillShape)
                            .background(ValenColors.Primary),
                    )
                    Spacer(Modifier.width(Space.step3))
                    Text(role, style = MaterialTheme.typography.titleLarge, color = ValenColors.Text)
                }
            }
        }
        if (action != null) {
            Spacer(Modifier.height(Space.step8))
            Box(Modifier.arrive(index + 2 + roles.size)) { action() }
        }
    }
}

/** The roles sit in one block, as wide as its longest line, in the middle of the card. */
private val IntrinsicWidth = 260.dp

/** A small heart drawn with the marker, on as the page arrives, between the two cards. */
@Composable
private fun Heart(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val draw = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) draw.animateTo(1f, tween(Motion.MarkerMs * 2, delayMillis = Motion.EnterMs * 2, easing = Motion.Standard)) }
    val colour = ValenColors.Primary
    Box(
        modifier
            .height(64.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.86f)
                    cubicTo(w * 0.02f, h * 0.52f, w * 0.08f, h * 0.08f, w * 0.5f, h * 0.3f)
                    cubicTo(w * 0.92f, h * 0.06f, w * 1.0f, h * 0.55f, w * 0.52f, h * 0.88f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val piece = Path()
                measure.getSegment(0f, measure.length * draw.value, piece, true)
                drawPath(piece, colour, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (draw.value >= 1f) drawCircle(colour, radius = 2.5.dp.toPx(), center = Offset(w * 0.52f, h * 0.88f + 9.dp.toPx()))
            },
    )
}
