// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Authoritt and the Overtake contributors.
package dev.overtake.maps.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "may I spend a Nominatim call?" decision, with a fake clock — no sleeping, no network. This is
 * what stops an as-you-type search from queueing a request per keystroke against a public server
 * that allows ~1 per second (the queue is what turned into the read timeout that left the rider
 * with a Photon-only, wrong-country list).
 */
class RequestPacerTest {

    private var now = 1_000_000L
    private val pacer = RequestPacer(minIntervalMs = 1_100L) { now }

    @Test
    fun `the first call is free`() {
        assertEquals(0L, pacer.waitMs())
        assertTrue(pacer.take())
    }

    @Test
    fun `a second call inside the window is refused and reports the remaining wait`() {
        assertTrue(pacer.take())
        now += 300
        assertEquals(800L, pacer.waitMs())
        assertFalse(pacer.take())
        // A refusal must not restart the interval — otherwise fast typing would starve the slot.
        now += 100
        assertEquals(700L, pacer.waitMs())
    }

    @Test
    fun `waiting exactly the reported time opens the slot`() {
        assertTrue(pacer.take())
        now += 500
        val wait = pacer.waitMs()
        assertEquals(600L, wait)
        now += wait
        assertEquals(0L, pacer.waitMs())
        assertTrue(pacer.take())
    }

    @Test
    fun `the interval restarts from each granted call`() {
        assertTrue(pacer.take())
        now += 1_100
        assertTrue(pacer.take())
        now += 10
        assertFalse(pacer.take())
        assertEquals(1_090L, pacer.waitMs())
    }

    @Test
    fun `a clock that jumps backwards costs one full interval, never a negative wait`() {
        assertTrue(pacer.take())
        now -= 60_000 // NTP correction mid-ride
        assertEquals(1_100L, pacer.waitMs())
        assertFalse(pacer.take())
    }

    @Test
    fun `the wait is never longer than the interval`() {
        assertTrue(pacer.take())
        now += 1
        assertTrue(pacer.waitMs() <= 1_100L)
    }
}
