package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RMSSD groups R-R per second and takes successive differences only within a second, so the order of
 * beats sharing a timestamp is its entire input. `ord` carries that order from the wire; `seq` cannot,
 * because it counts repeats of an identical beat.
 */
class RrEmissionOrderTest {

    private fun rows(ts: Long, vararg rrMs: Int) = rrMs.map { RrRow(ts, it) }

    @Test fun `ord follows the wire order, not the magnitude`() {
        // Deliberately not ascending: the wire order is what must survive.
        val out = assignRrSeq("d", rows(100L, 900, 700, 850))
        assertEquals(listOf(0, 1, 2), out.map { it.ord })
        assertEquals(listOf(900, 700, 850), out.map { it.rrMs })
    }

    @Test fun `seq cannot carry the order, which is why ord exists`() {
        // Three DISTINCT beats in one second: every one holds seq 0, so a sort on seq leaves them
        // tied and falls through to the primary-key index, which is ordered by magnitude.
        val out = assignRrSeq("d", rows(100L, 900, 700, 850))
        assertEquals(listOf(0, 0, 0), out.map { it.seq })
    }

    @Test fun `seq still separates identical beats so none is dropped`() {
        // The key is (deviceId, ts, rrMs, seq); two beats of the same length in one second must not
        // collide on it.
        val out = assignRrSeq("d", rows(100L, 800, 800, 800))
        assertEquals(listOf(0, 1, 2), out.map { it.seq })
        assertEquals(listOf(0, 1, 2), out.map { it.ord })
    }

    @Test fun `ord restarts each second`() {
        val out = assignRrSeq("d", rows(100L, 900, 700) + rows(101L, 880, 810))
        assertEquals(listOf(0, 1, 0, 1), out.map { it.ord })
    }

    @Test fun `a row written before the column existed carries no order`() {
        // Legacy rows keep ord NULL. SQLite sorts NULL first in ASC, so a second made only of them
        // ties and falls through to the old (rrMs, seq) order — existing history reads back unchanged.
        assertNull(RrInterval(deviceId = "d", ts = 1L, rrMs = 800).ord)
    }
}
