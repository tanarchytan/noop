package com.noop.ui.whoop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.CalendarDay
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ingest.HealthConnectImporter
import com.noop.ui.AppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.StressModel
import com.noop.ui.freshRestScore
import com.noop.ui.lastEffortRow
import com.noop.ui.lastScoredRecoveryDay
import com.noop.ui.lastSkinTempRow
import com.noop.ui.lastSpo2Row
import com.noop.ui.lastVitalsRow
import com.noop.ui.lastWorkoutsFeed
import com.noop.ui.latestWeightKg
import com.noop.ui.logicalDayNow
import com.noop.ui.recoveryCalibrationNights
import com.noop.ui.scoreHeroSourceLabel
import com.noop.ui.stepsForDay
import com.noop.ui.workoutsAllSources
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Home page state
//
// Everything WhoopHomeScreen renders for ONE selected day. Every field is a stored value, a
// whoop-rs-scored value, or a pure selection over the view model's day list — nothing is derived here.

/** The widest history bound the resolved-series reads use, matching the rest of the app. */
private const val SERIES_FROM = "0000-01-01"
private const val SERIES_TO = "9999-12-31"

/** The three scores the hero rings show, and so the three the provenance badge names. */
private val HERO_SCORE_KEYS = listOf("recovery", "strain", "sleep_performance")

internal data class WhoopHomeState(
    /** The calendar date on screen (offset back from the logical day, which rolls at 04:00). */
    val date: LocalDate,
    /** The `yyyy-MM-dd` key of the row actually surfaced for [date]. */
    val dayKey: String,
    /** "Today" / "Yesterday" / the weekday name. */
    val title: String,
    /** "Friday, 3 July". */
    val subtitle: String,
    val isToday: Boolean,
    val metric: DailyMetric?,
    val days: List<DailyMetric>,
    /** The nearest earlier banked row, for a row's comparison caption. */
    val previousDay: DailyMetric?,
    /** whoop-rs `sleep_performance` for the day, freshness-gated. */
    val restScore: Double?,
    /** The day's Effort on the STORED 0-100 axis; the screen maps it to the display scale. */
    val effort: Double?,
    val calibratingNights: Int?,
    val carriedDay: DailyMetric?,
    val vitalsDay: DailyMetric?,
    val spo2Day: DailyMetric?,
    val skinTempDay: DailyMetric?,
    val importedSteps: Int?,
    val estimatedSteps: Int?,
    val activeKcal: Double?,
    val stress: Double?,
    val fitnessAge: Double?,
    val vitality: Double?,
    val activities: List<WorkoutRow>,
    /** The newest imported body weight, or null when no source carries one. */
    val latestWeightKg: Double?,
    /** The profile's own weight, the Weight tile's fallback when nothing was imported. */
    val profileWeightKg: Double,
    /** The card-level badge naming the sources behind the three hero scores; null when none resolved. */
    val sourceLabel: String?,
    val healthAlert: String?,
    val connected: Boolean,
    val batteryPct: Double?,
    val alarmEnabled: Boolean,
    val alarmMinutes: Int,
)

/**
 * Resolve the Home page's inputs for the day [dayOffset] days back. Mirrors the loaders the Today lane
 * already runs (and reuses the view model's own re-mount caches), so opening this screen costs the same
 * reads and shows the same numbers.
 */
