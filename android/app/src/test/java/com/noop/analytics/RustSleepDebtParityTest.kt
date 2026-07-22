package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for the rolling sleep-debt ledger ([RustScores.sleepDebtLedger] -> uniffi -> whoop-rs
 * `sleep_debt`). The Σ(slept − need) accumulation, the capped window, the skip-no-data rule and the
 * sign-aware half-away-from-zero rounding all live in physio-algo; this pins the marshalled
 * [SleepDebtLedger] behaviour end-to-end. Exact balances are frozen by the Rust golden tests. Loads the
 * host libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustSleepDebtParityTest {

    private val need = 8.0
    private val needMin = need * 60.0

    @Test
    fun `surplus offsets deficit into a net balance`() {
        // d1 = 360 (−120), d2 = 600 (+120) -> net 0.
        val l = RustScores.sleepDebtLedger(listOf("d1" to 360.0, "d2" to 600.0), need, 14)
        assertEquals("both nights counted", 2, l.nights.size)
        assertEquals("surplus nets the deficit", 0.0, l.balanceMin, 1e-9)
        assertEquals(needMin, l.needMin, 1e-9)
    }

    @Test
    fun `null and non-positive nights are skipped, not zeroed`() {
        val s = listOf<Pair<String, Double?>>("d1" to null, "d2" to 0.0, "d3" to 240.0, "d4" to -5.0)
        val l = RustScores.sleepDebtLedger(s, need, 14)
        assertEquals("only the one usable night counts", listOf("d3"), l.nights.map { it.day })
        assertTrue("under need reads as debt", l.isDebt)
    }

    @Test
    fun `window caps to the most-recent counted nights`() {
        val s = (0 until 20).map { "d$it" to (400.0 + it * 3.0) as Double? }
        val l = RustScores.sleepDebtLedger(s, need, 14)
        assertEquals("capped at the window", 14, l.nights.size)
        assertEquals("keeps the newest nights", "d19", l.nights.last().day)
    }

    @Test
    fun `personal need override shifts every delta`() {
        val l = RustScores.sleepDebtLedger(listOf("d1" to 450.0), 7.5, 14)
        assertEquals("delta measured against 7.5 h", 450.0 - 7.5 * 60.0, l.nights.single().deltaMin, 1e-9)
    }

    @Test
    fun `empty series is an empty ledger`() {
        val l = RustScores.sleepDebtLedger(emptyList(), need, 14)
        assertTrue(l.nights.isEmpty())
        assertEquals(0.0, l.balanceMin, 1e-9)
    }
}
