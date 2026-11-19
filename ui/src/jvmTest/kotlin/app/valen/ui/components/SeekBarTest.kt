/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** The same rail stays mounted as a song changes or its duration becomes known. */
@OptIn(ExperimentalTestApi::class)
@Timeout(30)
class SeekBarTest {
    @Test
    fun `player bar taps use the current song duration`() = checkSeeking(inline = true, drag = false)

    @Test
    fun `stage taps use the current song duration`() = checkSeeking(inline = false, drag = false)

    @Test
    fun `player bar drags use the current song duration`() = checkSeeking(inline = true, drag = true)

    private fun checkSeeking(inline: Boolean, drag: Boolean) = runSkikoComposeUiTest {
        val duration = mutableLongStateOf(200_000L)
        val sought = mutableListOf<Long>()
        setContent {
            Box(Modifier.width(400.dp)) {
                if (inline) {
                    InlineSeek(0L, duration.longValue, { sought += it }, Modifier.testTag("seek"), showTimes = false)
                } else {
                    SeekBar(0L, duration.longValue, { sought += it }, Modifier.testTag("seek"))
                }
            }
        }
        // Start the pointer handlers, then change songs without removing the rail. Both a
        // longer and a shorter duration must replace the one captured by the first gesture.
        for (length in listOf(200_000L, 240_000L, 160_000L)) {
            runOnIdle { duration.longValue = length }
            onNodeWithTag("seek").performTouchInput {
                if (drag) {
                    down(Offset(width * 0.25f, 4f))
                    moveTo(Offset(width * 0.75f, 4f), delayMillis = 200)
                    up()
                } else {
                    click(Offset(center.x, 4f))
                }
            }
            runOnIdle {
                assertEquals((length * if (drag) 0.75 else 0.5).toLong(), sought.last())
            }
        }
        runOnIdle { assertEquals(3, sought.size, "one seek per gesture") }
    }
}
