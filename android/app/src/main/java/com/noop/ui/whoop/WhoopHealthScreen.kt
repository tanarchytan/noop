package com.noop.ui.whoop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.DaytimeStress
import com.noop.analytics.RustScores
import com.noop.ui.AppViewModel
import com.noop.ui.AutoSizeValue
import com.noop.ui.DataPendingNote
import com.noop.ui.InsetChartPlaceholder
import com.noop.ui.LazyScreenScaffold
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.ProfileStore
import com.noop.ui.RealtimeHrOwner
import com.noop.ui.RealtimeHrWhileVisible
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.StressModel
import com.noop.ui.TileSparkline
import com.noop.ui.UnitPrefs
import com.noop.ui.durationText
import java.time.LocalDate
import java.util.Locale

// MARK: - Health
//
// Two tappable monitors and the non-clinical notice, in that order: the Health Monitor's five vitals
// with the illness heads-up beneath them, then the Stress Monitor's high-stress total with today's
// scored hours beside it. Both cards open their detail screen.

/**
 * The Health page. [onOpenHealthMonitor] and [onOpenStressMonitor] push the two detail screens; both
 * default to no-ops so the page previews with nothing wired.
 */
@Composable
fun WhoopHealthScreen(
    vm: AppViewModel,
    onOpenHealthMonitor: () -> Unit = {},
    onOpenStressMonitor: () -> Unit = {},
) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val alert by vm.healthAlert.collectAsStateWithLifecycle()
    val tempUnit = UnitPrefs.temperature(context)
    val profile = remember { ProfileStore.from(context.applicationContext) }

    // Live heart rate leads this page rather than hiding a level down in Health Monitor: it is the one
    // vital that changes while you are looking at it, and the page read empty without it.
    // The want is refcounted per owner, so holding the same HEALTH owner here and on the Health Monitor
    // detail is safe — the stream stays armed across the push and is released once both let go.
    RealtimeHrWhileVisible(vm, RealtimeHrOwner.HEALTH)

    // Zone bounds come from whoop-rs, so no %HRmax ladder is written here. The override pins them to
    // the profile's max HR, which is why age never keys the memo.
    val zoneSet = remember(profile.hrMax) {
        RustScores.hrZonesForAge(profile.ageYears, profile.hrMax.toDouble())
    }

    val vitals = remember(days, tempUnit) { latestHealthVitals(days, tempUnit) }

    // Today's hour-by-hour stress, scored in whoop-rs. Null while the read is in flight.
    val todayIso = remember { LocalDate.now().toString() }
    var stress by remember { mutableStateOf<DaytimeStress.Result?>(null) }
    LaunchedEffect(todayIso) {
        stress = runCatching { loadStressDay(vm, todayIso) }.getOrDefault(DaytimeStress.Result.EMPTY)
    }
    // The day's 0-3 score, through the same model the Home tile reads, so the two cannot disagree.
    var stressScore by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        stressScore = runCatching { StressModel.build(days, loadStoredStress(vm))?.score }.getOrNull()
    }

    LazyScreenScaffold(
        title = "Health",
        subtitle = "Your vital signs and today's stress load.",
    ) {
        if (days.isEmpty()) {
            item {
                DataPendingNote(
                    title = "No biometrics yet",
                    body = "Wear your strap overnight, or import a WHOOP export in Data Sources, " +
                        "and your vitals appear here.",
                )
            }
            return@LazyScreenScaffold
        }
        item { HeartRateHero(vm, zoneSet) }
        item { HealthMonitorSummaryCard(vitals, alert, onOpenHealthMonitor) }
        item { StressMonitorSummaryCard(stressScore, stress, onOpenStressMonitor) }
        item { HealthDisclaimer() }
    }
}

// MARK: - Health Monitor summary

/** The five vitals as one dense row, with the illness heads-up banner beneath when one is raised. */
@Composable
private fun HealthMonitorSummaryCard(
    vitals: List<HealthVital>,
    alert: String?,
    onOpen: () -> Unit,
) {
    NoopCard(tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            NoopCardHeader("Health Monitor", onClick = onOpen)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                vitals.forEachIndexed { index, vital ->
                    if (index > 0) ColumnRule()
                    VitalSummaryColumn(vital, Modifier.weight(1f))
                }
            }
            healthRollUp(vitals)?.let { roll -> HealthRollUpPill(roll) }
            if (alert != null) {
                StatePill(
                    title = alert,
                    tone = StrandTone.Warning,
                    icon = Icons.Filled.WarningAmber,
                    fillsWidth = true,
                )
            }
        }
    }
}

