package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.SleepSession
import com.noop.data.StepSample
import com.noop.data.WhoopRepository
import kotlin.math.max

/**
 * Post-sync self-heal for the edit-races-sync bug: a user's hand-corrected wake/bed time (via
 * [WhoopRepository.updateSleepSessionTimes]) gets its stages reshaped by [SleepWindowReclip], which
 * FABRICATES a trailing "wake" block when no per-second staging exists yet, and stamps
 * `userEdited = true`. If the raw synced later, that fabricated breakdown was frozen forever.
 *
 * Fix: invoked from [IntelligenceEngine] after each sync backfill, before scoring, in two halves.
 * First the END: a night whose wake the user never set carries a DETECTED end
 * ([SleepSession.endTsAdjusted] null), so a fresh detection over the same night replaces it. Then the
 * STAGES: re-derived from the raw over the night's bounds, rewriting the breakdown ONLY. Neither half
 * touches the user's own onset, a wake the user DID set, or the `userEdited` flag. Takes
 * [WhoopRepository] as a parameter because [SleepStager.stageSession] is internal to this package.
 *
 * Idempotent: a night already staged from raw re-derives to byte-identical JSON (skips the write); a
 * night edited-too-early heals once its raw is dense; a true imported night (never dense) is left
 * untouched ([restageFromRaw] returns null); only `userEdited = 1` rows are touched.
 */
object SleepStageHealer {

    /**
     * Re-derive stages from the raw streams for `[start, end]` under strap [deviceId], returning the
     * encoded `stagesJSON`, or `null` when the window isn't densely covered — floor is ~1 sample / 2 min
     * (`max(20, windowSeconds / 120)`), so a few stray samples can't trigger a degenerate stage rewrite.
     * Reads over a ±1h padded window (`[start - 3600, end + 3600]`) for stager lead-in context, but the
     * density gate counts ONLY samples strictly within `[start, end]` — padding must not make a sparse
     * in-window night look dense.
     */
    suspend fun restageFromRaw(
        repo: WhoopRepository,
        deviceId: String,
        start: Long,
        end: Long,
    ): String? {
        val lo = start - 3_600L
        val hi = end + 3_600L
        val grav = repo.gravitySamples(deviceId, lo, hi, IntelligenceEngine.STREAM_LIMIT)
        // Cheap density gate FIRST (count only) so a sparse imported night skips the three further reads.
        if (!isDense(grav, start, end)) return null
        val hr = repo.hrSamples(deviceId, lo, hi, IntelligenceEngine.STREAM_LIMIT)
        val rr = repo.rrIntervals(deviceId, lo, hi, IntelligenceEngine.STREAM_LIMIT)
        // The whoop-rs stager always runs its motion-aware wake refinement, so the step stream is fed
        // unconditionally (a night with no steps is a no-op refinement either way).
        val steps = repo.stepSamples(deviceId, lo, hi, IntelligenceEngine.STREAM_LIMIT)
        return restageFromSamples(start, end, grav, hr, rr, steps)
    }

    /**
     * The density gate, in isolation: is the gravity stream dense enough over `[start, end]` to stage?
     * ~1 sample / 2 min, floored at 20 (`max(20, windowSeconds / 120)`). Counts ONLY samples strictly
     * within `[start, end]` (inclusive), so the ±1h read padding can't make a sparse in-window night
     * look dense. Pure — extracted so the heal-path test can assert the gate without a repository.
     */
    fun isDense(grav: List<GravitySample>, start: Long, end: Long): Boolean {
        val inWindowGravity = grav.count { it.ts in start..end }
        val windowSeconds = max(1L, end - start)
        return inWindowGravity >= max(20L, windowSeconds / 120L)
    }

    /**
     * Pure re-stage: gate on gravity density, then run the whoop-rs stager over the LOCKED
     * `[start, end]` bounds (via [RustSleepStager.stage] → V2 staging + motion-aware wake refinement)
     * and encode deterministically via [AnalyticsEngine.encodeStages]. Returns `null` when the raw
     * isn't dense (caller keeps the stored stages). No I/O — the test feeds raw sample lists directly.
     * `grav` is the SAME ±1h-padded read [restageFromRaw] fetched; the Rust stager clips each stream to
     * `[start, end]` itself, so passing the padded lists is correct.
     */
    fun restageFromSamples(
        start: Long,
        end: Long,
        grav: List<GravitySample>,
        hr: List<HrSample>,
        rr: List<RrInterval>,
        // Step stream for the motion-aware wake refinement. Default empty keeps every existing
        // positional caller/test byte-identical (a no-op refinement with no steps).
        steps: List<StepSample> = emptyList(),
    ): String? {
        if (!isDense(grav, start, end)) return null
        // Staging + motion-aware wake refinement both run in whoop-rs (V2 staging + motion-refine).
        val refined = RustSleepStager.stage(start, end, grav, hr, rr, steps)
        return AnalyticsEngine.encodeStages(refined)
    }

