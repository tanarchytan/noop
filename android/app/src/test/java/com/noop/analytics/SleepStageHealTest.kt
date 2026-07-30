package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the post-sync self-heal of edit-before-sync stages: an edit made before raw arrives freezes a
 * fabricated [SleepWindowReclip] breakdown (a trailing "wake" block, `userEdited = 1`); once dense raw
 * is available the heal re-derives REAL stages over the night's LOCKED bounds and rewrites stages only.
 */
class SleepStageHealTest {

    private val dev = "test"

    /** 2025-06-10 00:00:00 UTC — fixed midnight (ref % 86400 == 0). */
    private val refMidnight = 1_749_513_600L
    private fun startAtHour(hourUTC: Int): Long = refMidnight + hourUTC * 3_600L

    /** Dense still gravity (constant orientation) at 1 Hz over [start, start+durationS). */
    private fun stillGravity(start: Long, durationS: Int): List<GravitySample> =
        (0 until durationS).map { GravitySample(deviceId = dev, ts = start + it, x = 0.0, y = 0.0, z = 1.0) }

    private fun hrStream(start: Long, durationS: Int, bpm: Int): List<HrSample> =
        (0 until durationS).map { HrSample(deviceId = dev, ts = start + it, bpm = bpm) }

    /** Encode a single-stage span to the on-device `[{...}]` stagesJSON (the encoder under test). */
    private fun encoded(start: Long, end: Long, stage: String): String =
        AnalyticsEngine.encodeStages(listOf(StageSegment(start = start, end = end, stage = stage)))!!

    // ── 1. Heal once: edited-before-sync night re-derives to REAL stages when dense raw arrives ──────

    @Test
    fun editedBeforeSyncHealsToRealStagesOnceRawArrives() {
        val start = startAtHour(1)
        val dur = 6 * 60 * 60
        val end = start + dur - 1
        val grav = stillGravity(start, dur)
        val hr = hrStream(start, dur, 50)

        // Fabricated breakdown at edit time (raw not present yet): production SleepWindowReclip path
        // extends a stored "light" block to the corrected wake, appending a trailing "wake" segment.
        // This is EXACTLY what the heal must replace.
        val storedDetected = encoded(start, start + 3 * 60 * 60, "light") // a 3h detected block
        val fabricated = SleepWindowReclip.reclip(storedDetected, start, start + 3 * 60 * 60, start, end)!!

        val real = SleepStageHealer.restageFromSamples(start, end, grav, hr, emptyList(), emptyList())
        assertNotNull("dense raw over the locked window must re-derive real stages", real)
        assertNotEquals("real stages must differ from the fabricated reclip block", fabricated, real)
        // Real re-derive is a multi-segment hypnogram, not the 2-segment reclip approximation.
        assertTrue("re-derived JSON must be a segment array", real!!.trimStart().startsWith("["))
        val realSegments = SleepStageTotals.minutes(real)
        assertNotNull("real stages must decode to stage minutes", realSegments)
        assertTrue("a 6h dense night must record real asleep time, not all-wake",
            realSegments!!.asleep > 0.0)
    }

    // ── 2. No-raw night stays as-edited (imported night: gravity never dense) ────────────────────────

    @Test
    fun noRawNightStaysAsEdited() {
        val start = startAtHour(2)
        val end = start + 6 * 60 * 60 - 1
        // A genuine imported night: a handful of stray gravity samples, far below max(20, windowS/120).
        val sparseGrav = (0 until 5).map { GravitySample(dev, start + it * 600L, 0.0, 0.0, 1.0) }
        assertFalse("5 samples over a 6 h window must NOT clear the density gate",
            SleepStageHealer.isDense(sparseGrav, start, end))

        val derived = SleepStageHealer.restageFromSamples(start, end, sparseGrav, emptyList(), emptyList(), emptyList())
        assertNull("a non-dense night must return null so the stored stages are kept", derived)
    }

