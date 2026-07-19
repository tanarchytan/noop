package com.noop.ui

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that [isPreOnsetAwakeStub] lets [selectNight] skip a spurious pre-onset awake stub, so the
 * hypnogram/bedtime start at the main block's onset. A genuine biphasic first sleep is never a stub.
 */
class SleepOnsetStubTest {

    private fun block(startTs: Long, endTs: Long, stagesJSON: String?): SleepSession =
        SleepSession(deviceId = "dev", startTs = startTs, endTs = endTs, stagesJSON = stagesJSON)

    /** A brief, all-awake leading block (15 min, 0 asleep) IS a spurious pre-onset stub. */
    @Test
    fun briefAllAwakeIsStub() {
        val b = block(0, 15 * 60, """{"awake":15,"light":0,"deep":0,"rem":0}""")
        assertTrue(isPreOnsetAwakeStub(b))
    }

    /** A short block that already holds real sleep (12 min span, 8 asleep) is NOT a stub. */
    @Test
    fun shortButAsleepIsNotStub() {
        val b = block(0, 12 * 60, """{"awake":4,"light":8,"deep":0,"rem":0}""")
        assertFalse(isPreOnsetAwakeStub(b))
    }

    /** A LONG all-awake pre-sleep block (~2h45m, 0 asleep) IS a stub — a multi-hour lie-in before
     *  sleep is not part of the night, so it drops off the displayed bedtime. */
    @Test
    fun longAllAwakePreSleepBlockIsStub() {
        val b = block(0, 165 * 60, """{"awake":165,"light":0,"deep":0,"rem":0}""")
        assertTrue(isPreOnsetAwakeStub(b))
    }

    /** A truly absurd all-day awake block (beyond the cap) is NOT silently swallowed. */
    @Test
    fun beyondCapIsNotStub() {
        val b = block(0, 300 * 60, """{"awake":300,"light":0,"deep":0,"rem":0}""")
        assertFalse(isPreOnsetAwakeStub(b))
    }

    /** A block with no parseable stages but within the cap still reads as a (sleepless) stub. */
    @Test
    fun shortWithNoStagesIsStub() {
        val b = block(0, 10 * 60, null)
        assertTrue(isPreOnsetAwakeStub(b))
    }

    /** A leading fragment with some sleep but minor relative to the main block is a spurious lead (a
     *  stub). Without a reference the relative test is OFF, so the same fragment is NOT a stub. */
    @Test
    fun minorRelativeLeadingFragmentIsStub() {
        // 30 min span, 10 asleep min; main block asleep 400 -> 10 < 0.15*400 = 60 -> spurious.
        val b = block(0, 30 * 60, """{"awake":20,"light":10,"deep":0,"rem":0}""")
        assertTrue(isPreOnsetAwakeStub(b, refAsleepMin = 400.0))
        assertFalse(isPreOnsetAwakeStub(b))
    }

    /** A substantial earlier sleep (comparable to the main block) is a genuine biphasic first sleep
     *  and is NEVER dropped, even with a reference size. */
    @Test
    fun substantialBiphasicFragmentIsNotStubEvenWithRef() {
        // 90 asleep min vs main block 240 -> 90 >= 0.15*240 = 36 -> kept.
        val b = block(0, 100 * 60, """{"awake":10,"light":60,"deep":30,"rem":0}""")
        assertFalse(isPreOnsetAwakeStub(b, refAsleepMin = 240.0))
    }

    /**
     * At selectNight level: hero segments start at the FIRST real sleep fragment (never the stub), the
     * biphasic split is preserved, and the stub stays in the main-night group (not naps).
     */
    @Test
    fun selectNightDropsLeadingStubButKeepsBiphasicNight() {
        // Stub: 21:41-21:55 (14 min), all awake.
        val stubStart = 1_780_000_000L
        val stubEnd = stubStart + 14 * 60
        val stub = block(stubStart, stubEnd,
            """[{"start":$stubStart,"end":$stubEnd,"stage":"wake"}]""")
        // Sleep fragment A ~46 min after the stub (gap < gapBridgeMaxMin so all three bridge), 3h.
        val aStart = stubStart + 60 * 60
        val aEnd = aStart + 3 * 3600
        val fragA = block(aStart, aEnd,
            """[
                {"start":$aStart,"end":${aStart + 3600},"stage":"light"},
                {"start":${aStart + 3600},"end":$aEnd,"stage":"deep"}
            ]""")
        // A brief wake, then sleep fragment B, 4h (the longest → the main block / edit anchor).
        val bStart = aEnd + 20 * 60
        val bEnd = bStart + 4 * 3600
        val fragB = block(bStart, bEnd,
            """[
                {"start":$bStart,"end":${bStart + 2 * 3600},"stage":"light"},
                {"start":${bStart + 2 * 3600},"end":$bEnd,"stage":"rem"}
            ]""")
        val navDays = listOf(listOf(stub, fragA, fragB))

        val hero = selectNight(navDays, emptyList(), 0, habitualMidsleepSec = null)!!
        val firstSeg = hero.groupSegments!!.first()
        assertTrue("hero segments must start at real sleep (>= fragment A), not the pre-onset stub",
            firstSeg.start >= aStart)
        // Both real fragments contribute (biphasic night preserved): more than fragment B alone.
        assertTrue("biphasic night must keep both real fragments", hero.groupSegments!!.size >= 4)
        // The stub is not a nap — it stays inside the main-night group.
        assertTrue(hero.napBlocks.none { it.startTs == stubStart })
        // The hero's clock WINDOW spans the whole night (bedtime = fragment A onset, wake = fragment B
        // end), independent of the winning session (fragment B) — else the Asleep/Woke row and hypnogram
        // axis would contradict the header pill.
        assertTrue("session (edit anchor) is fragment B", hero.session.startTs == bStart)
        assertTrue("window opens at the displayed bedtime (fragment A)", hero.heroOnsetTs == aStart)
        assertTrue("window closes at the group's latest wake (fragment B end)", hero.heroWakeTs == bEnd)
    }

