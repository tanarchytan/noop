package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.noop.data.WhoopRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.StepsEstimateEngine
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - StepsCalibrationScreen
//
// WHOOP 4.0 steps-ESTIMATE calibration. A 4.0 sends no step count over BLE, so NOOP estimates steps
// from the strap's daily MOTION VOLUME, calibrated per-user against the phone's real step count. This
// screen is read-only over the engine's fit (it never recomputes the headline): an honest explainer,
// the current calibration, a recent estimated-vs-phone accuracy table, and a manual coefficient
// override with a live preview. Shares its confidence wording with the Profile summary row via
// [StepsCalibrationFormat]. Presented in a full-screen Dialog from Settings →
// Profile → "Steps estimate".

/** Shared formatters for the steps-estimate calibration UI — kept apart so the Profile summary row and
 *  this screen agree on the confidence wording. */
object StepsCalibrationFormat {
    /** A 0–1 confidence worded for this screen, banded by [StepsEstimateEngine.ConfidenceTier]. A
     *  manual coefficient is confidence 1.0 → "High". Resolved by the caller, which has a Context. */
    @StringRes
    fun confidenceLabelRes(confidence: Double): Int =
        when (StepsEstimateEngine.ConfidenceTier.from(confidence)) {
            StepsEstimateEngine.ConfidenceTier.LOW -> R.string.steps_confidence_low
            StepsEstimateEngine.ConfidenceTier.MEDIUM -> R.string.steps_confidence_medium
            StepsEstimateEngine.ConfidenceTier.HIGH -> R.string.steps_confidence_high
        }
}

/** One recent day's estimated-vs-phone steps comparison row for the accuracy table. */
private data class StepsComparisonRow(val day: String, val estimated: Int, val actual: Int) {
    /** Signed error of the estimate vs the phone count, as a percentage. */
    val errorPct: Double get() = if (actual > 0) (estimated - actual).toDouble() / actual * 100 else 0.0
}

