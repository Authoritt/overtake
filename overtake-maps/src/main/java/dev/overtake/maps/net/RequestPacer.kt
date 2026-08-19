// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors. Derived from OpenCfMoto
// (Alexandru, https://alexandru.rocks), AGPL-3.0-or-later.
package dev.overtake.maps.net

/**
 * A minimum interval between calls to ONE expensive endpoint, expressed as a DECISION instead of a
 * sleep — so the caller can either wait for the slot (suspending, cancellable) or go without.
 *
 * [OvertakeHttp.throttle] already enforces the wire-level rate by BLOCKING a thread, and it stays as
 * the backstop. Blocking is the wrong tool for as-you-type search: every superseded keystroke still
 * queues a request, so by the time the query the rider actually cares about reaches the front of the
 * queue it is seconds late and dies on a read timeout (measured, phone logcat:
 * `[search] Nominatim failed: java.net.SocketTimeoutException: timeout` — after which the merged
 * list was Photon-only and the rider got results in Spain, Mexico and Peru).
 *
 * With a pacer the caller asks FIRST — [waitMs] to learn how long the slot is still closed, [take]
 * to claim it — so a superseded query costs the public server nothing at all.
 *
 * Thread-safe (every method is synchronized); [clock] is injectable so the decision is unit-testable
 * without sleeping (`RequestPacerTest`).
 */
internal class RequestPacer(
    private val minIntervalMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var lastTakenMs: Long? = null

    /**
     * Milliseconds until the next call is allowed: 0 when the slot is free right now, otherwise at
     * most [minIntervalMs]. A clock that jumps BACKWARDS (NTP correction, timezone-less
     * `currentTimeMillis`) yields a full interval rather than a negative wait.
     */
    @Synchronized
    fun waitMs(): Long {
        val last = lastTakenMs ?: return 0L
        val elapsed = clock() - last
        if (elapsed < 0) return minIntervalMs
        return (minIntervalMs - elapsed).coerceIn(0L, minIntervalMs)
    }

    /**
     * Claim the slot: true (and the interval restarts) when the caller may make the call now, false
     * when it is still too soon — the caller then skips this endpoint for this round rather than
     * queueing behind it.
     */
    @Synchronized
    fun take(): Boolean {
        if (waitMs() > 0L) return false
        lastTakenMs = clock()
        return true
    }
}
