package com.noop.data

import com.noop.analytics.CircadianEngine
import com.noop.analytics.RustScores
import com.noop.analytics.SleepStageHealer
import com.noop.analytics.SleepStageTotals
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * What each mock dataset promises, as statements that can be checked. A screen is then ASSERTED
 * against a number the scenario states rather than eyeballed, which is the difference between
 * catching a display bug and catching a data bug.
 *
 * The claims are built from the dataset, so a test fails the moment a scenario stops delivering what
 * it says; the seeder also logs them, so a walk can read the same numbers off the screen.
 */
object MockClaims {

    /** How far a night's decoded stage minutes may sit from its daily columns: the segment boundaries
     *  are rounded to whole seconds, and nothing else may move. */
    private const val STAGE_TOLERANCE_MIN = 0.1

    fun of(scenario: MockScenario, ds: MockDataset, today: LocalDate, zone: ZoneId): List<MockClaim> =
        when (scenario) {
            MockScenario.TYPICAL -> typical(ds, zone)
            MockScenario.GAPS -> gaps(ds, today, zone)
            MockScenario.EMPTY -> empty(ds, zone)
            MockScenario.EXTREMES -> extremes(ds, zone)
            MockScenario.BOUNDARIES -> boundaries(ds, zone)
            MockScenario.TWO_STRAPS -> twoStraps(ds, zone)
        }

    private fun typical(ds: MockDataset, zone: ZoneId) = listOf(
        count("days with a row", MockSeeder.DAYS, ds.daily.size),
        count("nights", MockSeeder.DAYS - 1, ds.sleeps.size),
        count("days with a row but no night", 1, ds.daily.count { it.totalSleepMin == null }),
        count("sources writing rows", 3, sourceCount(ds)),
        stagesAgree(ds, zone),
        wornDaysClaim(ds, zone, MockSeeder.RAW_STREAM_DAYS),
        rhythmOffered(ds, zone, "yes"),
        stagesSurviveTheRaw(ds),
    )

    private fun gaps(ds: MockDataset, today: LocalDate, zone: ZoneId): List<MockClaim> {
        val absent = MockScenarios.UNWORN_WEEK.count() + 1
        val week = (0L..6L).map { today.minusDays(it).toString() }
        val nightsInWeek = ds.sleeps.count { MockScenarios.localDay(it.endTs, zone) in week }
        val unworn = today.minusDays(MockScenarios.UNWORN_DAY).toString()
        val unslept = today.minusDays(MockScenarios.EXTRA_UNSLEPT_DAY).toString()
        val partial = today.minusDays(MockScenarios.PARTIAL_NIGHT_DAY).toString()
        val partialRow = ds.daily.firstOrNull { it.day == partial }
        return listOf(
            count("days with a row", MockSeeder.DAYS - absent, ds.daily.size),
            count("days with a row in the last 7", 7 - 1, ds.daily.count { it.day in week }),
            count("nights in the last 7 days", 4, nightsInWeek),
            claim("the unworn day carries", "no row of any kind", rowsOn(ds, unworn, zone)),
            claim(
                "the second unslept day carries", "a row with no night",
                if (ds.daily.any { it.day == unslept && it.totalSleepMin == null } &&
                    ds.sleeps.none { MockScenarios.localDay(it.endTs, zone) == unslept }
                ) "a row with no night" else rowsOn(ds, unslept, zone),
            ),
            claim("the partial night, asleep minutes", "95.0", "${partialRow?.totalSleepMin}"),
            claim(
                "the partial night, sleep performance", "19.8",
                "${ds.series.firstOrNull { it.day == partial && it.key == "sleep_performance" }?.value}",
            ),
            count(
                "night-only series rows on the unslept day", 0,
                ds.series.count { it.day == unslept && it.key.startsWith("sleep_") },
            ),
            stagesAgree(ds, zone),
            // The unworn day is the only one of the wear gaps inside the rest-activity window.
            wornDaysClaim(ds, zone, MockSeeder.RAW_STREAM_DAYS - 1),
            rhythmOffered(ds, zone, "yes"),
        )
    }

    private fun empty(ds: MockDataset, zone: ZoneId) = listOf(
        claim("rows of any kind", "none", if (ds.isEmpty) "none" else "${ds.daily.size} days"),
        wornDaysClaim(ds, zone, 0),
    )