    @Test
    fun densityGateMatchesIosFloor() {
        val start = startAtHour(3)
        val end = start + 6 * 60 * 60 // windowSeconds == 21600, /120 == 180 → floor is max(20, 180) = 180
        // 179 samples just inside the window → still below the floor.
        val justUnder = (0 until 179).map { GravitySample(dev, start + it, 0.0, 0.0, 1.0) }
        assertFalse(SleepStageHealer.isDense(justUnder, start, end))
        // 180 in-window samples clears it; out-of-window padding must not be counted.
        val atFloor = (0 until 180).map { GravitySample(dev, start + it, 0.0, 0.0, 1.0) } +
            (0 until 50).map { GravitySample(dev, start - 100L - it, 0.0, 0.0, 1.0) } // before window
        assertTrue(SleepStageHealer.isDense(atFloor, start, end))
    }

    // ── 3. Second pass is a no-op (idempotent) — re-derive over identical bounds+raw equals stored ───

    @Test
    fun secondPassIsIdempotent() {
        val start = startAtHour(4)
        val dur = 6 * 60 * 60
        val end = start + dur - 1
        val grav = stillGravity(start, dur)
        val hr = hrStream(start, dur, 50)

        val first = SleepStageHealer.restageFromSamples(start, end, grav, hr, emptyList(), emptyList())
        val second = SleepStageHealer.restageFromSamples(start, end, grav, hr, emptyList(), emptyList())
        assertNotNull(first)
        assertEquals("re-deriving over identical bounds+raw must be byte-identical (equality-skip)", first, second)
    }

    // ── End-to-end: drive selfHealEditedStages' exact loop over an in-memory store ───────────────────

    /**
     * Drives the REAL [SleepStageHealer.healLoop] over an in-memory store keyed by (deviceId, detected
     * startTs), standing in for the `userEdited = 1`-scoped `updateSleepStages` write. Returns
     * (refreshedRows, writeCount) so callers assert both the heal write and the idempotent skip.
     */
    private fun runHealLoop(
        store: MutableMap<Pair<String, Long>, SleepSession>,
        edited: List<SleepSession>,
        restage: (SleepSession) -> String?,
    ): Pair<List<SleepSession>, Int> {
        val writes = runBlocking {
            SleepStageHealer.healLoop(edited, { restage(it) }) { row, json ->
                val key = row.deviceId to row.startTs
                val stored = store[key] ?: return@healLoop 0
                store[key] = stored.copy(stagesJSON = json)
                1
            }
        }
        return store.values.filter { it.userEdited } to writes
    }

    @Test
    fun endToEndHealsThenIsIdempotent() {
        val start = startAtHour(5)
        val dur = 6 * 60 * 60
        val end = start + dur - 1
        val grav = stillGravity(start, dur)
        val hr = hrStream(start, dur, 50)

        // Stored edited night with FABRICATED stages (trailing-wake reclip) — userEdited, bounds locked.
        val fabricated = encoded(start, end, "wake")
        val edited = SleepSession(
            deviceId = "my-whoop-noop", startTs = start, endTs = end,
            stagesJSON = fabricated, userEdited = true,
        )
        val store = mutableMapOf((edited.deviceId to start) to edited)
        val restage: (SleepSession) -> String? = { row ->
            SleepStageHealer.restageFromSamples(row.effectiveStartTs, row.endTs, grav, hr, emptyList(), emptyList())
        }

        // Pass 1: heals (one write); bounds + userEdited preserved, only stagesJSON changes.
        val (afterFirst, w1) = runHealLoop(store, listOf(edited), restage)
        assertEquals("the edited-before-sync night must heal exactly once", 1, w1)
        val healed = afterFirst.single()
        assertEquals("bed bound must be untouched", start, healed.startTs)
        assertEquals("wake bound must be untouched", end, healed.endTs)
        assertTrue("userEdited must stay set", healed.userEdited)
        assertNotEquals("stages must have been replaced", fabricated, healed.stagesJSON)

        // Pass 2: re-derive equals the now-stored real stages → NO write (idempotent steady state).
        val (_, w2) = runHealLoop(store, afterFirst, restage)
        assertEquals("a second pass must be a no-op", 0, w2)
    }

