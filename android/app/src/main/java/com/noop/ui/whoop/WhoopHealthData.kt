package com.noop.ui.whoop

import androidx.compose.ui.graphics.Color
import com.noop.analytics.Baselines
import com.noop.analytics.DaytimeStress
import com.noop.analytics.RustScores
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.ui.AppViewModel
import com.noop.ui.Palette
import com.noop.ui.TemperatureUnit
import com.noop.ui.UnitFormatter
import com.noop.ui.relativeDayLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// MARK: - Health page data plumbing
//
// The reads behind the Health page, the Health Monitor detail and the Stress Monitor detail. Every
// figure is a stored column or a whoop-rs result; nothing here derives a displayed number.

/** Rows pulled per intraday read — the window the stress timeline already asks for. */
private const val INTRADAY_SAMPLE_LIMIT = 200_000

/** Trailing days handed to whoop-rs as the daily-stress baseline. A window choice, not a cut point.
 *  The one window both stress reads use — the Health detail here and the Today card's StressModel. */
internal const val STRESS_BASELINE_DAYS = 30

/** The domain of whoop-rs's daily and hourly stress output, so a gauge can map it onto a fill. */
internal const val STRESS_SCALE_MAX = 3.0

/** What a metric with no stored reading prints. */
internal const val HEALTH_NO_READING = "—"

// Typical-adult fallback windows, used until the personal baseline is trusted and again whenever a
// wear gap makes it stale. Wellness bands, never clinical cut points.
private val RESP_TYPICAL_RPM = 12.0..20.0
private val SPO2_TYPICAL_PCT = 95.0..100.0
private val RHR_TYPICAL_BPM = 40.0..60.0
private val HRV_TYPICAL_MS = 40.0..120.0
private val SKIN_ABS_TYPICAL_C = 33.0..36.0
private val SKIN_DEV_TYPICAL_C = -0.6..0.6

/** The band a metric with no stored reading at all carries. */
private val NO_READING_BAND = VitalBands.Result(VitalBands.Band.NO_DATA, VitalBands.Basis.POPULATION, 0)

/**
 * One Health-Monitor vital: the newest stored reading, how to print it, when it was taken, and how it
 * bands. [key] is the metric-detail route key, so a tile taps through to the existing vital trend.
 */
internal data class HealthVital(
    val key: String,
    val label: String,
    val short: String,
    val unit: String,
    val value: Double?,
    val asOf: String?,
    val banding: VitalBands.Result,
    val format: (Double) -> String,
) {
    /** The reading with its unit, or null when the metric has no stored value at all. */
    val formatted: String? get() = value?.let { UnitFormatter.withUnit(format(it), unit) }

    /** The reading with its unit for the summary column: five numbers in five units need all five. */
    val compact: String get() = formatted ?: HEALTH_NO_READING

    /** In-range keeps the metric's own colour, out-of-range reads warning amber, no reading grey. */
    val accent: Color get() = when (banding.band) {
        VitalBands.Band.NO_DATA -> Palette.textTertiary
        VitalBands.Band.IN_RANGE -> healthVitalColor(key)
        VitalBands.Band.OUT_OF_RANGE -> Palette.statusWarning
    }

    /** Which yardstick judged the reading — your own baseline, or the typical adult range. */
    val stateCaption: String get() = when {
        banding.band == VitalBands.Band.NO_DATA -> "No reading yet"
        banding.basis == VitalBands.Basis.PERSONAL ->
            if (banding.band == VitalBands.Band.IN_RANGE) "In your range" else "Off your baseline"
        else ->
            if (banding.band == VitalBands.Band.IN_RANGE) "In typical range" else "Outside typical range"
    }

    val spoken: String get() = formatted?.let { "$label $it, $stateCaption" } ?: "$label, no reading"
}

/**
 * How many read vitals sit in their band. Home's monitor tile and the Health card both state this one
 * result, so one day cannot read as a green tick on one screen and an amber warning on the other.
 */
internal data class HealthRollUp(val inRange: Int, val read: Int) {
    val allInRange: Boolean get() = inRange == read
    val title: String get() = "$inRange/$read metrics within range"
}

