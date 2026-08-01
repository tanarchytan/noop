package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RESOLVER + SLEEP-BLOCK UNION (#1008, the #814 read-spine union joined to the two seams it missed):
 *
 * After a strap remove+re-add the Collector writes LIVE data under a fresh "whoop-<uuid>" id while the
 * WHOOP-export import + the engine's computed write target stay anchored on the CANONICAL
 * "my-whoop"/"my-whoop-noop". Two reads never joined the union model the rest of the repository uses:
 *
 *   1. [WhoopRepository.sourceCandidates] , the daily-metric resolver's strap-preferred candidate list
 *      was [strap, strap-noop, apple] with NO canonical fallback, so a caller threading the ACTIVE id
 *      orphaned the canonical history (and a caller passing the canonical default orphaned the live id).
 *      Fixed by appending the canonical pair, mirroring Swift Repository.sourceCandidates.
 *   2. [WhoopRepository.dedupSleepBlocks] , the union sleep reads ([WhoopRepository.sleepSessionsUnion] /
 *      habitualMidsleepSec) concatenate active + canonical blocks and must drop only EXACT-duplicate
 *      (startTs, endTs) twins, active copy surviving. Mirrors Swift Repository.dedupBlocks.
 *
 * These exercise the PURE companion seams only (no Room, plain JVM), complementing [ReadSpineUnionTest],
 * which covers the daily-metric union ids/merge. Stuck-sleep cluster: #1014 / #1009.
 */
class ResolverUnionTest {

    private val canonical = "my-whoop"
    private val reAdded = "whoop-ABC123" // the id a re-added strap gets (whoop-<uuid>)

    /** The read scope an install with [strap] active resolves to (registry-derived, active-first). */
    private fun scope(strap: String): List<String> = WhoopRepository.importedSourceIdsFor(
        if (strap == canonical) singleWhoopRegistry() else reAddedRegistry(strap),
    )

    /** Candidate SOURCE ids for a strap-preferred read ("my-whoop" preferred, as every UI caller passes). */
    private fun candidateSources(strap: String, key: String = "recovery") =
        WhoopRepository.sourceCandidates(key, canonical, scope(strap)).map { it.source }

    // --- sourceCandidates: the resolver's #814 union ---

    /** Single-WHOOP install: uniqued() collapses the active + canonical pairs to ONE pair, leaving the
     *  WHOOP pair plus the Health Connect gap-fill that closes every candidate list. */
    @Test
    fun singleDeviceCandidatesCollapseToCanonicalPair() {
        assertEquals(
            listOf("my-whoop", "my-whoop-noop", "health-connect"),
            candidateSources(strap = canonical),
        )
    }

    /** After a re-add the resolver tries the ACTIVE strap first (live/measured wins per day), then the
     *  CANONICAL "my-whoop" IMPORT, then the computed siblings — imports outrank computed estimates, so a
     *  fresh strap's computed rows no longer shadow richer imported my-whoop history (ryanbr/noop#241
     *  precedence fix). Before #1008 the canonical fallback was missing entirely. */
    @Test
    fun reAddCandidatesUnionActiveThenCanonical() {
        assertEquals(
            listOf(reAdded, "my-whoop", "$reAdded-noop", "my-whoop-noop", "health-connect"),
            candidateSources(strap = reAdded),
        )
    }

    /** A vital with a declared Apple-Health mapping appends the Apple candidate after the whole WHOOP
     *  union, so a real Apple export fills only days no WHOOP source covers, and Health Connect follows
     *  it as the lowest-precedence gap-fill. The Apple candidate carries the MAPPED key
     *  ("rhr" → "resting_hr"); Health Connect keeps the WHOOP key, since its vitals sit on a DailyMetric
     *  row read through dailyColumn. */
    @Test
    fun appleFallbackAppendsLastWithMappedKey() {
        val candidates = WhoopRepository.sourceCandidates("rhr", canonical, scope(reAdded))
        assertEquals(
            listOf(reAdded, "my-whoop", "$reAdded-noop", "my-whoop-noop", "apple-health", "health-connect"),
            candidates.map { it.source },
        )
        assertEquals("resting_hr", candidates[candidates.size - 2].key)
    }

    /** A derived score with NO Apple mapping never grows an Apple candidate , the resolver must not
     *  fabricate a cross-source fallback for a WHOOP-only composite. */
    @Test
    fun scoreWithNoAppleMappingGetsNoAppleCandidate() {
        assertNull(candidateSources(strap = reAdded, key = "recovery").find { it == "apple-health" })
    }

    /** The Apple-preferred and single-source paths are UNTOUCHED by the union fix: Apple still resolves
     *  [apple, health-connect] (+ the computed strap sibling only for the two totals the strap genuinely
     *  estimates), and any other source resolves itself only. */
    @Test
    fun applePreferredAndSingleSourcePathsUnchanged() {
        assertEquals(
            listOf("apple-health", "health-connect"),
            WhoopRepository.sourceCandidates("hrv", "apple-health", scope(reAdded)).map { it.source },
        )
        assertEquals(
            listOf("apple-health", "health-connect", "$reAdded-noop", "$canonical-noop"),
            WhoopRepository.sourceCandidates("steps", "apple-health", scope(reAdded)).map { it.source },
        )
        assertEquals(
            listOf("nutrition-csv"),
            WhoopRepository.sourceCandidates("calories_in", "nutrition-csv", scope(reAdded)).map { it.source },
        )
    }

    // --- dedupSleepBlocks: the sleep-block union's cross-source collapse ---

    private fun block(source: String, start: Long, end: Long) =
        SleepSession(deviceId = source, startTs = start, endTs = end)

    /** The same physical night recorded under TWO ids collapses to ONE block, and the earlier-listed
     *  (active-strap) copy survives , so the learner never double-weights a night and the live row wins. */
    @Test
    fun exactDuplicateNightKeepsActiveCopyOnly() {
        val night = 1_750_000_000L to 1_750_028_800L
        val deduped = WhoopRepository.dedupSleepBlocks(
            listOf(
                listOf(block(reAdded, night.first, night.second)), // active first, as the union orders
                listOf(block(canonical, night.first, night.second)),
            ),
        )
        assertEquals(1, deduped.size)
        assertEquals("the active-strap copy survives", reAdded, deduped[0].deviceId)
    }

    /** Genuinely DISTINCT blocks all survive , a nap and a main night on the same day are never
     *  collapsed (the union collapses cross-source copies only, the per-day merge stays the caller's). */
    @Test
    fun distinctBlocksSurviveNapPlusMainNight() {
        val deduped = WhoopRepository.dedupSleepBlocks(
            listOf(
                listOf(
                    block(reAdded, 1_750_000_000L, 1_750_028_800L), // main night
                    block(reAdded, 1_750_050_000L, 1_750_053_600L), // afternoon nap
                ),
                listOf(block(canonical, 1_750_090_000L, 1_750_118_800L)), // canonical-only older night
            ),
        )
        assertEquals(3, deduped.size)
    }

    /** Two straps stage one night at bounds MINUTES apart, so no exact key matches. The overlap rule
     *  collapses them to the active strap's copy — the exact-key rule left both and listed the night
     *  twice. */
    @Test
    fun overlappingCopiesOfOneNightUnderTwoIdsCollapse() {
        val deduped = WhoopRepository.dedupSleepBlocks(
            listOf(
                listOf(block(reAdded, 1_750_000_000L, 1_750_028_800L)),
                listOf(block(canonical, 1_750_000_346L, 1_750_030_000L)),
            ),
        )
        assertEquals(1, deduped.size)
        assertEquals(reAdded, deduped[0].deviceId)
    }

    /** Two blocks under the SAME id are never collapsed against each other, whatever they overlap: one
     *  source's own list is its own answer, so a single-source read is returned verbatim. */
    @Test
    fun sameSourceBlocksAreNeverCollapsed() {
        val one = listOf(
            block(reAdded, 1_750_000_000L, 1_750_028_800L),
            block(reAdded, 1_750_000_000L, 1_750_030_000L),
        )
        assertEquals(one, WhoopRepository.dedupSleepBlocks(listOf(one)))
    }

    // --- mergeComputedSeriesUnion: the computed metricSeries day-union (#349) ---

    private fun row(source: String, day: String, value: Double) =
        MetricSeriesRow(deviceId = source, day = day, key = "fitness_age", value = value)

    /** #349: the weekly computed scores (fitness_age / vitality / …) live under "<activeStrapId>-noop",
     *  so a live-BLE strap banks them under "whoop-<mac>-noop". The union read must surface them (the
     *  reported bug: a hardcoded "my-whoop-noop" read missed them → Fitness Age stuck "not ready"). The
     *  active strap wins per shared day; the canonical import fills days it doesn't cover; day-sorted.
     *  [perSource] is active-strap-first, exactly as computedSourceIds orders it. */
    @Test
    fun computedUnionActiveWinsPerDayAndCanonicalFillsGaps() {
        val merged = WhoopRepository.mergeComputedSeriesUnion(
            listOf(
                listOf(row("$reAdded-noop", "2026-07-11", 20.0), row("$reAdded-noop", "2026-07-04", 21.0)),
                listOf(row("my-whoop-noop", "2026-07-11", 40.0), row("my-whoop-noop", "2026-06-27", 42.0)),
            ),
        )
        assertEquals(listOf("2026-06-27", "2026-07-04", "2026-07-11"), merged.map { it.day })
        // The shared day: the ACTIVE strap's value + id win over the canonical import's.
        val shared = merged.first { it.day == "2026-07-11" }
        assertEquals(20.0, shared.value, 0.0)
        assertEquals("$reAdded-noop", shared.deviceId)
        // A canonical-only day still fills the gap.
        assertEquals(42.0, merged.first { it.day == "2026-06-27" }.value, 0.0)
    }

    /** A single-WHOOP install passes its one source list through (the instance method short-circuits to a
     *  single read); the merge just day-sorts it, byte-identical to the pre-fix single-source read. */
    @Test
    fun computedUnionSingleSourcePassesThroughSorted() {
        val merged = WhoopRepository.mergeComputedSeriesUnion(
            listOf(listOf(row("my-whoop-noop", "2026-07-04", 21.0), row("my-whoop-noop", "2026-07-11", 20.0))),
        )
        assertEquals(listOf(21.0, 20.0), merged.map { it.value })
    }

    // --- latestFromPerSourceLatest: the LIMIT-1 twin of the union's .lastOrNull() (perf) ---

    /** The latest-value pick must be BYTE-IDENTICAL to mergeComputedSeriesUnion(...).lastOrNull():
     *  strictly newest day wins across sources; a shared newest day keeps the ACTIVE strap's row
     *  (first in list order). Uses the same fixtures as the merge test so the equivalence is literal. */
    @Test
    fun latestPickMatchesFullMergeLastOrNull() {
        val perSourceFull = listOf(
            listOf(row("$reAdded-noop", "2026-07-11", 20.0), row("$reAdded-noop", "2026-07-04", 21.0)),
            listOf(row("my-whoop-noop", "2026-07-11", 40.0), row("my-whoop-noop", "2026-06-27", 42.0)),
        )
        val viaMerge = WhoopRepository.mergeComputedSeriesUnion(perSourceFull).lastOrNull()
        // Per-source LATEST rows, as the LIMIT-1 DAO read returns them (max day per source).
        val viaLatest = WhoopRepository.latestFromPerSourceLatest(
            listOf(row("$reAdded-noop", "2026-07-11", 20.0), row("my-whoop-noop", "2026-07-11", 40.0)),
        )
        assertEquals(viaMerge, viaLatest)
        assertEquals("$reAdded-noop", viaLatest?.deviceId)   // shared newest day → active wins
    }

    /** The canonical import's row wins when it is STRICTLY newer than the active strap's. */
    @Test
    fun latestPickNewerCanonicalBeatsOlderActive() {
        val picked = WhoopRepository.latestFromPerSourceLatest(
            listOf(row("$reAdded-noop", "2026-07-04", 21.0), row("my-whoop-noop", "2026-07-11", 40.0)),
        )
        assertEquals("my-whoop-noop", picked?.deviceId)
        assertEquals(40.0, picked!!.value, 0.0)
    }

    /** Null sources (no rows banked for that id) are skipped; all-null → null (no fabricated value). */
    @Test
    fun latestPickSkipsNullsAndReturnsNullWhenEmpty() {
        assertEquals(
            "$reAdded-noop",
            WhoopRepository.latestFromPerSourceLatest(
                listOf(null, row("$reAdded-noop", "2026-07-04", 21.0)),
            )?.deviceId,
        )
        assertNull(WhoopRepository.latestFromPerSourceLatest(listOf(null, null)))
    }
}