    @Test
    fun endToEndNoRawLeavesEditUntouched() {
        val start = startAtHour(6)
        val end = start + 6 * 60 * 60 - 1
        val fabricated = encoded(start, end, "wake")
        val edited = SleepSession(
            deviceId = "my-whoop-noop", startTs = start, endTs = end,
            stagesJSON = fabricated, userEdited = true,
        )
        val store = mutableMapOf((edited.deviceId to start) to edited)
        // restage returns null (imported night: raw never dense).
        val (rows, writes) = runHealLoop(store, listOf(edited)) { null }
        assertEquals("a no-raw night must not be written", 0, writes)
        assertEquals("the user's edited (fabricated) stages must remain", fabricated, rows.single().stagesJSON)
    }

    // ── The OTHER half: a bed-only edit's END re-detects, a hand-set wake does not

    /**
     * Drives the REAL [SleepStageHealer.refreshLoop] over an in-memory store, whose write applies the
     * DAO's own scope (`userEdited = 1 AND endTsAdjusted IS NULL`) so the test pins the durable
     * predicate and not just the picker. Returns (rows, writeCount).
     */
    private fun runRefreshLoop(
        store: MutableMap<Pair<String, Long>, SleepSession>,
        edited: List<SleepSession>,
        detectedSpans: List<Pair<Long, Long>>,
    ): Pair<List<SleepSession>, Int> {
        val writes = runBlocking {
            SleepStageHealer.refreshLoop(edited, detectedSpans) { row, end ->
                val key = row.deviceId to row.startTs
                val stored = store[key] ?: return@refreshLoop 0
                if (!stored.userEdited || stored.endTsAdjusted != null) return@refreshLoop 0
                store[key] = stored.copy(endTs = end)
                1
            }
        }
        return store.values.toList() to writes
    }

    /**
     * THE defect: the bed picker passes the detected wake straight through and `userEdited` then
     * exempted the whole row, so an end computed before the raw finished offloading froze for good.
     * With the wake unset, fresh detection replaces it.
     */
    @Test
    fun bedOnlyEditLetsTheDetectedEndRefresh() {
        val detectedStart = startAtHour(8)
        val shortEnd = detectedStart + 3 * 60 * 60      // computed mid-offload
        val freshEnd = shortEnd + 59 * 60               // what a complete stream detects
        val edited = SleepSession(
            deviceId = "my-whoop-noop", startTs = detectedStart, endTs = shortEnd,
            userEdited = true, startTsAdjusted = detectedStart - 30 * 60, // bedtime moved earlier
            endTsAdjusted = null,                                          // the wake was NOT set
        )
        val store = mutableMapOf((edited.deviceId to detectedStart) to edited)

        val (rows, writes) = runRefreshLoop(store, listOf(edited), listOf(detectedStart to freshEnd))
        assertEquals("a bed-only edit's end must be refreshed", 1, writes)
        val healed = rows.single()
        assertEquals("the end must move to fresh detection's", freshEnd, healed.endTs)
        assertEquals("the effective end follows, since no wake was set", freshEnd, healed.effectiveEndTs)
        assertEquals("the hand-set bedtime must survive", detectedStart - 30 * 60, healed.startTsAdjusted)
        assertTrue("userEdited must stay set", healed.userEdited)
        assertNull("no wake was set, so none is banked", healed.endTsAdjusted)
    }