/**
 * The roll-up over the vitals that HAVE a reading, so an unread metric is never counted as in range and
 * never as a flag. Null when nothing has been read at all, which is an empty state rather than a verdict.
 */
internal fun healthRollUp(vitals: List<HealthVital>): HealthRollUp? {
    val read = vitals.filter { it.banding.band != VitalBands.Band.NO_DATA }
    if (read.isEmpty()) return null
    return HealthRollUp(
        inRange = read.count { it.banding.band == VitalBands.Band.IN_RANGE },
        read = read.size,
    )
}

/**
 * The five Health-Monitor vitals, each taken from the newest [days] row that carries it, so a metric
 * banked on an older night still reads instead of blanking. Skin temp keeps the kind of the value it
 * shows — an absolute wrist temperature or a deviation from baseline — and never mixes the two.
 */
internal fun latestHealthVitals(
    days: List<DailyMetric>,
    tempUnit: TemperatureUnit,
): List<HealthVital> {
    fun latest(selector: (DailyMetric) -> Double?): Pair<String, Double>? =
        days.asReversed().firstNotNullOfOrNull { row -> selector(row)?.let { row.day to it } }

    // Nightly values strictly before the reading's own day, calendar-padded so a wear gap counts as
    // missing nights and a baseline gone stale falls back to the typical range.
    fun history(day: String?, selector: (DailyMetric) -> Double?): List<Double?> =
        VitalBands.calendarSeries(
            days.filter { day == null || it.day < day }.map { it.day to selector(it) },
        )

    val resp = latest { it.respRateBpm }
    val spo2 = latest { it.spo2Pct }
    val rhr = latest { it.restingHr?.toDouble() }
    val hrv = latest { it.avgHrv }
    val skin = latest { it.skinTempAbsC ?: it.skinTempDevC }

    val tempLabel = UnitFormatter.temperatureUnit(tempUnit)
    val skinIsAbsolute = skin?.second?.let { VitalBands.isAbsoluteSkinTemp(it) } ?: true
    val skinFormat: (Double) -> String = { c ->
        val full = if (skinIsAbsolute) {
            UnitFormatter.temperatureFromCelsius(c, tempUnit, decimals = 1)
        } else {
            UnitFormatter.temperatureDeltaFromCelsius(c, tempUnit, decimals = 1)
        }
        full.removeSuffix(" $tempLabel")
    }
    // A deviation reading has no whoop-rs MetricCfg, so it bands against the typical window only —
    // folding it through the absolute skin-temp config would read every ±°C value as implausible.
    val skinBanding = skin?.let { (day, value) ->
        VitalBands.band(
            value = value,
            history = VitalBands.skinTempHistory(
                value, history(day) { it.skinTempAbsC ?: it.skinTempDevC },
            ),
            populationRange = if (skinIsAbsolute) SKIN_ABS_TYPICAL_C else SKIN_DEV_TYPICAL_C,
            cfg = if (skinIsAbsolute) Baselines.metricCfg["skin_temp"] else null,
        )
    } ?: NO_READING_BAND

    return listOf(
        HealthVital(
            key = "resp", label = "Respiratory rate", short = "Resp", unit = "rpm",
            value = resp?.second, asOf = healthAsOfLabel(resp?.first),
            banding = VitalBands.band(
                resp?.second, history(resp?.first) { it.respRateBpm },
                RESP_TYPICAL_RPM, Baselines.respCfg,
            ),
            format = { String.format(Locale.US, "%.1f", it) },
        ),
        // SpO₂ has no MetricCfg and an absolute floor is meaningful regardless of personal history,
        // so it stays population-only — the null cfg is what disables the personal path.
        HealthVital(
            key = "spo2", label = "SpO₂", short = "SpO₂", unit = "%",
            value = spo2?.second, asOf = healthAsOfLabel(spo2?.first),
            banding = VitalBands.band(spo2?.second, emptyList(), SPO2_TYPICAL_PCT, null),
            format = { String.format(Locale.US, "%.0f", it) },
        ),
        HealthVital(
            key = "rhr", label = "Resting HR", short = "RHR", unit = "bpm",
            value = rhr?.second, asOf = healthAsOfLabel(rhr?.first),
            banding = VitalBands.band(
                rhr?.second, history(rhr?.first) { it.restingHr?.toDouble() },
                RHR_TYPICAL_BPM, Baselines.restingHRCfg,
            ),
            format = { it.roundToInt().toString() },
        ),
        HealthVital(
            key = "hrv", label = "HRV", short = "HRV", unit = "ms",
            value = hrv?.second, asOf = healthAsOfLabel(hrv?.first),
            banding = VitalBands.band(
                hrv?.second, history(hrv?.first) { it.avgHrv },
                HRV_TYPICAL_MS, Baselines.hrvCfg,
            ),
            format = { it.roundToInt().toString() },
        ),
        HealthVital(
            key = "skin",
            label = if (skinIsAbsolute) "Skin temp" else "Skin temp (from baseline)",
            short = "Temp", unit = tempLabel,
            value = skin?.second, asOf = healthAsOfLabel(skin?.first),
            banding = skinBanding,
            format = skinFormat,
        ),
    )
}