    /**
     * The heal loop with its I/O injected, returning the number of rows written. [restage] re-derives a
     * row's stages (null = raw not dense yet); [write] persists them under the row's OWN `deviceId`,
     * keyed by the IMMUTABLE detected `startTs`, and returns rows changed. Extracted so a test drives
     * this loop rather than a copy of it.
     */
    internal suspend fun healLoop(
        edited: List<SleepSession>,
        restage: suspend (SleepSession) -> String?,
        write: suspend (SleepSession, String) -> Int,
    ): Int {
        var writes = 0
        for (row in edited) {
            val newJSON = restage(row) ?: continue
            if (newJSON == row.stagesJSON) continue
            if (write(row, newJSON) > 0) writes++
        }
        return writes
    }

    /**
     * The detected end [detectedSpans] gives this row's night, or null when nothing should move: the
     * user set this wake ([SleepSession.endTsAdjusted]), no span overlaps the row, the end would not
     * move, or the end would land at/before the row's own onset. Picks the LARGEST overlap, since a
     * re-detected onset drifts and a nap can clip a night's tail.
     */
    fun refreshedEnd(row: SleepSession, detectedSpans: List<Pair<Long, Long>>): Long? {
        if (row.endTsAdjusted != null) return null
        val start = row.effectiveStartTs
        val end = row.effectiveEndTs
        val best = detectedSpans
            .map { (s, e) -> (minOf(end, e) - maxOf(start, s)) to e }
            .filter { it.first > 0L }
            .maxByOrNull { it.first }
            ?: return null
        val fresh = best.second
        return if (fresh != row.endTs && fresh > start) fresh else null
    }

    /**
     * The end-refresh loop with its I/O injected, returning the number of rows written. [write] persists
     * the fresh end under the row's OWN `deviceId`, keyed by the IMMUTABLE detected `startTs`, and
     * returns rows changed. Extracted so a test drives this loop rather than a copy of it.
     */
    internal suspend fun refreshLoop(
        edited: List<SleepSession>,
        detectedSpans: List<Pair<Long, Long>>,
        write: suspend (SleepSession, Long) -> Int,
    ): Int {
        var writes = 0
        for (row in edited) {
            val fresh = refreshedEnd(row, detectedSpans) ?: continue
            if (write(row, fresh) > 0) writes++
        }
        return writes
    }

    /**
     * Self-heal pass: for every user-edited night in `[windowStart, windowEnd]` across the computed union
     * ([WhoopRepository.editedSleeps]), refresh a still-DETECTED end from [detectedSpans] (via
     * [WhoopRepository.refreshSleepEnd]) and then rewrite the stage breakdown (via
     * [WhoopRepository.updateSleepStages]), both scoped to `userEdited = 1` and neither touching the
     * user's own correction. Same idempotency guarantee as the class doc above. Returns the (possibly
     * refreshed) edited rows for the caller to score, so an edit under a retired computed id still
     * reaches the recompute guard.
     */
    suspend fun selfHealEditedStages(
        repo: WhoopRepository,
        strapDeviceId: String,
        windowStart: Long,
        windowEnd: Long,
        // This pass's freshly detected in-bed spans. Empty leaves every end where it is, so a caller
        // with no detection to hand degrades to the stages-only heal.
        detectedSpans: List<Pair<Long, Long>> = emptyList(),
    ): List<SleepSession> {
        suspend fun editedRows(): List<SleepSession> = repo.editedSleeps(windowStart, windowEnd)

        var edited = editedRows()
        if (edited.isEmpty()) return emptyList()
        // The END first, so the stage re-derive below runs over the refreshed bounds in the SAME pass.
        val refreshed = refreshLoop(
            edited,
            detectedSpans,
            { row, end -> repo.refreshSleepEnd(row.deviceId, row.startTs, end) },
        )
        if (refreshed > 0) edited = editedRows()
        // Re-derive over the night's window (effective onset → wake), reading raw under the STRAP id
        // (where sensor streams live), not the computed namespace. The write goes back to the row's own
        // deviceId, which the union read may have sourced from a retired namespace.
        val healed = healLoop(
            edited,
            { row -> restageFromRaw(repo, strapDeviceId, row.effectiveStartTs, row.effectiveEndTs) },
            { row, json -> repo.updateSleepStages(row.deviceId, row.startTs, json) },
        )
        return if (healed > 0) editedRows() else edited
    }
}