    /** The other side of the same bit: a wake the user picked is frozen and never moves. */
    @Test
    fun handSetWakeIsNeverRefreshed() {
        val detectedStart = startAtHour(9)
        val handSetEnd = detectedStart + 4 * 60 * 60
        val freshSpan = detectedStart to detectedStart + 7 * 60 * 60
        val edited = SleepSession(
            deviceId = "my-whoop-noop", startTs = detectedStart, endTs = detectedStart + 3 * 60 * 60,
            userEdited = true, endTsAdjusted = handSetEnd,
        )
        val store = mutableMapOf((edited.deviceId to detectedStart) to edited)

        val (rows, writes) = runRefreshLoop(store, listOf(edited), listOf(freshSpan))
        assertEquals("a hand-set wake must not be refreshed", 0, writes)
        assertEquals("the user's wake is what the app reads", handSetEnd, rows.single().effectiveEndTs)
        assertNull(
            "the picker itself must decline a frozen row",
            SleepStageHealer.refreshedEnd(edited, listOf(freshSpan)),
        )
    }

    /** Largest overlap wins, so a nap clipping a night's tail can't hand the night the nap's end. */
    @Test
    fun refreshTakesTheLargestOverlappingSpan() {
        val start = startAtHour(10)
        val night = start to start + 7 * 60 * 60
        val nap = start + 6 * 60 * 60 to start + 6 * 60 * 60 + 20 * 60
        val row = SleepSession(
            deviceId = "d", startTs = start, endTs = start + 6 * 60 * 60 + 30 * 60, userEdited = true,
        )
        assertEquals(
            "the night, not the nap, supplies the end",
            night.second, SleepStageHealer.refreshedEnd(row, listOf(nap, night)),
        )
    }

    @Test
    fun refreshDeclinesWhenNothingWouldMoveOrTheSpanIsWrong() {
        val start = startAtHour(11)
        val row = SleepSession(deviceId = "d", startTs = start, endTs = start + 5 * 60 * 60, userEdited = true)
        assertNull("no detection to hand", SleepStageHealer.refreshedEnd(row, emptyList()))
        assertNull(
            "no overlap",
            SleepStageHealer.refreshedEnd(row, listOf(start + 40 * 60 * 60 to start + 46 * 60 * 60)),
        )
        assertNull(
            "an identical end is not a change",
            SleepStageHealer.refreshedEnd(row, listOf(start to start + 5 * 60 * 60)),
        )
        // A refreshed end at/before the row's own onset would invert the window.
        val lateBed = row.copy(startTsAdjusted = start + 4 * 60 * 60)
        assertNull(
            "an end at/before the hand-set onset is refused",
            SleepStageHealer.refreshedEnd(lateBed, listOf(start to start + 3 * 60 * 60)),
        )
    }

    /**
     * The two halves in the order the pass runs them: the END first, so the stage re-derive covers the
     * refreshed window in the SAME pass rather than a stale one.
     */
    @Test
    fun endRefreshRunsBeforeTheStageRederiveSoStagesCoverTheNewWindow() {
        val start = startAtHour(12)
        val shortEnd = start + 3 * 60 * 60
        val freshEnd = start + 6 * 60 * 60
        val grav = stillGravity(start, 6 * 60 * 60)
        val hr = hrStream(start, 6 * 60 * 60, 50)
        val edited = SleepSession(
            deviceId = "my-whoop-noop", startTs = start, endTs = shortEnd,
            stagesJSON = encoded(start, shortEnd, "wake"), userEdited = true,
        )
        val store = mutableMapOf((edited.deviceId to start) to edited)

        val (afterRefresh, refreshWrites) = runRefreshLoop(store, listOf(edited), listOf(start to freshEnd))
        assertEquals(1, refreshWrites)
        val (afterHeal, healWrites) = runHealLoop(store, afterRefresh) { row ->
            SleepStageHealer.restageFromSamples(
                row.effectiveStartTs, row.effectiveEndTs, grav, hr, emptyList(), emptyList(),
            )
        }
        assertEquals("the refreshed night must then re-stage", 1, healWrites)
        val stagedEnd = lastStageEnd(afterHeal.single().stagesJSON)
        assertEquals("staging must reach the REFRESHED wake, not the short one", freshEnd, stagedEnd)
    }

