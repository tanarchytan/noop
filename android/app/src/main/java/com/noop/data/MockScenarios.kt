package com.noop.data

import com.noop.analytics.CircadianEngine
import com.noop.analytics.RustScores
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Builds every mock dataset that is not the default one. Two are reshapings of
 * [MockScenario.TYPICAL] (a wear gap, a strap swap) and two are hand-written tables, where each
 * day's every column is stated rather than drawn so the screen has a known answer to be checked
 * against.
 *
 * Nothing here derives a displayed metric: these are fixture inputs, the same class of value a
 * decoded strap record carries. The one threshold in play, the recovery-state edge, is read back
 * from whoop-rs ([recoveryStateEdges]) rather than written down a second time.
 */
object MockScenarios {

    // ── Gaps: offsets back from today, so 0 is today ──

    /** A single day the strap was off: no row of any kind, inside the last-7 window. */
    internal const val UNWORN_DAY = 3L
    /** A whole week off, far enough back to put a real hole in a 30-day chart. */
    internal val UNWORN_WEEK = 20L..26L
    /** A second day worn but not slept, so the state appears twice and inside the week. */
    internal const val EXTRA_UNSLEPT_DAY = 5L
    /** The night cut to [PARTIAL_ASLEEP_MIN] — worn, slept, but nothing like a full night. */
    internal const val PARTIAL_NIGHT_DAY = 2L
    internal const val PARTIAL_ASLEEP_MIN = 95.0
    internal const val PARTIAL_EFF_PCT = 85.0
    internal const val PARTIAL_DEEP_MIN = 20.0
    internal const val PARTIAL_REM_MIN = 25.0
    internal const val PARTIAL_LIGHT_MIN = 50.0

    /** Series keys a day with no night must not carry, so an unslept day is not half-scored. */
    private val NIGHT_SERIES_KEYS =
        setOf("sleep_performance", "sleep_consistency", "sleep_need_min", "sleep_debt_min", "stress")

    // ── Two straps ──

    /** The swap: this day and every older one belong to the second strap. */
    internal const val STRAP_SWAP_DAY = 3L
    private const val SECOND_STRAP_ADDRESS = "C0:FF:EE:00:00:04"
    private const val SECOND_STRAP_SERIAL = "WHOOP 4A00000004"

    // ── The tabled scenarios ──

    /** The sleep need every tabled day carries, so its performance and debt follow from one number. */
    internal const val TABLED_NEED_MIN = 480.0
    /** Local WAKE time for a tabled night. Fixing the wake rather than the onset keeps a 2-minute
     *  night and a 14-hour one both attributed to the day they end on. */
    internal const val TABLED_WAKE_HOUR = 7
    /** Resolution of the recovery-state edge scan, and therefore the step below an edge. */
    internal const val EDGE_STEP = 0.1

    private const val EXTREMES_HR_SEED = 0xE0E0E0L
    private const val BOUNDARIES_HR_SEED = 0xB0B0B0L

