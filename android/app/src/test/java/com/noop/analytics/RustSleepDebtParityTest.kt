package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App-side parity gate for routing the rolling sleep-debt ledger through whoop-rs. The Σ(slept − need)
 * ledger is moving from the Kotlin [SleepDebt.ledger] to physio-algo (via [RustScores.sleepDebtLedger] ->
 * uniffi -> whoop-rs `sleep_debt_ledger`); this pins the swap by replaying the SAME night series through
 * BOTH paths and asserting the full [SleepDebtLedger] is identical (balance, per-night deltas, need).
 * `SleepDebtLedger`/`SleepDebtNight` are data classes, so structural `assertEquals` compares every Double
 * by its bits. Negative-balance cases exercise the sign-aware half-away-from-zero `round1` on both sides.
 * Loads the host libwhoop_ffi via JNA.
 */
class RustSleepDebtParityTest {

    private fun assertLedgerParity(label: String, series: List<Pair<String, Double?>>, needHours: Double, window: Int) {
        val k = SleepDebt.ledger(series, needHours, window)
        val r = RustScores.sleepDebtLedger(series, needHours, window)
        assertEquals(label, k, r)
    }

    @Test
    fun `surplus offsets deficit`() {
        val s = listOf("d1" to 360.0, "d2" to 600.0)
        assertLedgerParity("surplus-deficit", s, 8.0, 14)
    }

    @Test
    fun `net-debt negative balance rounds identically`() {
        // Balances that land on negative half-ties stress the sign-aware round1 twin.
        val s = listOf("d1" to 415.0, "d2" to 400.0, "d3" to 439.95)
        assertLedgerParity("net-debt", s, 8.0, 14)
    }

    @Test
    fun `null and non-positive nights are skipped not zeroed`() {
        val s = listOf<Pair<String, Double?>>("d1" to null, "d2" to 0.0, "d3" to 240.0, "d4" to -5.0)
        assertLedgerParity("skip-nulls", s, 8.0, 14)
    }

    @Test
    fun `window caps to the most-recent counted nights`() {
        val s = (0 until 20).map { "d$it" to (400.0 + it * 3.0) as Double? }
        assertLedgerParity("window-cap", s, 8.0, 14)
    }

    @Test
    fun `personal need override matches`() {
        val s = listOf("d1" to 450.0, "d2" to 470.0, "d3" to 500.0)
        assertLedgerParity("need-7.5h", s, 7.5, 14)
    }

    @Test
    fun `empty series is an empty ledger on both paths`() {
        assertLedgerParity("empty", emptyList(), 8.0, 14)
    }
}