    /** The largest `end` in a stagesJSON segment array — how far the breakdown actually reaches. */
    private fun lastStageEnd(stagesJSON: String?): Long {
        val arr = org.json.JSONArray(stagesJSON)
        var last = 0L
        for (i in 0 until arr.length()) last = maxOf(last, arr.getJSONObject(i).getLong("end"))
        return last
    }


    // ── 5. Edits survive a change of active strap id ─────────────────────────────────────────────────

    /**
     * An edit made before the active strap id changed lives under the OLD computed namespace. The heal
     * must write it back under its own `deviceId`, not a single passed-in id, or the correction is
     * stranded and the recompute re-creates the detected twin beside it.
     */
    @Test
    fun healWritesBackUnderEachRowsOwnComputedNamespace() {
        val legacyStart = startAtHour(7)
        val activeStart = startAtHour(20)
        val dur = 6 * 60 * 60
        val grav = stillGravity(legacyStart, dur) + stillGravity(activeStart, dur)
        val hr = hrStream(legacyStart, dur, 50) + hrStream(activeStart, dur, 50)

        val legacy = SleepSession(
            deviceId = "my-whoop-noop", startTs = legacyStart, endTs = legacyStart + dur - 1,
            stagesJSON = encoded(legacyStart, legacyStart + dur - 1, "wake"), userEdited = true,
        )
        val active = SleepSession(
            deviceId = "whoop-AA:BB-noop", startTs = activeStart, endTs = activeStart + dur - 1,
            stagesJSON = encoded(activeStart, activeStart + dur - 1, "wake"), userEdited = true,
        )
        val store = mutableMapOf((legacy.deviceId to legacyStart) to legacy, (active.deviceId to activeStart) to active)

        val (rows, writes) = runHealLoop(store, listOf(legacy, active)) { row ->
            SleepStageHealer.restageFromSamples(row.effectiveStartTs, row.endTs, grav, hr, emptyList(), emptyList())
        }
        assertEquals("both namespaces must heal", 2, writes)
        assertEquals(2, rows.size)
        for (row in rows) {
            assertNotEquals(
                "row under ${row.deviceId} must have been rewritten in its own namespace",
                encoded(row.startTs, row.endTs, "wake"),
                row.stagesJSON,
            )
        }
    }

    /**
     * The edit read is scoped to the computed UNION, the same sources the display reads, so a strap-id
     * install still sees an edit banked under the canonical legacy namespace.
     */
    @Test
    fun editedSleepsAreReadAcrossTheComputedUnion() {
        assertEquals(
            listOf("whoop-AA:BB-noop", "my-whoop-noop"),
            WhoopRepository.computedSourceIdsFor("whoop-AA:BB"),
        )
    }

    // ── 4. Encoder determinism (the linchpin: equality-skip relies on stable key order) ──────────────

    @Test
    fun encodeStagesEmitsSortedKeysDeterministically() {
        val segs = listOf(
            StageSegment(start = 100, end = 200, stage = "deep"),
            StageSegment(start = 200, end = 300, stage = "rem"),
        )
        val json = AnalyticsEngine.encodeStages(segs)!!
        // Keys alphabetical (end, stage, start).
        assertEquals(
            """[{"end":200,"stage":"deep","start":100},{"end":300,"stage":"rem","start":200}]""",
            json,
        )
    }

    @Test
    fun encodeStagesIsStableAcrossCalls() {
        val segs = listOf(StageSegment(start = 1, end = 2, stage = "light"))
        val a = AnalyticsEngine.encodeStages(segs)
        val b = AnalyticsEngine.encodeStages(segs.map { it.copy() })
        assertEquals("identical segments must encode byte-identically every call", a, b)
    }
}