    /**
     * [MockScenario.TYPICAL] with holes: [UNWORN_WEEK] and [UNWORN_DAY] lose every row, so an absent
     * day is absent rather than zero; [EXTRA_UNSLEPT_DAY] keeps its day columns and loses its night;
     * [PARTIAL_NIGHT_DAY] keeps a night of [PARTIAL_ASLEEP_MIN] minutes.
     */
    internal fun gaps(base: MockDataset, today: LocalDate, zone: ZoneId): MockDataset {
        val absent = (UNWORN_WEEK.map { today.minusDays(it) } + today.minusDays(UNWORN_DAY))
            .map { it.toString() }.toSet()
        val unslept = today.minusDays(EXTRA_UNSLEPT_DAY).toString()
        val partialDay = today.minusDays(PARTIAL_NIGHT_DAY).toString()

        val original = base.sleeps.firstOrNull { localDay(it.endTs, zone) == partialDay }
        val partial = original?.let {
            val span = MockSeeder.inBedSecFor(PARTIAL_ASLEEP_MIN, PARTIAL_EFF_PCT)
            val end = it.startTs + span
            it.copy(
                endTs = end, efficiency = MockSeeder.effFraction(PARTIAL_EFF_PCT),
                stagesJSON = MockSeeder.stagesJson(
                    PARTIAL_DEEP_MIN, PARTIAL_REM_MIN, PARTIAL_LIGHT_MIN, it.startTs, end,
                ),
            )
        }

        val dropped = base.sleeps.filter { localDay(it.endTs, zone).let { d -> d in absent || d == unslept } }
        val sleeps = base.sleeps.filterNot { it in dropped }
            .map { if (partial != null && it.startTs == partial.startTs && it.deviceId == partial.deviceId) partial else it }

        var hr = base.hr.filterNot { s ->
            dropped.any { s.ts >= it.startTs - MockSeeder.HR_EDGE_PAD_SEC && s.ts <= it.endTs + MockSeeder.HR_EDGE_PAD_SEC }
        }
        // The night was shortened, so the trace past its new wake belongs to no night any more.
        if (original != null && partial != null) {
            hr = hr.filterNot { it.ts > partial.endTs + MockSeeder.HR_EDGE_PAD_SEC && it.ts <= original.endTs + MockSeeder.HR_EDGE_PAD_SEC }
        }

        val daily = base.daily.filterNot { it.day in absent }.map {
            when (it.day) {
                unslept -> MockSeeder.withoutSleep(it)
                partialDay -> it.copy(
                    totalSleepMin = PARTIAL_ASLEEP_MIN, efficiency = MockSeeder.effFraction(PARTIAL_EFF_PCT),
                    deepMin = PARTIAL_DEEP_MIN, remMin = PARTIAL_REM_MIN, lightMin = PARTIAL_LIGHT_MIN,
                )
                else -> it
            }
        }

        val series = base.series
            .filterNot { it.day in absent }
            .filterNot { it.day == unslept && it.key in NIGHT_SERIES_KEYS }
            .map { if (it.day == partialDay) partialNightSeries(it) else it }

        return base.copy(
            daily = daily,
            sleeps = sleeps,
            series = series,
            apple = base.apple.filterNot { it.day in absent },
            workouts = base.workouts.filterNot { localDay(it.startTs, zone) in absent },
            journal = base.journal.filterNot { it.day in absent },
            hr = hr,
            // A day the strap was off banked no motion either, so the rest-activity window loses that
            // day rather than reading a still one.
            gravity = base.gravity.filterNot { localDay(it.ts, zone) in absent },
            steps = base.steps.filterNot { localDay(it.ts, zone) in absent },
            skinTemp = base.skinTemp.filterNot { localDay(it.ts, zone) in absent },
        )
    }

    /** The shortened night's own figures, so its tiles agree with its stages. */
    private fun partialNightSeries(row: MetricSeriesRow): MetricSeriesRow = when (row.key) {
        "sleep_need_min" -> row.copy(value = TABLED_NEED_MIN)
        "sleep_performance" -> row.copy(value = performance(PARTIAL_ASLEEP_MIN))
        "sleep_debt_min" -> row.copy(value = TABLED_NEED_MIN - PARTIAL_ASLEEP_MIN)
        else -> row
    }

    /**
     * [MockScenario.TYPICAL] split at [STRAP_SWAP_DAY]: that day and every older one move to a second
     * WHOOP, the three newest stay on the first. Apple Health and the computed weekly scores keep
     * their own sources, so only the strap's own rows change hands.
     */
    internal fun twoStraps(base: MockDataset, today: LocalDate, zone: ZoneId): MockDataset {
        val swapDay = today.minusDays(STRAP_SWAP_DAY).toString()
        fun older(day: String) = day <= swapDay
        fun reassign(id: String, day: String) =
            if (id == MockSeeder.WHOOP && older(day)) MockSeeder.SECOND_STRAP else id

        val sleeps = base.sleeps.map { it.copy(deviceId = reassign(it.deviceId, localDay(it.endTs, zone))) }
        val moved = sleeps.filter { it.deviceId == MockSeeder.SECOND_STRAP }
        val hr = base.hr.map { s ->
            val onMoved = moved.any {
                s.ts >= it.startTs - MockSeeder.HR_EDGE_PAD_SEC && s.ts <= it.endTs + MockSeeder.HR_EDGE_PAD_SEC
            }
            if (onMoved && s.deviceId == MockSeeder.WHOOP) s.copy(deviceId = MockSeeder.SECOND_STRAP) else s
        }
        val windowStart = today.minusDays((MockSeeder.DAYS - 1).toLong())

        return base.copy(
            devices = base.devices + MockDeviceRow(MockSeeder.SECOND_STRAP, "WHOOP 4.0 (mock)"),
            pairedDevices = base.pairedDevices + PairedDeviceRow(
                id = MockSeeder.SECOND_STRAP,
                brand = "WHOOP",
                model = "WHOOP 4.0",
                nickname = null,
                peripheralId = SECOND_STRAP_ADDRESS,
                sourceKind = SourceKind.liveBLE.name,
                capabilities = "hr,hrv,spo2,skinTemp,sleep,strainLoad",
                status = DeviceStatus.paired.name,
                addedAt = windowStart.atStartOfDay(zone).toEpochSecond(),
                lastSeenAt = today.minusDays(STRAP_SWAP_DAY).atTime(TABLED_WAKE_HOUR, 0)
                    .atZone(zone).toEpochSecond(),
                serial = SECOND_STRAP_SERIAL,
            ),
            daily = base.daily.map { it.copy(deviceId = reassign(it.deviceId, it.day)) },
            sleeps = sleeps,
            series = base.series.map { it.copy(deviceId = reassign(it.deviceId, it.day)) },
            workouts = base.workouts.map {
                val day = localDay(it.startTs, zone)
                it.copy(deviceId = reassign(it.deviceId, day), source = reassign(it.source, day))
            },
            journal = base.journal.map { it.copy(deviceId = reassign(it.deviceId, it.day)) },
            hr = hr,
            // A strap's raw streams follow its days, so the active strap's rest-activity window holds
            // only the days it was actually on the wrist.
            gravity = base.gravity.map { it.copy(deviceId = reassign(it.deviceId, localDay(it.ts, zone))) },
            steps = base.steps.map { it.copy(deviceId = reassign(it.deviceId, localDay(it.ts, zone))) },
            skinTemp = base.skinTemp.map { it.copy(deviceId = reassign(it.deviceId, localDay(it.ts, zone))) },
        )
    }

