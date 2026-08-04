package com.noop.ui

import com.noop.analytics.BaselineState
import com.noop.analytics.RustScores
import uniffi.whoop_ffi.DriverKind
import uniffi.whoop_ffi.DriverVerdict
import java.util.Locale
import kotlin.math.roundToInt

// ChargeDriverRows.kt - the wording for the "What shaped it" breakdown under the Charge ring.
//
// whoop-rs decides WHICH terms the Charge score used, each one's marginal whole-point swing and how it
// read against the personal baseline; everything here is presentation - the signal's name, its value
// and baseline printed with their units, and the sentence a direction reads as.

/**
 * One driver row behind the Charge (recovery) score, ready to render.
 *
 * @property label short signal name, e.g. "Resting heart rate".
 * @property deltaPoints signed contribution to the 0-100 Charge score versus this signal sitting at
 *   the personal baseline (positive = lifted Charge, negative = pulled it down).
 * @property valueText the night's value, formatted with its unit, e.g. "58 bpm".
 * @property baselineText the personal baseline it was scored against, e.g. "61 bpm baseline". Empty
 *   for a term with no learned baseline.
 * @property verdict short plain-English read, e.g. "below baseline, supporting recovery".
 */
data class ChargeDriver(
    val label: String,
    val deltaPoints: Int,
    val valueText: String,
    val baselineText: String,
    val verdict: String,
)

/**
 * The ordered "What shaped it" rows for one night's Charge score, or an EMPTY list when the score
 * itself can't compute. Takes the same arguments the score does, so a row can never describe a term
 * the number did not use; a term whose input is missing yields NO row.
 *
 * [BaselineState] carries each personal baseline (mean) a row names. [skinTempDev] is a RELATIVE
 * deviation in +/- C, never an absolute temperature; [recoveryIndexSlope] is bpm/hour, negative
 * (declining) being the good pattern.
 */
internal fun chargeDriverRows(
    hrv: Double,
    rhr: Double,
    resp: Double?,
    hrvBaseline: BaselineState,
    rhrBaseline: BaselineState?,
    respBaseline: BaselineState?,
    sleepPerf: Double?,
    skinTempDev: Double? = null,
    recoveryIndexSlope: Double? = null,
    effortBaseline: BaselineState? = null,
    priorDayEffort: Double? = null,
): List<ChargeDriver> {
    val rows = RustScores.chargeDriverRows(
        hrv = hrv,
        rhr = rhr,
        resp = resp,
        hrvBaseline = hrvBaseline,
        rhrBaseline = rhrBaseline,
        respBaseline = respBaseline,
        sleepPerf = sleepPerf,
        skinTempDev = skinTempDev,
        recoveryIndexSlope = recoveryIndexSlope,
        effortBaseline = effortBaseline,
        priorDayEffort = priorDayEffort,
    )
    if (rows.isEmpty()) return emptyList()

    // Name, value and baseline for every term that HAS an input, keyed the way whoop-rs names it.
    val text = HashMap<DriverKind, Triple<String, String, String>>()
    text[DriverKind.HRV] = Triple(
        "Heart rate variability",
        "${hrv.roundToInt()} ms",
        "${hrvBaseline.baseline.roundToInt()} ms baseline",
    )
    if (rhrBaseline != null) {
        text[DriverKind.RESTING_HR] = Triple(
            "Resting heart rate",
            "${rhr.roundToInt()} bpm",
            "${rhrBaseline.baseline.roundToInt()} bpm baseline",
        )
    }
    if (sleepPerf != null) {
        // Centred on a fixed "good night", not a learned baseline, so there is nothing to name.
        text[DriverKind.SLEEP] = Triple("Sleep quality", "${(sleepPerf * 100.0).roundToInt()}%", "")
    }
    if (resp != null && respBaseline != null) {
        text[DriverKind.RESPIRATORY] = Triple(
            "Respiratory rate",
            String.format(Locale.US, "%.1f br/min", resp),
            String.format(Locale.US, "%.1f br/min baseline", respBaseline.baseline),
        )
    }
    if (skinTempDev != null) {
        // A deviation already; its reference is the personal baseline, which sits at zero. The
        // reference goes in the baseline slot so the value stays short enough for the row's label.
        text[DriverKind.SKIN_TEMP] = Triple(
            "Skin temperature",
            String.format(Locale.US, "%+.1f °C", skinTempDev),
            "vs baseline",
        )
    }
    if (recoveryIndexSlope != null) {
        text[DriverKind.RECOVERY_INDEX] = Triple(
            "Recovery index",
            String.format(Locale.US, "%+.1f bpm/hr", recoveryIndexSlope),
            "overnight",
        )
    }
    if (priorDayEffort != null && effortBaseline != null) {
        text[DriverKind.ACTIVITY_BALANCE] = Triple(
            "Activity balance",
            "${priorDayEffort.roundToInt()} effort yesterday",
            "${effortBaseline.baseline.roundToInt()} baseline",
        )
    }

    return rows.mapNotNull { row ->
        val (label, valueText, baselineText) = text[row.kind] ?: return@mapNotNull null
        ChargeDriver(
            label = label,
            deltaPoints = row.deltaPoints.roundToInt(),
            valueText = valueText,
            baselineText = baselineText,
            verdict = verdictSentence(row.kind, row.verdict),
        )
    }
}

/** The three sentences a single-sided driver reads as: on the good side, at baseline, on the bad side. */
private fun sentences(kind: DriverKind): Triple<String, String, String> = when (kind) {
    DriverKind.HRV -> Triple(
        "above baseline, supporting recovery", "at baseline", "below baseline, limiting recovery",
    )
    DriverKind.SLEEP -> Triple(
        "a strong night, supporting recovery", "a typical night", "below a good night, limiting recovery",
    )
    DriverKind.RECOVERY_INDEX -> Triple(
        "resting HR fell through the night, supporting recovery",
        "resting HR held flat overnight",
        "resting HR rose overnight, limiting recovery",
    )
    DriverKind.ACTIVITY_BALANCE -> Triple(
        "a lighter day yesterday, supporting recovery",
        "a typical day yesterday",
        "a harder day yesterday, limiting recovery",
    )
    // Resting HR and respiration are both "lower is better", so they share their wording.
    else -> Triple(
        "below baseline, supporting recovery", "at baseline", "above baseline, limiting recovery",
    )
}

/** The plain-English read for a row: whoop-rs picked the direction, this picks the words for it. */
private fun verdictSentence(kind: DriverKind, verdict: DriverVerdict): String {
    if (kind == DriverKind.SKIN_TEMP) {
        // Symmetric term: only whoop-rs's side matters, and inside its band neither side is named.
        return when (verdict) {
            DriverVerdict.LIMITING_HIGH -> "warmer than baseline, limiting recovery"
            DriverVerdict.LIMITING_LOW -> "cooler than baseline, limiting recovery"
            else -> "near baseline"
        }
    }
    val (good, flat, bad) = sentences(kind)
    return when (verdict) {
        DriverVerdict.SUPPORTING -> good
        DriverVerdict.NEUTRAL -> flat
        else -> bad
    }
}