@Composable
fun StepsCalibrationScreen(
    vm: AppViewModel,
    profile: ProfileStore,
    onProfileChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()

    // Recent days that have BOTH an estimate (reconstructed) and a phone count — the accuracy table.
    var comparison by remember { mutableStateOf<List<StepsComparisonRow>>(emptyList()) }
    // A representative recent motion volume (median of the days we measured), seeding the live preview.
    var sampleMotion by remember { mutableStateOf<Double?>(null) }
    // Flips true once the load pass has run, so the "no motion synced" note doesn't flash on first frame.
    var loaded by remember { mutableStateOf(false) }

    // The stepper's ceiling anchors to whatever's in force with generous headroom, so a nudge either way
    // stays reachable; a floor keeps it usable before any fit.
    val stepperMax = maxOf(profile.stepsCalibrationCoefficient, profile.stepsManualCoefficient, 50.0) * 2

    // Build the comparison table + a typical-day motion, once. The engine stores `steps_est` ONLY for
    // strap-only days (a phone-covered day uses the phone's real count), so an estimate and a phone
    // count never co-exist in storage. To still SHOW how close the estimate is, we reconstruct what the
    // estimate WOULD have been on recent phone-covered days: read each day's motion the same way the
    // engine does (gravity over [localMidnight, +24h)) and run the public StepsEstimateEngine with the
    // live calibration. Reuses the engine, never invents a number, needs no extra storage.
    LaunchedEffect(Unit) {
        loaded = true
        val coeff = if (profile.stepsManualCoefficient > 0) {
            profile.stepsManualCoefficient
        } else {
            profile.stepsCalibrationCoefficient
        }
        if (coeff <= 0) return@LaunchedEffect

        // Phone step counts come from apple-health AND, for HC-only users, Health Connect. Both are
        // stored in appleDaily under their own source; union them with apple-health winning per day.
        val stepsByDay = LinkedHashMap<String, Int>()
        for (row in vm.repo.appleDaily(WhoopRepository.APPLE_HEALTH_SOURCE, "0000-01-01", "9999-12-31")) {
            row.steps?.takeIf { it > 0 }?.let { stepsByDay[row.day] = it }
        }
        for (row in vm.repo.appleDaily(WhoopRepository.HEALTH_CONNECT_SOURCE, "0000-01-01", "9999-12-31")) {
            row.steps?.takeIf { it > 0 }?.let { stepsByDay.putIfAbsent(row.day, it) }
        }
        val phoneDays = stepsByDay.entries
            .map { it.key to it.value }
            .sortedByDescending { it.first }

        val cal = StepsEstimateEngine.Calibration(
            coefficient = coeff,
            sampleDays = profile.stepsCalibrationSampleDays,
            confidence = profile.stepsCalibrationConfidence,
            manual = profile.stepsManualCoefficient > 0,
        )
        val rows = ArrayList<StepsComparisonRow>()
        val motions = ArrayList<Double>()
        for ((day, phone) in phoneDays.take(10)) {           // scan extra to fill 7 after motion gaps
            val mid = runCatching {
                LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            }.getOrNull() ?: continue
            val grav = vm.repo.gravitySamples("my-whoop", mid, mid + 86_400 - 1)
            val motion = StepsEstimateEngine.dayMotionIntensity(grav)
            val est = StepsEstimateEngine.estimate(motion, cal) ?: continue
            motions.add(motion)
            rows.add(StepsComparisonRow(day, est, phone))
            if (rows.size >= 7) break
        }
        comparison = rows
        if (motions.isNotEmpty()) sampleMotion = motions.sorted()[motions.size / 2]
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onClose)
            Hairline()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(Metrics.screenRowSpacing),
                verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            ) {
                ExplainerCard()
                if (loaded && sampleMotion == null) NoMotionNote()
                // the matched-day count (phone-counted days we could pair with strap motion — the
                // engine's "usable overlapping days") drives the "Need N more days…" countdown. In the
                // not-calibrated state the comparison build early-returns on coeff <= 0, so this is 0 and
                // the headline reads the full MIN_CALIBRATION_DAYS.
                CurrentFitCard(profile, matchedDays = comparison.size)
                ComparisonCard(comparison)
                ManualAdjustCard(
                    profile = profile,
                    stepperMax = stepperMax,
                    sampleMotion = sampleMotion,
                    onProfileChanged = onProfileChanged,
                )
            }
            Hairline()
            Footer(onClose)
        }
    }
}

// MARK: - Header / footer

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Metrics.screenRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Overline(stringResource(R.string.profile_steps_estimate), color = Palette.textTertiary)
            Text(
                stringResource(R.string.steps_title),
                style = NoopType.display(26f),
                color = Palette.textPrimary,
            )
            Text(
                stringResource(R.string.steps_subtitle),
                style = NoopType.caption,
                color = Palette.textSecondary,
            )
        }
        CloseButton(onClick = onClose)
    }
}

@Composable
private fun Footer(onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(Metrics.space16), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.surfaceBase),
        ) {
            Text(
                stringResource(R.string.steps_done),
                modifier = Modifier.padding(horizontal = Metrics.space24),
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Metrics.divider)
            .background(Palette.hairline),
    )
}

// MARK: - Cards

