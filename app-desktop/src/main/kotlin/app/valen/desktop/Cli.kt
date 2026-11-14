/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.ProviderId
import app.valen.ui.Route
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop binary's command line. Everything here is for development and the screenshot pass:
 * a normal start takes no arguments at all.
 */
data class Cli(
    /** Window size in px; the desktop reference is 900×640 and the phone one 390×844. */
    val width: Int = 1440,
    val height: Int = 900,
    val route: Route = Route.Home,
    /** Text the search field starts with (`--query`). */
    val query: String? = null,
    /** `provider:id`: the song to start, looked up through that provider's own search. */
    val play: PlayRequest? = null,
    /** Save the window to this path after the UI is up and quit. */
    val screenshot: Path? = null,
    /** Prints the flags and exits. */
    val help: Boolean = false,
) {
    /** A `--play` argument: [provider] when it was named (`spotify:…`), otherwise a plain query. */
    data class PlayRequest(val provider: ProviderId?, val id: String, val raw: String) {
        companion object {
            fun parse(value: String): PlayRequest {
                val colon = value.indexOf(':')
                if (colon <= 0) return PlayRequest(null, value.trim(), value)
                val head = value.substring(0, colon).trim()
                val id = value.substring(colon + 1).trim()
                val provider = when (head.lowercase()) {
                    "spotify", "sp" -> ProviderId.SPOTIFY
                    "ytmusic", "youtube", "ytm", "yt" -> ProviderId.YOUTUBE_MUSIC
                    "soundcloud", "sc" -> ProviderId.SOUNDCLOUD
                    else -> null
                }
                return if (provider == null || id.isEmpty()) PlayRequest(null, value.trim(), value)
                else PlayRequest(provider, id, value)
            }
        }
    }

    companion object {
        const val DESKTOP_WIDTH = 900
        const val DESKTOP_HEIGHT = 640
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        val USAGE: String = """
Valen (desktop)

  valen [--width N] [--height N] [--route ROUTE] [--query TEXT]
        [--play PROVIDER:ID] [--screenshot PATH]

Development flags (not for normal use):
  --width N --height N   window size in px (desktop 900x640, phone 390x844)
  --route ROUTE          home | search | now-playing | queue | settings
  --query TEXT           text the search screen starts with (use --route search)
  --play PROVIDER:ID     start a song found by that provider's search, where PROVIDER is
                         spotify, ytmusic or soundcloud and ID is a track id of that service
                         (a bare TEXT searches every provider and plays the first result)
  --screenshot PATH      write the window to PATH (PNG) once the UI is up, then exit
  --help                 this text

Environment: VALEN_CHROME_BIN (Chrome binary), VALEN_CHROME_PROFILE (profile directory),
XDG_DATA_HOME (Chrome profile), XDG_CONFIG_HOME (settings.json).
""".trimIndent()

        /** Reads [args]; throws [IllegalArgumentException] with a readable message on a bad flag. */
        fun parse(args: Array<String>): Cli {
            var cli = Cli()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                fun value(): String {
                    if (i + 1 >= args.size) throw IllegalArgumentException("$arg needs a value")
                    return args[++i]
                }
                when (arg) {
                    "--help", "-h" -> cli = cli.copy(help = true)
                    "--width" -> cli = cli.copy(width = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--width needs a number"))
                    "--height" -> cli = cli.copy(height = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--height needs a number"))
                    "--route" -> cli = cli.copy(route = Route.from(value()))
                    "--query" -> cli = cli.copy(query = value())
                    "--play" -> cli = cli.copy(play = PlayRequest.parse(value()))
                    "--screenshot" -> cli = cli.copy(screenshot = expand(value()))
                    else -> throw IllegalArgumentException("unknown flag: $arg (see --help)")
                }
                i++
            }
            return cli
        }

        private fun expand(path: String): Path =
            Paths.get(if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path)
                .toAbsolutePath()
    }
}
