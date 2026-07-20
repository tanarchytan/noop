package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.SleepDebt
import com.noop.analytics.SleepDebtLedger
import com.noop.analytics.SleepStageTotals
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Model + derivation

/** Stage minutes for a single night. */
internal data class Stages(
    val awake: Double,
    val light: Double,
    val deep: Double,
    val rem: Double,
) {
    /** Total time in bed (includes awake). */
    val total: Double get() = awake + light + deep + rem

    /** Asleep time = total minus awake. */
    val asleep: Double get() = light + deep + rem
}

/** (latest, typical mean, full history) per metric. */
internal data class Metric(
    val latest: Double?,
    val typical: Double?,
    val series: List<Double>,
)

/** Export-verbatim per-day sleep figures. */
internal data class ImportedSleepSeries(
    val performance: Map<String, Double> = emptyMap(), // sleep_performance, 0–100
    val consistency: Map<String, Double> = emptyMap(), // sleep_consistency, 0–100
    val needMin: Map<String, Double> = emptyMap(),     // sleep_need_min, minutes
    val debtMin: Map<String, Double> = emptyMap(),     // sleep_debt_min, minutes
)

/** Everything the screen renders, derived once per data change. */
internal data class SleepModel(
    val stages: Stages,
    val clockLabel: String,
    val efficiencyText: String,
    val performance: Metric,
    val efficiency: Metric,
    val consistency: Metric,
    val hoursVsNeeded: Metric,
    val restorative: Metric,
    val respiratory: Metric,
    val sleepDebt: Metric,
    val typicalTotalMin: Double?,
    val typicalDeepMin: Double?,
    val typicalRemMin: Double?,
    val typicalLightMin: Double?,
    val trendHours: List<Double>,
    val trendNeedHours: List<Double>,
    val trendDebtHours: List<Double>,
    val trendDates: List<String>,
    /** Persisted per-epoch segments as ordered (stage, minutes) weights — the REAL hypnogram (on-device
     *  approximate staging) — or null → synthesized fallback. */
    val realSegments: List<Pair<String, Float>>?,
    /** Rolling 14-night sleep-debt ledger: Σ(slept − personal need) across the recent fortnight, with the
     *  per-night deltas behind it. */
    val sleepDebtLedger: SleepDebtLedger,
)

/** The night the ◀/▶ chevrons selected: its MAIN session, the day-metric key it resolves to, its persisted
 *  per-epoch weights (or null), the "EEE d MMM · HH:mm–HH:mm" clock, and the day's other blocks (naps /
 *  split-sleep) for the naps card. */
internal data class HeroNight(
    val session: SleepSession,
    val dayKey: String,
    val realSegments: List<Pair<String, Float>>?,
    val clockLabel: String,
    val napBlocks: List<SleepSession> = emptyList(),
    // The bridged main-night GROUP: summed stage minutes + full-night segments, when the night is more than
    // one fragment. `session` stays the single WINNING block (the edit anchor); these let buildSleepModel
    // render the WHOLE night, not one fragment. Null for a single-block day.
    val groupStages: StageMins? = null,
    val groupSegments: List<PersistedSegment>? = null,
    // Per-epoch MOTION for the main-night GROUP, laid fragment-by-fragment in the same order as the group's
    // stage segments. Empty when no group fragment has a persisted motionJSON → honest empty state. Read off
    // the already-resolved group.
    val groupMotion: List<Double> = emptyList(),
    // Time-in-bed for the whole main-night GROUP: Σ(endTs − effectiveStartTs) across the hero fragments, in
    // minutes. Summing fragment windows (NOT wall-clock first-onset→last-wake) excludes the inter-fragment
    // awake gaps, so asleep ≤ in-bed and the efficiency beside it stays coherent. Null for a single-block day.
    val groupInBedMin: Double? = null,
    // The whole bridged night's clock WINDOW: the displayed bedtime (first non-stub fragment's onset) to the
    // group's latest wake, carried as timestamps so the Asleep/Woke row + hypnogram axis can use them. On a
    // split night `session` (the edit anchor) can end mid-night, so reading ITS endTs contradicted the pill.
    val heroOnsetTs: Long? = null,
    val heroWakeTs: Long? = null,
)

/** What the hero card draws for the selected night — null means no usable stage data (renders the honest
 *  "No stage data recorded for this night." fallback). */
internal data class HeroDisplay(
    val stages: Stages,
    val realSegments: List<Pair<String, Float>>?,
    val efficiencyText: String,
)

/**
 * Pick the night for the DAY [offset] stops back from the most recent (0 = latest). [navDays] is
 * grouped-by-calendar-day, newest first, so the chevrons step by DAY not by flat session index — a single
 * detected night is exactly one stop, not arrows that appear stuck moving within one night's blocks.
 *
 * The day's REPRESENTATIVE session is its MAIN sleep block (the mainNightIndex pick); the other blocks are
 * carried as `napBlocks`. The day key tries UTC then local-tz attribution of the MAIN block's wake — imported
 * DailyMetric.day is local-tz while dayString is UTC; both derive from THIS night's endTs, never another.
 */
