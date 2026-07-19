package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SleepDebt.ledger math — night deltas, surplus/deficit netting, window cap, away-from-zero rounding. */
class SleepDebtTest {

    @Test
    fun onTarget_netsToZero() {
        val series = listOf(
            "2026-06-01" to 480.0, "2026-06-02" to 480.0, "2026-06-03" to 480.0,
        )
        val l = SleepDebt.ledger(series, needHours = 8.0)
        assertEquals(0.0, l.balanceMin, 1e-9)
        assertEquals(3, l.nightCount)
        assertFalse(l.isDebt)
        assertEquals(480.0, l.needMin, 1e-9)
    }

    @Test
    fun surplus_offsetsDeficit() {
        val series = listOf(
            "2026-06-01" to 360.0,   // −120
            "2026-06-02" to 540.0,   // +60
            "2026-06-03" to 420.0,   // −60
        )
        val l = SleepDebt.ledger(series, needHours = 8.0)
        assertEquals(-120.0, l.balanceMin, 1e-9)
        assertTrue(l.isDebt)
        assertEquals(120.0, l.magnitudeMin, 1e-9)
        assertEquals(listOf(-120.0, 60.0, -60.0), l.nights.map { it.deltaMin })
    }

    @Test
    fun skipsNoDataNights() {
        val series = listOf<Pair<String, Double?>>(
            "2026-06-01" to 480.0,
            "2026-06-02" to null,     // skipped
            "2026-06-03" to 0.0,      // skipped (non-positive)
            "2026-06-04" to 420.0,    // −60
        )
        val l = SleepDebt.ledger(series, needHours = 8.0)
        assertEquals(2, l.nightCount)
        assertEquals(-60.0, l.balanceMin, 1e-9)
        assertEquals(listOf("2026-06-01", "2026-06-04"), l.nights.map { it.day })
    }

    @Test
    fun windowCap_keepsMostRecent() {
        val series = (1..16).map { String.format("2026-06-%02d", it) to (420.0 as Double?) }
        val l = SleepDebt.ledger(series, needHours = 8.0, window = 14)
        assertEquals(14, l.nightCount)
        assertEquals(-840.0, l.balanceMin, 1e-9)   // 14 × −60
        assertEquals("2026-06-03", l.nights.first().day)
        assertEquals("2026-06-16", l.nights.last().day)
    }

    @Test
    fun emptyLedger() {
        val l = SleepDebt.ledger(emptyList())
        assertEquals(0.0, l.balanceMin, 1e-9)
        assertEquals(0, l.nightCount)
        assertTrue(l.nights.isEmpty())

        val allNull = listOf<Pair<String, Double?>>("2026-06-01" to null)
        assertEquals(0, SleepDebt.ledger(allNull).nightCount)
    }

    @Test
    fun defaultNeed_isEightHours() {
        val l = SleepDebt.ledger(listOf("2026-06-01" to 420.0))
        assertEquals(RestScorer.defaultSleepNeedHours * 60.0, l.needMin, 1e-9)
        assertEquals(-60.0, l.balanceMin, 1e-9)
    }

    /**
     * A NEGATIVE EXACT half-tie balance must round AWAY from zero (−0.05 → −0.1).
     * needMin = 0.1, slept 0.05 → delta −0.05 exactly → balance −0.1.
     */
    @Test
    fun negativeHalfTie_roundsAwayFromZero() {
        val l = SleepDebt.ledger(listOf("2026-06-01" to 0.05), needHours = 0.1 / 60.0)
        assertEquals(-0.1, l.balanceMin, 1e-9)   // away-from-zero, not 0.0
    }

    /** The symmetric positive half-tie (+0.05 → +0.1), pinned so the sign-aware rounding holds.
     *  needMin = 0, slept 0.05 → delta +0.05. */
    @Test
    fun positiveHalfTie_roundsAwayFromZero() {
        val l = SleepDebt.ledger(listOf("2026-06-01" to 0.05), needHours = 0.0)
        assertEquals(0.1, l.balanceMin, 1e-9)
    }

    /** round1 pinned directly: both signs, a non-tie, and a larger tie. */
    @Test
    fun round1_halfTiesAwayFromZero() {
        assertEquals(-0.1, SleepDebt.round1(-0.05), 1e-9)
        assertEquals(0.1, SleepDebt.round1(0.05), 1e-9)
        assertEquals(0.0, SleepDebt.round1(-0.04), 1e-9)
        assertEquals(-0.3, SleepDebt.round1(-0.25), 1e-9)
    }
}
