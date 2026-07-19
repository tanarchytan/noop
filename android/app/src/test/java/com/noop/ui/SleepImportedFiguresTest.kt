package com.noop.ui

import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Sleep screen's prefer-imported logic: an exported figure passes through verbatim; days the
 * export does not cover fall back to on-device recomputation so sparklines stay continuous.
 */
class SleepImportedFiguresTest {

    private fun day(d: String, asleep: Double?) = DailyMetric(
        deviceId = "my-whoop", day = d, totalSleepMin = asleep,
        deepMin = 80.0, remMin = 90.0, lightMin = 200.0, efficiency = 90.0,
    )

    @Test
    fun importedPerformanceWinsPerDay() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        val imported = ImportedSleepSeries(performance = mapOf("2026-06-02" to 85.0))
        val m = buildSleepModel(days, session = null, imported = imported)!!
        assertEquals(85.0, m.performance.latest!!, 1e-9)
    }

    @Test
    fun importedDebtPassesThroughInMinutes() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        val imported = ImportedSleepSeries(debtMin = mapOf("2026-06-02" to 60.0))
        val m = buildSleepModel(days, session = null, imported = imported)!!
        assertEquals(60.0, m.sleepDebt.latest!!, 1e-9)
    }

    @Test
    fun hoursVsNeededUsesImportedNeedPerDay() {
        val days = listOf(day("2026-06-01", 400.0))
        val imported = ImportedSleepSeries(needMin = mapOf("2026-06-01" to 480.0))
        val m = buildSleepModel(days, session = null, imported = imported)!!
        assertEquals(400.0 / 480.0 * 100.0, m.hoursVsNeeded.latest!!, 1e-9)
    }

    @Test
    fun uncoveredDaysUseTheRestComposite() {
        // Imported covers only day 1; day 2 (latest) must use the REAL Rest composite
        // (RestScorer.restFromDaily).
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        val imported = ImportedSleepSeries(performance = mapOf("2026-06-01" to 85.0))
        val m = buildSleepModel(days, session = null, imported = imported)!!
        assertEquals(RestScorer.restFromDaily(days[1])!!, m.performance.latest!!, 1e-9)
        // …and it is NOT the retired asleep/need approximation.
        assertNotEquals(410.0 / 450.0 * 100.0, m.performance.latest!!, 1e-6)
        // …and the imported day still carries the verbatim figure inside the series.
        assertEquals(85.0, m.performance.series.first(), 1e-9)
    }

    /** A live night long enough to ceiling a raw asleep/need ratio at 100% must instead show the
     *  Rest composite (< 100 once efficiency / restorative pull it down). */
    @Test
    fun longLiveNightShowsCompositeNotCeilingedProxy() {
        // 8 h asleep, 82% efficiency, modest deep+REM → asleep ≥ personal need, so a raw asleep/need
        // ratio reads min(100, 480/450·100) = 100%; the composite scores quality and lands < 100.
        val night = DailyMetric(
            deviceId = "my-whoop", day = "2026-06-02", totalSleepMin = 480.0,
            deepMin = 70.0, remMin = 80.0, lightMin = 330.0, efficiency = 0.82,
        )
        val days = listOf(
            DailyMetric(deviceId = "my-whoop", day = "2026-06-01", totalSleepMin = 420.0,
                deepMin = 70.0, remMin = 80.0, lightMin = 270.0, efficiency = 0.85),
            night,
        )
        val m = buildSleepModel(days, session = null)!!
        val composite = RestScorer.restFromDaily(night)!!
        assertEquals(composite, m.performance.latest!!, 1e-9)
        assertTrue("composite should be below the 100% proxy ceiling", composite < 100.0)
        assertNotEquals(100.0, m.performance.latest!!, 1e-6)
    }

    @Test
    fun importedConsistencyUsedOnlyWhenItCoversTheLatestNight() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        // Covers the latest night → verbatim series wins.
        val covered = buildSleepModel(days, null,
            ImportedSleepSeries(consistency = mapOf("2026-06-01" to 70.0, "2026-06-02" to 74.0)))!!
        assertEquals(74.0, covered.consistency.latest!!, 1e-9)
        // Ends before the latest night → the APPROXIMATE duration-spread proxy, never a
        // months-old import-era value presented as "latest".
        val stale = buildSleepModel(days, null,
            ImportedSleepSeries(consistency = mapOf("2026-06-01" to 70.0)))!!
        assertNotEquals(70.0, stale.consistency.latest)
    }

    @Test
    fun emptyImportedReproducesTheApproximateBaseline() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        val a = buildSleepModel(days, session = null)!!
        val b = buildSleepModel(days, session = null, imported = ImportedSleepSeries())!!
        assertEquals(a, b)
    }

    // --- Tiles read ASLEEP (not in-bed) over the full history, not the browsed night.

    /** A session whose IN-BED window (600 min) dwarfs the night's ASLEEP total (410 min) must NOT
     *  bleed time-in-bed into the per-tile passes. */
    @Test
    fun sessionInBedWindowDoesNotSubstituteForAsleep() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        // endTs = 2026-06-02 08:00 UTC; onset 600 min earlier → 600 min IN BED, but asleep = 410.
        val session = SleepSession(
            deviceId = "my-whoop", startTs = 1780351200L, endTs = 1780387200L, efficiency = 90.0,
        )
        val m = buildSleepModel(days, session = session)!!
        // need = max(450, mean asleep[420,410]=415) = 450. hours-vs-needed reads ASLEEP 410, not 600.
        assertEquals(410.0 / 450.0 * 100.0, m.hoursVsNeeded.latest!!, 1e-9)
        // Debt tile reads ASLEEP too: max(0, 450 − 410) = 40, never max(0, 450 − 600) = 0.
        assertEquals(40.0, m.sleepDebt.latest!!, 1e-9)
        // The debt TILE and the LEDGER agree (both asleep over the full history).
        assertEquals(m.sleepDebt.latest!!, -m.sleepDebtLedger.nights.last().deltaMin, 1e-9)
    }

    /** A passed session gives the SAME tiles/ledger as no session. (Stage cards still update from
     *  the reclipped stagesJSON; tiles do not.) */
    @Test
    fun passingASessionDoesNotChangeTheTiles() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0))
        val session = SleepSession(
            deviceId = "my-whoop", startTs = 1780351200L, endTs = 1780387200L, efficiency = 90.0,
        )
        val withSession = buildSleepModel(days, session = session)!!
        val noSession = buildSleepModel(days, session = null)!!
        assertEquals(noSession.performance, withSession.performance)
        assertEquals(noSession.hoursVsNeeded, withSession.hoursVsNeeded)
        assertEquals(noSession.sleepDebt, withSession.sleepDebt)
        assertEquals(noSession.sleepDebtLedger, withSession.sleepDebtLedger)
        assertEquals(noSession.typicalTotalMin, withSession.typicalTotalMin)
    }

    /** Browsing a PAST night leaves the at-a-glance tiles and the "Last 14 nights" ledger
     *  LATEST-anchored (full history); only the hero re-points. */
    @Test
    fun browsingAPastNightRepointsPerNightTilesButKeepsTrendAnchored() {
        val days = listOf(day("2026-06-01", 420.0), day("2026-06-02", 410.0), day("2026-06-03", 400.0))
        val latestView = buildSleepModel(days, session = null)!!                       // newest night (06-03)
        val browsedView = buildSleepModel(days, session = null, selectedDay = "2026-06-01")!!
        // The per-night METRIC tiles re-point to the browsed night — its OWN reading, not the latest's.
        assertNotEquals(latestView.performance.latest, browsedView.performance.latest)
        assertEquals("browsed Rest = the SELECTED night's composite",
            RestScorer.restFromDaily(days[0])!!, browsedView.performance.latest!!, 1e-9)
        assertNotEquals(latestView.sleepDebt.latest, browsedView.sleepDebt.latest)
        assertNotEquals(latestView.hoursVsNeeded.latest, browsedView.hoursVsNeeded.latest)
        // The FULL-HISTORY context stays put regardless of which night is browsed: the ledger, the
        // "typical" baselines, and each tile's `typical` mean (the "vs typical" reference).
        assertEquals(latestView.sleepDebtLedger, browsedView.sleepDebtLedger)
        assertEquals(latestView.typicalTotalMin, browsedView.typicalTotalMin)
        assertEquals(latestView.performance.typical, browsedView.performance.typical)
        // The HERO also follows the browsed night — its stages come from the selected day's row.
        assertNotEquals(latestView.stages, browsedView.stages)
    }
}