internal fun selectNight(
    navDays: List<List<SleepSession>>,
    days: List<DailyMetric>,
    offset: Int,
    // The LEARNED habitual midsleep, so the hero, the naps split, and the edit target pick the same block the
    // analytics rollup did. null = cold-start band.
    habitualMidsleepSec: Long? = null,
    // Per-epoch MOTION keyed by detected startTs; the group's fragments' series are concatenated in group
    // order onto HeroNight.groupMotion. Empty/absent → honest empty state. Default empty so existing callers
    // compile unchanged.
    motionByStart: Map<Long, List<Double>> = emptyMap(),
): HeroNight? {
    if (navDays.isEmpty()) return null
    val dayIdx = offset.coerceIn(0, navDays.size - 1)
    val blocks = navDays[dayIdx]
    val session = mainSleepBlock(blocks, habitualMidsleepSec) ?: return null
    // The day's MAIN sleep is the bridged main-night GROUP: a biphasic night's sibling fragments belong to
    // the night, NOT the naps card — only blocks OUTSIDE the group are naps. `session` stays the WINNING
    // block (the edit anchor), but the group drives the naps split, summed stage minutes and full-night hypnogram.
    val group = mainSleepGroup(blocks, habitualMidsleepSec)
    val groupStarts = group.map { it.startTs }.toHashSet()
    val napBlocks = blocks.filter { it.startTs !in groupStarts }
        .sortedBy { it.effectiveStartTs }
    // Drop a spurious leading pre-sleep awake stub from the hero's RECONSTRUCTION so the hypnogram and summed
    // minutes start at the displayed bedtime (the main block's onset). Only a BRIEF, essentially-sleepless
    // fragment before the main block is dropped; it still rides in `groupStarts`, so it's never a nap.
    val onsetTsForHero = session.effectiveStartTs
    // Reference size for the "minor relative to the main block" stub test = the largest asleep span in the
    // group. A genuine biphasic first sleep is comparable and kept; only a small stray lead is dropped.
    val groupRefAsleepMin = group.maxOfOrNull { frag ->
        decodedAsleepMinutes(frag.stagesJSON, frag.effectiveStartTs)
    } ?: 0.0
    val heroGroup = group.dropWhile {
        it.effectiveStartTs < onsetTsForHero && isPreOnsetAwakeStub(it, groupRefAsleepMin)
    }
    val utcKey = AnalyticsEngine.dayString(session.endTs)
    val localKey = localDayString(session.endTs)
    val dayKey = listOf(utcKey, localKey).firstOrNull { key ->
        days.any { it.day == key && (it.deepMin ?: 0.0) + (it.remMin ?: 0.0) + (it.lightMin ?: 0.0) > 0.0 }
    } ?: utcKey
    // Lay every fragment's persisted segments end-to-end so a biphasic night draws as one continuous
    // hypnogram, and SUM their stage minutes. Built from `heroGroup` (group minus a leading stub) and clamped
    // to each fragment's effective onset, so an edited bedtime shows no pre-onset bars. Null for a single block.
    val groupSegmentsRaw = if (heroGroup.size > 1) {
        heroGroup.flatMap { parsePersistedSegments(SleepStageTotals.clampStagesToOnset(it.stagesJSON, it.effectiveStartTs)).orEmpty() }
            .sortedBy { it.start }
            .takeIf { it.size >= 2 }
    } else null
    // Draw each inter-fragment wake seam as an explicit wake segment in the full-night hypnogram, so the
    // merged night has no silent hole where the user was up. TIMELINE only: the seam is NOT added to the stage
    // MINUTES (sumGroupStages) or groupInBedMin — those keep the fragment-only accounting (asleep ≤ in-bed).
    val groupSegments = groupSegmentsRaw?.let { segs ->
        val seams = heroGroup.zipWithNext().mapNotNull { (prev, next) ->
            if (next.effectiveStartTs > prev.endTs)
                PersistedSegment(prev.endTs, next.effectiveStartTs, "wake") else null
        }
        (segs + seams).sortedBy { it.start }
    }
    val groupStages = if (heroGroup.size > 1) sumGroupStages(heroGroup) else null
    val segments = (groupSegmentsRaw ?: parsePersistedSegments(SleepStageTotals.clampStagesToOnset(session.stagesJSON, session.effectiveStartTs)))
        ?.map { seg -> seg.stage to ((seg.end - seg.start) / 60f) }
    // Lay the GROUP's per-epoch motion fragment-by-fragment in `heroGroup` order (the same order
    // `groupSegments` lays the stage timeline). The detected `startTs` is the motion store's key; no fragment
    // with a series → empty `groupMotion` → honest empty state.
    val groupMotion = heroGroup.flatMap { motionByStart[it.startTs].orEmpty() }
    // The displayed bedtime must match where the hypnogram starts. The chart is built from heroGroup (first
    // non-stub fragment onward), so label from THAT fragment's onset, closed by the group's latest wake.
    // `session` stays the edit anchor only.
    val heroOnsetTs = heroGroup.firstOrNull()?.effectiveStartTs ?: session.effectiveStartTs
    val heroWakeTs = heroGroup.maxOfOrNull { it.endTs } ?: session.endTs
    // Whole-group time-in-bed (minutes) — fragment windows summed, gaps excluded — so the subtitle matches
    // the multi-fragment stage total beside it. Single-block days stay null.
    val groupInBedMin = if (heroGroup.size > 1) {
        heroGroup.sumOf { (it.endTs - it.effectiveStartTs).coerceAtLeast(0L) } / 60.0
    } else null
    return HeroNight(session, dayKey, segments, clockLabelFor(heroOnsetTs, heroWakeTs), napBlocks, groupStages,
        groupSegments, groupMotion, groupInBedMin, heroOnsetTs, heroWakeTs)
}

