package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins that a bare imported sleep total (a stage-less schedule span, e.g. 450 min) yields the WHOLE
 * sleep block to a scored computed night in mergeDaily, while session-grade imports (efficiency or
 * stages beside the total) and HC-only users (no computed night) keep the import.
 */
class Issue993BareSleepTotalMergeTest {

    /** HC backfill shape: a bare sleep total (450-min schedule span) plus HC's real daily aggregates
     *  (resting HR / HRV); everything sleep-detail null. */
    private fun hcBackfillRow(day: String, totalSleepMin: Double = 450.0) = DailyMetric(
        deviceId = "my-whoop",
        day = day,
        totalSleepMin = totalSleepMin,
        restingHr = 55,
        avgHrv = 62.0,
    )

    /** A strap-scored computed night. */
    private fun scoredNight(day: String) = DailyMetric(
        deviceId = "my-whoop-noop",
        day = day,
        totalSleepMin = 341.0,
        efficiency = 0.94,
        deepMin = 62.0,
        remMin = 78.0,
        lightMin = 201.0,
        disturbances = 3,
        restingHr = 52,
        avgHrv = 71.0,
        recovery = 74.0,
        strain = 41.0,
    )

    @Test
    fun bareImportedTotal_neverOverridesScoredNight() {
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(hcBackfillRow("2026-06-28")),
            computed = listOf(scoredNight("2026-06-28")),
        )

        assertEquals(1, merged.size)
        // Whole sleep block comes from the scored night, never the 450-min schedule target or a mixed row.
        assertEquals(341.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals(0.94, merged[0].efficiency!!, 0.0)
        assertEquals(62.0, merged[0].deepMin!!, 0.0)
        assertEquals(78.0, merged[0].remMin!!, 0.0)
        assertEquals(201.0, merged[0].lightMin!!, 0.0)
        assertEquals(3, merged[0].disturbances)
        // Non-sleep fields keep the imports-win merge: HC's measured daily aggregates still win.
        assertEquals(55, merged[0].restingHr)
        assertEquals(62.0, merged[0].avgHrv!!, 0.0)
        // Fields the import left null are still gap-filled from the computed row.
        assertEquals(74.0, merged[0].recovery!!, 0.0)
        assertEquals(41.0, merged[0].strain!!, 0.0)
    }

    @Test
    fun bareImportedTotal_fillsWhenNoScoredNight() {
        // HC-only population: no scored night, so the bare total must survive unblanked.
        val computedNoSleep = DailyMetric(
            deviceId = "my-whoop-noop", day = "2026-06-28", strain = 38.0, steps = 8200,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(hcBackfillRow("2026-06-28")),
            computed = listOf(computedNoSleep),
        )
        assertEquals(450.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals(8200, merged[0].steps)
    }

    @Test
    fun bareImportedTotal_passesThroughWithNoComputedRow() {
        // No computed row at all (pure import day): unchanged pass-through.
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(hcBackfillRow("2026-06-28")),
            computed = emptyList(),
        )
        assertEquals(450.0, merged[0].totalSleepMin!!, 0.0)
        assertNull(merged[0].efficiency)
    }

