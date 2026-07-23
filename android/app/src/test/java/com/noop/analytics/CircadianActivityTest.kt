package com.noop.analytics

import com.noop.data.GravitySample
import org.junit.Assert.assertEquals
import org.junit.Test

class CircadianActivityTest {

    private fun sample(ts: Long, dyn: Double?) = GravitySample("d", ts, 0.0, 0.0, 1.0, dynAccelG = dyn)

    @Test
    fun `pools per-hour mean and skips null dynAccel`() {
        // Two samples in UTC hour 0 (ts 0, 1800) → mean; one in hour 1 (ts 3600); one null → skipped.
        val bins = CircadianActivity.hourlyBins(
            listOf(sample(0, 0.01), sample(1800, 0.03), sample(3600, 0.10), sample(1000, null)),
            tzOffsetSeconds = 0,
        )
        assertEquals(2, bins.size)
        assertEquals(0.0, bins[0].hour, 1e-9)
        assertEquals(0.02, bins[0].activity, 1e-9) // (0.01 + 0.03) / 2
        assertEquals(1.0, bins[1].hour, 1e-9)
        assertEquals(0.10, bins[1].activity, 1e-9)
    }

    @Test
    fun `tz offset shifts the local hour`() {
        // ts 0 (UTC hour 0) with a +2 h offset → local hour 2.
        val bins = CircadianActivity.hourlyBins(listOf(sample(0, 0.05)), tzOffsetSeconds = 2 * 3600)
        assertEquals(1, bins.size)
        assertEquals(2.0, bins[0].hour, 1e-9)
    }

    @Test
    fun `empty when no non-null samples`() {
        assertEquals(0, CircadianActivity.hourlyBins(listOf(sample(0, null)), 0).size)
    }
}