/** The honest "it's an estimate, not a step counter" framing — reused verbatim from the engine doc. */
@Composable
private fun ExplainerCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.steps_how_this_works),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }
            Text(
                stringResource(R.string.steps_explainer_body),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Text(
                stringResource(R.string.steps_explainer_detail),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** Shown when the strap has banked NO motion yet (sampleMotion == null) — the real reason a fresh
 *  WHOOP 4.0 reads zero steps (bringiton321). Steps come from the strap's synced motion history,
 *  so without a backfill there's nothing to estimate from — calibration can't help until it syncs. */
@Composable
private fun NoMotionNote() {
    NoopCard(padding = 20.dp, tint = Palette.metricAmber) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = Palette.metricAmber, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.steps_no_motion_title),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }
            Text(
                stringResource(R.string.steps_no_motion_body),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Text(
                stringResource(R.string.steps_no_motion_detail),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** The current calibration read-out: coefficient, sample days, and a Low/Medium/High confidence — or
 *  an honest "what we still need" prompt when nothing's fit and no manual value is set. */
@Composable
private fun CurrentFitCard(profile: ProfileStore, matchedDays: Int) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(stringResource(R.string.steps_current_calibration))
            if (profile.stepsCalibrationCoefficient > 0 || profile.stepsManualCoefficient > 0) {
                val coeff = if (profile.stepsManualCoefficient > 0) {
                    profile.stepsManualCoefficient
                } else {
                    profile.stepsCalibrationCoefficient
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(String.format(Locale.US, "%.1f", coeff), style = NoopType.number(30f), color = Palette.accent)
                    Text(
                        stringResource(R.string.steps_per_motion_unit),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (profile.stepsManualCoefficient > 0) {
                    StatLine(
                        stringResource(R.string.steps_stat_source),
                        stringResource(R.string.steps_source_manual),
                    )
                } else {
                    val days = profile.stepsCalibrationSampleDays
                    StatLine(
                        stringResource(R.string.steps_stat_fitted_from),
                        stringResource(
                            if (days == 1) R.string.steps_fitted_one_day else R.string.steps_fitted_days,
                            days,
                        ),
                    )
                    StatLine(
                        stringResource(R.string.steps_stat_confidence),
                        stringResource(
                            R.string.steps_confidence_value,
                            stringResource(
                                StepsCalibrationFormat.confidenceLabelRes(profile.stepsCalibrationConfidence),
                            ),
                            (profile.stepsCalibrationConfidence * 100).roundToInt(),
                        ),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.steps_not_calibrated_yet),
                    style = NoopType.bodyNumber,
                    color = Palette.textPrimary,
                )
                // a concrete countdown instead of a vague "a few days". Headline comes straight from
                // the engine's NeedsMoreDays state so the wording matches the Today steps tile.
                Text(
                    StepsEstimateEngine.CalibrationStatus
                        .NeedsMoreDays(have = matchedDays, need = StepsEstimateEngine.MIN_CALIBRATION_DAYS)
                        .headline,
                    style = NoopType.bodyNumber,
                    color = Palette.accent,
                )
                Text(
                    stringResource(R.string.steps_needs_days_note),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The accuracy table: recent days with BOTH an estimate and a phone count, side by side, so the user
 *  can SEE how close the estimate runs. Empty until enough both-have days exist. */
@Composable
private fun ComparisonCard(rows: List<StepsComparisonRow>) {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(stringResource(R.string.steps_comparison_title))
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.steps_comparison_empty),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.steps_col_day), style = NoopType.caption, color = Palette.textTertiary, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.steps_col_est), style = NoopType.caption, color = Palette.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    Text(stringResource(R.string.steps_col_phone), style = NoopType.caption, color = Palette.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    Text("Δ", style = NoopType.caption, color = Palette.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                }
                for (row in rows) {
                    val rowLabel = stringResource(
                        R.string.steps_row_a11y,
                        shortDay(row.day),
                        row.estimated,
                        row.actual,
                        row.errorPct.roundToInt(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = rowLabel },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(shortDay(row.day), style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                        Text(grouped(row.estimated), style = NoopType.captionNumber, color = Palette.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                        Text(grouped(row.actual), style = NoopType.captionNumber, color = Palette.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                        Text(
                            String.format(Locale.US, "%+.0f%%", row.errorPct),
                            style = NoopType.captionNumber,
                            color = if (abs(row.errorPct) <= 15) Palette.metricCyan else Palette.statusWarning,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(52.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.steps_comparison_note),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

private const val STEPS_COEFFICIENT_STEP = 0.1

/** Manual override: a stepper (mirrors the Profile card's age/weight [StepperField]s) bound to
 *  [ProfileStore.stepsManualCoefficient], with a live preview of what a typical recent day would
 *  estimate at the current setting. 0 means auto-fit; stepping down to 0 returns to it. Nudges start
 *  from whichever value is currently EFFECTIVE (the auto fit, until first overridden), so a nearby
 *  value like 1.2 from an auto-fitted 1.4 is two taps, not a drag from zero. */
@Composable
private fun ManualAdjustCard(
    profile: ProfileStore,
    stepperMax: Double,
    sampleMotion: Double?,
    onProfileChanged: () -> Unit,
) {
    val manual = profile.stepsManualCoefficient
    val effective = if (manual > 0) manual else profile.stepsCalibrationCoefficient

    fun setCoefficient(value: Double) {
        profile.stepsManualCoefficient = value.coerceIn(0.0, stepperMax)
        onProfileChanged()
    }

    // Every reachable coefficient, 0.1 apart. Index 0 of the OPTION list is Auto, so the wheel is one
    // longer than this and the mapping is offset by one.
    val coefficientSteps = remember(stepperMax) {
        val n = (stepperMax / STEPS_COEFFICIENT_STEP).roundToInt()
        (1..n).map { Math.round(it * STEPS_COEFFICIENT_STEP * 10) / 10.0 }
    }
    val autoLabel = stringResource(R.string.profile_auto)
    val coefficientOptions = remember(coefficientSteps, autoLabel) {
        listOf(autoLabel) + coefficientSteps.map { String.format(Locale.US, "%.1f", it) }
    }

    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(stringResource(R.string.steps_adjust_manually))
            Text(
                stringResource(R.string.steps_manual_body),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            val manualValue = String.format(Locale.US, "%.1f", manual)
            val autoWord = stringResource(R.string.profile_auto)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Text(
                    if (manual > 0) manualValue else autoWord,
                    style = NoopType.number(24f),
                    color = if (manual > 0) Palette.accent else Palette.textSecondary,
                )
                Text(
                    stringResource(
                        if (manual > 0) R.string.steps_per_motion_unit_short
                        else R.string.steps_fit_from_phone,
                    ),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                // Index 0 is Auto (coefficient 0); the rest walk 0.1 up to stepperMax, so a high
                // coefficient is one scroll rather than dozens of taps.
                WheelPickerField(
                    value = if (manual > 0) manualValue else autoWord,
                    accessibility = if (manual > 0) {
                        stringResource(R.string.steps_manual_a11y, manualValue)
                    } else {
                        stringResource(R.string.steps_manual_a11y_auto)
                    },
                    options = coefficientOptions,
                    selectedIndex = if (manual > 0) {
                        coefficientSteps.indices.minByOrNull { abs(coefficientSteps[it] - manual) }
                            ?.plus(1) ?: 0
                    } else {
                        0
                    },
                    dialogTitle = stringResource(R.string.steps_adjust_manually),
                    onSelected = { setCoefficient(if (it == 0) 0.0 else coefficientSteps[it - 1]) },
                )
            }
            // Live preview: a typical recent day re-estimated at the effective (manual or auto) coefficient.
            if (sampleMotion != null && effective > 0) {
                val preview = (sampleMotion * effective).roundToInt()
                StatLine(
                    stringResource(R.string.steps_typical_day),
                    stringResource(
                        if (manual > 0) R.string.steps_preview_at_setting else R.string.steps_preview_auto,
                        grouped(preview),
                    ),
                )
            }
            if (manual > 0) {
                Text(
                    stringResource(R.string.steps_takes_effect),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** A small "label … value" line shared by the fit + preview cards. */
@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Metrics.space12))
        Text(value, style = NoopType.footnote, color = Palette.textSecondary, textAlign = TextAlign.End)
    }
}

// MARK: - Formatting

private fun grouped(n: Int): String =
    if (abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"

/** "yyyy-MM-dd" → "EEE d MMM" for the table's day column. */
private fun shortDay(key: String): String = runCatching {
    LocalDate.parse(key).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
}.getOrDefault(key)