    private fun extremes(ds: MockDataset, zone: ZoneId): List<MockClaim> {
        val longest = ds.sleeps.maxByOrNull { it.endTs - it.startTs }
        val shortest = ds.sleeps.minByOrNull { it.endTs - it.startTs }
        return listOf(
            claim("highest recovery", "100.0", "${ds.daily.mapNotNull { it.recovery }.maxOrNull()}"),
            claim("lowest recovery", "0.0", "${ds.daily.mapNotNull { it.recovery }.minOrNull()}"),
            claim("highest effort", "100.0", "${ds.daily.mapNotNull { it.strain }.maxOrNull()}"),
            claim("lowest effort", "0.0", "${ds.daily.mapNotNull { it.strain }.minOrNull()}"),
            claim("longest night, asleep minutes", "840.0", "${ds.daily.mapNotNull { it.totalSleepMin }.maxOrNull()}"),
            claim("shortest night, asleep minutes", "2.0", "${ds.daily.mapNotNull { it.totalSleepMin }.minOrNull()}"),
            claim("the 14-hour night decodes to", "840.0 asleep, 0.0 awake", decoded(longest)),
            claim("the 2-minute night decodes to", "2.0 asleep, 38.0 awake", decoded(shortest)),
            count("days with a row but no night", 1, ds.daily.count { it.totalSleepMin == null }),
            claim("highest daily steps", "40000", "${ds.apple.mapNotNull { it.steps }.maxOrNull()}"),
            claim("lowest daily steps", "0", "${ds.apple.mapNotNull { it.steps }.minOrNull()}"),
            claim(
                "sleep performance for the night slept to exactly the need", "100.0",
                "${ds.series.filter { it.key == "sleep_performance" }.map { it.value }.maxOrNull()}",
            ),
            stagesAgree(ds, zone),
            // The bottom end of the wear scale: a strap banking no motion at all, where a card that
            // counted up for ever would be noise, so it is left out instead.
            wornDaysClaim(ds, zone, 0),
        )
    }

    private fun boundaries(ds: MockDataset, zone: ZoneId): List<MockClaim> {
        val edges = MockScenarios.recoveryStateEdges()
        val scores = ds.daily.mapNotNull { it.recovery }.toSet()
        val below = edges.map { MockSeeder.round1(it - MockScenarios.EDGE_STEP) }
        val changes = edges.count { RustScores.state(it) != RustScores.state(it - MockScenarios.EDGE_STEP) }
        return listOf(
            count("days sitting exactly on a state edge", edges.size, edges.count { it in scores }),
            count("days one step below an edge", edges.size, below.count { it in scores }),
            count("edges that change state from the step below", edges.size, changes),
            claim("highest recovery", "100.0", "${ds.daily.mapNotNull { it.recovery }.maxOrNull()}"),
            claim("lowest recovery", "0.0", "${ds.daily.mapNotNull { it.recovery }.minOrNull()}"),
            claim(
                "sleep performance for the night slept to exactly the need", "100.0",
                "${ds.series.filter { it.key == "sleep_performance" }.map { it.value }.maxOrNull()}",
            ),
            stagesAgree(ds, zone),
            wornDaysClaim(ds, zone, CircadianEngine.MIN_WORN_DAYS - 1),
            rhythmOffered(ds, zone, "no, ${CircadianEngine.MIN_WORN_DAYS - 1} of ${CircadianEngine.MIN_WORN_DAYS} days"),
        )
    }

    private fun twoStraps(ds: MockDataset, zone: ZoneId): List<MockClaim> {
        val second = ds.daily.filter { it.deviceId == MockSeeder.SECOND_STRAP }.map { it.day }.toSet()
        val first = ds.daily.filter { it.deviceId == MockSeeder.WHOOP }.map { it.day }.toSet()
        val strayHr = ds.hr.count { s ->
            val night = ds.sleeps.firstOrNull {
                s.ts >= it.startTs - MockSeeder.HR_EDGE_PAD_SEC && s.ts <= it.endTs + MockSeeder.HR_EDGE_PAD_SEC
            }
            night != null && night.deviceId != s.deviceId
        }
        return listOf(
            count("days owned by the second strap", MockSeeder.DAYS - MockScenarios.STRAP_SWAP_DAY.toInt(), second.size),
            count("days owned by the first strap", MockScenarios.STRAP_SWAP_DAY.toInt(), first.size),
            count("days claimed by both straps", 0, (second intersect first).size),
            count("HR samples on a different strap from their night", 0, strayHr),
            count("straps in the device list", 1, ds.pairedDevices.count { it.id == MockSeeder.SECOND_STRAP }),
            stagesAgree(ds, zone),
            // The swap took the rest-activity window with it: the active strap holds only the days
            // since the swap, which is under the floor, and the retired strap holds the rest.
            wornDaysClaim(ds, zone, MockScenarios.STRAP_SWAP_DAY.toInt()),
            count(
                "days of motion under the retired strap",
                MockSeeder.RAW_STREAM_DAYS - MockScenarios.STRAP_SWAP_DAY.toInt(),
                wornDays(ds, zone, MockSeeder.SECOND_STRAP),
            ),
        )
    }

    // MARK: - shared

    /** Every night's stage segments still decode back to the daily columns that defined it. This is the
     *  one disagreement that produced most of the walk's findings, so every scenario carries the check. */
    private fun stagesAgree(ds: MockDataset, zone: ZoneId): MockClaim {
        val byDay = ds.daily.associateBy { "${it.deviceId}/${it.day}" }
        val agreeing = ds.sleeps.count { s ->
            val d = byDay["${s.deviceId}/${MockScenarios.localDay(s.endTs, zone)}"]
            val m = SleepStageTotals.minutes(s.stagesJSON)
            d != null && m != null &&
                near(m.asleep, d.totalSleepMin) && near(m.deep, d.deepMin) &&
                near(m.rem, d.remMin) && near(m.light, d.lightMin)
        }
        return count("nights whose stages decode to their daily row", ds.sleeps.size, agreeing)
    }