    // ── Real-night regression: a genuine short first sleep is NOT a lead ──

    /** Real-night regression: a 34-min first-sleep fragment beside a ~340-min main asleep is NOT a stub —
     *  the absolute asleep floor (34 ≥ 20) keeps a real sleep episode even below the 15% relative cutoff. */
    @Test
    fun realFirstSleepFragmentIsNotStub() {
        // 66.8 min span, 34 asleep min, main asleep 340 — 34 < 15%*340 (≈51) but the absolute floor keeps it.
        val b = block(0, 4008, """{"awake":33,"light":34,"deep":0,"rem":0}""")
        assertFalse("a real 34-min first sleep beside a 340-min main is NOT a spurious lead",
            isPreOnsetAwakeStub(b, refAsleepMin = 340.0))
    }

    /** Floor boundary: a fragment carrying at least [PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN] asleep minutes is a
     *  real sleep episode and never a spurious lead, whatever the main block's size. */
    @Test
    fun atMinorAsleepFloorIsNotStub() {
        val b = block(0, 40 * 60, """{"awake":5,"light":20,"deep":0,"rem":0}""")   // exactly 20 asleep min
        assertFalse(isPreOnsetAwakeStub(b, refAsleepMin = 1000.0))
    }

    /** A tiny stray sleep lead (10 asleep, under the floor AND under 15% of a big main) is still a
     *  spurious lead. */
    @Test
    fun tinyStrayLeadStillStubUnderFloor() {
        val b = block(0, 30 * 60, """{"awake":20,"light":10,"deep":0,"rem":0}""")
        assertTrue(isPreOnsetAwakeStub(b, refAsleepMin = 400.0))
    }

    // ── Decode-format regression: a computed night's SEGMENT ARRAY first sleep is not read as 0 asleep ──

    /**
     * A COMPUTED night's SEGMENT ARRAY first-sleep fragment. Non-wake sum: light 926s + deep 1320s +
     * rem 990s = 3236s ≈ 53.9 asleep min. Must decode format-agnostically ([decodedAsleepMinutes]).
     */
    private val fragmentSegmentJson =
        """[{"end":1784013450,"stage":"light","start":1784013364},{"end":1784013540,"stage":"wake","start":1784013450},{"end":1784013600,"stage":"light","start":1784013540},{"end":1784013720,"stage":"wake","start":1784013600},{"end":1784013930,"stage":"light","start":1784013720},{"end":1784015250,"stage":"deep","start":1784013930},{"end":1784015580,"stage":"light","start":1784015250},{"end":1784015640,"stage":"wake","start":1784015580},{"end":1784015880,"stage":"light","start":1784015640},{"end":1784016180,"stage":"wake","start":1784015880},{"end":1784017170,"stage":"rem","start":1784016180},{"end":1784017375,"stage":"wake","start":1784017170}]"""

    /** Guard: the segment-array format decodes to its real asleep minutes, not 0. */
    @Test
    fun segmentArrayFormatDecodesRealAsleepMinutes() {
        val asleep = decodedAsleepMinutes(fragmentSegmentJson, effectiveStartTs = 1_784_013_364L)
        assertEquals(3236.0 / 60.0, asleep, 0.01)
        // Above the floor: a real sleep episode must clear it.
        assertTrue(asleep >= PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN)
    }

    /** END-TO-END: a real segment-array first-sleep fragment beside a large main block is NOT skipped as a
     *  stub — format-agnostic decode populates its asleep minutes and the floor keeps it. */
    @Test
    fun segmentArrayFirstSleepFragmentIsNotSkipped() {
        val frag = block(1_784_013_364L, 1_784_017_375L, fragmentSegmentJson)
        assertFalse("a real segment-array first sleep must not be skipped as a stub",
            isPreOnsetAwakeStub(frag, refAsleepMin = 340.0))
    }

    /** A genuine single-block night is unchanged: the session is that block. */
    @Test
    fun singleBlockNightUnchanged() {
        val start = 1_780_000_000L
        val end = start + 7 * 3600
        val one = block(start, end,
            """[
                {"start":$start,"end":${start + 3 * 3600},"stage":"light"},
                {"start":${start + 3 * 3600},"end":$end,"stage":"deep"}
            ]""")
        val hero = selectNight(listOf(listOf(one)), emptyList(), 0, habitualMidsleepSec = null)!!
        assertEquals(start, hero.session.effectiveStartTs)
    }
}