    /**
     * Both ends of every scale on adjacent days: today is the ceiling (100 recovery, 100 effort, a
     * 14-hour night at 100% efficiency, 40,000 steps), yesterday the floor (0, 0, a 2-minute night,
     * no steps), then a day with no night and a day slept to exactly the need.
     */
    internal fun extremes(today: LocalDate, zone: ZoneId): MockDataset {
        val rows = listOf(
            FixtureDay(
                back = 0, asleep = 840.0, eff = 100.0, deep = 200.0, rem = 240.0, light = 400.0,
                rhr = 42, hrv = 150.0, recovery = 100.0, strain = 100.0, disturbances = 0,
                spo2 = 100.0, skinTempDev = 1.4, resp = 19.0, steps = 40_000, activeKcal = 4000.0,
                vo2max = 56.0, stress = 0.0, bodyFat = 10.0, consistency = 100.0,
            ),
            FixtureDay(
                back = 1, asleep = 2.0, eff = 5.0, deep = 0.0, rem = 0.0, light = 2.0,
                rhr = 70, hrv = 28.0, recovery = 0.0, strain = 0.0, disturbances = 18,
                spo2 = 93.0, skinTempDev = -1.2, resp = 11.0, steps = 0, activeKcal = 0.0,
                vo2max = 38.0, stress = 3.0, bodyFat = 24.0, consistency = 40.0,
                workouts = listOf(FixtureWorkout("Yoga", 12, 60.0, 0.0, 60, 60, 0.0, null)),
            ),
            FixtureDay(back = 2, asleep = null, recovery = 50.0, strain = 50.0, steps = 12_000),
            FixtureDay(
                back = 3, asleep = TABLED_NEED_MIN, eff = 90.0, deep = 100.0, rem = 110.0, light = 270.0,
                recovery = 50.0, strain = 50.0,
                // The longest and shortest sessions sit on finished days: a tabled day is written whole,
                // and today's later hours have not happened yet.
                workouts = listOf(
                    FixtureWorkout("Cycling", 9, 21_600.0, 3000.0, 165, 205, 100.0, 180_000.0),
                ),
            ),
        ) + (4L..13L).map { FixtureDay(back = it) }
        return tabled(rows, today, zone, EXTREMES_HR_SEED)
            .let { it.copy(series = it.series + weekly(today, zone, rows.size, EXTREMES_WEEKLY_NEWEST, EXTREMES_WEEKLY_OLDER)) }
    }

