package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noop.R
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
 * @property labelRes short signal name, e.g. "Resting heart rate".
 * @property deltaPoints signed contribution to the 0-100 Charge score versus this signal sitting at
 *   the personal baseline (positive = lifted Charge, negative = pulled it down).
 * @property valueText the night's value with its unit, e.g. "58 bpm"; [valueRes] words it when the row
 *   reads as a phrase rather than a bare figure.
 * @property baselineValue the personal baseline it was scored against, e.g. "61 bpm", worded by
 *   [baselineRes]. Both are empty/null for a term with no learned baseline.
 * @property verdictRes short plain-English read, e.g. "below baseline, supporting recovery".
 */
data class ChargeDriver(
    @StringRes val labelRes: Int,
    val deltaPoints: Int,
    val valueText: String,
    @StringRes val valueRes: Int? = null,
    val baselineValue: String = "",
    @StringRes val baselineRes: Int? = null,
    @StringRes val verdictRes: Int,
)

/** The row's value as drawn: the bare figure, or the phrase its resource wraps it in. */
@Composable
internal fun ChargeDriver.valueLabel(): String =
    valueRes?.let { stringResource(it, valueText) } ?: valueText

/** The row's reference line as drawn: empty when the term has no learned baseline to name. */
@Composable
internal fun ChargeDriver.baselineLabel(): String =
    baselineRes?.let { stringResource(it, baselineValue) } ?: baselineValue

/** One term's drawn text: its name, the night's value, and the reference printed under it. */
private class DriverText(
    @StringRes val label: Int,
    val value: String,
    @StringRes val valueRes: Int? = null,
    val baseline: String = "",
    @StringRes val baselineRes: Int? = null,
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
    tempUnit: TemperatureUnit,
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
    val text = HashMap<DriverKind, DriverText>()
    text[DriverKind.HRV] = DriverText(
        label = R.string.charge_driver_hrv,
        value = "${hrv.roundToInt()} ms",
        baseline = "${hrvBaseline.baseline.roundToInt()} ms",
        baselineRes = R.string.charge_driver_baseline,
    )
    if (rhrBaseline != null) {
        text[DriverKind.RESTING_HR] = DriverText(
            label = R.string.charge_driver_resting_hr,
            value = "${rhr.roundToInt()} bpm",
            baseline = "${rhrBaseline.baseline.roundToInt()} bpm",
            baselineRes = R.string.charge_driver_baseline,
        )
    }
    if (sleepPerf != null) {
        // Centred on a fixed "good night", not a learned baseline, so there is nothing to name.
        text[DriverKind.SLEEP] = DriverText(
            label = R.string.charge_driver_sleep,
            value = "${(sleepPerf * 100.0).roundToInt()}%",
        )
    }
    if (resp != null && respBaseline != null) {
        text[DriverKind.RESPIRATORY] = DriverText(
            label = R.string.charge_driver_respiratory,
            value = String.format(Locale.US, "%.1f br/min", resp),
            baseline = String.format(Locale.US, "%.1f br/min", respBaseline.baseline),
            baselineRes = R.string.charge_driver_baseline,
        )
    }
    if (skinTempDev != null) {
        // A deviation already; its reference is the personal baseline, which sits at zero. The
        // reference goes in the baseline slot so the value stays short enough for the row's label.
        text[DriverKind.SKIN_TEMP] = DriverText(
            label = R.string.charge_driver_skin_temp,
            value = UnitFormatter.temperatureDeltaFromCelsius(skinTempDev, tempUnit),
            baselineRes = R.string.charge_driver_vs_baseline,
        )
    }
    if (recoveryIndexSlope != null) {
        text[DriverKind.RECOVERY_INDEX] = DriverText(
            label = R.string.charge_driver_recovery_index,
            value = String.format(Locale.US, "%+.1f bpm/hr", recoveryIndexSlope),
            baselineRes = R.string.charge_driver_overnight,
        )
    }
    if (priorDayEffort != null && effortBaseline != null) {
        text[DriverKind.ACTIVITY_BALANCE] = DriverText(
            label = R.string.charge_driver_activity_balance,
            value = "${priorDayEffort.roundToInt()}",
            valueRes = R.string.charge_driver_effort_yesterday,
            baseline = "${effortBaseline.baseline.roundToInt()}",
            baselineRes = R.string.charge_driver_baseline,
        )
    }

    return rows.mapNotNull { row ->
        val t = text[row.kind] ?: return@mapNotNull null
        ChargeDriver(
            labelRes = t.label,
            deltaPoints = row.deltaPoints.roundToInt(),
            valueText = t.value,
            valueRes = t.valueRes,
            baselineValue = t.baseline,
            baselineRes = t.baselineRes,
            verdictRes = verdictSentence(row.kind, row.verdict),
        )
    }
}

/** The three sentences a single-sided driver reads as: on the good side, at baseline, on the bad side. */
private fun sentences(kind: DriverKind): Triple<Int, Int, Int> = when (kind) {
    DriverKind.HRV -> Triple(
        R.string.charge_verdict_above_supporting,
        R.string.charge_verdict_at_baseline,
        R.string.charge_verdict_below_limiting,
    )
    DriverKind.SLEEP -> Triple(
        R.string.charge_verdict_sleep_supporting,
        R.string.charge_verdict_sleep_neutral,
        R.string.charge_verdict_sleep_limiting,
    )
    DriverKind.RECOVERY_INDEX -> Triple(
        R.string.charge_verdict_index_supporting,
        R.string.charge_verdict_index_neutral,
        R.string.charge_verdict_index_limiting,
    )
    DriverKind.ACTIVITY_BALANCE -> Triple(
        R.string.charge_verdict_activity_supporting,
        R.string.charge_verdict_activity_neutral,
        R.string.charge_verdict_activity_limiting,
    )
    // Resting HR and respiration are both "lower is better", so they share their wording.
    else -> Triple(
        R.string.charge_verdict_below_supporting,
        R.string.charge_verdict_at_baseline,
        R.string.charge_verdict_above_limiting,
    )
}

/** The plain-English read for a row: whoop-rs picked the direction, this picks the words for it. */
@StringRes
private fun verdictSentence(kind: DriverKind, verdict: DriverVerdict): Int {
    if (kind == DriverKind.SKIN_TEMP) {
        // Symmetric term: only whoop-rs's side matters, and inside its band neither side is named.
        return when (verdict) {
            DriverVerdict.LIMITING_HIGH -> R.string.charge_verdict_skin_temp_warm
            DriverVerdict.LIMITING_LOW -> R.string.charge_verdict_skin_temp_cool
            else -> R.string.charge_verdict_skin_temp_near
        }
    }
    val (good, flat, bad) = sentences(kind)
    return when (verdict) {
        DriverVerdict.SUPPORTING -> good
        DriverVerdict.NEUTRAL -> flat
        else -> bad
    }
}