/**
 * The day's MAIN sleep block — the night people mean by "last night" — resolved by the single shared selector
 * [SleepStageTotals.mainNightIndex] the analytics rollup uses, so hero, edit affordance, analytics total and
 * the Sleep tab all resolve to the identical block. The HERO/nap split use [mainSleepGroup] to bridge fragments.
 */
internal fun mainSleepBlock(blocks: List<SleepSession>, habitualMidsleepSec: Long? = null): SleepSession? {
    if (blocks.isEmpty()) return null
    val idx = SleepStageTotals.mainNightIndex(
        blocks.map { SleepStageTotals.NightBlock(it.effectiveStartTs, it.endTs) },
        uiTzOffsetSec(),
        habitualMidsleepSec,
    ) ?: return null
    return blocks[idx]
}

/**
 * The day's MAIN-night GROUP — the winning block PLUS any adjacent fragments bridged into it (a wake gap
 * shorter than [SleepStageTotals.gapBridgeMaxMin]), so a biphasic night reads as ONE continuous sleep the way
 * AnalyticsEngine rolls it up. Only blocks outside the group are naps. Returns ascending by effective onset.
 */
internal fun mainSleepGroup(blocks: List<SleepSession>, habitualMidsleepSec: Long? = null): List<SleepSession> {
    val idx = SleepStageTotals.mainNightGroupIndices(
        blocks.map { SleepStageTotals.NightBlock(it.effectiveStartTs, it.endTs) },
        uiTzOffsetSec(),
        habitualMidsleepSec,
    ) ?: return emptyList()
    return idx.map { blocks[it] }.sortedBy { it.effectiveStartTs }
}

/**
 * The day's main-night bridged SPAN (onset → wake), the same window [mainSleepGroup] bridges into one
 * continuous night. The one canonical bed/wake every glance screen should show — never a screen-local
 * "freshest" or "longest single block" heuristic, which can disagree with the Sleep tab hero on a multi-block night.
 */
internal fun mainSleepSpan(blocks: List<SleepSession>, habitualMidsleepSec: Long? = null): Pair<Long, Long>? {
    val group = mainSleepGroup(blocks, habitualMidsleepSec)
    val first = group.firstOrNull() ?: return null
    val last = group.lastOrNull() ?: return null
    return first.effectiveStartTs to last.endTs
}

/** Longest a leading block can be and still count as a spurious pre-sleep awake stub. Generous (a few hours)
 *  because a real stub can run ~2h45m of pre-sleep awake. The real guard against swallowing a genuine first
 *  sleep fragment is [PRE_ONSET_STUB_ASLEEP_MAX_MIN]: a stub must be essentially SLEEPLESS. */
private const val PRE_ONSET_STUB_MAX_MIN = 240.0
/** Most asleep minutes a fragment can carry and still count as a (sleepless) pre-onset awake stub. A real
 *  first sleep fragment of a biphasic night carries far more. */
private const val PRE_ONSET_STUB_ASLEEP_MAX_MIN = 3.0
/** A leading pre-onset fragment that carries SOME sleep is still spurious when it is minor RELATIVE to the
 *  night's main block: its asleep minutes are below this fraction of the largest fragment's. A genuine
 *  biphasic first sleep is comparable in size, so it is never dropped; only a small stray lead is. */
private const val PRE_ONSET_STUB_MINOR_FRAC = 0.15

/** Absolute floor (ASLEEP minutes) under the relative "minor lead" test: a leading fragment carrying at least
 *  this much real sleep is a genuine first sleep and is NEVER a spurious lead, however large the main block is.
 *  Without it a long main sleep inflates the 15% relative bar and swallows a genuine short first sleep. ~20 min. */