    /**
     * A day sitting exactly on each recovery-state edge and a day one [EDGE_STEP] below it, plus the
     * two ends of the scale and a night slept to exactly the need. The edges are read back from
     * whoop-rs, so this fixture holds no copy of a threshold.
     *
     * It carries the OTHER edge nothing else covers: one day short of the rest-activity worn-day floor,
     * so the Body Clock and Rhythm Age cards are exercised counting up ("6 of 7 days") rather than
     * naming a year. [MockScenario.TYPICAL] sits above that floor and [MockScenario.EXTREMES] seeds no
     * motion at all, so the three states a motion-derived card has are each on a scenario.
     */
    internal fun boundaries(today: LocalDate, zone: ZoneId, nowSec: Long): MockDataset {
        val scores = buildList {
            add(100.0)
            for (e in recoveryStateEdges().asReversed()) {
                add(e)
                add(MockSeeder.round1(e - EDGE_STEP))
            }
            add(0.0)
        }
        val rows = scores.mapIndexed { i, score -> FixtureDay(back = i.toLong(), recovery = score) } +
            FixtureDay(
                back = scores.size.toLong(), asleep = TABLED_NEED_MIN, eff = 90.0,
                deep = 100.0, rem = 110.0, light = 270.0,
            )
        return tabled(rows, today, zone, BOUNDARIES_HR_SEED)
            .let { it.copy(series = it.series + weekly(today, zone, rows.size, BOUNDARIES_WEEKLY, BOUNDARIES_WEEKLY)) }
            .let { ds ->
                val raw = MockSeeder.rawStreams(
                    MockSeeder.WHOOP, ds.daily, ds.sleeps, ds.apple, today, zone, nowSec,
                    days = CircadianEngine.MIN_WORN_DAYS - 1,
                )
                ds.copy(gravity = raw.gravity, steps = raw.steps, skinTemp = raw.skinTemp)
            }
    }

    /**
     * The recovery scores at which the whoop-rs state band changes, discovered by walking the scale
     * rather than declared here, so a fixture can never become a second source of truth for a cut point.
     */
    internal fun recoveryStateEdges(): List<Double> {
        val out = ArrayList<Double>()
        var prev = RustScores.state(0.0)
        var step = 1
        while (step <= 1000) {
            val x = MockSeeder.round1(step * EDGE_STEP)
            val state = RustScores.state(x)
            if (state != prev) {
                out.add(x)
                prev = state
            }
            step++
        }
        return out
    }

    // MARK: - the tabled-day machinery

    /** One hand-set day: every column stated, nothing drawn. A null [asleep] is a day nobody slept. */
    private data class FixtureDay(
        val back: Long,
        val asleep: Double? = 420.0,
        val eff: Double = 85.0,
        val deep: Double = 90.0,
        val rem: Double = 100.0,
        val light: Double = 230.0,
        val rhr: Int = 58,
        val hrv: Double = 70.0,
        val recovery: Double = 45.0,
        val strain: Double = 40.0,
        val disturbances: Int = 4,
        val spo2: Double = 96.5,
        val skinTempDev: Double = 0.0,
        val resp: Double = 14.6,
        val steps: Int = 7_000,
        val activeKcal: Double = 400.0,
        val vo2max: Double = 46.0,
        val stress: Double = 1.5,
        val bodyFat: Double = 18.0,
        val consistency: Double = 80.0,
        val workouts: List<FixtureWorkout> = emptyList(),
    )

    /** One hand-set workout on a tabled day, started at [startHour] local. */
    private data class FixtureWorkout(
        val sport: String,
        val startHour: Int,
        val durationS: Double,
        val kcal: Double,
        val avgHr: Int,
        val maxHr: Int,
        val strain: Double,
        val distanceM: Double?,
    )

    private val EXTREMES_WEEKLY_NEWEST =
        listOf("fitness_age" to 34.0, "vo2max_est" to 52.0, "vitality" to 100.0, "body_age" to 30.0)
    private val EXTREMES_WEEKLY_OLDER =
        listOf("fitness_age" to 44.0, "vo2max_est" to 42.0, "vitality" to 0.0, "body_age" to 45.0)
    private val BOUNDARIES_WEEKLY =
        listOf("fitness_age" to 40.0, "vo2max_est" to 46.0, "vitality" to 50.0, "body_age" to 40.0)

    private val JOURNAL_QUESTIONS = listOf("Any alcohol?", "Caffeine after 4pm?", "Felt stressed?")

