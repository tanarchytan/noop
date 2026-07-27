package com.noop.ingest

import com.noop.ingest.HealthConnectImporter.KcalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crediting an imported exercise session with the calories burned inside its window. Overlap is time
 * weighted, so a per-session record counts in full while a day-spanning record contributes only the
 * session's slice. Two shapes break that on its own: a source writing only total energy, and a source
 * whose only active cover is one coarse record for the whole day.
 */
class HealthConnectActiveKcalTest {

    private fun rec(start: Long, end: Long, kcal: Double, src: String = "a") =
        KcalRecord(src, start, end, kcal)

    private fun active(recs: List<KcalRecord>, from: Long, to: Long): Double? =
        HealthConnectImporter.sessionKcal(recs, emptyList(), from, to, null)

    // ── the original behaviour, unchanged ────────────────────────────────────

    @Test fun noRecordsGivesNull() {
        assertNull(active(emptyList(), 100, 200))
    }

    @Test fun aRecordFullyInsideCountsInFull() {
        assertEquals(300.0, active(listOf(rec(120, 180, 300.0)), 100, 200)!!, 0.001)
    }

    @Test fun aRecordExactlyMatchingTheSessionCountsInFull() {
        assertEquals(250.0, active(listOf(rec(100, 200, 250.0)), 100, 200)!!, 0.001)
    }

    @Test fun aNonOverlappingRecordIsIgnored() {
        assertNull(active(listOf(rec(300, 400, 500.0)), 100, 200))
    }

    @Test fun multiplePerMinuteRecordsInsideAreSummed() {
        val recs = listOf(rec(100, 160, 60.0), rec(160, 200, 40.0))
        assertEquals(100.0, active(recs, 100, 200)!!, 0.001)
    }

    @Test fun partialOverlapIsProRated() {
        assertEquals(50.0, active(listOf(rec(150, 250, 100.0)), 100, 200)!!, 0.001)
    }

    @Test fun aDaySpanningRecordOnlyContributesTheSessionsFraction() {
        // Right for the uniform case, and kept: 1440 kcal over a day, a 60-minute session takes 60.
        val recs = listOf(rec(0, 86_400, 1440.0))
        assertEquals(60.0, active(recs, 10_000, 13_600)!!, 0.001)
    }

    // ── two sources logging one session must not be summed ───────────────────

    @Test fun aPhoneAndAWatchLoggingTheSameSessionDoNotDoubleIt() {
        val recs = listOf(rec(100, 200, 240.0, "phone"), rec(100, 200, 250.0, "watch"))
        // Summed it would read 490. Within a source, max across them.
        assertEquals(250.0, active(recs, 100, 200)!!, 0.001)
    }

    @Test fun oneSourcesConsecutiveRecordsStillSum() {
        val recs = listOf(rec(100, 160, 60.0, "watch"), rec(160, 200, 40.0, "watch"))
        assertEquals(100.0, active(recs, 100, 200)!!, 0.001)
    }

    // ── the two under-report shapes ──────────────────────────────────────────

    @Test fun aSourceWritingOnlyTotalEnergyStillCreditsTheSession() {
        // No active records at all. A 60-minute session inside a day whose basal is 1440 kcal: the
        // window's basal share is 60, so 400 of total burn leaves 340 as the session's own.
        val total = listOf(rec(10_000, 13_600, 400.0))
        val v = HealthConnectImporter.sessionKcal(emptyList(), total, 10_000, 13_600, 1440.0)!!
        assertEquals(340.0, v, 0.001)
    }

    @Test fun aCoarseDailyActiveRecordDoesNotStarveAHardHour() {
        // The shape the proration gets wrong: the only active cover is one 24-hour record, so it
        // credits 1/24 of the day. Total energy over the same window carries the real burn.
        val activeRecs = listOf(rec(0, 86_400, 720.0))
        val total = listOf(rec(10_000, 13_600, 500.0))
        val prorated = HealthConnectImporter.sessionKcal(activeRecs, emptyList(), 10_000, 13_600, null)!!
        val both = HealthConnectImporter.sessionKcal(activeRecs, total, 10_000, 13_600, 1440.0)!!
        assertEquals(30.0, prorated, 0.001)
        assertEquals(440.0, both, 0.001)
        assertTrue("the total-derived estimate must win here", both > prorated)
    }

    @Test fun realSessionCoverBeatsAStrayBackgroundTotal() {
        // A source writing one record per session is already right; a thin total must not replace it.
        val activeRecs = listOf(rec(10_000, 13_600, 300.0))
        val total = listOf(rec(0, 86_400, 2000.0))
        val v = HealthConnectImporter.sessionKcal(activeRecs, total, 10_000, 13_600, 1440.0)!!
        assertEquals(300.0, v, 0.001)
    }

    @Test fun neitherStreamCoveringTheSessionGivesNull() {
        val far = listOf(rec(50_000, 60_000, 900.0))
        assertNull(HealthConnectImporter.sessionKcal(far, far, 10_000, 13_600, 1440.0))
    }

    @Test fun anUnknownDayBasalFallsBackToTheActiveEstimate() {
        val activeRecs = listOf(rec(10_000, 13_600, 120.0))
        val total = listOf(rec(10_000, 13_600, 900.0))
        assertEquals(120.0, HealthConnectImporter.sessionKcal(activeRecs, total, 10_000, 13_600, null)!!, 0.001)
    }
}
