/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.ProviderId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Typing at the import page's lists: their names and whose they are, whatever the case. */
class PlaylistImportTest {
    private fun list(name: String, owner: String? = null) = PlaylistRef(
        name = name,
        owner = owner,
        link = PlaylistLink(provider = ProviderId.SPOTIFY, playlistId = name, url = "https://open.spotify.com/playlist/$name"),
    )

    @Test
    fun `a list is found by a piece of its name, in any case`() {
        assertTrue(listMatches(list("Late night drive"), "night"))
        assertTrue(listMatches(list("Late night drive"), "NIGHT"))
        assertTrue(listMatches(list("Late night drive"), "late night drive"))
    }

    @Test
    fun `a list is found by whose it is`() {
        assertTrue(listMatches(list("shishaoabaivsja", owner = "dodo"), "DODO"))
        assertFalse(listMatches(list("shishaoabaivsja", owner = "dodo"), "someone else"))
    }

    @Test
    fun `a list with no owner is not matched by a name that is not in its title`() {
        assertFalse(listMatches(list("Liked songs"), "dodo"))
        assertTrue(listMatches(list("Liked songs"), "liked"))
    }

    @Test
    fun `nothing matches when neither the name nor the owner has it`() {
        assertFalse(listMatches(list("Late night drive", owner = "dodo"), "rap"))
        assertFalse(listMatches(list("Late night drive"), "radio"))
    }
}