internal const val PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN = 20.0

/**
 * Asleep minutes decoded from a stored [stagesJSON] in either DB format (computed nights store a SEGMENT
 * ARRAY `[{start,end,stage}]`; imported nights store a MINUTES dict). Threads [effectiveStartTs] through
 * [SleepStageTotals.clampStagesToOnset] so a segment array is trimmed to the effective onset like the stage totals.
 */
internal fun decodedAsleepMinutes(stagesJSON: String?, effectiveStartTs: Long): Double =
    parseSessionStages(SleepStageTotals.clampStagesToOnset(stagesJSON, effectiveStartTs))
        ?.let { it.light + it.deep + it.rem } ?: 0.0

/** A fragment is a spurious pre-onset awake stub when it is within the lie-in cap (≤ [PRE_ONSET_STUB_MAX_MIN])
 *  and EITHER carries essentially no sleep (≤ [PRE_ONSET_STUB_ASLEEP_MAX_MIN]) OR is minor relative to the main
 *  block ([refAsleepMin]). Used only to skip such a stub when it leads the main-night group. [refAsleepMin]
 *  defaults to 0 (relative test off) so existing callers are byte-identical. */
internal fun isPreOnsetAwakeStub(frag: SleepSession, refAsleepMin: Double = 0.0): Boolean {
    val spanMin = (frag.endTs - frag.effectiveStartTs) / 60.0
    if (spanMin > PRE_ONSET_STUB_MAX_MIN) return false
    val asleepMin = decodedAsleepMinutes(frag.stagesJSON, frag.effectiveStartTs)
    if (asleepMin <= PRE_ONSET_STUB_ASLEEP_MAX_MIN) return true
    // Relative "minor lead" test, floored: a real sleep episode (≥ the floor) is never a stray lead, so a long
    // main block can't inflate the bar past a genuine short first sleep.
    return refAsleepMin > 0.0 &&
        asleepMin < PRE_ONSET_STUB_MINOR_FRAC * refAsleepMin &&
        asleepMin < PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN
}

/** SUM the per-stage minutes across a bridged main-night group, so the hero's stage breakdown reflects the
 *  WHOLE night instead of one fragment. The inter-fragment wake gap belongs to no fragment, so it is excluded
 *  like AnalyticsEngine excludes it. Null if no fragment has parseable stages. */
private fun sumGroupStages(group: List<SleepSession>): StageMins? {
    var aw = 0.0; var li = 0.0; var dp = 0.0; var rm = 0.0; var any = false
    for (frag in group) {
        // Each fragment's stages trimmed to its effective onset before summing.
        val s = parseSessionStages(SleepStageTotals.clampStagesToOnset(frag.stagesJSON, frag.effectiveStartTs)) ?: continue
        aw += s.awake; li += s.light; dp += s.deep; rm += s.rem; any = true
    }
    return if (any) StageMins(aw, li, dp, rm) else null
}

/** The device's current UTC offset (seconds east), fed to the selector's `offsetSec` so the timing test reads
 *  the user's clock via the same `offsetSec` math the engine uses ([SleepStageTotals.localSecOfDay]) rather
 *  than a DST-fragile Calendar.get(HOUR_OF_DAY) gate. */
internal fun uiTzOffsetSec(): Long =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L

/**
 * Resolve what the hero shows: the day-metric model when it resolved for the selected night; else the
 * session's own persisted segments (the day row can miss while the segments exist); else null → the honest
 * fallback. Never another night's data.
 */
internal fun heroDisplay(model: SleepModel?, night: HeroNight?): HeroDisplay? {
    if (model != null) return HeroDisplay(model.stages, model.realSegments, model.efficiencyText)
    val segments = night?.realSegments ?: return null
    val stages = stagesFromSegments(segments) ?: return null
    val eff = night.session.efficiency
        ?.let { e -> "${(if (e <= 1.0) e * 100.0 else e).roundToInt()}%" } ?: "—"
    return HeroDisplay(stages, segments, eff)
}

/** Sum (stage, minutes) weights into per-stage totals; null when nothing is > 0. */
internal fun stagesFromSegments(segments: List<Pair<String, Float>>): Stages? {
    var awake = 0.0; var light = 0.0; var deep = 0.0; var rem = 0.0
    for ((stage, minutes) in segments) {
        val m = minutes.toDouble()
        when (stage) {
            "wake", "awake" -> awake += m
            "light" -> light += m
            "deep" -> deep += m
            "rem" -> rem += m
        }
    }
    val s = Stages(awake = awake, light = light, deep = deep, rem = rem)
    return if (s.total > 0.0) s else null
}

internal data class StageMins(val awake: Double, val light: Double, val deep: Double, val rem: Double)

