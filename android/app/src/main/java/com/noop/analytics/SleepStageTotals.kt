package com.noop.analytics

import org.json.JSONArray
import org.json.JSONObject
import uniffi.whoop_ffi.MainNightBlock
import uniffi.whoop_ffi.MainNightScoredBlock
import uniffi.whoop_ffi.SleepHistoryBlock
import uniffi.whoop_ffi.bridgedNightGroups as ffiBridgedNightGroups
import uniffi.whoop_ffi.habitualMidsleepSec as ffiHabitualMidsleepSec
import uniffi.whoop_ffi.mainNightGroupIndices as ffiMainNightGroupIndices
import uniffi.whoop_ffi.mainNightGroupIndicesScored as ffiMainNightGroupIndicesScored
import uniffi.whoop_ffi.mainNightIndex as ffiMainNightIndex
import uniffi.whoop_ffi.mainNightIndexScored as ffiMainNightIndexScored
import uniffi.whoop_ffi.mainNightSelection as ffiMainNightSelection
import uniffi.whoop_ffi.mainNightSelectionScored as ffiMainNightSelectionScored

/**
 * Decode a sleep session's `stagesJSON` into stage MINUTE totals, and aggregate a night's blocks into
 * the sleep-derived daily fields. Pure and deterministic, so a bed/wake-time edit's recompute can run
 * off the stored (reshaped) stages with no raw streams needed.
 *
 * Handles two stagesJSON shapes: on-device COMPUTED (written via [AnalyticsEngine.encodeStages]) as
 * `[{start,end,stage}]` per-segment unix SECONDS spans, and IMPORTED (WhoopCsvImporter.stagesJson) as
 * `[{stage,min}]` per-stage MINUTE totals. Both "wake" and "awake" stage names map to `awake`.
 *
 * The edit/recompute path only ever feeds the COMPUTED (`-noop`) source's `[{start,end,stage}]` stages
 * here, but [minutes] handles both shapes so it is robust to either input.
 */
object SleepStageTotals {

    /** Stage minute totals for one session. `asleep` = light+deep+rem; `inBed` = asleep+awake. */
    data class Minutes(
        var awake: Double = 0.0,
        var light: Double = 0.0,
        var deep: Double = 0.0,
        var rem: Double = 0.0,
    ) {
        val asleep: Double get() = light + deep + rem
        val inBed: Double get() = asleep + awake
    }

    /**
     * The sleep-derived daily fields for a night, or null if nothing decodes. `efficiency` is
     * asleep / in-bed (TST / sum of stage minutes) in [0,1]. For the segment stages noop stores (which
     * tile the window), this coincides with SleepStager's TST/(end−start).
     */
    data class DailySleep(
        val totalSleepMin: Double,
        val efficiency: Double,
        val deepMin: Double,
        val remMin: Double,
        val lightMin: Double,
    )