    @Test
    fun sessionGradeImport_stillWinsOverComputed() {
        // A real WHOOP CSV row carries efficiency + stage minutes beside the total (session-grade),
        // so imports-win precedence stays byte-identical.
        val whoopCsvRow = DailyMetric(
            deviceId = "my-whoop", day = "2026-06-28",
            totalSleepMin = 480.0, efficiency = 0.92,
            deepMin = 90.0, remMin = 110.0, lightMin = 280.0,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(whoopCsvRow),
            computed = listOf(scoredNight("2026-06-28")),
        )
        assertEquals(480.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals(90.0, merged[0].deepMin!!, 0.0)
        assertEquals(0.92, merged[0].efficiency!!, 0.0)
    }

    @Test
    fun importWithEfficiencyOnly_isSessionGrade() {
        // Total + efficiency but no stage split (e.g. a stage-less tracker). Efficiency is session
        // evidence, so the import keeps winning.
        val effOnly = DailyMetric(
            deviceId = "my-whoop", day = "2026-06-28",
            totalSleepMin = 402.0, efficiency = 0.88,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(effOnly),
            computed = listOf(scoredNight("2026-06-28")),
        )
        assertEquals(402.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals(0.88, merged[0].efficiency!!, 0.0)
        // Stage minutes the import lacks still gap-fill from the computed row.
        assertEquals(62.0, merged[0].deepMin!!, 0.0)
    }

    // MARK: - bareSleepAggregate predicate (the one shared definition)

    @Test
    fun bareSleepAggregate_matchesHcBackfillShapeOnly() {
        // The HC backfill shape (total only) is bare; anything with efficiency or a stage minute
        // beside the total is session-grade; no total at all is not a sleep block to judge.
        org.junit.Assert.assertTrue(WhoopRepository.bareSleepAggregate(hcBackfillRow("2026-06-28")))
        org.junit.Assert.assertFalse(WhoopRepository.bareSleepAggregate(scoredNight("2026-06-28")))
        org.junit.Assert.assertFalse(
            WhoopRepository.bareSleepAggregate(
                DailyMetric(deviceId = "my-whoop", day = "2026-06-28", totalSleepMin = 402.0, efficiency = 0.88),
            ),
        )
        org.junit.Assert.assertFalse(
            WhoopRepository.bareSleepAggregate(
                DailyMetric(deviceId = "my-whoop", day = "2026-06-28", restingHr = 55),
            ),
        )
    }

    // MARK: - Cross-source resolver seam
    //
    // Compare/Lab Book resolve "sleep_total_min" via resolvedSeries, which reads raw per-source rows and
    // never passes mergeDaily. resolveFirstWins is the pure per-day merge: a WEAK sleep-total yields to
    // a later candidate's scored value and nothing else moves.

    private fun candidate(source: String) = WhoopRepository.MetricSourceCandidate(source, "sleep_total_min")

    @Test
    fun resolver_weakSleepTotal_supersededByScoredNight() {
        val points = WhoopRepository.resolveFirstWins(
            listOf(
                candidate("my-whoop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-28", 450.0, weakSleepTotal = true),
                ),
                candidate("my-whoop-noop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-28", 341.0),
                ),
            ),
        )
        assertEquals(1, points.size)
        assertEquals(341.0, points[0].value, 0.0)
        assertEquals("my-whoop-noop", points[0].source)
    }

    @Test
    fun resolver_weakSleepTotal_keptWhenNoStrongerSibling() {
        // HC-only user: weak total is the only sleep there is — must survive, never blank.
        val points = WhoopRepository.resolveFirstWins(
            listOf(
                candidate("my-whoop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-28", 450.0, weakSleepTotal = true),
                ),
                candidate("my-whoop-noop") to emptyList(),
            ),
        )
        assertEquals(450.0, points[0].value, 0.0)
        assertEquals("my-whoop", points[0].source)
    }

    @Test
    fun resolver_strongFirstCandidate_precedenceByteIdentical() {
        // Session-grade import (never flagged weak) keeps winning over the computed candidate.
        val points = WhoopRepository.resolveFirstWins(
            listOf(
                candidate("my-whoop") to listOf(WhoopRepository.CandidateRow("2026-06-28", 480.0)),
                candidate("my-whoop-noop") to listOf(WhoopRepository.CandidateRow("2026-06-28", 341.0)),
            ),
        )
        assertEquals(480.0, points[0].value, 0.0)
        assertEquals("my-whoop", points[0].source)
    }

    @Test
    fun resolver_laterWeakNeverReplacesEarlierValue() {
        // Symmetry guard: weakness only ever CONCEDES a day, it never claims one already taken.
        val points = WhoopRepository.resolveFirstWins(
            listOf(
                candidate("my-whoop") to listOf(WhoopRepository.CandidateRow("2026-06-28", 341.0)),
                candidate("my-whoop-noop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-28", 450.0, weakSleepTotal = true),
                ),
            ),
        )
        assertEquals(341.0, points[0].value, 0.0)
        assertEquals("my-whoop", points[0].source)
    }

    @Test
    fun resolver_fillsAcrossDaysAndSortsAscending() {
        // Multi-day shape check: later candidates fill uncovered days; output stays day-ascending.
        val points = WhoopRepository.resolveFirstWins(
            listOf(
                candidate("my-whoop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-29", 450.0, weakSleepTotal = true),
                ),
                candidate("my-whoop-noop") to listOf(
                    WhoopRepository.CandidateRow("2026-06-28", 322.0),
                    WhoopRepository.CandidateRow("2026-06-29", 355.0),
                ),
            ),
        )
        assertEquals(listOf("2026-06-28" to 322.0, "2026-06-29" to 355.0), points.map { it.day to it.value })
    }

    @Test
    fun editedDay_precedenceUnchangedByGuard() {
        // An edited day takes the computed sleep block even against a session-grade import.
        val whoopCsvRow = DailyMetric(
            deviceId = "my-whoop", day = "2026-06-28",
            totalSleepMin = 480.0, efficiency = 0.92,
            deepMin = 90.0, remMin = 110.0, lightMin = 280.0,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(whoopCsvRow),
            computed = listOf(scoredNight("2026-06-28")),
            userEditedDays = setOf("2026-06-28"),
        )
        assertEquals(341.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals(62.0, merged[0].deepMin!!, 0.0)
    }
}