/**
 * Extract stage minute counts from a session's stagesJSON, handling both formats:
 *  • Minute dict  {"awake":…,"light":…,"deep":…,"rem":…}  — imported nights
 *  • Segment array [{start,end,stage}]                     — on-device computed nights
 * Returns null when the JSON is absent or unparseable, so callers fall back to DailyMetric columns.
 */
private fun parseSessionStages(stagesJSON: String?): StageMins? {
    stagesJSON ?: return null
    return runCatching {
        val trimmed = stagesJSON.trim()
        when {
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val aw = obj.optDouble("awake", 0.0)
                val li = obj.optDouble("light", 0.0)
                val dp = obj.optDouble("deep", 0.0)
                val rm = obj.optDouble("rem", 0.0)
                if (aw + li + dp + rm > 0.0) StageMins(aw, li, dp, rm) else null
            }
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                var aw = 0.0; var li = 0.0; var dp = 0.0; var rm = 0.0
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONObject(i) ?: continue
                    val start = seg.optLong("start", -1)
                    val end = seg.optLong("end", -1)
                    if (end <= start) continue
                    val durMin = (end - start) / 60.0
                    when (seg.optString("stage")) {
                        "wake"  -> aw += durMin
                        "light" -> li += durMin
                        "deep"  -> dp += durMin
                        "rem"   -> rm += durMin
                    }
                }
                if (aw + li + dp + rm > 0.0) StageMins(aw, li, dp, rm) else null
            }
            else -> null
        }
    }.getOrNull()
}

/**
 * Build the whole model from the cached daily metrics + the selected sleep session + the export-verbatim
 * sleep figures. Returns null when there is no usable night (no stage minutes), rendering the empty state.
 * Internal so SleepImportedFiguresTest can pin the prefer-imported logic.
 */