    /**
     * Stage minutes for one session's `stagesJSON`, or null if it decodes to nothing usable.
     * Handles both shapes: `[{start,end,stage}]` (seconds spans) and `[{stage,min}]` (minute totals).
     */
    fun minutes(stagesJSON: String?): Minutes? {
        val json = stagesJSON ?: return null
        val arr = try {
            JSONArray(json)
        } catch (e: Throwable) {
            android.util.Log.w("SleepStages", "minutes: failed to parse stages JSONArray, returning null", e)
            // Object/dict shape {"awake":N,"light":N,"deep":N,"rem":N} of minute totals (imported
            // sessions), decoded here too so imported sleep isn't limited to segment-array shapes.
            val dict = try { JSONObject(json) } catch (e: Throwable) { android.util.Log.w("SleepStages", "minutes: failed JSONObject parse, returning null", e); return null }
            val md = Minutes()
            md.awake = dict.optDouble("awake", 0.0)
            md.light = dict.optDouble("light", 0.0)
            md.deep = dict.optDouble("deep", 0.0)
            md.rem = dict.optDouble("rem", 0.0)
            return if (md.inBed > 0.0) md else null
        }
        val m = Minutes()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONObject(i) ?: continue
            val name = seg.optString("stage", "")
            // Per-segment SECONDS span (computed/edited) → minutes; else a direct minute total (imported).
            val mins = when {
                seg.has("start") && seg.has("end") -> {
                    val s = seg.optLong("start")
                    val e = seg.optLong("end")
                    if (e > s) (e - s) / 60.0 else continue
                }
                seg.has("min") -> seg.optDouble("min", 0.0)
                else -> continue
            }
            if (mins <= 0.0) continue
            when (name) {
                "wake", "awake" -> m.awake += mins
                "light" -> m.light += mins
                "deep" -> m.deep += mins
                "rem" -> m.rem += mins
                else -> continue
            }
        }
        return if (m.inBed > 0.0) m else null
    }

    /**
     * Trims a computed `[{start,end,stage}]` stagesJSON so no segment begins before [onsetSec]:
     * segments fully before it are dropped, one straddling it is cut to `[onsetSec, end]`. The
     * `{stage,min}` / dict (imported) shape and unparseable JSON pass through UNCHANGED.
     */
    internal fun clampStagesToOnset(stagesJSON: String?, onsetSec: Long): String? {
        val json = stagesJSON ?: return null
        val arr = try { JSONArray(json) } catch (e: Throwable) { android.util.Log.w("SleepStages", "stage breakdown: failed JSONArray parse, skipping reclip", e); return stagesJSON }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONObject(i) ?: continue
            if (!seg.has("start") || !seg.has("end")) return stagesJSON   // {stage,min} shape → unchanged
            val e = seg.optLong("end")
            val s = maxOf(seg.optLong("start"), onsetSec)
            if (e <= s) continue                                          // fully before the onset → drop
            out.put(JSONObject().put("start", s).put("end", e).put("stage", seg.optString("stage", "")))
        }
        return out.toString()
    }

    // ── Canonical main-night selection (learned-timing scored pick) ──────────────────────────────────

    /** Broad overnight band used ONLY for the cold-start alignment bonus (NOT a gate). The band is
     *  [OVERNIGHT_START_HOUR, OVERNIGHT_END_HOUR) local, reconciled with the detector's
     *  `SleepStager.isOvernightOnset` window [20:00, 11:00) so the selector and detector agree. */
    const val OVERNIGHT_START_HOUR = 20

    /** Local hour (exclusive) that closes the cold-start overnight band, matching the detector's
     *  [20:00, 11:00) onset window. A block onset in [OVERNIGHT_END_HOUR, OVERNIGHT_START_HOUR)
     *  is daytime; everything else is overnight. */
    const val OVERNIGHT_END_HOUR = 11

    /** Seconds in a day, for circular time-of-day math. */
    const val SECONDS_PER_DAY = 86_400L

    /** Fixed alignment credit (MINUTES) added to a block's asleep minutes when its midpoint sits on the
     *  habitual midsleep (or, cold-start, the overnight band center). A BONUS, not a gate: a long enough
     *  off-timing block can still out-score a short well-timed one. ~90 min ≈ one sleep cycle. */
    const val ALIGNMENT_BONUS_MIN: Double = 90.0

    /** Full alignment bonus within this many seconds (circular) of the habitual midsleep; decays linearly
     *  to 0 at [ALIGNMENT_ZERO_SEC]. ±2h full, →0 by ±5h. */
    const val ALIGNMENT_FULL_WINDOW_SEC = 2 * 3_600L

    /** Circular distance (seconds) at/after which the alignment bonus is 0. */
    const val ALIGNMENT_ZERO_SEC = 5 * 3_600L

    /** One candidate block for main-night selection: its effective onset and end (unix seconds). A user
     *  wake/bed edit moves [end], never the detected onset key. */
    data class NightBlock(val start: Long, val end: Long) {
        val durationS: Long get() = end - start
        val midpointSec: Long get() = start + (end - start) / 2
    }

    /** One candidate scored on what its stages DECODED: the effective onset plus the asleep and in-bed
     *  seconds the hypnogram holds. A block whose stages do not decode reads its clock span for both, so
     *  the score falls back to duration rather than to zero. */
    data class ScoredNightBlock(val onset: Long, val asleepS: Double, val inBedS: Double)

    /** The scored reading of one block: its decoded asleep/in-bed seconds, or its clock span when
     *  [stagesJSON] decodes to nothing (an unstaged or stub block, where the span is all we know). */
    fun scoredBlock(onset: Long, end: Long, stagesJSON: String?): ScoredNightBlock {
        val m = minutes(stagesJSON)
        val span = (end - onset).coerceAtLeast(0L).toDouble()
        return if (m == null) ScoredNightBlock(onset, span, span)
        else ScoredNightBlock(onset, m.asleep * 60.0, m.inBed * 60.0)
    }

    /** True when a block's onset falls in the cold-start overnight band (>= [OVERNIGHT_START_HOUR] or
     *  < [OVERNIGHT_END_HOUR], local; kept in sync with `SleepStager.isOvernightOnset`). No longer a
     *  gate for the scored selector, only feeds the cold-start alignment bonus. [offsetSec] is seconds
     *  EAST of UTC. */
    fun isOvernightOnset(ts: Long, offsetSec: Long): Boolean {
        val local = ts + offsetSec
        val secOfDay = ((local % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY
        val hour = (secOfDay / 3_600L).toInt()
        return hour >= OVERNIGHT_START_HOUR || hour < OVERNIGHT_END_HOUR
    }

    /** Local time-of-day, in seconds [0, 86400), of a unix timestamp shifted east by [offsetSec]. */
    internal fun localSecOfDay(ts: Long, offsetSec: Long): Long {
        val local = ts + offsetSec
        return ((local % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY
    }

    /** Smallest circular distance (seconds, 0..43200) between two times-of-day, so 23:30 and 00:30 are
     *  3600s apart, not 82800. Both inputs are seconds-of-day in [0, 86400). */
    internal fun circularDistanceSec(a: Long, b: Long): Long {
        val raw = Math.abs(a - b) % SECONDS_PER_DAY
        return minOf(raw, SECONDS_PER_DAY - raw)
    }

    /** The cold-start anchor: the CENTER of the overnight band [OVERNIGHT_START_HOUR, OVERNIGHT_END_HOUR),
     *  as a time-of-day in seconds. The band wraps midnight (20:00 → 11:00 = 15h wide) so the center is
     *  03:30 local. */
    val coldStartAnchorSec: Long
        get() {
            val startSec = OVERNIGHT_START_HOUR * 3_600L
            val span = ((OVERNIGHT_END_HOUR - OVERNIGHT_START_HOUR) * 3_600L + SECONDS_PER_DAY) % SECONDS_PER_DAY
            return (startSec + span / 2) % SECONDS_PER_DAY
        }

    /** The alignment bonus (MINUTES) a block earns for sitting near the target midsleep. Full
     *  [ALIGNMENT_BONUS_MIN] within [ALIGNMENT_FULL_WINDOW_SEC], decaying linearly to 0 by
     *  [ALIGNMENT_ZERO_SEC]. [blockMidSec]/[targetMidSec] are local times-of-day in seconds. */
    internal fun alignmentBonusMinutes(blockMidSec: Long, targetMidSec: Long): Double {
        val d = circularDistanceSec(blockMidSec, targetMidSec)
        if (d <= ALIGNMENT_FULL_WINDOW_SEC) return ALIGNMENT_BONUS_MIN
        if (d >= ALIGNMENT_ZERO_SEC) return 0.0
        val frac = (ALIGNMENT_ZERO_SEC - d).toDouble() / (ALIGNMENT_ZERO_SEC - ALIGNMENT_FULL_WINDOW_SEC).toDouble()
        return ALIGNMENT_BONUS_MIN * frac
    }

    /** The target midsleep time-of-day (seconds) the scorer aligns to: the learned [habitualMidsleepSec]
     *  when supplied, else the cold-start overnight-band center. */
    internal fun targetMidsleepSec(habitualMidsleepSec: Long?): Long =
        habitualMidsleepSec ?: coldStartAnchorSec

    /** One bridged night group over the whole input: the fragments (as ORIGINAL indices, ascending) plus
     *  the group's inter-fragment wake seams (start, end) pairs. Produced by [bridgedNightGroups] for
     *  consumers (Health Connect export, Sleep screen) that must fold a briefly-interrupted night into one. */
    data class BridgedNightGroup(val indices: List<Int>, val gaps: List<Pair<Long, Long>>)

    /** EVERY bridged group over [blocks]: the same two-tier bridge [mainNightGroupIndices] applies
     *  (short-wake plus overnight night-tail widening), WITHOUT the winner pick. A negative gap (a block
     *  starting inside the previous span) never bridges (`gap >= 0` required); groups are ordered by start. */
    fun bridgedNightGroups(blocks: List<NightBlock>, offsetSec: Long): List<BridgedNightGroup> =
        ffiBridgedNightGroups(blocks.map { MainNightBlock(it.start, it.end) }, offsetSec)
            .map { g -> BridgedNightGroup(g.indices.map { it.toInt() }, g.gaps.map { it.start to it.end }) }

    /** The indices (into the ORIGINAL [blocks]) of the MAIN-NIGHT GROUP: the main night plus any adjacent
     *  fragments bridged into it, so a biphasic sleep split by a short wake gap scores as ONE night and the
     *  caller can SUM all its fragments' stages. Null only for an empty list. */
    fun mainNightGroupIndices(blocks: List<NightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): List<Int>? =
        ffiMainNightGroupIndices(blocks.map { MainNightBlock(it.start, it.end) }, offsetSec, habitualMidsleepSec)
            ?.map { it.toInt() }

    /** Index of the day's MAIN night: score(block) = asleepMinutes + alignmentBonus, crediting a midpoint
     *  near [habitualMidsleepSec] (or the overnight-band center, cold-start); no duration floor or overnight
     *  gate, ties break to the EARLIER onset, null only when [blocks] is empty; asleep = clock span here. */
    fun mainNightIndex(blocks: List<NightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): Int? =
        ffiMainNightIndex(blocks.map { MainNightBlock(it.start, it.end) }, offsetSec, habitualMidsleepSec)?.toInt()

    // ── The same pick, scored on DECODED stage time (one scorer, in whoop-rs) ─────────────────────────

    /** As [mainNightIndex] but scored on each block's DECODED asleep time instead of its clock span, so a
     *  candidate the stager filled with wake cannot out-score a shorter real night. */
    fun mainNightIndexScored(blocks: List<ScoredNightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): Int? =
        ffiMainNightIndexScored(blocks.map { MainNightScoredBlock(it.onset, it.asleepS, it.inBedS) }, offsetSec, habitualMidsleepSec)
            ?.toInt()

    /** As [mainNightGroupIndices] but scored on DECODED asleep time: candidates bridge on
     *  `[onset, onset + inBedS]` and a group scores as the SUM of its fragments, so a bridged gap adds
     *  nothing to either term. */
    fun mainNightGroupIndicesScored(blocks: List<ScoredNightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): List<Int>? =
        ffiMainNightGroupIndicesScored(blocks.map { MainNightScoredBlock(it.onset, it.asleepS, it.inBedS) }, offsetSec, habitualMidsleepSec)
            ?.map { it.toInt() }

    // ── Selection REASON (explainability — why THIS block won) ───────────────────────────────────────

    /** Why the main-night selector chose the block it chose, from the same signals the score used (asleep
     *  duration vs the alignment bonus), so the UI can explain the pick in plain English.
     *  - [onlyBlock] — the day had a single block, nothing to choose between.
     *  - [longest] — won on raw asleep duration (cold-start or no timing credit); the default reason.
     *  - [longestNearUsual] — longest by duration AND earned a meaningful alignment bonus (near the
     *    learned habitual).
     *  - [alignedToUsual] — the alignment bonus, not raw duration, flipped the pick: a shorter block near
     *    the usual sleep time out-scored the longest one. */
    enum class MainNightReason { onlyBlock, longest, longestNearUsual, alignedToUsual }

    /** The resolved main-night pick plus the truth needed to explain it: which block won ([index]), WHY it
     *  won ([reason]), and the chosen block's ASLEEP duration ([asleepSec]) so the UI can fill "Xh Ym". For
     *  the [NightBlock] overload "asleep" is the block's clock span, matching [mainNightIndex]'s scoring. */
    data class MainNightSelection(val index: Int, val reason: MainNightReason, val asleepSec: Long) {
        /** The chosen block's asleep duration in whole MINUTES (floored), for "Xh Ym" copy. */
        val asleepMin: Long get() = asleepSec / 60L
    }

    /** The day's MAIN night and why it won: like [mainNightIndex] (same score/tie-break/null-on-empty),
     *  plus [MainNightReason] checked in order: one block → onlyBlock; the bonus flipped the winner →
     *  alignedToUsual; winner is also longest and earns a bonus → longestNearUsual; else → longest. */
    fun mainNightSelection(blocks: List<NightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): MainNightSelection? {
        val sel = ffiMainNightSelection(
            blocks.map { MainNightBlock(it.start, it.end) }, offsetSec, habitualMidsleepSec,
        ) ?: return null
        return MainNightSelection(sel.index.toInt(), reasonOf(sel.reason), sel.asleepSec)
    }

    /** As [mainNightSelection] but scored on DECODED asleep time, so [MainNightSelection.asleepSec] is the
     *  winner's real sleep rather than its time in bed and "longest" ranks by that. */
    fun mainNightSelectionScored(blocks: List<ScoredNightBlock>, offsetSec: Long, habitualMidsleepSec: Long? = null): MainNightSelection? {
        val sel = ffiMainNightSelectionScored(
            blocks.map { MainNightScoredBlock(it.onset, it.asleepS, it.inBedS) }, offsetSec, habitualMidsleepSec,
        ) ?: return null
        return MainNightSelection(sel.index.toInt(), reasonOf(sel.reason), sel.asleepSec)
    }

    private fun reasonOf(r: uniffi.whoop_ffi.MainNightReason): MainNightReason = when (r) {
        uniffi.whoop_ffi.MainNightReason.ONLY_BLOCK -> MainNightReason.onlyBlock
        uniffi.whoop_ffi.MainNightReason.LONGEST -> MainNightReason.longest
        uniffi.whoop_ffi.MainNightReason.LONGEST_NEAR_USUAL -> MainNightReason.longestNearUsual
        uniffi.whoop_ffi.MainNightReason.ALIGNED_TO_USUAL -> MainNightReason.alignedToUsual
    }

    /** The night's daily sleep aggregate over these blocks' `stagesJSON`, or null if none decode. */
    fun dailyAggregate(stagesJSONs: List<String?>): DailySleep? =
        dailyAggregate(stagesJSONs, interFragmentAwakeSeconds = 0.0)

    /** As [dailyAggregate], but folds the out-of-bed time between bridged main-night fragments into the
     *  night's awake total, and so into its in-bed denominator. The caller sums those gaps once and
     *  passes them, so the rollup and the edit seam apply one definition. A value <= 0 sums stages alone. */
    fun dailyAggregate(stagesJSONs: List<String?>, interFragmentAwakeSeconds: Double): DailySleep? {
        val total = Minutes()
        var any = false
        for (j in stagesJSONs) {
            val mm = minutes(j) ?: continue
            total.awake += mm.awake
            total.light += mm.light
            total.deep += mm.deep
            total.rem += mm.rem
            any = true
        }
        if (interFragmentAwakeSeconds > 0.0) total.awake += interFragmentAwakeSeconds / 60.0
        if (!any || total.inBed <= 0.0) return null
        return DailySleep(
            totalSleepMin = total.asleep,
            efficiency = total.asleep / total.inBed,
            deepMin = total.deep,
            remMin = total.rem,
            lightMin = total.light,
        )
    }

    /** The out-of-bed time (seconds) between consecutive bridged sleep fragments: sorted by start, the gap
     *  after fragment i is `max(0, start[i+1] - end[i])`, summing only positive gaps. Both `analyzeDay` and
     *  the edit/recompute seam fold this into AWAKE, so the two paths agree (no double-count). */
    fun interFragmentAwakeSeconds(spans: List<Pair<Long, Long>>): Double {
        if (spans.size <= 1) return 0.0
        val sorted = spans.sortedBy { it.first }
        var gap = 0L
        for (i in 1 until sorted.size) {
            val g = sorted[i].first - sorted[i - 1].second
            if (g > 0L) gap += g
        }
        return gap.toDouble()
    }

    /** Result of [dailyAggregateHonoringEdits]: the aggregate plus whether an edit actually applied. */
    data class HonoredAggregate(val sleep: DailySleep, val editApplied: Boolean)

    /**
     * The night's daily sleep aggregate: substitutes any USER-EDITED block for its detected twin (matched
     * by the stable startTs, since a bed/wake edit never moves it) before summing, then unions in any
     * user-added [manual] block with no detected twin, de-duped by startTs so nothing double-counts.
     * An edit with non-null stages substitutes; one that reshaped to null falls back to the detected
     * stages rather than dropping the block, which would otherwise collapse the night's sleep total.
     * Returns the aggregate plus whether an edit or manual block actually contributed, or null if
     * nothing decodes. Pure; unit-tested with synthetic data, no store/stager.
     */
    fun dailyAggregateHonoringEdits(
        detected: List<Pair<Long, String?>>,
        edited: Map<Long, String?>,
        manual: List<Pair<Long, String?>> = emptyList(),
        // The block's effective onset (a wake/bed edit moves end, not the start key), keyed by startTs,
        // plus the device's UTC offset, so the main-night pick reads the user's local clock. When null,
        // falls back to the legacy sum-of-all-blocks behaviour.
        onsetByStart: Map<Long, Long>? = null,
        offsetSec: Long = 0L,
        // The learned habitual midsleep (local time-of-day seconds) so the scored pick aligns to the
        // user's real bedtime, not a fixed clock band. null = cold-start.
        habitualMidsleepSec: Long? = null,
    ): HonoredAggregate? {
        var applied = false
        // (startTs, effective stages) for every block on the day — detected (edit-substituted) then any
        // twinless manual block UNIONED in. Identity is preserved for the main-night selection.
        val blocks = detected.map { (startTs, detectedStages) ->
            // `edited[startTs]` is null both when the key is ABSENT and when it maps to NULL stages (an
            // edit that reshaped to nothing); in both cases we fall back to detected stages and do NOT
            // mark `applied`. Only a present, non-null edit substitutes.
            val editStages = edited[startTs]
            if (editStages != null) {
                applied = true
                startTs to editStages
            } else {
                startTs to detectedStages
            }
        }.toMutableList()
        // Union: a user-added block the detector never found (no detected twin) must still be on the day
        // so the main-night pick (or the plain sum) sees it, otherwise a manually-logged nap is dropped.
        // Match on the stable startTs and add ONLY rows absent from [detected], with usable stages.
        val detectedStarts = detected.map { it.first }.toHashSet()
        for ((startTs, manualStages) in manual) {
            if (startTs in detectedStarts) continue
            if (manualStages != null) {
                blocks.add(startTs to manualStages)
                applied = true
            }
        }
        // With block onsets supplied, the daily figure is the MAIN NIGHT only (the same block the Sleep
        // tab shows), so Intelligence / Sleep Need / the debt ledger / the card all read the same number.
        // Nap blocks stay their own rows and are NOT summed in; with no onsets, this sums all blocks.
        if (onsetByStart != null) {
            // BIPHASIC GAP-BRIDGE: bridge adjacent blocks split by a short wake gap into the main-night
            // GROUP and SUM that group's stages, so the edit/recompute seam reports the SAME night
            // `analyzeDay` does. Naps outside the group remain their own rows.
            val group = mainNightGroupIndicesByStages(blocks, onsetByStart, offsetSec, habitualMidsleepSec)
                ?: return null
            // Trim each SELECTED block's stages to its EFFECTIVE onset before summing: a hand-edited or
            // onset-trimmed bedtime the raw was too sparse to re-stage (WHOOP 4.0) leaves pre-onset segments
            // in stagesJSON, which would push asleep past time-in-bed (an impossible "asleep > in-bed" card).
            val clampedStages = group.map { i ->
                val onset = onsetByStart[blocks[i].first]
                if (onset != null) clampStagesToOnset(blocks[i].second, onset) else blocks[i].second
            }
            // OUT-OF-BED time between bridged fragments counts as AWAKE, using the SAME definition
            // `analyzeDay` applies so the seam can't double-count it. Each fragment's effective span is
            // `[onset, onset + decoded in-bed]`; the gap between fragments is awake time no stages cover.
            val spans = group.mapIndexed { gi, i ->
                val onset = onsetByStart[blocks[i].first] ?: blocks[i].first
                val inBedSec = ((minutes(clampedStages[gi])?.inBed ?: 0.0) * 60.0).toLong()
                onset to (onset + inBedSec)
            }
            val gapAwakeS = interFragmentAwakeSeconds(spans)
            val agg = dailyAggregate(clampedStages, gapAwakeS) ?: return null
            return HonoredAggregate(agg, applied)
        }
        val agg = dailyAggregate(blocks.map { it.second }) ?: return null
        return HonoredAggregate(agg, applied)
    }

    /** The candidates of the edit/recompute seam as scored blocks: each keyed by its detected `startTs`,
     *  read at its EFFECTIVE onset with the asleep and in-bed seconds its stages decode. */
    private fun scoredOf(blocks: List<Pair<Long, String?>>, onsetByStart: Map<Long, Long>): List<ScoredNightBlock> =
        blocks.map { b ->
            val onset = onsetByStart[b.first] ?: b.first
            val m = minutes(b.second)
            ScoredNightBlock(onset, (m?.asleep ?: 0.0) * 60.0, (m?.inBed ?: 0.0) * 60.0)
        }

    /** The original-index group (ascending) of the day's MAIN night on the STAGES path: the main night plus
     *  adjacent fragments [bridgedNightGroups] folds into it, so the edit/recompute seam sums the same
     *  fragments `analyzeDay` does. One scorer, in whoop-rs. Null only for an empty list. */
    internal fun mainNightGroupIndicesByStages(
        blocks: List<Pair<Long, String?>>,
        onsetByStart: Map<Long, Long>,
        offsetSec: Long,
        habitualMidsleepSec: Long? = null,
    ): List<Int>? =
        mainNightGroupIndicesScored(scoredOf(blocks, onsetByStart), offsetSec, habitualMidsleepSec)

    /** Index into [blocks] of the day's MAIN night on the STAGES path: the shared scorer over each block's
     *  decoded ASLEEP minutes, read at its effective onset. Undecoded blocks score 0 here (the caller
     *  supplies stages by construction); [scoredBlock] is the clock-fallback reading. */
    internal fun mainNightIndexByStages(
        blocks: List<Pair<Long, String?>>,
        onsetByStart: Map<Long, Long>,
        offsetSec: Long,
        habitualMidsleepSec: Long? = null,
    ): Int? = mainNightIndexScored(scoredOf(blocks, onsetByStart), offsetSec, habitualMidsleepSec)

    // ── Habitual midsleep (learned timing — non-circular dependency) ──────────────────────────────

    /** One detected sleep block from the trailing history, for learning the user's habitual timing.
     *  [start]/[end] are unix seconds; [dayKey] groups blocks by local calendar day so the LONGEST block
     *  per day can be picked selection-independently (no chicken-and-egg with main-night selection). */
    data class HistoryBlock(val start: Long, val end: Long, val dayKey: String) {
        val durationS: Long get() = end - start
        val midpointSec: Long get() = start + (end - start) / 2
    }

    /** Minimum number of DAYS (with at least one block) before a habitual midsleep is trusted; a shorter
     *  history returns null (cold-start). ~2 weeks. */
    const val HABITUAL_MIN_DAYS = 14

    /** The user's habitual midsleep as a LOCAL TIME-OF-DAY (seconds in [0, 86400)), or null when history is
     *  too short (cold-start): the CIRCULAR MEAN of the midpoint-time-of-day of the LONGEST block per local
     *  day. Longest-per-day is selection-independent, avoiding a circular dependency on main-night selection. */
    fun habitualMidsleepSec(
        history: List<HistoryBlock>,
        offsetSec: Long,
        minDays: Int = HABITUAL_MIN_DAYS,
    ): Long? = ffiHabitualMidsleepSec(
        history.map { SleepHistoryBlock(it.start, it.end, it.dayKey) }, offsetSec, minDays.toUInt(),
    )
}
