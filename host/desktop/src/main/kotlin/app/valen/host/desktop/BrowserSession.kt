/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises visible sign-ins and silent recovery, with a bounded retry budget until Retry. */
internal class BrowserSession(
    private val start: suspend (visible: Boolean) -> Boolean,
    private val stop: () -> Unit,
    private val reportFailure: () -> Unit,
) : AutoCloseable {
    private val lock = Mutex()
    private var failures = 0

    @Volatile var closed = false
        private set
    @Volatile var starting = false
        private set

    suspend fun ensure(visible: Boolean = false): Boolean = lock.withLock {
        if (closed) return false
        if (visible) failures = 0
        if (failures >= MAX_FAILURES) return false
        starting = true
        try {
            val ok = try {
                start(visible)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (closed) return false
            if (ok) failures = 0
            else if (++failures == MAX_FAILURES) reportFailure()
            ok
        } finally {
            // close() is synchronous and can race a suspended start. Stop a process that
            // finished launching after shutdown too; no later poll may bring it back.
            if (closed) stop()
            starting = false
        }
    }

    override fun close() {
        closed = true
        stop()
    }

    private companion object {
        const val MAX_FAILURES = 3
    }
}