internal fun buildSleepModel(
    days: List<DailyMetric>,
    session: SleepSession?,
    imported: ImportedSleepSeries = ImportedSleepSeries(),
    selectedDay: String? = null,
    // The bridged main-night GROUP's summed stage minutes + full-night segments, threaded from selectNight so
    // a biphasic night's hero shows the WHOLE night, not one fragment. Null for a single-block day → the
    // session/DailyMetric path below applies.
    heroStages: StageMins? = null,
    heroSegments: List<PersistedSegment>? = null,
): SleepModel? {
    val effectiveDay = selectedDay ?: days.lastOrNull()?.day ?: return null
    // The HERO night = the selected day's stage-bearing row. The per-night METRIC tiles also re-point to the
    // browsed night: their CURRENT value is the selected day's reading (`metricAtDay`), so a past night shows
    // its OWN numbers. Their typical/series, the ledger, the personal need and the trend stay FULL-HISTORY.
    val latest = days.lastOrNull {
        it.day == effectiveDay && (it.deepMin ?: 0.0) + (it.remMin ?: 0.0) + (it.lightMin ?: 0.0) > 0.0
    }
        ?: return null

    // Prefer stage minutes from the session's (possibly reclipped) stagesJSON when it belongs to this night,
    // so a wake-time edit updates the stage cards immediately without waiting on a rescore.
    val sessionStageMins = session
        ?.takeIf { AnalyticsEngine.dayString(it.endTs) == latest.day || localDayString(it.endTs) == latest.day }
        // Trim to the EFFECTIVE onset before summing, so a hand-edited bedtime the raw was too sparse to
        // re-stage can't show pre-onset stages that push asleep past time-in-bed. No-op when the session
        // already starts at its onset. Matches the analytics-side clamp.
        ?.let { parseSessionStages(SleepStageTotals.clampStagesToOnset(it.stagesJSON, it.effectiveStartTs)) }
    val deep = heroStages?.deep ?: sessionStageMins?.deep ?: latest.deepMin ?: 0.0
    val rem = heroStages?.rem ?: sessionStageMins?.rem ?: latest.remMin ?: 0.0
    val light = heroStages?.light ?: sessionStageMins?.light ?: latest.lightMin ?: 0.0

    // Hero awake estimate works off ASLEEP minutes (totalSleepMin), never the in-bed window — a sleep edit
    // reaches the tiles via the re-score path, not a display-time in-bed swap.
    val asleep = latest.totalSleepMin ?: (deep + rem + light)
    // Awake estimate: prefer (time-in-bed − asleep) implied by efficiency; else from disturbances.
    val effFrac = latest.efficiency?.let { if (it > 1.0) it / 100.0 else it }
    val awake = when {
        effFrac != null && effFrac in 0.01..0.999 -> max(0.0, asleep / effFrac - asleep)
        latest.disturbances != null -> latest.disturbances * 6.0
        else -> 0.0
    }
    val stages = Stages(awake = awake, light = light, deep = deep, rem = rem)
    if (stages.total <= 0.0) return null

    // Typical = mean across ALL nights with data (full history, never bounded to the browsed night).
    val typicalTotalMin = mean(days.mapNotNull { it.totalSleepMin }.filter { it > 0.0 })
    val typicalDeepMin = mean(days.mapNotNull { it.deepMin }.filter { it > 0.0 })
    val typicalRemMin = mean(days.mapNotNull { it.remMin }.filter { it > 0.0 })
    val typicalLightMin = mean(days.mapNotNull { it.lightMin }.filter { it > 0.0 })

    // Personal sleep need (minutes): mean asleep, floored at 7.5h (450 min).
    val needMin = max(450.0, typicalTotalMin ?: 450.0)

    // Per-tile metrics — each a full pass over the FULL day history (asleep totals). Where the WHOOP export
    // carried the figure verbatim (metricSeries), it wins per day; the on-device recomputation fills the rest.
    val performance = metricAtDay(days, latest) { d ->
        imported.performance[d.day]                       // WHOOP's own 0–100 figure wins per day
            // else the REAL Rest composite (RestScorer.restFromDaily) — the same source the Today Rest score
            // and the metric-detail overlay read, so every surface agrees (a hours-vs-need proxy ceilings
            // live 5.0 nights at 100%).
            ?: com.noop.analytics.RestScorer.restFromDaily(d)
    }
    val efficiency = metricAtDay(days, latest) { d ->
        d.efficiency?.let { if (it <= 1.0) it * 100.0 else it }
    }
    val consistency = run {
        // Prefer the imported sleep_consistency series, but only when it covers the latest
        // night — otherwise the fallback current would silently be a months-old import-era value.
        val lastDay = days.lastOrNull()?.day
        if (lastDay != null && imported.consistency[lastDay] != null) {
            val series = days.mapNotNull { imported.consistency[it.day] }
            // Current = the SELECTED night's imported consistency (else newest, if this night has none).
            Metric(imported.consistency[latest.day] ?: series.lastOrNull(), mean(series), series)
        } else {
            consistencySeries(days, latest.day)
        }
    }
    val hoursVsNeeded = metricAtDay(days, latest) { d ->
        val need = imported.needMin[d.day] ?: needMin   // imported need wins per day
        d.totalSleepMin?.takeIf { it > 0.0 && need > 0.0 }?.let { it / need * 100.0 }
    }
    val restorative = metricAtDay(days, latest) { d ->
        val dp = d.deepMin; val rm = d.remMin; val sl = d.totalSleepMin
        if (dp != null && rm != null && sl != null && sl > 0.0) (dp + rm) / sl * 100.0 else null
    }
    val respiratory = metricAtDay(days, latest) { it.respRateBpm }
    val sleepDebt = run {
        fun debtOf(d: DailyMetric): Double? =
            imported.debtMin[d.day]   // minutes, export-verbatim
                ?: d.totalSleepMin?.takeIf { it > 0.0 && needMin > 0.0 }
                    ?.let { max(0.0, needMin - it) }   // APPROXIMATE fallback
        val series = days.mapNotNull(::debtOf)
        // Current = the SELECTED night's debt (the debt carried that morning), not the newest's.
        Metric(debtOf(latest), mean(series), series)
    }

    // Trend set = the most-recent nights with data (asleep totals, full history, not the browsed night).
    val trendRows = days.filter { (it.totalSleepMin ?: 0.0) > 0.0 }.takeLast(14)
    val trendHours = trendRows.mapNotNull { it.totalSleepMin?.let { minutes -> minutes / 60.0 } }
    val trendNeedHours = trendRows.map { row -> ((imported.needMin[row.day] ?: needMin) / 60.0) }
    val trendDebtHours = trendRows.map { row ->
        val sleptMin = row.totalSleepMin ?: 0.0
        val neededMin = imported.needMin[row.day] ?: needMin
        ((imported.debtMin[row.day] ?: max(0.0, neededMin - sleptMin)) / 60.0)
    }
    val trendDates = trendRows.map { it.day }

    // Real per-epoch timeline only when the merged session IS this night — UTC OR local-tz end-day match
    // (imported DailyMetric.day is local-tz while dayString is UTC). A non-matching session degrades to
    // synthesis, never a wrong night.
    val realSegments = heroSegments?.map { seg -> seg.stage to ((seg.end - seg.start) / 60f) }
        ?: session
            ?.takeIf {
                AnalyticsEngine.dayString(it.endTs) == latest.day || localDayString(it.endTs) == latest.day
            }
            ?.let { parsePersistedSegments(it.stagesJSON) }
            ?.map { seg -> seg.stage to ((seg.end - seg.start) / 60f) }

    // Rolling 14-night sleep-debt ledger over the FULL day history (capped to the most-recent 14 counted
    // nights, no-data nights skipped), using the same personal need the tiles use (`needMin`, ≥ 7.5h). Full
    // history, not the browsed-night window — a "Last 14 nights" summary matching the debt TILE.
    val sleepDebtLedger = SleepDebt.ledger(
        series = days.map { it.day to it.totalSleepMin },
        needHours = needMin / 60.0,
    )

    return SleepModel(
        stages = stages,
        clockLabel = clockLabel(latest, session),
        efficiencyText = efficiency.latest?.let { "${it.roundToInt()}%" } ?: "—",
        performance = performance,
        efficiency = efficiency,
        consistency = consistency,
        hoursVsNeeded = hoursVsNeeded,
        restorative = restorative,
        respiratory = respiratory,
        sleepDebt = sleepDebt,
        typicalTotalMin = typicalTotalMin,
        typicalDeepMin = typicalDeepMin,
        typicalRemMin = typicalRemMin,
        typicalLightMin = typicalLightMin,
        trendHours = trendHours,
        trendNeedHours = trendNeedHours,
        trendDebtHours = trendDebtHours,
        trendDates = trendDates,
        realSegments = realSegments,
        sleepDebtLedger = sleepDebtLedger,
    )
}

