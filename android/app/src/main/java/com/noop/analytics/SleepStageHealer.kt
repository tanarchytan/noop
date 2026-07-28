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
 * Fix: invoked from [IntelligenceEngine] after each sync backfill, before scoring — re-derives stages
 * from the raw over each edited night's LOCKED bounds (effective onset → wake) and rewrites the stage
 * breakdown ONLY, never the user's correction or the `userEdited` flag. Takes [WhoopRepository] as a
 * parameter because [SleepStager.stageSession] is internal to this package.
 *
 * Idempotent: a night already staged from raw re-derives to byte-identical JSON (skips the write); a
 * night edited-too-early heals once its raw is dense; a true imported night (never dense) is left
 * untouched ([restageFromRaw] returns null); only `userEdited = 1` rows are touched, bounds never move.
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
     * Self-heal pass: re-derive stages for every user-edited night in `[windowStart, windowEnd]` under
     * the COMPUTED source ([computedDeviceId]), rewriting the stage breakdown ONLY (via
     * [WhoopRepository.updateSleepStages], scoped to `userEdited = 1`), never the user's correction.
     * Reads/writes the SAME computed source [IntelligenceEngine] reads its edited rows from, so healed
     * stages flow into this run's daily aggregate. Same idempotency guarantee as the class doc above.
     * Returns the (possibly refreshed) edited rows for the caller to score.
     */
    suspend fun selfHealEditedStages(
        repo: WhoopRepository,
        computedDeviceId: String,
        strapDeviceId: String,
        windowStart: Long,
        windowEnd: Long,
    ): List<SleepSession> {
        suspend fun editedRows(): List<SleepSession> =
            repo.sleepSessions(computedDeviceId, windowStart, windowEnd).filter { it.userEdited }

        val edited = editedRows()
        if (edited.isEmpty()) return emptyList()
        var healed = false
        for (row in edited) {
            // Re-derive over the LOCKED corrected window (effective onset → wake), reading raw under the
            // STRAP id (where sensor streams live), not the computed namespace. Skip when not dense yet,
            // or when the result already matches what's stored (no write).
            val newJSON = restageFromRaw(repo, strapDeviceId, row.effectiveStartTs, row.endTs) ?: continue
            if (newJSON == row.stagesJSON) continue
            // Keyed by the IMMUTABLE detected startTs (never effectiveStartTs) so it lands on the right
            // primary-key row; the DAO scopes the write to userEdited = 1.
            val n = repo.updateSleepStages(computedDeviceId, row.startTs, newJSON)
            if (n > 0) healed = true
        }
        return if (healed) editedRows() else edited
    }
}