@Composable
internal fun rememberWhoopHomeState(viewModel: AppViewModel, dayOffset: Int): WhoopHomeState {
    val context = LocalContext.current
    val today by viewModel.today.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val alert by viewModel.healthAlert.collectAsStateWithLifecycle()
    val alarmEnabled by viewModel.smartAlarmEnabled.collectAsStateWithLifecycle()
    val alarmMinutes by viewModel.smartAlarmMinutes.collectAsStateWithLifecycle()

    // Offset 0 anchors on the LOGICAL day, so between midnight and 04:00 "Today" still resolves to the
    // prior calendar day's banked row instead of an empty new one.
    val anchor = logicalDayNow()
    val date = remember(dayOffset, anchor) { anchor.minusDays(dayOffset.toLong()) }
    val dayKey = remember(date, today, dayOffset) {
        if (dayOffset == 0) today?.day ?: date.toString() else date.toString()
    }
    val historical = remember(days, dayKey) { days.lastOrNull { it.day == dayKey } }
    val metric = remember(today, historical, dayOffset) {
        if (dayOffset == 0) today ?: historical else historical
    }
    val previousDay = remember(days, dayKey) { days.lastOrNull { it.day < dayKey } }
    val labelDate = remember(dayKey, date) {
        runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: date
    }
    val todayWord = stringResource(R.string.common_today)
    val yesterdayWord = stringResource(R.string.whoopskin_yesterday)
    val title = remember(dayOffset, labelDate, todayWord, yesterdayWord) {
        when (dayOffset) {
            0 -> todayWord
            1 -> yesterdayWord
            else -> labelDate.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        }
    }
    val subtitle = remember(labelDate) {
        labelDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US))
    }

    // A future-dated row (a bad strap clock) must never win the carry, so bound it by the later of the
    // logical day and the local calendar day. ISO date strings compare chronologically.
    val carryBound = remember(anchor) { maxOf(anchor.toString(), LocalDate.now().toString()) }
    val calibratingNights = if (dayOffset == 0) {
        val epoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble()
        recoveryCalibrationNights(days, metric?.recovery != null, epoch)
    } else {
        null
    }
    val carriedDay = remember(days, dayKey, calibratingNights, dayOffset, metric, carryBound) {
        lastScoredRecoveryDay(
            days = days,
            selectedDayKey = dayKey,
            isToday = dayOffset == 0,
            todayScored = metric?.recovery != null,
            isCalibrating = calibratingNights != null,
            today = carryBound,
        )
    }
    val vitalsDay = remember(days, carryBound, dayOffset, metric) {
        if (dayOffset == 0) lastVitalsRow(days, maxOf(metric?.day ?: "", carryBound)) else null
    }
    val spo2Day = remember(days, carryBound, dayOffset, metric) {
        if (dayOffset == 0) lastSpo2Row(days, maxOf(metric?.day ?: "", carryBound)) else null
    }
    val skinTempDay = remember(days, carryBound, dayOffset, metric) {
        if (dayOffset == 0) lastSkinTempRow(days, maxOf(metric?.day ?: "", carryBound)) else null
    }

    var restScore by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days, dayKey, dayOffset) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("sleep_performance", "my-whoop", SERIES_FROM, SERIES_TO)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        val latest = byDay.entries.maxByOrNull { it.key }
        restScore = freshRestScore(
            todayValue = byDay[dayKey],
            lastDay = latest?.key,
            lastValue = latest?.value,
            isTodaySelected = dayOffset == 0,
            today = dayKey,
        )
    }

    // Today's Effort accrues through the day, so the stored row lags until the daily pass re-scores.
    // Integrate today's own HR window through whoop-rs and floor the result at what is already banked.
    var liveStrain by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days, dayKey, dayOffset) {
        liveStrain = if (dayOffset != 0) {
            null
        } else {
            runCatching {
                val profile = ProfileStore.from(context)
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).toEpochSecond()
                val now = System.currentTimeMillis() / 1000
                val hr = viewModel.repo.hrSamplesUnion(start, now)
                val maxHr = profile.hrMaxOverride.takeIf { it > 0 }?.toDouble()
                    ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.ageYears) else null
                StrainScorer.strain(
                    hr = hr,
                    maxHR = maxHr,
                    restingHR = metric?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR,
                    sex = profile.sex,
                )
            }.getOrNull()
        }
    }
    // Charge carries a prior scored day forward when today has none, so Effort does too — otherwise a
    // store whose newest day is older than today shows one ring filled and the other empty, and the
    // empty one reads as "Effort never imported". Today's own value always wins where it exists.
    val effortDay = remember(days, carryBound, dayOffset, metric) {
        if (dayOffset == 0) lastEffortRow(days, maxOf(metric?.day ?: "", carryBound)) else null
    }
    val effort = run {
        val stored = metric?.strain ?: effortDay?.strain
        val live = liveStrain
        if (live != null && stored != null) maxOf(live, stored) else (live ?: stored)
    }

    var importedSteps by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days, dayKey, dayOffset) {
        // Today's step count keeps moving after a one-shot import, so top the stored row up first.
        if (dayOffset == 0) {
            runCatching { HealthConnectImporter.refreshTodaySteps(context, viewModel.repo) }
        }
        importedSteps = runCatching {
            stepsForDay(
                viewModel.repo.appleDaily("apple-health", SERIES_FROM, SERIES_TO),
                viewModel.repo.appleDaily("health-connect", SERIES_FROM, SERIES_TO),
                dayKey,
            )
        }.getOrNull()
    }

    var estimatedSteps by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days, dayKey) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("steps_est", "my-whoop", SERIES_FROM, SERIES_TO)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        estimatedSteps = byDay[dayKey]?.let { Math.round(it).toInt() }
    }

    var activeKcal by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        activeKcal = runCatching {
            val imported = LinkedHashMap<String, Double>()
            for (r in viewModel.repo.appleDaily("apple-health", SERIES_FROM, SERIES_TO) +
                viewModel.repo.appleDaily("health-connect", SERIES_FROM, SERIES_TO)
            ) {
                r.activeKcal?.let { imported.putIfAbsent(r.day, it) }
            }
            val est = viewModel.repo.resolvedSeries("active_kcal", "my-whoop", SERIES_FROM, SERIES_TO)
                .points.associate { it.day to it.value }
            (imported.keys + est.keys).maxOrNull()?.let { imported[it] ?: est[it] }
        }.getOrNull()
    }

    // The three whole-history reads share the view model's re-mount guard, so a tab-return restores
    // them instead of re-scanning.
    var stress by remember { mutableStateOf(viewModel.todayStressCache) }
    var fitnessAge by remember { mutableStateOf(viewModel.todayFitnessAgeCache) }
    var vitality by remember { mutableStateOf(viewModel.todayVitalityCache) }
    LaunchedEffect(days) {
        val sig = days.hashCode()
        if (viewModel.todayCardsLoadedSig == sig) return@LaunchedEffect
        stress = runCatching {
            val stored = viewModel.repo.resolvedSeries("stress", WhoopRepository.WHOOP_SOURCE, SERIES_FROM, SERIES_TO)
                .values.toMap()
            StressModel.build(days, stored)?.score
        }.getOrNull()
        fitnessAge = runCatching { viewModel.repo.latestMetricComputedUnion("fitness_age")?.value }.getOrNull()
        vitality = runCatching { viewModel.repo.latestMetricComputedUnion("vitality")?.value }.getOrNull()
        viewModel.todayStressCache = stress
        viewModel.todayFitnessAgeCache = fitnessAge
        viewModel.todayVitalityCache = vitality
        viewModel.todayCardsLoadedSig = sig
    }

    var activities by remember { mutableStateOf<List<WorkoutRow>>(emptyList()) }
    LaunchedEffect(days, dayKey) {
        activities = runCatching {
            val start = date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            lastWorkoutsFeed(
                viewModel.repo.workoutsAllSources(
                    viewModel.deviceId, start, start + CalendarDay.SECONDS_PER_DAY,
                ),
            )
        }.getOrDefault(emptyList())
    }

    // The card-level provenance badge: the resolver's winning source per hero score for the day on
    // screen, plus the source behind a carried Charge, so the badge names what actually supplied them.
    var provenanceByMetric by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(days, dayKey, viewModel.activeStrapId) {
        val resolved = mutableMapOf<String, String>()
        for (key in HERO_SCORE_KEYS) {
            val win = runCatching {
                viewModel.repo.resolvedSeries(key, "my-whoop", dayKey, dayKey)
                    .points.lastOrNull { it.day == dayKey }?.source
            }.getOrNull()
            if (win != null) resolved[key] = win
        }
        provenanceByMetric = resolved
    }
    var carriedRecoverySource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(carriedDay?.day, viewModel.activeStrapId) {
        val carried = carriedDay?.day
        carriedRecoverySource = if (carried == null) {
            null
        } else {
            runCatching {
                viewModel.repo.resolvedSeries("recovery", "my-whoop", carried, carried)
                    .points.lastOrNull { it.day == carried }?.source
            }.getOrNull()
        }
    }
    val sourceLabel = remember(provenanceByMetric, carriedRecoverySource, metric?.recovery, carriedDay) {
        scoreHeroSourceLabel(
            provenanceByMetric = provenanceByMetric,
            carriedRecoverySource = carriedRecoverySource,
            usesCarriedRecovery = metric?.recovery == null && carriedDay != null,
            deviceId = viewModel.activeStrapId,
        )
    }

    // The Key-Metrics grid's Weight tile reads the newest imported body weight, falling back to the
    // profile figure the user entered; both come from stores this page already opened.
    var weightKg by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        weightKg = runCatching {
            latestWeightKg(
                viewModel.repo.appleDaily("apple-health", SERIES_FROM, SERIES_TO),
                viewModel.repo.appleDaily("health-connect", SERIES_FROM, SERIES_TO),
            )
        }.getOrNull()
    }

    return WhoopHomeState(
        date = date,
        dayKey = dayKey,
        title = title,
        subtitle = subtitle,
        isToday = dayOffset == 0,
        metric = metric,
        days = days,
        previousDay = previousDay,
        restScore = restScore,
        effort = effort,
        calibratingNights = calibratingNights,
        carriedDay = carriedDay,
        vitalsDay = vitalsDay,
        spo2Day = spo2Day,
        skinTempDay = skinTempDay,
        importedSteps = importedSteps,
        estimatedSteps = estimatedSteps,
        activeKcal = activeKcal,
        stress = stress,
        fitnessAge = fitnessAge,
        vitality = vitality,
        activities = activities,
        latestWeightKg = weightKg,
        profileWeightKg = ProfileStore.from(context).weightKg,
        sourceLabel = sourceLabel,
        healthAlert = alert,
        connected = live.connected,
        batteryPct = live.batteryPct,
        alarmEnabled = alarmEnabled,
        alarmMinutes = alarmMinutes,
    )
}