/**
 * No-blank fallback: a stage-less SELECTED day must not hide the tab's full-history surfaces. Re-anchor the
 * model to the newest day that HAS stage minutes; the tiles / ledger / trends it feeds are full-history, so
 * this only changes which day supplies the hero-independent anchor. Null only when NO day carries stage data.
 */
internal fun fallbackSleepModel(
    days: List<DailyMetric>,
    imported: ImportedSleepSeries = ImportedSleepSeries(),
): SleepModel? {
    val anchorDay = days.lastOrNull {
        (it.deepMin ?: 0.0) + (it.remMin ?: 0.0) + (it.lightMin ?: 0.0) > 0.0
    }?.day ?: return null
    return buildSleepModel(days, null, imported, selectedDay = anchorDay)
}

/** A per-night metric whose CURRENT value ([current]) is the SELECTED day's reading — so browsing a past
 *  night re-points these tiles too, not just the hero. `typical`/`series` stay the FULL-HISTORY mean +
 *  sparkline for the "vs typical" context. */
private fun metricAtDay(days: List<DailyMetric>, current: DailyMetric, transform: (DailyMetric) -> Double?): Metric {
    val series = days.mapNotNull(transform).filter { it.isFinite() }
    val cur = transform(current)?.takeIf { it.isFinite() }
    return Metric(cur, mean(series), series)
}

/**
 * Consistency per day. Android's daily metrics carry no per-night onset timestamp, so a bedtime-variance
 * score isn't reconstructable — approximate the same intent (steadier nights → higher score) from the
 * trailing-14 spread of total-sleep duration. A duration-based proxy, not the onset-spread score.
 */
private fun consistencySeries(days: List<DailyMetric>, selectedDay: String? = null): Metric {
    val rows = days.filter { (it.totalSleepMin ?: 0.0) > 0.0 }
    val mins = rows.map { it.totalSleepMin!! }
    if (mins.size < 3) return Metric(null, null, emptyList())
    val scores = ArrayList<Double>()
    val scoreByDay = HashMap<String, Double>()
    for (i in mins.indices) {
        val lo = max(0, i - 13)
        val window = mins.subList(lo, i + 1)
        if (window.size < 3) continue
        val m = window.average()
        val variance = window.sumOf { (it - m) * (it - m) } / window.size
        val sd = Math.sqrt(variance)
        // 90 min of duration SD maps to a 0 score; tighter routines climb to 100.
        val score = (100.0 * (1.0 - sd / 90.0)).coerceIn(0.0, 100.0)
        scores.add(score)
        scoreByDay[rows[i].day] = score
    }
    // Current = the SELECTED night's score, so browsing re-points it too (else newest, unchanged).
    val current = selectedDay?.let { scoreByDay[it] } ?: scores.lastOrNull()
    return Metric(current, mean(scores), scores)
}

private fun mean(vals: List<Double>): Double? = if (vals.isEmpty()) null else vals.sum() / vals.size

// MARK: - Stage segment reconstruction (durations only)

/**
 * Lay the stage minutes end-to-end as proportional hypnogram segments: light → deep → light → rem → light →
 * awake (deep early, REM later, awake last). Weights are minutes; the Hypnogram normalizes them to width.
 */
internal fun stageSegments(s: Stages): List<Pair<String, Float>> {
    val out = ArrayList<Pair<String, Float>>()
    fun add(name: String, minutes: Double) {
        if (minutes > 0.0) out.add(name to minutes.toFloat())
    }
    add("light", s.light * 0.4)
    add("deep", s.deep)
    add("light", s.light * 0.3)
    add("rem", s.rem)
    add("light", s.light * 0.3)
    add("awake", s.awake)
    return out
}

// MARK: - Formatting helpers

