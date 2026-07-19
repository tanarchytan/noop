package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day's canonical sleep total is the MAIN night (the block the Sleep tab hero shows), never the
 * night + nap sum. Naps stay their own session rows.
 */
class MainNightConsistencyTest {

    /** An arbitrary fixed UTC midnight (ref % 86400 == 0). tzOffset 0 → local == UTC. */
    private val refMidnight = 1_749_513_600L
    private fun atHour(hourUTC: Int): Long = refMidnight + hourUTC * 3_600L
    private fun atMin(hour: Int, min: Int): Long = refMidnight + hour * 3_600L + min * 60L
    /** Local time-of-day "HH:mm" → seconds. */
    private fun sod(hour: Int, min: Int): Long = hour * 3_600L + min * 60L

    // ── main-night selection (the single shared rule) ────────────────────────────────────────────

    @Test
    fun mainNightPrefersOvernightOverLongerDaytimeNap() {
        val nightStart = atHour(23) - 86_400L  // 2026-06-09 23:00 overnight onset
        val napStart = atHour(13)              // 13:00 daytime onset
        // The nap is LONGER in clock span, but the overnight block must still win.
        val blocks = listOf(
            SleepStageTotals.NightBlock(napStart, napStart + 5 * 3600),    // 5h daytime
            SleepStageTotals.NightBlock(nightStart, nightStart + 4 * 3600), // 4h overnight
        )
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    @Test
    fun mainNightLongestAmongOvernightBlocks() {
        val a = atHour(22) - 86_400L
        val b = atHour(23) - 86_400L + 1_800L
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, a + 3 * 3600),  // 3h
            SleepStageTotals.NightBlock(b, b + 6 * 3600),  // 6h longer overnight wins
        )
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** A biphasic main night (fragments split by short wakes) resolves to one bridged group with all
     *  fragments; a distant afternoon nap stays outside it. */
    @Test
    fun biphasicNightGroupsAllFragmentsAndExcludesNap() {
        val f1 = atHour(23) - 86_400L          // 23:00–01:00
        val f2 = atMin(1, 40)                  // 01:40–04:00 (40 min gap → bridges)
        val f3 = atMin(4, 30)                  // 04:30–07:00 (30 min gap → bridges)
        val nap = atHour(14)                   // 14:00–15:00 (7 h gap → does NOT bridge)
        val blocks = listOf(
            SleepStageTotals.NightBlock(f1, f1 + 2 * 3600),
            SleepStageTotals.NightBlock(f2, f2 + 140 * 60),
            SleepStageTotals.NightBlock(f3, f3 + 150 * 60),
            SleepStageTotals.NightBlock(nap, nap + 3600),
        )
        assertEquals(
            "all three bridged night fragments are the main group; the afternoon nap is excluded",
            listOf(0, 1, 2), SleepStageTotals.mainNightGroupIndices(blocks, 0L),
        )
        val single = SleepStageTotals.mainNightIndex(blocks, 0L)
        assertNotNull(single)
        assertTrue("the bare winner is one of the night fragments, never the nap", single!! in listOf(0, 1, 2))
        assertTrue(
            "the afternoon nap is never in the main-night group",
            SleepStageTotals.mainNightGroupIndices(blocks, 0L)?.contains(3) == false,
        )
    }

    @Test
    fun mainNightEmptyAndTieAreDeterministic() {
        assertNull(SleepStageTotals.mainNightIndex(emptyList(), 0L))
        // Two score-tied blocks (equal duration + circular distance to the 03:30 anchor) → earlier onset
        // breaks the tie. early: 00:30 onset, 4h → mid 02:30 (1h before); late: 02:30 onset, 4h → mid 04:30 (1h after).
        val early = atMin(0, 30)
        val late = atMin(2, 30)
        val blocks = listOf(
            SleepStageTotals.NightBlock(late, late + 4 * 3600),
            SleepStageTotals.NightBlock(early, early + 4 * 3600),
        )
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    // ── seam invariant: total == main night, not the sum ────────────────────────────────

    @Test
    fun overnightPlusNapReportsConsistentTotalsNotTheSum() {
        val nightStart = atHour(23) - 86_400L
        val napStart = atHour(14)
        val nightStages = """{"awake":24,"light":214,"deep":82,"rem":96}""" // 392 min asleep
        val napStages = """{"awake":2,"light":30,"deep":10,"rem":8}"""       // 48 min asleep

        val mainOnly = SleepStageTotals.dailyAggregate(listOf(nightStages))!!
        assertEquals(392.0, mainOnly.totalSleepMin, 1e-6)

        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(nightStart to nightStages, napStart to napStages),
            edited = emptyMap(),
            onsetByStart = mapOf(nightStart to nightStart, napStart to napStart),
            offsetSec = 0L,
        )
        assertNotNull(r)
        assertEquals("day total = MAIN night, not night+nap sum", mainOnly.totalSleepMin, r!!.sleep.totalSleepMin, 1e-6)
        assertEquals(mainOnly.deepMin, r.sleep.deepMin, 1e-6)
        assertEquals(mainOnly.remMin, r.sleep.remMin, 1e-6)
        assertNotEquals("must NOT sum the nap in", 440.0, r.sleep.totalSleepMin, 1e-6)
    }