/** The roll-up as one pill. Home's monitor tile draws the same pill from the same result. */
@Composable
internal fun HealthRollUpPill(roll: HealthRollUp) {
    StatePill(
        title = roll.title,
        tone = if (roll.allInRange) StrandTone.Accent else StrandTone.Warning,
        icon = if (roll.allInRange) Icons.Filled.Check else Icons.Filled.WarningAmber,
        fillsWidth = true,
    )
}

/**
 * One vital in the summary row: its glyph, its short name, and the reading itself. The reading takes
 * the band's accent, so a value off the wearer's own baseline reads amber without a second line.
 */
@Composable
private fun VitalSummaryColumn(vital: HealthVital, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = vital.spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Icon(
            healthVitalIcon(vital.key),
            contentDescription = null,
            tint = Palette.textSecondary,
            modifier = Modifier.size(Metrics.iconSmall),
        )
        Text(
            vital.short.uppercase(),
            style = NoopType.overline,
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        // Shrinks to fit rather than clipping: a fifth of the row is narrow, and a unit clipped off is
        // the defect this line exists to fix.
        AutoSizeValue(
            text = vital.compact,
            style = NoopType.captionNumber.copy(textAlign = TextAlign.Center),
            color = vital.accent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The hairline that separates two summary columns. */
@Composable
private fun ColumnRule() {
    Box(
        modifier = Modifier
            .width(Metrics.divider)
            .height(Metrics.iconButton)
            .background(Palette.hairline),
    )
}

// MARK: - Stress Monitor summary

/**
 * The day's 0-3 score — the same quantity the Home tile and the Stress Monitor gauge read — with the
 * scored hours behind it. The hourly series only draws the sparkline; its absence never denies the score.
 */
@Composable
private fun StressMonitorSummaryCard(score: Double?, read: DaytimeStress.Result?, onOpen: () -> Unit) {
    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            NoopCardHeader("Stress Monitor", onClick = onOpen)
            val levels = read?.scored?.mapNotNull { it.level }.orEmpty()
            if (score == null) {
                InsetChartPlaceholder(
                    message = if (read == null) {
                        "Reading today's heart rate…"
                    } else {
                        "Not enough resting heart rate or HRV to score today."
                    },
                    height = Metrics.motionStripHeight + Metrics.sectionGap,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
                    ) {
                        Overline("Today's stress")
                        Text(
                            String.format(Locale.US, "%.1f", score),
                            style = NoopType.number(26f, FontWeight.Bold),
                            color = Palette.textPrimary,
                            maxLines = 1,
                        )
                        Text(
                            stressHoursCaption(levels.size, read?.highMinutes),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    if (levels.size >= 2) {
                        TileSparkline(
                            values = levels,
                            color = Palette.stressColor,
                            modifier = Modifier
                                .weight(1f)
                                .height(Metrics.motionStripHeight),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The line under the day's score: the high-stress total once an hour has been scored, else what the
 * hour-by-hour read is still short of. PURE.
 */
internal fun stressHoursCaption(scoredHours: Int, highMinutes: Long?): String = when {
    scoredHours >= 2 && highMinutes != null -> "${durationText(highMinutes.toDouble())} high stress today"
    else -> "Hour-by-hour detail needs more scored hours"
}

// MARK: - Notice

/** The non-clinical notice that closes the page. */
@Composable
private fun HealthDisclaimer() {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        HorizontalDivider(color = Palette.hairline)
        Text(
            "Health Monitor and Stress Monitor are not medical devices and cannot diagnose or " +
                "manage a medical condition. They give wellness estimates, never medical advice. " +
                "Always consult your doctor about a health concern, and never delay or change " +
                "medical care because of what you read here.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

// MARK: - Per-vital presentation

/** The glyph a vital is drawn with. Presentation only; the value behind it is stored, not derived. */
internal fun healthVitalIcon(key: String): ImageVector = when (key) {
    "resp" -> Icons.Filled.Air
    "spo2" -> Icons.Filled.WaterDrop
    "rhr" -> Icons.Filled.Favorite
    "hrv" -> Icons.Filled.GraphicEq
    "skin" -> Icons.Filled.Thermostat
    else -> Icons.Filled.MonitorHeart
}

/** The colour world a vital reads in, matching the Today tiles for the same metric. */
internal fun healthVitalColor(key: String): Color = when (key) {
    "resp", "spo2" -> Palette.metricCyan
    "rhr" -> Palette.metricRose
    "hrv" -> Palette.metricPurple
    "skin" -> Palette.metricAmber
    else -> Palette.textPrimary
}