internal fun pctValue(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"

/** "+12% vs typical" / "−0.4 rpm vs typical" — the latest-vs-mean caption every tile carries. */
internal fun vsTypical(latest: Double?, typical: Double?, suffix: String, decimals: Int = 0): String {
    if (latest == null || typical == null || typical == 0.0) return "vs typical - "
    val diff = latest - typical
    val sign = if (diff >= 0) "+" else "−"
    val mag = abs(diff)
    val num = if (decimals == 0) "${mag.roundToInt()}" else String.format(Locale.US, "%.${decimals}f", mag)
    return "$sign$num$suffix vs typical"
}

internal fun debtCaption(debt: Double?): String {
    if (debt == null) return "vs need"
    return if (debt < 15.0) "On target" else "Below need"
}

internal fun debtColor(debt: Double?): Color = when {
    debt == null -> Palette.textPrimary
    debt < 15.0 -> Palette.statusPositive
    debt < 60.0 -> Palette.statusWarning
    else -> Palette.statusCritical
}

// MARK: - Sleep-debt ledger formatting

/**
 * "≈2h 10m" magnitude headline — leading "≈" because it's an accumulated estimate. Reads
 * "On target" inside the deadband so a few stray minutes don't show as debt.
 */
internal fun debtHeadline(ledger: SleepDebtLedger): String =
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN) "On target"
    else "≈${durationText(ledger.magnitudeMin)}"

/** Short tag beside the headline: sleep debt / surplus / balanced. */
internal fun debtTag(ledger: SleepDebtLedger): String = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN -> "balanced"
    ledger.isDebt -> "sleep debt"
    else -> "surplus"
}

/** Plain-English read of the running balance over the window. */
internal fun debtRead(ledger: SleepDebtLedger): String {
    val nights = ledger.nightCount
    val span = "the last $nights night${if (nights == 1) "" else "s"}"
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN) {
        return "You're roughly on top of your sleep across $span. Slept minutes balance out against your need."
    }
    val mag = durationText(ledger.magnitudeMin)
    return if (ledger.isDebt) {
        "You've banked about $mag of sleep debt over $span. Surplus nights count back against it. An earlier night or two would clear it."
    } else {
        "You're carrying about $mag of surplus over $span. You've slept past your need on balance. Nicely ahead."
    }
}

/**
 * Color the balance by sign + size: surplus/within-band → positive green, modest debt →
 * warning, heavier debt → critical.
 */
internal fun debtBalanceColor(ledger: SleepDebtLedger): Color = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN || !ledger.isDebt -> Palette.statusPositive
    ledger.magnitudeMin < 180.0 -> Palette.statusWarning
    else -> Palette.statusCritical
}

/** Signed "+1h 20m" / "−2h 10m" / "0m" balance string. */
internal fun debtSigned(minutes: Double): String {
    if (abs(minutes) < 1.0) return "0m"
    val sign = if (minutes >= 0.0) "+" else "−"
    return "$sign${durationText(abs(minutes))}"
}

internal fun durationText(minutes: Double): String {
    val m = max(0, minutes.roundToInt())
    return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

/** A short "4 Jun" date label from a YYYY-MM-DD day string. */
internal fun shortDayLabel(day: String): String =
    runCatching {
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
    }.getOrDefault(day)

internal fun List<Double>.sleepAverageOrNull(): Double? =
    if (isEmpty()) null else sum() / size

internal fun clockLabel(latest: DailyMetric, session: SleepSession?): String {
    if (session != null) return sessionClockLabel(session)
    // Fall back to the daily metric's day string (YYYY-MM-DD), formatted to "EEE d MMM".
    val dateFmt = SimpleDateFormat("EEE d MMM", Locale.US)
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        parser.parse(latest.day)?.let { dateFmt.format(it) }
    }.getOrNull() ?: latest.day
}

/** "Wed 4 Jun · 22:50–06:48" — the night-nav header's date · onset–wake line. */
private fun sessionClockLabel(session: SleepSession): String =
    clockLabelFor(session.effectiveStartTs, session.endTs) // EFFECTIVE onset so an edited bedtime shows

/** Same date · onset–wake line from explicit unix-second bounds (the group-aligned bedtime). */
private fun clockLabelFor(onsetTs: Long, wakeTs: Long): String {
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val dateFmt = SimpleDateFormat("EEE d MMM", Locale.US)
    val onset = Date(onsetTs * 1000L)
    val wake = Date(wakeTs * 1000L)
    return "${dateFmt.format(onset)} · ${timeFmt.format(onset)} - ${timeFmt.format(wake)}"
}

/** Unix seconds → "YYYY-MM-DD" in the DEVICE timezone (vs AnalyticsEngine.dayString = UTC). */
internal fun localDayString(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts * 1000L))

/** Unix seconds → a local wall-clock "HH:mm" (same 24h formatting the nav-header span uses). */
internal fun clockTimeLabel(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(ts * 1000L))

/** One persisted per-epoch stage segment (wall-clock unix seconds). */