/** "as of today" / "as of 9 Jun" for a reading day; null when there is no reading. */
internal fun healthAsOfLabel(day: String?): String? {
    if (day.isNullOrBlank()) return null
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return "as of $day"
    return "as of " + relativeDayLabel(
        date, today = "today", yesterday = "yesterday",
        other = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)),
    )
}

/** "Today" / "Yesterday" / "Wed 16 Jul" for the Stress Monitor's day pager. */
internal fun stressDayLabel(day: String): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return relativeDayLabel(
        date, today = "Today", yesterday = "Yesterday",
        other = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)),
    )
}

/** "6 am" / "2 pm" for an hour-of-day on the local clock. */
internal fun stressHourLabel(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val suffix = if (h < 12) "am" else "pm"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 $suffix"
}

/** The wall-clock [start, end) seconds of the local calendar day [dayIso], clipped to [nowSeconds]. */
internal fun stressDayWindow(dayIso: String, nowSeconds: Long): Pair<Long, Long>? {
    val date = runCatching { LocalDate.parse(dayIso) }.getOrNull() ?: return null
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), nowSeconds)
    return if (end <= start) null else start to end
}

/**
 * One local day's banked HR + R-R, scored hour by hour in whoop-rs. Returns the empty read when the
 * day has too little heart rate to place a single hour on the curve.
 */
internal suspend fun loadStressDay(vm: AppViewModel, dayIso: String): DaytimeStress.Result {
    val now = System.currentTimeMillis() / 1000L
    val window = stressDayWindow(dayIso, now) ?: return DaytimeStress.Result.EMPTY
    val (from, to) = window
    val tzOffsetSeconds = TimeZone.getDefault().getOffset(from * 1_000L) / 1_000L
    val hr = vm.repo.hrSamplesUnion(from, to, limit = INTRADAY_SAMPLE_LIMIT)
    if (hr.size < DaytimeStress.minHourHrSamples) return DaytimeStress.Result.EMPTY
    val rr = vm.repo.rrIntervalsUnion(from, to, limit = INTRADAY_SAMPLE_LIMIT)
    return DaytimeStress.analyze(hr, rr, tzOffsetSeconds)
}

/** Every recorded daily stress value keyed by day, resolved over the registry read scope so every
 *  paired strap's history counts, not only the import sink's. */
internal suspend fun loadStoredStress(vm: AppViewModel): Map<String, Double> =
    runCatching { vm.repo.resolvedSeries("stress", WhoopRepository.WHOOP_SOURCE, "0000-01-01", "9999-12-31").values }
        .getOrDefault(emptyList())
        .toMap()

/**
 * The 0-3 stress of `days[index]`: the recorded value when one exists, else whoop-rs's own
 * daily_stress over the trailing baseline. Null when neither is available.
 */
internal fun dailyStressScore(
    days: List<DailyMetric>,
    stored: Map<String, Double>,
    index: Int,
): Double? {
    val row = days.getOrNull(index) ?: return null
    stored[row.day]?.let { return it.coerceIn(0.0, STRESS_SCALE_MAX) }
    val baseline = days.subList(0, index)
        .takeLast(STRESS_BASELINE_DAYS)
        .map { it.restingHr?.toDouble() to it.avgHrv }
    return RustScores.dailyStress(row.restingHr?.toDouble(), row.avgHrv, baseline)
}