    /** Turn a table of days into a dataset. Newest first in, chronological out. */
    private fun tabled(rows: List<FixtureDay>, today: LocalDate, zone: ZoneId, hrSeed: Long): MockDataset {
        val daily = ArrayList<DailyMetric>(rows.size)
        val sleeps = ArrayList<SleepSession>(rows.size)
        val series = ArrayList<MetricSeriesRow>(rows.size * 8)
        val apple = ArrayList<AppleDaily>(rows.size)
        val workouts = ArrayList<WorkoutRow>()
        val journal = ArrayList<JournalEntry>(rows.size * JOURNAL_QUESTIONS.size)

        for (r in rows.sortedByDescending { it.back }) {
            val date = today.minusDays(r.back)
            val day = date.toString()
            val full = DailyMetric(
                deviceId = MockSeeder.WHOOP, day = day,
                totalSleepMin = r.asleep, efficiency = MockSeeder.effFraction(r.eff),
                deepMin = r.deep, remMin = r.rem, lightMin = r.light,
                disturbances = r.disturbances, restingHr = r.rhr, avgHrv = r.hrv,
                recovery = r.recovery, strain = r.strain, exerciseCount = r.workouts.size,
                spo2Pct = r.spo2, skinTempDevC = r.skinTempDev,
                skinTempAbsC = MockSeeder.round2(34.0 + r.skinTempDev), respRateBpm = r.resp,
            )
            daily.add(if (r.asleep == null) MockSeeder.withoutSleep(full) else full)

            if (r.asleep != null) {
                val wake = date.atTime(TABLED_WAKE_HOUR, 0).atZone(zone).toEpochSecond()
                val onset = wake - MockSeeder.inBedSecFor(r.asleep, r.eff)
                sleeps.add(
                    SleepSession(
                        deviceId = MockSeeder.WHOOP, startTs = onset, endTs = wake,
                        efficiency = MockSeeder.effFraction(r.eff), restingHr = r.rhr, avgHrv = r.hrv,
                        stagesJSON = MockSeeder.stagesJson(r.deep, r.rem, r.light, onset, wake),
                    )
                )
                series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "sleep_need_min", TABLED_NEED_MIN))
                series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "sleep_performance", performance(r.asleep)))
                series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "sleep_debt_min",
                    (TABLED_NEED_MIN - r.asleep).coerceAtLeast(0.0)))
                series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "sleep_consistency", r.consistency))
                series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "stress", r.stress))
            }
            series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "weightKg", 79.5))
            series.add(MetricSeriesRow(MockSeeder.WHOOP, day, "bodyFatPct", r.bodyFat))

            apple.add(
                AppleDaily(
                    deviceId = MockSeeder.APPLE, day = day,
                    steps = r.steps, activeKcal = r.activeKcal, basalKcal = 1650.0,
                    vo2max = r.vo2max, avgHr = 72, maxHr = 150, walkingHr = 108, weightKg = 79.5,
                )
            )

            for (w in r.workouts) {
                val start = date.atTime(w.startHour, 0).atZone(zone).toEpochSecond()
                workouts.add(
                    WorkoutRow(
                        deviceId = MockSeeder.WHOOP, startTs = start,
                        endTs = start + w.durationS.toLong(), sport = w.sport, source = MockSeeder.WHOOP,
                        durationS = w.durationS, energyKcal = w.kcal,
                        avgHr = w.avgHr, maxHr = w.maxHr, strain = w.strain, distanceM = w.distanceM,
                        zonesJSON = """{"zone1":20.0,"zone2":20.0,"zone3":20.0,"zone4":20.0,"zone5":20.0}""",
                        notes = null,
                    )
                )
            }

            JOURNAL_QUESTIONS.forEachIndexed { q, question ->
                journal.add(JournalEntry(MockSeeder.WHOOP, day, question, (r.back + q) % 3L == 0L))
            }
        }

        val rng = Random(hrSeed)
        return MockDataset(
            devices = listOf(MockDeviceRow(MockSeeder.WHOOP, "WHOOP (mock)")),
            daily = daily, sleeps = sleeps, series = series, apple = apple,
            workouts = workouts, journal = journal,
            hr = sleeps.flatMap { MockSeeder.nightHrSamples(rng, it) },
        )
    }

    /** The computed weekly scores, stamped on each Saturday in the window the tabled days cover. */
    private fun weekly(
        today: LocalDate,
        zone: ZoneId,
        days: Int,
        newest: List<Pair<String, Double>>,
        older: List<Pair<String, Double>>,
    ): List<MetricSeriesRow> {
        val saturdays = (0 until days).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value == 6 }
        return saturdays.mapIndexed { i, date ->
            (if (i == 0) newest else older).map { (key, value) ->
                MetricSeriesRow(MockSeeder.WHOOP_NOOP, date.toString(), key, value)
            }
        }.flatten()
    }

    /** Sleep performance for a tabled night: asleep over [TABLED_NEED_MIN], never above 100. */
    internal fun performance(asleepMin: Double): Double =
        MockSeeder.round1((asleepMin / TABLED_NEED_MIN * 100.0).coerceAtMost(100.0))

    /** The local day an instant falls on — how a night (by its wake) and a workout (by its start)
     *  are attributed to a day. */
    internal fun localDay(ts: Long, zone: ZoneId): String =
        Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()
}