    private fun near(a: Double, b: Double?): Boolean = b != null && abs(a - b) <= STAGE_TOLERANCE_MIN

    // MARK: - rest-activity

    /**
     * Distinct local days of on-chip motion under [deviceId] — what the Body Clock and Rhythm Age cards
     * count against whoop-rs's worn-day floor, and therefore what decides whether either appears at all.
     * Counted by the one owner of that rule, never by a second copy of it here.
     */
    private fun wornDays(ds: MockDataset, zone: ZoneId, deviceId: String = MockSeeder.WHOOP): Int =
        CircadianEngine.wornDays(activity(ds, deviceId), tzOffsetSeconds(zone))

    private fun wornDaysClaim(ds: MockDataset, zone: ZoneId, expected: Int) =
        count("days of motion under the active strap", expected, wornDays(ds, zone))

    /**
     * Whether whoop-rs reads a Rhythm Age off the seeded rhythm, and when it does not, the count the
     * card shows instead. Asked at [REFERENCE_AGE] because the transform needs SOME real age; which one
     * scales the answer but never decides that there is one, and the wearer's own age is not the
     * dataset's to state.
     */
    private fun rhythmOffered(ds: MockDataset, zone: ZoneId, expected: String): MockClaim {
        val samples = activity(ds, MockSeeder.WHOOP)
        val worn = CircadianEngine.wornDays(samples, tzOffsetSeconds(zone))
        val age = if (worn >= CircadianEngine.MIN_WORN_DAYS) {
            RustScores.rhythmAge(samples, tzOffsetSeconds(zone), REFERENCE_AGE, RustScores.sexInput("male"))
        } else {
            null
        }
        val actual = when {
            age != null -> "yes"
            worn >= CircadianEngine.MIN_WORN_DAYS -> "no, the rhythm is too flat"
            else -> "no, $worn of ${CircadianEngine.MIN_WORN_DAYS} days"
        }
        return claim("whoop-rs reads a rhythm age", expected, actual)
    }

    /** The age the Rhythm Age question is asked at. The dataset owns the motion, not the wearer. */
    private const val REFERENCE_AGE = 47.0

    /** The seeded motion is deliberately too coarse to restage a night, so every night keeps the stages
     *  the scenario gave it rather than a re-derived set the claims above never saw. */
    private fun stagesSurviveTheRaw(ds: MockDataset): MockClaim = count(
        "nights the seeded motion is dense enough to restage", 0,
        ds.sleeps.count {
            SleepStageHealer.isDense(ds.gravity.filter { g -> g.deviceId == it.deviceId }, it.startTs, it.endTs)
        },
    )

    private fun activity(ds: MockDataset, deviceId: String): List<uniffi.whoop_ffi.ActivitySample> =
        ds.gravity.filter { it.deviceId == deviceId }
            .mapNotNull { g -> g.dynAccelG?.let { uniffi.whoop_ffi.ActivitySample(g.ts, it) } }

    private fun tzOffsetSeconds(zone: ZoneId): Long =
        zone.rules.getOffset(java.time.Instant.now()).totalSeconds.toLong()

    /** "840.0 asleep, 0.0 awake" for a night, so a decode mismatch names both halves. */
    private fun decoded(s: SleepSession?): String {
        val m = s?.let { SleepStageTotals.minutes(it.stagesJSON) } ?: return "no night"
        return "${MockSeeder.round1(m.asleep)} asleep, ${MockSeeder.round1(m.awake)} awake"
    }

    /** How many of the mock's sources actually wrote a row, so a scenario cannot silently lose one. */
    private fun sourceCount(ds: MockDataset): Int =
        (ds.daily.map { it.deviceId } + ds.series.map { it.deviceId } + ds.apple.map { it.deviceId }).toSet().size

    private fun rowsOn(ds: MockDataset, day: String, zone: ZoneId): String {
        val parts = buildList {
            if (ds.daily.any { it.day == day }) add("a daily row")
            if (ds.sleeps.any { MockScenarios.localDay(it.endTs, zone) == day }) add("a night")
            if (ds.apple.any { it.day == day }) add("an Apple day")
            if (ds.series.any { it.day == day }) add("series")
            if (ds.journal.any { it.day == day }) add("journal")
            if (ds.workouts.any { MockScenarios.localDay(it.startTs, zone) == day }) add("a workout")
        }
        return if (parts.isEmpty()) "no row of any kind" else parts.joinToString(", ")
    }

    private fun claim(name: String, expected: String, actual: String) = MockClaim(name, expected, actual)

    private fun count(name: String, expected: Int, actual: Int) =
        MockClaim(name, expected.toString(), actual.toString())
}