    @Test
    fun honoringEditsMainNightModeTracksEditedNightNotNapSum() {
        val nightStart = atHour(23) - 86_400L
        val napStart = atHour(14)
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(
                nightStart to """{"awake":24,"light":214,"deep":82,"rem":96}""",
                napStart to """{"awake":2,"light":30,"deep":10,"rem":8}""",
            ),
            edited = mapOf(nightStart to """{"awake":0,"light":118,"deep":82,"rem":96}"""), // trimmed to 296
            onsetByStart = mapOf(nightStart to nightStart, napStart to napStart),
            offsetSec = 0L,
        )
        assertNotNull(r)
        assertTrue(r!!.editApplied)
        assertEquals("tracks the EDITED main night, nap excluded", 296.0, r.sleep.totalSleepMin, 1e-6)
    }

    @Test
    fun honoringEditsLegacySumWhenNoOnsets() {
        // With no onsets the seam sums all blocks.
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(
                100L to """{"awake":2,"light":30,"deep":10,"rem":8}""",
                1000L to """{"awake":24,"light":214,"deep":82,"rem":96}""",
            ),
            edited = emptyMap(),
        )
        assertNotNull(r)
        assertEquals(48.0 + 392.0, r!!.sleep.totalSleepMin, 1e-6)
    }

    // ── inter-fragment awake (out-of-bed gap between bridged fragments) ────────────────────

    /** Sums only the positive gaps between consecutive (start,end) spans, sorted by start. Abutting or
     *  overlapping fragments contribute 0. */
    @Test
    fun interFragmentAwakeSecondsSumsPositiveGapsOnly() {
        val f1 = 0L to 6 * 3600L
        val f2 = (6 * 3600L + 20 * 60L) to 9 * 3600L
        assertEquals((20 * 60).toDouble(), SleepStageTotals.interFragmentAwakeSeconds(listOf(f1, f2)), 1e-6)
        // Order-independent.
        assertEquals((20 * 60).toDouble(), SleepStageTotals.interFragmentAwakeSeconds(listOf(f2, f1)), 1e-6)
        // Abutting / single → 0.
        assertEquals(0.0, SleepStageTotals.interFragmentAwakeSeconds(listOf(0L to 100L, 100L to 200L)), 1e-6)
        assertEquals(0.0, SleepStageTotals.interFragmentAwakeSeconds(listOf(0L to 100L)), 1e-6)
        // Two gaps across three fragments sum (60s + 40s = 100s).
        val three = listOf(0L to 100L, 160L to 300L, 340L to 500L)
        assertEquals(100.0, SleepStageTotals.interFragmentAwakeSeconds(three), 1e-6)
    }

    /** A main night bridged from two fragments split by a 20-min out-of-bed gap reports ~20 min awake,
     *  folded via the in-bed denominator with no double-count. */
    @Test
    fun honoringEditsFragmentedNightCountsGapAsAwake() {
        val f1Start = atHour(23) - 86_400L          // 23:00 onset, 180 min span
        val f2Start = atMin(2, 20)                  // 02:20 onset → 20-min gap after f1's 02:00 end
        val f1Stages = """{"awake":0,"light":120,"deep":30,"rem":30}""" // 180 asleep, 180 in-bed
        val f2Stages = """{"awake":0,"light":140,"deep":40,"rem":40}""" // 220 asleep, 220 in-bed

        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(f1Start to f1Stages, f2Start to f2Stages),
            edited = emptyMap(),
            onsetByStart = mapOf(f1Start to f1Start, f2Start to f2Start),
            offsetSec = 0L,
        )
        assertNotNull(r)
        assertFalse(r!!.editApplied)
        // Asleep is the SUM of the two fragments (400 min); the gap is awake, not sleep.
        assertEquals("asleep is the sum of both fragments", 400.0, r.sleep.totalSleepMin, 1e-6)
        // In-bed = 180 + 220 + 20 (gap) = 420 min. Awake = in-bed − asleep = 20 min (the gap), NOT ~0.
        val awakeMin = r.sleep.totalSleepMin / r.sleep.efficiency - r.sleep.totalSleepMin
        assertEquals("the 20-min out-of-bed gap reads as ~20 min awake (#777)", 20.0, awakeMin, 0.01)
        assertEquals("efficiency reflects the gap", 400.0 / 420.0, r.sleep.efficiency, 1e-4)
    }

    /** The seam and the standalone aggregate agree to the minute (no double-count): the same gap fed to
     *  dailyAggregate yields identical in-bed/awake. A zero gap matches the no-gap path. */
    @Test
    fun interFragmentAwakeFoldsConsistentlyNoDoubleCount() {
        val f1 = """{"awake":0,"light":120,"deep":30,"rem":30}""" // 180 asleep
        val f2 = """{"awake":0,"light":140,"deep":40,"rem":40}""" // 220 asleep
        val agg = SleepStageTotals.dailyAggregate(listOf(f1, f2), (20 * 60).toDouble())!!
        assertEquals(400.0, agg.totalSleepMin, 1e-6)
        assertEquals(400.0 / 420.0, agg.efficiency, 1e-4)
        val legacy = SleepStageTotals.dailyAggregate(listOf(f1, f2))!!
        val folded0 = SleepStageTotals.dailyAggregate(listOf(f1, f2), 0.0)!!
        assertEquals(legacy.efficiency, folded0.efficiency, 1e-12)
    }

    // ── learned-timing scored selector ───────────────────────────────────

    /** A long sleep with onset in the daytime gap [10:00, 20:00) beats a short overnight fragment;
     *  duration wins. */
    @Test
    fun longDaytimeOnsetBeatsShortOvernightFragment() {
        val dayLong = atHour(11)               // daytime-gap onset, 7h
        val nightFrag = atHour(23) - 86_400L   // overnight onset, only 1.5h
        val blocks = listOf(
            SleepStageTotals.NightBlock(dayLong, dayLong + 7 * 3600),       // 420 + ~0 bonus
            SleepStageTotals.NightBlock(nightFrag, nightFrag + 90 * 60),    // 90 + up-to-90 bonus
        )
        assertEquals("7h daytime sleep outscores a 1.5h overnight fragment", 0,
            SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** A 10:30 onset is overnight under [20:00,11:00). Equal-duration far-from-anchor blocks tie on score →
     *  earlier wins. */
    @Test
    fun tenThirtyOnsetIsTreatedAsOvernightNotDaytime() {
        assertTrue(SleepStageTotals.isOvernightOnset(atMin(10, 30), 0L))
        val early = atMin(10, 30)  // 3h, mid 12:00
        val nap = atHour(15)       // 3h, mid 16:30 (both beyond the 5h bonus zero → bonus 0)
        val blocks = listOf(
            SleepStageTotals.NightBlock(nap, nap + 3 * 3600),
            SleepStageTotals.NightBlock(early, early + 3 * 3600),
        )
        assertEquals("score tie → earlier onset; the [10,11) boundary no longer disagrees", 1,
            SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** A late/shift sleeper: a 14:00 habitual midsleep makes a daytime sleep the MAIN block. */
    @Test
    fun habitualMidsleepShiftsThePickForADaytimeSleeper() {
        val habitual = sod(14, 0)
        val dayBlock = atHour(11)             // 6h daytime, mid 14:00 (on the habitual)
        val nightBlock = atHour(23) - 86_400L // 6h overnight, mid 02:00
        val blocks = listOf(
            SleepStageTotals.NightBlock(nightBlock, nightBlock + 6 * 3600),
            SleepStageTotals.NightBlock(dayBlock, dayBlock + 6 * 3600),
        )
        assertEquals("with a 14:00 habitual midsleep the daytime sleep is main", 1,
            SleepStageTotals.mainNightIndex(blocks, 0L, habitual))
        assertEquals("cold-start band still favors the overnight block", 0,
            SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** A 4h habitual-aligned night beats a 5h afternoon block (timing, not duration). */
    @Test
    fun habitualAlignedShorterNightBeatsLongerAfternoon() {
        val habitual = sod(3, 0)
        val afternoon = atHour(13)  // 5h = 300, mid 15:30, bonus 0
        val night = atHour(1)       // 4h = 240, mid 03:00, bonus 90 → 330
        val blocks = listOf(
            SleepStageTotals.NightBlock(afternoon, afternoon + 5 * 3600),
            SleepStageTotals.NightBlock(night, night + 4 * 3600),
        )
        assertEquals("habitual-aligned 4h night beats a 5h afternoon (timing, not a hard floor)", 1,
            SleepStageTotals.mainNightIndex(blocks, 0L, habitual))
    }

    // ── invariant: a realistic daytime nap can NEVER out-rank the real night ───────────────

    // The invariant is protected by the +90 min alignment margin (score = asleepMin + bonus, bonus [0,90]).
    // Cold-start anchor 03:30: a >=4h night scores >=240; a true daytime doze (onset >=06:00, <=180 min)
    // tops out at 210, so the night always wins.

    /** A realistic daytime nap (20–180 min) can NEVER beat a real 4h+ night, COLD-START. Exhaustive
     *  sweep over the realistic ranges; the night (index 1) must always be the pick. */
    @Test
    fun realisticNapNeverBeatsRealNightColdStart() {
        // night: 4h..9h, onset 20:00..01:00 (prev-evening via -86400). nap: 20..180 min, onset 06:00..21:00.
        val nightStarts = listOf(atHour(20) - 86_400L, atHour(22) - 86_400L, atHour(23) - 86_400L,
            atHour(0), atHour(1))
        val nightHours = listOf(4, 5, 6, 7, 8, 9)
        val napStarts = listOf(atHour(6), atHour(8), atHour(10), atHour(12), atHour(13), atHour(15),
            atHour(17), atHour(19), atHour(21))
        val napMins = listOf(20, 30, 45, 60, 90, 120, 150, 180)
        for (nStart in nightStarts) for (nh in nightHours) for (pStart in napStarts) for (pm in napMins) {
            val blocks = listOf(
                SleepStageTotals.NightBlock(pStart, pStart + pm * 60L),
                SleepStageTotals.NightBlock(nStart, nStart + nh * 3600L),
            )
            assertEquals(
                "cold-start: a ${pm}min nap must NOT out-rank a ${nh}h night",
                1, SleepStageTotals.mainNightIndex(blocks, 0L),
            )
        }
    }

    /** Same pin, LEARNED-TIMING (habitual 03:00): the night earns the full +90 and a daytime nap earns 0
     *  (>5h circular away), so the night wins by an even larger margin. */
    @Test
    fun realisticNapNeverBeatsRealNightLearnedTiming() {
        val habitual = sod(3, 0)
        val nightStarts = listOf(atHour(22) - 86_400L, atHour(23) - 86_400L, atHour(0), atHour(1))
        val nightHours = listOf(4, 5, 6, 7, 8)
        val napStarts = listOf(atHour(10), atHour(12), atHour(13), atHour(15), atHour(17), atHour(19))
        val napMins = listOf(20, 45, 60, 90, 120, 150, 180)
        for (nStart in nightStarts) for (nh in nightHours) for (pStart in napStarts) for (pm in napMins) {
            val blocks = listOf(
                SleepStageTotals.NightBlock(pStart, pStart + pm * 60L),
                SleepStageTotals.NightBlock(nStart, nStart + nh * 3600L),
            )
            assertEquals(
                "learned: a ${pm}min nap must NOT out-rank a ${nh}h night",
                1, SleepStageTotals.mainNightIndex(blocks, 0L, habitual),
            )
        }
    }

    /** Tightest cold-start margin: a 4h night (mid 22:00, bonus 0 → 240) vs the best-case true-daytime doze
     *  (180min, mid 07:30, bonus 30 → 210) — night wins by 30. Pins the margin so a future bonus change
     *  can't erode it. */
    @Test
    fun tightestColdStartMarginNightStillWins() {
        val night = atHour(20) - 86_400L   // 4h, mid 22:00 → bonus 0 → 240
        val bestNap = atHour(6)            // 180min, mid 07:30 → bonus 30 → 210
        val blocks = listOf(
            SleepStageTotals.NightBlock(bestNap, bestNap + 180 * 60L),
            SleepStageTotals.NightBlock(night, night + 4 * 3600L),
        )
        assertEquals("worst-case realistic doze (210) still loses to a barely-timed 4h night (240)",
            1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** The genuinely-ambiguous case is intentional, not a regression: a short 4h night + a long 6h daytime
     *  sleep → the 6h block wins (longest qualifying, user can edit). Pins this so a future "harden the
     *  night" change can't silently flip it back. */
    @Test
    fun ambiguousLongDaytimeSleepBeatsShortNightByDesign() {
        val night = atHour(23) - 86_400L   // 4h overnight = 240, mid 01:00, cold-start bonus 75 → 315
        val dayLong = atHour(12)           // 6h daytime  = 360, mid 15:00, bonus 0 → 360
        val blocks = listOf(
            SleepStageTotals.NightBlock(night, night + 4 * 3600L),
            SleepStageTotals.NightBlock(dayLong, dayLong + 6 * 3600L),
        )
        assertEquals(
            "a 6h daytime sleep (360) beats a 4h night even WITH its bonus (315) — longest wins, by design",
            1, SleepStageTotals.mainNightIndex(blocks, 0L),
        )
    }

    /** Nap-only day (NO hard duration floor): a lone short nap still resolves to a main block. */
    @Test
    fun napOnlyDayResolvesToTheNapAsMain() {
        val nap = atHour(13)  // 40 min daytime nap, the only block
        assertEquals(0, SleepStageTotals.mainNightIndex(
            listOf(SleepStageTotals.NightBlock(nap, nap + 40 * 60)), 0L))
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(nap to """{"awake":2,"light":24,"deep":8,"rem":6}"""),
            edited = emptyMap(),
            onsetByStart = mapOf(nap to nap), offsetSec = 0L,
        )
        assertNotNull(r)
        assertEquals("nap-only day reports the nap's sleep", 38.0, r!!.sleep.totalSleepMin, 1e-6)
    }

    /** Cross-midnight onset: a 23:30 onset is overnight and its midpoint math wraps correctly. */
    @Test
    fun crossMidnightOnsetScoresAsNight() {
        val night = atMin(23, 30) - 86_400L  // 6h crossing midnight, mid 02:30
        val nap = atHour(13)                 // 1h
        val blocks = listOf(
            SleepStageTotals.NightBlock(nap, nap + 1 * 3600),
            SleepStageTotals.NightBlock(night, night + 6 * 3600),
        )
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
    }

    /** Circular-time correctness: 23:30 and 00:30 are an HOUR apart, not 23h. */
    @Test
    fun circularDistanceWrapsMidnight() {
        assertEquals(3600L, SleepStageTotals.circularDistanceSec(sod(23, 30), sod(0, 30)))
        assertEquals(3600L, SleepStageTotals.circularDistanceSec(sod(0, 30), sod(23, 30)))
        assertEquals(43200L, SleepStageTotals.circularDistanceSec(sod(12, 0), sod(0, 0)))
        assertEquals(0L, SleepStageTotals.circularDistanceSec(sod(3, 30), sod(3, 30)))
    }

    // ── habitual midsleep (learned timing) ──────────────────────────────────────────────────

    /** Cold-start: fewer than minDays of history → null (the scorer then uses the overnight band). */
    @Test
    fun habitualMidsleepNullOnColdStart() {
        val hist = (0 until 5).map { d ->
            val onset = atHour(23) - 86_400L + d * 86_400L
            SleepStageTotals.HistoryBlock(onset, onset + 7 * 3600, "day-$d")
        }
        assertNull(SleepStageTotals.habitualMidsleepSec(hist, 0L))
    }

    /** A regular sleeper: 20 nights at 23:00→06:00 (mid 02:30) → habitual midsleep == 02:30. Each night
     *  shares its day key with a short same-day nap; longest-per-day picks the night. */
    @Test
    fun habitualMidsleepLearnsRegularTiming() {
        val hist = ArrayList<SleepStageTotals.HistoryBlock>()
        for (d in 0 until 20) {
            val onset = atHour(23) - 86_400L + d * 86_400L
            val key = "night-$d"
            hist.add(SleepStageTotals.HistoryBlock(onset, onset + 7 * 3600, key))         // 7h night
            val napOnset = onset - 8 * 3600                                               // a 15:00 nap
            hist.add(SleepStageTotals.HistoryBlock(napOnset, napOnset + 1 * 3600, key))   // 1h nap, same key
        }
        val mid = SleepStageTotals.habitualMidsleepSec(hist, 0L)
        assertNotNull(mid)
        assertEquals("midsleep is the night's midpoint, naps excluded", sod(2, 30), mid!!)
    }

    /** Circular learning across midnight: mids at 23:30 and 00:30 average to ~midnight, not noon. */
    @Test
    fun habitualMidsleepCircularAcrossMidnight() {
        val hist = ArrayList<SleepStageTotals.HistoryBlock>()
        for (d in 0 until 16) {
            // even d → onset 20:00 (7h → mid 23:30); odd d → onset 21:00 (7h → mid 00:30).
            val baseOnset = if (d % 2 == 0) atHour(20) - 86_400L else atHour(21) - 86_400L
            val onset = baseOnset + d * 86_400L
            hist.add(SleepStageTotals.HistoryBlock(onset, onset + 7 * 3600, "day-$d"))
        }
        val mid = SleepStageTotals.habitualMidsleepSec(hist, 0L)
        assertNotNull(mid)
        val dist = SleepStageTotals.circularDistanceSec(mid!!, sod(0, 0))
        assertTrue("circular mean of 23:30/00:30 ≈ midnight, not noon", dist < 120L)
    }

    // ── UI selector and engine selector agree for a shift sleeper ───────────

    /** With a learned ~15:00 habitual midsleep for a shift sleeper, the UI hero and engine selectors both
     *  pick the afternoon block; the cold-start selector (null habitual) instead picks overnight. */
    @Test
    fun shiftSleeperUIAndEngineSelectorPickSameAfternoonBlock() {
        // 1) Learn the habitual from 20 afternoon nights (onset 12:00, 6h → mid 15:00). Distinct day keys.
        val hist = ArrayList<SleepStageTotals.HistoryBlock>()
        for (d in 0 until 20) {
            val onset = atHour(12) + d * 86_400L
            hist.add(SleepStageTotals.HistoryBlock(onset, onset + 6 * 3600, "day-$d"))
        }
        val habitual = SleepStageTotals.habitualMidsleepSec(hist, 0L)
        assertNotNull("20 afternoon nights clear the cold-start threshold", habitual)
        assertTrue("learned midsleep is ~15:00 for this shift sleeper",
            SleepStageTotals.circularDistanceSec(habitual!!, sod(15, 0)) < 120L)

        // 2) A target day with BOTH an afternoon main sleep (mid ~15:00, on the habitual) AND a shorter
        //    overnight block. Index 0 = overnight, index 1 = afternoon (input order is irrelevant).
        val overnight = atHour(23) - 86_400L          // 5h overnight, mid ~01:30 (far from 15:00)
        val afternoon = atHour(12)                    // 6h afternoon, mid 15:00 (on the habitual)
        val blocks = listOf(
            SleepStageTotals.NightBlock(overnight, overnight + 5 * 3600),
            SleepStageTotals.NightBlock(afternoon, afternoon + 6 * 3600),
        )

        // The shared selector WITH the learned habitual (used by both the UI hero and the engine).
        val withHabitual = SleepStageTotals.mainNightIndex(blocks, 0L, habitual)
        assertEquals("with the learned ~15:00 habitual, the afternoon block is main (engine + UI agree)",
            1, withHabitual)

        // The cold-start call (null habitual) picks the overnight block.
        val coldStart = SleepStageTotals.mainNightIndex(blocks, 0L)
        assertEquals("cold-start band picks the overnight block — the pre-fix UI/engine divergence",
            0, coldStart)
        assertNotEquals("the learned habitual is exactly what makes the UI agree with the engine",
            withHabitual, coldStart)
    }

    // ── circularMeanSec degenerate-vector guard ────────────────────────────────────

    /** A 16-day history split evenly between two antipodal sleep times (midpoints 12h apart) clears the
     *  day-count threshold but yields null, not a bogus midnight/noon anchor. */
    @Test
    fun habitualMidsleepNullWhenLearnedTimingIsAntipodal() {
        val hist = ArrayList<SleepStageTotals.HistoryBlock>()
        for (d in 0 until 16) {
            // even d → onset 20:30 (7h → mid 00:00); odd d → onset 08:30 (7h → mid 12:00). Distinct keys.
            val baseOnset = if (d % 2 == 0) atMin(20, 30) - 86_400L else atMin(8, 30)
            val onset = baseOnset + d * 86_400L
            hist.add(SleepStageTotals.HistoryBlock(onset, onset + 7 * 3600, "day-$d"))
        }
        assertNull("antipodal learned timing → null (cold-start fallback), not a meaningless anchor",
            SleepStageTotals.habitualMidsleepSec(hist, 0L))
    }

    // ── effective (edited) onset crosses the boundary ────

    /** The seam scores on the EFFECTIVE (edited) onset, not the detected key. Main (300 asleep) detected
     *  09:30 (bonus 0) but edited to 22:30 (~75 bonus → ~375) beats a longer 340-asleep nap on effective
     *  onset (375>340), while on the detected onset the nap mis-wins (340>300). */
    @Test
    fun editedOnsetCrossingBoundaryIsScoredOnTheEffectiveOnset() {
        val detectedStart = atMin(9, 30)            // detected daytime onset (bonus 0)
        val effectiveStart = atMin(22, 30) - 86_400L // user moved bedtime to the prior evening (bonus ~75)
        val napStart = atHour(15)                    // far from the band center (bonus 0)
        val mainStages = """{"awake":0,"light":150,"deep":80,"rem":70}"""   // 300 asleep
        val napStages = """{"awake":0,"light":170,"deep":90,"rem":80}"""    // 340 asleep (longer)
        val blocksByStages = listOf(detectedStart to mainStages, napStart to napStages)
        val onEffective = mapOf(detectedStart to effectiveStart, napStart to napStart)
        assertEquals(
            "effective overnight onset earns the bonus → the main block wins", 0,
            SleepStageTotals.mainNightIndexByStages(blocksByStages, onEffective, 0L),
        )
        val onDetected = mapOf(detectedStart to detectedStart, napStart to napStart)
        assertEquals(
            "detected onset misses the bonus → the longer nap mis-wins (the finding-C bug)", 1,
            SleepStageTotals.mainNightIndexByStages(blocksByStages, onDetected, 0L),
        )
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(detectedStart to mainStages, napStart to napStages),
            edited = mapOf(detectedStart to mainStages),
            onsetByStart = onEffective, offsetSec = 0L,
        )
        assertNotNull(r)
        assertTrue(r!!.editApplied)
        assertEquals(
            "seam scores on the EFFECTIVE onset → the corrected overnight block is the day total",
            300.0, r.sleep.totalSleepMin, 1e-6,
        )
    }

    /** The habitual midsleep threads through the seam: an afternoon habitual makes a longer afternoon
     *  block the headline total over a shorter overnight one, exactly as the bare selector does. */
    @Test
    fun honoringEditsHonorsHabitualMidsleep() {
        val habitual = sod(14, 0)
        val nightStart = atHour(23) - 86_400L  // 4h overnight, mid 01:00
        val dayStart = atHour(11)              // 6h afternoon, mid 14:00 (on the habitual)
        val nightStages = """{"awake":0,"light":120,"deep":60,"rem":60}"""  // 240 asleep
        val dayStages = """{"awake":0,"light":200,"deep":80,"rem":80}"""    // 360 asleep
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(nightStart to nightStages, dayStart to dayStages),
            edited = mapOf(dayStart to dayStages),
            onsetByStart = mapOf(nightStart to nightStart, dayStart to dayStart),
            offsetSec = 0L,
            habitualMidsleepSec = habitual,
        )
        assertNotNull(r)
        assertEquals(
            "afternoon habitual → the on-timing afternoon block is the headline total",
            360.0, r!!.sleep.totalSleepMin, 1e-6,
        )
    }

    // ── selection REASON (explainability — one test per branch) ──────────────────────────────────
    // mainNightSelection mirrors mainNightIndex (same score/tie-break/null-on-empty) + adds MainNightReason
    // and the chosen block's asleep duration for UI copy. Each branch pinned with the same score fixtures.

    /** Empty → null, exactly like [SleepStageTotals.mainNightIndex]. */
    @Test
    fun selectionNullOnEmpty() {
        assertNull(SleepStageTotals.mainNightSelection(emptyList(), 0L))
    }

    /** REASON onlyBlock: a single block → nothing to choose between; carries that block's asleep span. */
    @Test
    fun reasonOnlyBlock() {
        val night = atHour(23) - 86_400L
        val sel = SleepStageTotals.mainNightSelection(
            listOf(SleepStageTotals.NightBlock(night, night + 7 * 3600 + 12 * 60)), 0L)
        assertNotNull(sel)
        assertEquals(0, sel!!.index)
        assertEquals(SleepStageTotals.MainNightReason.onlyBlock, sel.reason)
        assertEquals("asleep span carried for the copy (7h 12m)", 7 * 3600L + 12 * 60L, sel.asleepSec)
        assertEquals(432L, sel.asleepMin)  // 7h12m = 432 min
    }

    /** REASON longest (cold-start): null habitual → longest wins on duration and the reason is
     *  [SleepStageTotals.MainNightReason.longest], even though this block also earns a cold-start bonus
     *  (null habitual short-circuits to longest, never longestNearUsual). */
    @Test
    fun reasonLongestColdStart() {
        val a = atHour(22) - 86_400L
        val b = atHour(23) - 86_400L + 1_800L   // 23:30 onset, 6h → mid 02:30 (earns a cold-start bonus)
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, a + 3 * 3600),  // 3h
            SleepStageTotals.NightBlock(b, b + 6 * 3600),  // 6h longest
        )
        // selector picks index 1 and explains it cold-start.
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L))
        val sel = SleepStageTotals.mainNightSelection(blocks, 0L)   // null habitual = cold-start
        assertNotNull(sel)
        assertEquals(1, sel!!.index)
        assertEquals("cold-start (null habitual) → longest, never longestNearUsual",
            SleepStageTotals.MainNightReason.longest, sel.reason)
        assertEquals(6 * 3600L, sel.asleepSec)
    }

    /** REASON longestNearUsual: a learned habitual is present and the chosen block is both the longest by
     *  duration AND alignment-bonus-eligible. Habitual 03:00; the 6h night (mid 02:00) beats a 1h nap on
     *  duration alone, so the bonus didn't flip the pick — "longest, near usual". */
    @Test
    fun reasonLongestNearUsual() {
        val habitual = sod(3, 0)
        val night = atHour(23) - 86_400L  // 6h, mid 02:00 — longest AND ~1h from the 03:00 habitual
        val nap = atHour(15)              // 1h afternoon, far from the habitual
        val blocks = listOf(
            SleepStageTotals.NightBlock(nap, nap + 1 * 3600),
            SleepStageTotals.NightBlock(night, night + 6 * 3600),
        )
        // duration-only winner == score winner == the night (index 1): the bonus did NOT flip it.
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L, habitual))
        val sel = SleepStageTotals.mainNightSelection(blocks, 0L, habitual)
        assertNotNull(sel)
        assertEquals(1, sel!!.index)
        assertEquals("learned habitual + longest is also bonus-aligned → longestNearUsual",
            SleepStageTotals.MainNightReason.longestNearUsual, sel.reason)
        assertEquals(6 * 3600L, sel.asleepSec)
    }

    /** REASON alignedToUsual: the alignment bonus (not duration) flips the pick. Habitual 03:00; a 5h
     *  afternoon (mid 15:30, bonus 0 → 300) is longer, but a 4h aligned night (mid 03:00, bonus 90 → 330)
     *  wins on timing. */
    @Test
    fun reasonAlignedToUsual() {
        val habitual = sod(3, 0)
        val afternoon = atHour(13)  // 5h = 300, mid 15:30, bonus 0  (the duration-only winner)
        val night = atHour(1)       // 4h = 240, mid 03:00, bonus 90 → 330 (the score winner)
        val blocks = listOf(
            SleepStageTotals.NightBlock(afternoon, afternoon + 5 * 3600),
            SleepStageTotals.NightBlock(night, night + 4 * 3600),
        )
        // the score winner (night, index 1) differs from the duration-only winner (afternoon, index 0).
        assertEquals(1, SleepStageTotals.mainNightIndex(blocks, 0L, habitual))
        val sel = SleepStageTotals.mainNightSelection(blocks, 0L, habitual)
        assertNotNull(sel)
        assertEquals(1, sel!!.index)
        assertEquals("a shorter well-timed block out-scored the longest → alignedToUsual (timing flipped it)",
            SleepStageTotals.MainNightReason.alignedToUsual, sel.reason)
        assertEquals("carries the CHOSEN (4h night) block's asleep span, not the longer afternoon's",
            4 * 3600L, sel.asleepSec)
    }

    /** The selection's index and the chosen block always agree with the bare [SleepStageTotals.mainNightIndex]
     *  selector across the cold-start AND learned paths — the explainer can never point at a different block
     *  than the one the score actually chose. */
    @Test
    fun selectionIndexAlwaysMatchesMainNightIndex() {
        val habitual = sod(3, 0)
        val a = atHour(23) - 86_400L
        val b = atHour(13)
        val blocks = listOf(
            SleepStageTotals.NightBlock(b, b + 5 * 3600),
            SleepStageTotals.NightBlock(a, a + 4 * 3600),
        )
        for (h in listOf(null, habitual)) {
            assertEquals(
                SleepStageTotals.mainNightIndex(blocks, 0L, h),
                SleepStageTotals.mainNightSelection(blocks, 0L, h)?.index,
            )
        }
    }

    // ── biphasic gap-bridge (mainNightGroupIndices) ─────────────────────────────────────────

    /** Two overnight fragments split by a < 60-min wake gap → one group of both. */
    @Test
    fun groupIndicesBridgesTwoAdjacentFragments() {
        val a = atHour(23) - 86_400L
        val aEnd = a + 3 * 3600           // 23:00 → 02:00
        val b = aEnd + 30 * 60            // 02:30 (30-min gap < 60-min bridge)
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, aEnd),
            SleepStageTotals.NightBlock(b, b + 3 * 3600),
        )
        assertEquals(listOf(0, 1), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    /** A long wake gap is NOT bridged — only the winning main block is the group. */
    @Test
    fun groupIndicesDoesNotBridgeLongGap() {
        val a = atHour(23) - 86_400L
        val aEnd = a + 5 * 3600           // 5h overnight (the main night)
        val b = aEnd + 5 * 3600           // 5h gap >> bridge
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, aEnd),
            SleepStageTotals.NightBlock(b, b + 2 * 3600),
        )
        assertEquals(listOf(0), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    /** No gap → the group is exactly the single block mainNightIndex would pick (no regression). */
    @Test
    fun groupIndicesSingleBlockMatchesBareSelector() {
        val s = atHour(0)
        val blocks = listOf(SleepStageTotals.NightBlock(s, s + 7 * 3600))
        assertEquals(listOf(0), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
        assertNull(SleepStageTotals.mainNightGroupIndices(emptyList(), 0L))
    }

    /** A bridged biphasic night (2h + gap + 2h) out-scores a lone 3h nap, returning BOTH its fragments. */
    @Test
    fun groupIndicesBridgedNightOutscoresLoneNap() {
        val f1 = atHour(23) - 86_400L
        val f1End = f1 + 2 * 3600
        val f2 = f1End + 20 * 60
        val f2End = f2 + 2 * 3600
        val nap = atHour(13)
        val blocks = listOf(
            SleepStageTotals.NightBlock(f1, f1End),
            SleepStageTotals.NightBlock(nap, nap + 3 * 3600),
            SleepStageTotals.NightBlock(f2, f2End),
        )
        assertEquals(listOf(0, 2), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    // ── a real overnight night split by a 60–90 min wake is ONE sleep, not nap + sleep ──────────

    /** A real overnight sleep split by a ~70-min mid-night wake folds into one main-night group via the
     *  night-tail bridge (overnight-band onset, ≤ NIGHT_TAIL_BRIDGE_MAX_MIN); the gap folds into awake,
     *  no stage invented. */
    @Test
    fun overnightNightSplitBySeventyMinuteWakeMergesIntoOneSleepNotNap() {
        val a = atMin(23, 30) - 86_400L              // overnight onset
        val aEnd = a + 3 * 3600                       // 23:30 → 02:30
        val b = aEnd + 70 * 60                        // 03:40 onset (70-min gap; 60 ≤ gap < 90)
        val bEnd = b + 4 * 3600                       // 03:40 → 07:40 (the longer tail)
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, aEnd),
            SleepStageTotals.NightBlock(b, bEnd),
        )
        assertEquals(listOf(0, 1), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    /** A genuine afternoon nap (daytime onset) stays its own block; the night-tail bridge requires an
     *  overnight-band onset. */
    @Test
    fun daytimeNapStillStaysItsOwnBlockUnderWiderBridge() {
        val night = atHour(0)                         // 00:00 → 06:00 overnight
        val nightEnd = night + 6 * 3600
        val nap = atHour(13)                          // 13:00 daytime onset → never a night-tail
        val blocks = listOf(
            SleepStageTotals.NightBlock(night, nightEnd),
            SleepStageTotals.NightBlock(nap, nap + 90 * 60),
        )
        assertEquals(listOf(0), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    /** A wake gap at/over NIGHT_TAIL_BRIDGE_MAX_MIN (90 min) is not a mid-night wake, so the blocks stay
     *  separate even for an overnight-band onset. */
    @Test
    fun overnightGapAtOrAboveNinetyMinutesDoesNotBridge() {
        val a = atHour(23) - 86_400L
        val aEnd = a + 3 * 3600                        // 23:00 → 02:00
        val b = aEnd + 95 * 60                         // 03:35 onset (95-min gap ≥ 90)
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, aEnd),
            SleepStageTotals.NightBlock(b, b + 4 * 3600),
        )
        assertEquals(listOf(1), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
    }

    /**
     * Regression guard: pins `mainNightGroupIndices` for a biphasic main night to a byte-identical frozen
     * golden, so an unrelated ingest/motion change can't move the selection.
     */
    @Test
    fun mainNightGroupIndicesByteIdenticalForBiphasicNight() {
        // Two night fragments split by a 35-min wake gap (bridges) + a far afternoon nap (does NOT bridge).
        val a = atMin(23, 10) - 86_400L            // 23:10 → 01:30 (140 min)
        val aEnd = a + 140 * 60
        val b = aEnd + 35 * 60                       // 02:05, 35-min gap → bridges
        val bEnd = b + 275 * 60                      // → 06:40
        val nap = atHour(15)                         // 15:00 → 16:20 (far → no bridge)
        val blocks = listOf(
            SleepStageTotals.NightBlock(a, aEnd),    // idx 0
            SleepStageTotals.NightBlock(b, bEnd),    // idx 1
            SleepStageTotals.NightBlock(nap, nap + 80 * 60), // idx 2
        )
        // Frozen golden: the two bridged night fragments are the group; the nap is excluded. A change here
        // means the main-night selection moved.
        assertEquals(listOf(0, 1), SleepStageTotals.mainNightGroupIndices(blocks, 0L))
        // Cold-start and learned-habitual both land identically (duration dominates), proving neither path
        // perturbs the bridge.
        val habitualMid = ((a + 70 * 60) % 86_400L)
        assertEquals(listOf(0, 1), SleepStageTotals.mainNightGroupIndices(blocks, 0L, habitualMid))
        assertTrue(SleepStageTotals.mainNightIndex(blocks, 0L) in listOf(0, 1))
    }

    /** The stages-path seam sums the bridged biphasic group, not the longest fragment. */
    @Test
    fun honoringEditsSumsBiphasicGroup() {
        val a = atHour(23) - 86_400L
        val aStages = """{"awake":8,"light":24,"deep":82,"rem":96}"""   // 202 asleep + 8 wake = 210 in-bed
        val aInBedSec = 210 * 60
        val b = a + aInBedSec + 20 * 60                                  // 20-min wake gap < 60-min bridge
        val bStages = """{"awake":10,"light":20,"deep":90,"rem":70}"""   // 180 asleep
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detected = listOf(a to aStages, b to bStages),
            edited = emptyMap(),
            onsetByStart = mapOf(a to a, b to b),
            offsetSec = 0L,
        )
        assertNotNull(r)
        assertEquals("the seam SUMS the bridged biphasic group", 382.0, r!!.sleep.totalSleepMin, 1e-6)
        assertEquals(172.0, r.sleep.deepMin, 1e-6)   // 82 + 90
    }
}
