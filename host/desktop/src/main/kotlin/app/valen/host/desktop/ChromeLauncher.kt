/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One Chrome, launched by Valen itself with a private profile, for the CDP hosts of this host.
 *
 * The profile lives in `$XDG_DATA_HOME/valen/chrome` (default `~/.local/share/valen/chrome`), so
 * the user's sign-ins survive restarts and never touch the browser they use for everything else.
 * A Chrome already listening on [port] is reused as it is (the user may have started it, or a
 * previous run left it open); only a Chrome this class started is stopped by [close]. The one
 * exception is a sign-in: a headless browser has no page a person can log in on, so [start] with
 * `visible = true` stops it ([stopBrowser]) and opens a windowed one, whoever started it.
 *
 * It runs **headless** unless asked otherwise: Spotify's Web Player needs a browser engine, not a
 * window, and a headless engine still opens a real audio stream (verified against PipeWire), so
 * music plays with nothing on screen. A sign-in is the one thing a person has to do in a window,
 * hence [start] takes `visible`; `VALEN_CHROME_VISIBLE=1` keeps every launch visible.
 */
class ChromeLauncher(
    val port: Int = DEFAULT_PORT,
    /** `$XDG_DATA_HOME/valen/chrome` unless [VALEN_CHROME_PROFILE] says otherwise. */
    val profileDir: Path = defaultProfileDir(),
    val startUrl: String = "https://open.spotify.com",
    /** Re-discovered on a later [start] when the first search found nothing. */
    var binary: Path? = findBinary(),
    /** Waits this long for `/json/version` after launching before giving up. */
    private val readyTimeoutMs: Long = 30_000,
) : AutoCloseable {

    /** How many browsers this launcher has launched, so a caller notices a replacement. */
    @Volatile var launchCount: Int = 0
        private set

    /** Whether the browser this launcher uses has no window. */
    @Volatile var headless: Boolean = System.getenv("VALEN_CHROME_VISIBLE") == null
        private set

    /** The DevTools endpoint, the url the CDP hosts attach to. */
    val cdpUrl: String get() = "http://127.0.0.1:$port"

    @Volatile private var process: Process? = null

    /** Whether this launcher is talking to a Chrome it started itself. */
    val startedHere: Boolean get() = process != null

    /**
     * Starts Chrome with the private profile, or reuses the one already listening on [port].
     * Returns true when the browser Valen needs is up afterwards; never throws for a missing Chrome
     * (the reason is in [lastError]). With `visible = true` the browser must have a window: a
     * headless one is replaced, and a browser whose state cannot be read is a false with a reason.
     */
    suspend fun start(visible: Boolean = !headless): Boolean {
        val wantHeadless = !visible
        if (probe()) {
            // Only a sign-in needs a window. For playback any browser already running is good
            // enough: replacing a visible one would close the page the user is logging in on, and
            // replacing a headless one would restart the engine for nothing.
            val actual = isHeadless()
            if (actual != null) headless = actual
            if (visible && actual == true) {
                // A headless browser is replaced by a windowed one even when this launcher did not
                // start it: an earlier Valen run that was killed never called close(), and a
                // person cannot log in to a page they cannot see. Silence here was the bug - a
                // Connect that did nothing looked like a dead button.
                if (!stopBrowser()) {
                    lastError = "Could not close the headless Chrome on $cdpUrl: quit it and try again"
                    return false
                }
            } else if (visible && actual == null) {
                // Something answers on the port but no browser process owns it: a window cannot be
                // promised, and guessing would either kill the wrong thing or do nothing again.
                lastError = "Chrome answers on $cdpUrl but its window state could not be read: quit that browser and try again"
                return false
            } else {
                lastError = null
                return true
            }
        }
        val exe = binary ?: findBinary()?.also { binary = it } ?: run {
            lastError = "Chrome not found: install Chrome or Chromium, or set VALEN_CHROME_BIN"
            return false
        }
        if (!Files.isExecutable(exe)) { lastError = "Chrome is not executable: $exe"; return false }
        runCatching { Files.createDirectories(profileDir) }
            .onFailure { lastError = "Could not create ${profileDir}: ${it.message}"; return false }
        val log = profileDir.resolve("chrome.log")
        val command = buildList {
            add(exe.toString())
            if (wantHeadless) {
                // The engine, without a window. Media still reaches the sound card.
                add("--headless=new")
                // A headless user agent would make some players refuse; this one looks ordinary.
                userAgent(exe)?.let { add("--user-agent=$it") }
            }
            add("--user-data-dir=${profileDir.toAbsolutePath()}")
            add("--remote-debugging-port=$port")
            add("--remote-debugging-address=127.0.0.1")
            add("--no-first-run")
            add("--no-default-browser-check")
            add("--autoplay-policy=no-user-gesture-required")
            add("--disable-background-timer-throttling")
            add("--disable-renderer-backgrounding")
            add("--disable-backgrounding-occluded-windows")
            add(startUrl)
        }
        headless = wantHeadless
        launchCount++
        process = runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
        }.onFailure { lastError = "Could not start Chrome: ${it.message}" }.getOrNull() ?: return false
        val ok = awaitReady()
        if (!ok && process?.isAlive == true) {
            // A Chrome that refused to run its sandbox (some distros restrict user namespaces)
            // says so in the log: one retry without it is better than no player at all.
            val saidSandbox = runCatching { Files.readString(log) }.getOrDefault("")
                .contains("sandbox", ignoreCase = true)
            if (saidSandbox) {
                process?.destroy()
                runCatching { process?.waitFor() }
                process = runCatching {
                    ProcessBuilder(command + "--no-sandbox")
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start()
                }.getOrNull()
                if (awaitReady()) {
                    lastError = null
                    return true
                }
            }
            lastError = "Chrome did not open $cdpUrl within ${readyTimeoutMs / 1000}s: see $log"
        }
        if (ok) lastError = null
        return ok
    }

    /**
     * Whether the browser on [port] has no window; null when no browser process can be identified.
     *
     * Read from the process itself, not from `/json/version`: a headless engine is launched with an
     * ordinary user agent on purpose (the Web Player refuses a headless one), so the endpoint's
     * user agent says "Chrome", never "HeadlessChrome", and the old check always answered "visible".
     */
    suspend fun isHeadless(): Boolean? = withContext(Dispatchers.IO) {
        val line = browserCommandLine() ?: return@withContext null
        flags(line).any { it == "--headless" || it.startsWith("--headless=") }
    }

    /**
     * The command line of the browser itself, when one can be identified. Not its helpers: every
     * renderer and zygote repeats the launch flags (including the DevTools port) but says nothing
     * about windows, so only the process without a `--type=` is the browser.
     */
    private fun browserCommandLine(): String? = runCatching {
        ProcessHandle.allProcesses()
            .filter { isBrowserOnPort(it, "--remote-debugging-port=$port") }
            .filter { handle -> flags(commandLineOf(handle)).none { it.startsWith("--type=") } }
            .findFirst()
            .map { handle -> handle.info().commandLine().orElse("") }
            .orElse(null)
    }.getOrNull()

    /**
     * Every live process belonging to the browser on this launcher's DevTools port - the browser and
     * its helpers - whichever process started it.
     */
    private fun browserProcesses(): List<ProcessHandle> = runCatching {
        ProcessHandle.allProcesses()
            .filter { isBrowserOnPort(it, "--remote-debugging-port=$port") }
            .toList()
    }.getOrDefault(emptyList())

    private fun isBrowserOnPort(handle: ProcessHandle, flag: String): Boolean {
        val line = commandLineOf(handle)
        if (!flags(line).contains(flag)) return false
        val exe = line.substringBefore(' ').substringAfterLast('/')
        return BROWSER_EXECUTABLE.containsMatchIn(exe)
    }

    private fun commandLineOf(handle: ProcessHandle): String =
        runCatching { handle.info().commandLine().orElse("") }.getOrDefault("")

    private fun flags(line: String): List<String> = line.split(' ')

    /**
     * Stops the browser this launcher is talking to, whether it started it or an earlier run left
     * it behind, and waits until the port is free. Only ever called when a window is required and
     * the running browser has none: one browser per profile and port can exist at a time.
     */
    suspend fun stopBrowser(): Boolean {
        val targets = buildList {
            process?.let { add(it.toHandle()) }
            addAll(browserProcesses())
        }.distinct()
        process = null
        if (targets.isEmpty()) return true
        targets.forEach { runCatching { it.destroy() } }
        val politeBy = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < politeBy && targets.any { it.isAlive }) delay(100)
        targets.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
        val goneBy = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < goneBy && (targets.any { it.isAlive } || probe())) delay(100)
        if (targets.any { it.isAlive } || probe()) return false
        lastError = null
        return true
    }

    /** The engine's own major version, for a plausible user agent. */
    private fun userAgent(exe: Path): String? {
        cachedUserAgent?.let { return it }
        val major = runCatching {
            val p = ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start()
            val said = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(5, TimeUnit.SECONDS)
            (Regex("""Chrome\s+(\d+)\.""").find(said)
                ?: Regex("""(\d+)\.\d+\.\d+\.\d+""").find(said))?.groupValues?.get(1)
        }.getOrNull() ?: return null
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36".also { cachedUserAgent = it }
    }

    @Volatile private var cachedUserAgent: String? = null

    /** Whether `/json/version` answers now. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = http ?: HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build().also { http = it }
            val request = HttpRequest.newBuilder(URI.create("$cdpUrl/json/version")).timeout(Duration.ofMillis(1200)).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.onFailure {
            // A probe that fails for a reason other than "not up yet" is not a slow start: say so.
            if (System.getenv("VALEN_CHROME_LOG") != null) System.err.println("valen: CDP probe $cdpUrl: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /** Waits until `/json/version` answers, or [readyTimeoutMs] passes. */
    suspend fun awaitReady(timeoutMs: Long = readyTimeoutMs): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            if (process?.isAlive == false) {
                // A second Chrome on the same profile hands its command line to the first one
                // and exits at once; the port may answer the moment it is gone.
                if (probe()) return true
                lastError = "Chrome exited while starting: see ${profileDir.resolve("chrome.log")}"
                return false
            }
            delay(200)
        }
        return probe()
    }

    /** Opens [url] in a new tab of this Chrome (a no-op when it is not running). */
    suspend fun openTab(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = http ?: HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build().also { http = it }
            val request = HttpRequest.newBuilder(URI.create("$cdpUrl/json/new?$url"))
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }.getOrDefault(false)
    }

    /** The reason the last [start] failed, when it did. */
    @Volatile var lastError: String? = null
        private set

    @Volatile private var http: HttpClient? = null

    /** Stops the Chrome this launcher started; a Chrome that was already running is left alone. */
    override fun close() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    companion object {
        const val DEFAULT_PORT = 9333

        /** How long a browser gets to close politely, then forcibly, before a sign-in gives up. */
        private const val STOP_TIMEOUT_MS = 4_000L

        /** `$XDG_DATA_HOME/valen/chrome`, default `~/.local/share/valen/chrome`. */
        fun defaultProfileDir(): Path {
            System.getenv("VALEN_CHROME_PROFILE")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return Paths.get(dataHome, "valen", "chrome")
        }

        /**
         * `$VALEN_CHROME_BIN`, then the project's `.tools/chrome/opt/google/chrome/chrome` (walking
         * up from the working directory), then any Chromium-family browser this machine has.
         */
        fun findBinary(): Path? {
            System.getenv("VALEN_CHROME_BIN")?.takeIf { it.isNotBlank() }?.let {
                val p = Paths.get(it)
                if (Files.isExecutable(p)) return p
            }
            val local = projectRoot()?.resolve(".tools/chrome/opt/google/chrome/chrome")
            if (local != null && Files.isExecutable(local)) return local
            return systemBinary()
        }

        /**
         * A Chromium-family browser already on the machine. Only the DevTools port and the usual
         * autoplay flags are used, so Chrome, Chromium, Helium, Brave, Vivaldi and Edge all work.
         * Each candidate is asked its `--version` first: a `chrome` on `PATH` that is something
         * else (a wrapper, an unrelated tool) is never launched.
         */
        fun systemBinary(): Path? {
            val seen = LinkedHashSet<Path>()
            val names = listOf(
                "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome",
                "helium-browser", "brave-browser", "vivaldi-stable",
                "microsoft-edge", "microsoft-edge-stable",
            )
            for (name in names) {
                val candidate = onPath(name) ?: continue
                if (!seen.add(candidate)) continue
                if (isChromium(candidate)) return candidate
            }
            for (fixed in KNOWN_BINARIES) {
                val candidate = runCatching { Paths.get(fixed).toAbsolutePath().normalize() }.getOrNull() ?: continue
                if (!Files.isExecutable(candidate) || !seen.add(candidate)) continue
                if (isChromium(candidate)) return candidate
            }
            for (candidate in desktopExecutables()) {
                if (!seen.add(candidate)) continue
                if (isChromium(candidate)) return candidate
            }
            return null
        }

        /** The executable [name] resolves to on `PATH`, if any. */
        private fun onPath(name: String): Path? {
            val dirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
            return dirs.asSequence()
                .map { runCatching { Paths.get(it, name) }.getOrNull() }
                .filterNotNull()
                .firstOrNull { Files.isExecutable(it) }
                ?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() }
        }

        /**
         * Executables named by this machine's application entries. A browser installed under a name
         * Valen does not know (Helium, a distro build, a Flatpak) still declares itself in its
         * `.desktop` file, so this is the fallback that keeps the list from being a guess.
         */
        private fun desktopExecutables(): List<Path> {
            val dirs = buildList {
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { add(Paths.get(it, "applications")) }
                add(Paths.get(System.getProperty("user.home"), ".local/share/applications"))
                (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() }?.split(':')
                    ?: listOf("/usr/local/share", "/usr/share"))
                    .forEach { add(Paths.get(it, "applications")) }
                add(Paths.get("/var/lib/flatpak/exports/share/applications"))
                add(Paths.get(System.getProperty("user.home"), ".local/share/flatpak/exports/share/applications"))
            }
            val found = LinkedHashSet<Path>()
            for (dir in dirs.distinct()) {
                val files = runCatching { Files.list(dir).use { s -> s.toList() } }.getOrNull() ?: continue
                for (file in files) {
                    if (!file.fileName.toString().endsWith(".desktop")) continue
                    val text = runCatching { Files.readString(file).take(16_384) }.getOrNull() ?: continue
                    if (!CHROMIUM_HINT.containsMatchIn(text)) continue
                    for (line in text.lineSequence()) {
                        if (!line.startsWith("Exec=")) continue
                        val token = line.removePrefix("Exec=").trim().split(' ').firstOrNull() ?: continue
                        val name = token.trim('"', '\'').substringBefore(' ')
                        if (name.isBlank() || name.contains('%') || name.contains('$')) continue
                        val exe = if (name.contains('/')) Paths.get(name) else onPath(name)
                        if (exe == null || !Files.isExecutable(exe)) continue
                        val absolute = runCatching { exe.toAbsolutePath().normalize() }.getOrNull() ?: continue
                        found.add(absolute)
                    }
                }
            }
            return found.toList()
        }

        /** Whether [exe] answers `--version` as a Chromium build. Never throws, never keeps it open. */
        private fun isChromium(exe: Path): Boolean = runCatching {
            val process = ProcessBuilder(exe.toString(), "--version")
                .redirectErrorStream(true)
                .start()
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) return@runCatching false
                val text = process.inputStream.bufferedReader().use { it.readText() }.take(600)
                text.contains("chromium", ignoreCase = true) || text.contains("chrome/", ignoreCase = true)
            } finally {
                process.destroyForcibly()
            }
        }.getOrDefault(false)

        private val CHROMIUM_HINT = Regex("(?i)chromium|google.chrome|helium|brave|vivaldi|microsoft.edge")

        /** A process whose executable name is a Chromium-family browser. */
        private val BROWSER_EXECUTABLE = Regex("(?i)chrom|helium|brave|vivaldi|edge")

        private val KNOWN_BINARIES = listOf(
            "/opt/google/chrome/chrome",
            "/opt/google/chrome-beta/chrome",
            "/opt/helium-browser-bin/helium",
            "/opt/brave.com/brave/brave",
            "/opt/vivaldi/vivaldi",
            "/opt/microsoft/msedge/msedge",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/lib/chromium/chromium",
            "/snap/bin/chromium",
            "/var/lib/flatpak/exports/bin/com.google.Chrome",
            "/var/lib/flatpak/exports/bin/org.chromium.Chromium",
        )

        /** The Valen checkout holding `.tools/`, found from the working directory upwards. */
        fun projectRoot(): Path? {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(".tools"))) return dir
                dir = dir.parent
            }
            return null
        }
    }
}
