package com.noop.data

import com.noop.analytics.StrainScorer
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds the mock flavour's synthetic datasets so every screen (Today, Sleep, Trends, Workouts,
 * Health, Stress, Insights, Explore, Compare, Apple Health) has data with no strap or import needed.
 * Gated by the caller to `BuildConfig.ENABLE_MOCK`.
 *
 * WHICH dataset is [MockScenario]'s; the rows are built in memory first ([build]) and written after,
 * so a scenario can be asserted without a database. Every scenario is deterministic — one fixed seed
 * or an explicit table, no wall-clock randomness — so the same day produces the same rows on every
 * install. [MockScenario.TYPICAL] is additionally physiologically plausible and internally correlated
 * (recovery ↔ HRV ↔ resting-HR ↔ sleep; strain ↔ workouts; a slow fitness drift over the window).
 */
object MockSeeder {

    internal const val WHOOP = "my-whoop"
    internal const val APPLE = "apple-health"
    // NOOP-COMPUTED strap source ("<strap>-noop") holding IntelligenceEngine's derived weekly scores
    // (fitness_age / vo2max_est / vitality / body_age). The computed UNION resolves these under
    // "my-whoop-noop" for the mock, so seeding here (not under "my-whoop") is required or those cards read empty.
    internal const val WHOOP_NOOP = "$WHOOP-noop"
    internal const val DAYS = 120

    /** The mock's second paired device, an Oura ring, and the second WHOOP strap
     *  [MockScenario.TWO_STRAPS] hands the older half of the window to. */
    internal const val MOCK_OURA = "mock-oura-ring"
    internal const val SECOND_STRAP = "mock-whoop-4"

    /** The one day in the window the wearer did not sleep at all: yesterday, so today carries the night
     *  that follows it and both states are on screen. Fixed, never drawn, so every install matches. */
    internal const val UNSLEPT_DAY_INDEX = DAYS - 2

    /** Local bedtime on the evening sleep resumes, earlier than the usual ~23:10 so that night's start
     *  day and end day differ. */
    internal const val CRASH_OUT_HOUR = 20
    internal const val CRASH_OUT_MINUTE = 41

    /** Nights (newest last) that get per-minute HR, so the Sleep hero's HR chart has a real trace. The
     *  whole 120-day window at this cadence is six figures of rows, which is not a fixture. */
    internal const val HR_NIGHTS = 21
    /** Seconds between seeded HR samples, and how far either side of a night they run. */
    internal const val HR_STEP_SEC = 60L
    internal const val HR_EDGE_PAD_SEC = 1_800L

    /** Effort rescale factor: WHOOP's 0–21 Day Strain axis → our Effort axis. Read from the one owner
     *  rather than restated, so a seeded Effort cannot land on a different scale than an imported one. */
    private val STRAIN_SCALE = StrainScorer.whoopDayStrainToEffort

    private val SPORTS = listOf(
        "Running", "Cycling", "Strength", "HIIT", "Swimming", "Yoga", "Walking", "Rowing"
    )

    /** Every source id a scenario may write rows under, so switching scenarios can clear the last one. */
    private val DATA_IDS = listOf(WHOOP, WHOOP_NOOP, APPLE, SECOND_STRAP, MOCK_OURA)

    /** The registry rows the mock adds on top of the migration-seeded WHOOP, removed on a switch so a
     *  scenario never inherits the previous one's device list. */
    private val ADDED_REGISTRY_IDS = listOf(MOCK_OURA, SECOND_STRAP)

    /**
     * Hold [scenario]'s dataset. A no-op when it is already the seeded one; otherwise every mock row
     * from the previous scenario is cleared first, so a switch never blends two datasets. Returns true
     * when rows were (re)written, which is the caller's cue that the screens on display are stale.
     *
     * [seededId] is the scenario id last written, persisted by the caller. Null means nothing has been
     * seeded yet, which is also what a fresh install reads.
     */
    suspend fun seedScenario(
        repo: WhoopRepository,
        registry: DeviceRegistry,
        scenario: MockScenario,
        seededId: String?,
    ): Boolean {
        val seeded = MockScenario.forId(seededId)
        if (seeded == scenario && (scenario == MockScenario.EMPTY || repo.days(WHOOP).isNotEmpty())) return false
        // An unrecorded scenario on a store that already holds the default dataset is the pre-scenario
        // install: it is TYPICAL, so recognising it here keeps an upgrade from pointlessly reseeding.
        if (seeded == null && scenario == MockScenario.TYPICAL && repo.days(WHOOP).isNotEmpty()) return false
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val ds = build(scenario, today, zone)
        wipe(registry)
        if (scenario.seedsData) seedMockRing(registry)
        write(repo, registry, ds)
        // The dataset's own expected values, so a walk reads them off `adb logcat -s MockSeeder`
        // instead of judging by eye whether a number looks plausible.
        for (c in MockClaims.of(scenario, ds, today, zone)) {
            android.util.Log.i("MockSeeder", "${scenario.id}: $c")
        }
        return true
    }

    /**
     * Mock-only: adds a second paired device (an Oura ring) so Devices shows the WHOOP (Active)
     * alongside a paired ring, no real hardware needed. Only runs if the registry currently holds
     * exactly the WHOOP, so it fires once and never clobbers a real pairing. Status `paired` (not
     * active) keeps SourceCoordinator dormant on the WHOOP.
     */
    private suspend fun seedMockRing(registry: DeviceRegistry) {
        val devices = registry.all()
        if (devices.size != 1) return
        if (!isWhoop(devices.first())) return
        val now = System.currentTimeMillis() / 1000
        registry.add(
            PairedDeviceRow(
                id = MOCK_OURA,
                brand = "Oura",
                model = "Oura Ring 3",
                nickname = null,
                sourceKind = SourceKind.oura.name,
                capabilities = "hr,hrv,sleep,skinTemp",
                status = DeviceStatus.paired.name,
                addedAt = now,
                // A plausible "Last seen 3h ago" so the card's last-seen line reads naturally in the mock.
                lastSeenAt = now - 3 * 3600,
            ),
        )
    }

    /** Local WHOOP check, kept here so MockSeeder (data layer) needn't import the BLE-layer coordinator. */
    private fun isWhoop(d: PairedDeviceRow): Boolean =
        d.id == WHOOP || d.brand.equals("WHOOP", ignoreCase = true)

    /** Clear every row the mock writes, and drop the registry rows it adds, so the next scenario
     *  starts from the fresh-install state rather than the previous dataset. */
    private suspend fun wipe(registry: DeviceRegistry) {
        for (id in DATA_IDS) registry.deleteDeviceData(id)
        for (id in ADDED_REGISTRY_IDS) registry.delete(id)
    }

    /** Persist a built dataset. Nothing here derives a value — [build] already decided every row. */
    private suspend fun write(repo: WhoopRepository, registry: DeviceRegistry, ds: MockDataset) {
        for (d in ds.devices) repo.upsertDevice(d.id, name = d.name)
        for (p in ds.pairedDevices) registry.add(p)
        if (ds.daily.isNotEmpty()) repo.upsertDailyMetrics(ds.daily)
        if (ds.sleeps.isNotEmpty()) repo.upsertSleepSessions(ds.sleeps)
        if (ds.hr.isNotEmpty()) repo.insertHr(ds.hr)
        if (ds.series.isNotEmpty()) repo.upsertMetricSeries(ds.series)
        if (ds.apple.isNotEmpty()) repo.upsertAppleDaily(ds.apple)
        if (ds.workouts.isNotEmpty()) repo.upsertWorkouts(ds.workouts)
        if (ds.journal.isNotEmpty()) repo.upsertJournal(ds.journal)
    }

    /**
     * One scenario's rows, in memory. Pure: the same [scenario] on the same [today] in [zone] always
     * produces the same dataset, so a test can assert it and two runs can be diffed.
     */
    internal fun build(
        scenario: MockScenario,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): MockDataset = when (scenario) {
        MockScenario.TYPICAL -> typical(today, zone, nowSec)
        MockScenario.GAPS -> MockScenarios.gaps(typical(today, zone, nowSec), today, zone)
        MockScenario.EMPTY -> MockDataset()
        MockScenario.EXTREMES -> MockScenarios.extremes(today, zone)
        MockScenario.BOUNDARIES -> MockScenarios.boundaries(today, zone)
        MockScenario.TWO_STRAPS -> MockScenarios.twoStraps(typical(today, zone, nowSec), today, zone)
    }

    /**
     * The default dataset: [DAYS] correlated days ending on [today], one of them unslept. A session
     * that would end after [nowSec] is not written at all: today is in progress, and a completed
     * workout four hours from now is a claim the app cannot make.
     */
    private fun typical(today: LocalDate, zone: ZoneId, nowSec: Long): MockDataset {
        val rng = Random(0xC0FFEE)
        val startDay = today.minusDays((DAYS - 1).toLong())

        val daily = ArrayList<DailyMetric>(DAYS)
        val sleeps = ArrayList<SleepSession>(DAYS)
        val series = ArrayList<MetricSeriesRow>(DAYS * 2)
        val apple = ArrayList<AppleDaily>(DAYS)
        val workouts = ArrayList<WorkoutRow>()
        val journal = ArrayList<JournalEntry>()

        var weight = 79.5
        var fitness = 0.0 // slow upward drift: HRV rises, resting-HR falls, VO2max climbs

        for (i in 0 until DAYS) {
            val date = startDay.plusDays(i.toLong())
            val day = date.toString() // ISO yyyy-MM-dd
            val weekend = date.dayOfWeek.value >= 6
            fitness += 0.012

            // --- training load for the day ---
            val trains = if (weekend) rng.nextDouble() < 0.40 else rng.nextDouble() < 0.62
            val nWorkouts = if (!trains) 0 else if (rng.nextDouble() < 0.22) 2 else 1

            // --- sleep architecture ---
            // ONE night, ONE set of figures. The rounded stage minutes below are the night's definition:
            // the daily columns and the session's per-epoch segments are both written from them, so the
            // hero card, the tiles, the weekly charts and the fused record decode the same night.
            val drawnSleep = gauss(rng, 430.0, 35.0).coerceIn(300.0, 540.0)
            val efficiency = gauss(rng, 89.0, 4.0).coerceIn(72.0, 98.0)
            val deep = round1((drawnSleep * gauss(rng, 0.20, 0.03)).coerceIn(35.0, 130.0))
            val rem = round1((drawnSleep * gauss(rng, 0.23, 0.03)).coerceIn(45.0, 150.0))
            val light = round1((drawnSleep - deep - rem).coerceAtLeast(60.0))
            // Asleep is the SUM of the stages, never the draw: the light floor can lift the sum above it,
            // and a totalSleepMin that disagreed with deep+rem+light is the same defect one level up.
            val totalSleep = round1(deep + rem + light)
            val disturbances = gauss(rng, 6.0, 3.0).coerceIn(0.0, 18.0).toInt()

            // --- autonomic markers ---
            val hrv = (gauss(rng, 78.0 + fitness * 1.5, 12.0) + (if (weekend) 6 else 0) - nWorkouts * 4)
                .coerceIn(28.0, 150.0)
            val rhr = (gauss(rng, 56.0 - fitness * 0.4, 3.0) + nWorkouts * 1.2)
                .coerceIn(42.0, 70.0).toInt()
            val spo2 = gauss(rng, 96.5, 0.8).coerceIn(93.0, 100.0)
            val skinTempDev = gauss(rng, 0.0, 0.25).coerceIn(-1.2, 1.4)
            val resp = gauss(rng, 14.6, 0.9).coerceIn(11.0, 19.0)

            // --- recovery: a function of HRV, sleep quality and resting-HR ---
            val recovery = (
                40 + (hrv - 70) * 0.55 + (efficiency - 85) * 0.6 + (totalSleep - 420) * 0.03 -
                    (rhr - 55) * 1.4 - disturbances * 0.8 + gauss(rng, 0.0, 5.0)
                ).coerceIn(8.0, 99.0)

            // --- strain (Effort): workout-driven, rescaled 0–21 → 0–100 (×100/21) so mock
            // Effort sits on the new scale ---
            val strain = (
                (if (nWorkouts == 0) gauss(rng, 7.5, 1.8)
                else gauss(rng, 13.5, 2.4) + (nWorkouts - 1) * 2.5) * STRAIN_SCALE
                ).coerceIn(3.0 * STRAIN_SCALE, 100.0)

            val unslept = i == UNSLEPT_DAY_INDEX
            val fullRow = DailyMetric(
                deviceId = WHOOP, day = day,
                totalSleepMin = totalSleep, efficiency = effFraction(round1(efficiency)),
                deepMin = deep, remMin = rem, lightMin = light,
                disturbances = disturbances, restingHr = rhr, avgHrv = round1(hrv),
                recovery = round1(recovery), strain = round1(strain), exerciseCount = nWorkouts,
                spo2Pct = round1(spo2), skinTempDevC = round2(skinTempDev),
                // Absolute nightly skin temp (~34 °C baseline + the deviation) so the Today card and
                // Health show a real temperature, not just the ±deviation.
                skinTempAbsC = round2(34.0 + skinTempDev), respRateBpm = round1(resp),
            )
            daily.add(if (unslept) withoutSleep(fullRow) else fullRow)

            // --- sleep session: previous night ~23:10 → wake. The night after the unslept day starts on
            // that evening instead, so it spans two calendar days; the unslept day gets none. The draw is
            // still taken either way, so every other day is unchanged. ---
            val bedHour = if (i == UNSLEPT_DAY_INDEX + 1) CRASH_OUT_HOUR else 23
            val bedMinute = if (i == UNSLEPT_DAY_INDEX + 1) CRASH_OUT_MINUTE else 10
            val onset = date.minusDays(1).atTime(bedHour, bedMinute).atZone(zone).toEpochSecond() +
                rng.nextInt(-1800, 1800)
            val effPct = round1(efficiency)
            val inBedSec = inBedSecFor(totalSleep, effPct)
            if (!unslept) {
                sleeps.add(
                    SleepSession(
                        deviceId = WHOOP, startTs = onset, endTs = onset + inBedSec,
                        efficiency = effFraction(effPct), restingHr = rhr, avgHrv = round1(hrv),
                        stagesJSON = stagesJson(deep, rem, light, onset, onset + inBedSec),
                    )
                )
            }

            // --- long-format extras (body composition) under my-whoop ---
            weight += gauss(rng, -0.02, 0.18)
            series.add(MetricSeriesRow(WHOOP, day, "weightKg", round2(weight)))
            series.add(
                MetricSeriesRow(
                    WHOOP, day, "bodyFatPct",
                    round1((18.0 - fitness * 0.2 + gauss(rng, 0.0, 0.4)).coerceIn(10.0, 24.0))
                )
            )
            // Export-verbatim sleep figures (same metricSeries keys the importers write), so
            // the mock Sleep tiles exercise the prefer-imported path.
            val mockNeedMin = (totalSleep + gauss(rng, 25.0, 20.0)).coerceIn(420.0, 560.0)
            series.add(MetricSeriesRow(WHOOP, day, "sleep_performance",
                round1((totalSleep / mockNeedMin * 100.0).coerceAtMost(100.0))))
            series.add(MetricSeriesRow(WHOOP, day, "sleep_consistency",
                round1(gauss(rng, 80.0, 8.0).coerceIn(40.0, 100.0))))
            series.add(MetricSeriesRow(WHOOP, day, "sleep_need_min", round1(mockNeedMin)))
            series.add(MetricSeriesRow(WHOOP, day, "sleep_debt_min",
                round1((mockNeedMin - totalSleep).coerceAtLeast(0.0))))

            // --- Apple Health daily aggregate ---
            val steps = gauss(rng, 8500.0, 2600.0).coerceIn(1200.0, 19000.0).toInt()
            apple.add(
                AppleDaily(
                    deviceId = APPLE, day = day,
                    steps = steps,
                    activeKcal = round1((steps * 0.045 + nWorkouts * 220).coerceIn(120.0, 1400.0)),
                    basalKcal = round1(gauss(rng, 1650.0, 40.0)),
                    vo2max = round1((46 + fitness * 0.3 + gauss(rng, 0.0, 0.5)).coerceIn(38.0, 56.0)),
                    avgHr = gauss(rng, 72.0, 5.0).toInt(),
                    maxHr = gauss(rng, 150.0, 12.0).toInt(),
                    walkingHr = gauss(rng, 108.0, 6.0).toInt(),
                    weightKg = round2(weight),
                )
            )

            // --- workouts on training days ---
            var kept = 0
            repeat(nWorkouts) { k ->
                val sport = SPORTS[rng.nextInt(SPORTS.size)]
                val durSec = (gauss(rng, 48.0, 16.0).coerceIn(18.0, 110.0) * 60)
                val start = date.atTime(if (weekend) 9 else 18, rng.nextInt(0, 50))
                    .atZone(zone).toEpochSecond() + k * 3600
                val avg = gauss(rng, 138.0, 12.0).toInt()
                val src = if (rng.nextDouble() < 0.7) WHOOP else APPLE
                val distanceSports = setOf("Running", "Cycling", "Walking", "Swimming", "Rowing")
                // Every draw above is taken either way, so dropping an unfinished session leaves the
                // rest of the dataset byte-identical.
                if (start + durSec.toLong() > nowSec) return@repeat
                kept++
                workouts.add(
                    WorkoutRow(
                        deviceId = src, startTs = start, endTs = start + durSec.toLong(),
                        sport = sport, source = src,
                        durationS = round1(durSec),
                        energyKcal = round1((durSec / 60) * gauss(rng, 9.0, 2.0)),
                        avgHr = avg, maxHr = (avg + gauss(rng, 22.0, 6.0)).toInt(),
                        // strain is already 0–100 (daily Effort), so the per-workout share keeps
                        // the 0–100 scale; bounds rescaled from the old 4–21 (×100/21).
                        strain = round1((strain * gauss(rng, 0.6, 0.1)).coerceIn(4.0 * STRAIN_SCALE, 100.0)),
                        distanceM = if (sport in distanceSports)
                            round1(gauss(rng, 6500.0, 2500.0).coerceAtLeast(500.0)) else null,
                        // Only WHOOP-sourced rows carry zones (matching real imports — Apple Health
                        // rows never do), so the mock Workouts screen showcases the HR Zones card.
                        zonesJSON = if (src == WHOOP) run {
                            val z = listOf(
                                gauss(rng, 15.0, 5.0), gauss(rng, 30.0, 8.0), gauss(rng, 28.0, 8.0),
                                gauss(rng, 15.0, 6.0), gauss(rng, 6.0, 3.0),
                            ).map { it.coerceIn(0.0, 100.0) }
                            """{"zone1":${round1(z[0])},"zone2":${round1(z[1])},"zone3":${round1(z[2])},"zone4":${round1(z[3])},"zone5":${round1(z[4])}}"""
                        } else null,
                        notes = null,
                    )
                )
            }

            if (kept != nWorkouts) {
                daily[daily.lastIndex] = daily.last().copy(exerciseCount = kept)
            }

            // --- journal answers for the recent 40 days ---
            if (i >= DAYS - 40) {
                journal.add(JournalEntry(WHOOP, day, "Any alcohol?", rng.nextDouble() < 0.18))
                journal.add(JournalEntry(WHOOP, day, "Caffeine after 4pm?", rng.nextDouble() < 0.30))
                journal.add(JournalEntry(WHOOP, day, "Felt stressed?", rng.nextDouble() < 0.28))
            }
        }

        // --- weekly Fitness Age + VO2max estimate, stamped on each week's Saturday so the
        // Fitness Age screen renders in the mock build. Trends ~42 → ~36 (younger) as the
        // mock "fitness" drift climbs; vo2max ~44 → ~50.
        var fitnessAge = 42.0
        var vo2 = 44.0
        var vitality = 55.0      // weekly Vitality (0–100) trending up as the mock habits improve
        var bodyAgeMock = 40.0   // Body Age (years) trending down (younger)
        for (i in 0 until DAYS) {
            val date = startDay.plusDays(i.toLong())
            if (date.dayOfWeek.value != 6) continue // 6 = Saturday
            val day = date.toString()
            // Seeded under the NOOP-COMPUTED source (WHOOP_NOOP), where IntelligenceEngine writes these
            // weekly scores, so the computed union resolves them for Health / Today "Your cards" /
            // Trends instead of "No Data". Fitness age trends ~42 → ~34; vitality climbs ~55 → ~80.
            series.add(MetricSeriesRow(WHOOP_NOOP, day, "fitness_age",
                round1((fitnessAge + gauss(rng, 0.0, 0.3)).coerceIn(34.0, 44.0))))
            series.add(MetricSeriesRow(WHOOP_NOOP, day, "vo2max_est",
                round1((vo2 + gauss(rng, 0.0, 0.4)).coerceIn(42.0, 52.0))))
            series.add(MetricSeriesRow(WHOOP_NOOP, day, "vitality",
                round1((vitality + gauss(rng, 0.0, 1.0)).coerceIn(40.0, 80.0))))
            series.add(MetricSeriesRow(WHOOP_NOOP, day, "body_age",
                round1((bodyAgeMock + gauss(rng, 0.0, 0.3)).coerceIn(30.0, 45.0))))
            fitnessAge -= 0.75 // ~6 yr younger across the 8 seeded Saturdays
            vo2 += 0.75
            vitality += 2.0
            bodyAgeMock -= 0.6
        }

        // --- daily "stress" series (0–3) under my-whoop, matching WhoopImporter's derivation exactly:
        // z = 0.6·((rhr−rmean)/rsd) − 0.6·((hrv−hmean)/hsd), stress = clamp(1.5 + z, 0, 3). Feeds the
        // Today "Your cards" Stress card, the Stress screen's stored-series path, and Trends.
        run {
            val rhrAll = daily.mapNotNull { it.restingHr?.toDouble() }
            val hrvAll = daily.mapNotNull { it.avgHrv }
            val (rMean, rSd) = meanStd(rhrAll)
            val (hMean, hSd) = meanStd(hrvAll)
            for (d in daily) {
                val rhr = d.restingHr?.toDouble() ?: continue
                val hrv = d.avgHrv ?: continue
                val z = 0.6 * ((rhr - rMean) / rSd) - 0.6 * ((hrv - hMean) / hSd)
                series.add(MetricSeriesRow(WHOOP, d.day, "stress", round2((1.5 + z).coerceIn(0.0, 3.0))))
            }
        }

        // Per-minute HR across the most recent nights, generated last so no earlier draw shifts.
        val hr = sleeps.takeLast(HR_NIGHTS).flatMap { nightHrSamples(rng, it) }

        return MockDataset(
            devices = listOf(MockDeviceRow(WHOOP, "WHOOP (mock)")),
            daily = daily, sleeps = sleeps, series = series, apple = apple,
            workouts = workouts, journal = journal, hr = hr,
        )
    }

    // MARK: - helpers

    /**
     * The daily row a day with NO sleep carries: the row EXISTS — an absent day and a day nobody slept
     * are different states — and every column a NIGHT produces is null, the totals, the stage minutes,
     * the vitals only measured during sleep, and the two scores those feed. Strain, activity, HR zones,
     * sleep need and consistency and the prior day's effort belong to the day, and stay.
     */
    internal fun withoutSleep(d: DailyMetric): DailyMetric = d.copy(
        totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null,
        disturbances = null, restingHr = null, avgHrv = null, recovery = null,
        spo2Pct = null, skinTempDevC = null, skinTempAbsC = null, respRateBpm = null,
        spo2Red = null, spo2Ir = null, recoveryIndexSlope = null,
    )

    /**
     * One night's per-minute HR: the night's own resting rate, dipping and lifting across four sleep
     * cycles, with the half hour either side of the session raised to a lying-awake rate. Spans that
     * padded window so the Sleep hero's chart has a trace beyond its dashed onset and wake bounds.
     */
    internal fun nightHrSamples(rng: Random, s: SleepSession): List<HrSample> {
        val base = (s.restingHr ?: 55).toDouble()
        val from = s.startTs - HR_EDGE_PAD_SEC
        val to = s.endTs + HR_EDGE_PAD_SEC
        val span = (to - from).coerceAtLeast(1L).toDouble()
        val out = ArrayList<HrSample>((((to - from) / HR_STEP_SEC) + 1).toInt())
        var ts = from
        while (ts <= to) {
            val cycles = cos(2.0 * PI * ((ts - from) / span) * 4.0)
            val awakeLift = if (ts < s.startTs || ts > s.endTs) 14.0 else 0.0
            val bpm = base + 6.0 - cycles * 5.0 + awakeLift + gauss(rng, 0.0, 2.2)
            out.add(HrSample(deviceId = s.deviceId, ts = ts, bpm = bpm.coerceIn(38.0, 130.0).toInt()))
            ts += HR_STEP_SEC
        }
        return out
    }

    /** Box–Muller normal sample. */
    private fun gauss(rng: Random, mean: Double, sd: Double): Double {
        val u1 = rng.nextDouble().coerceIn(1e-9, 1.0)
        val u2 = rng.nextDouble()
        return mean + sd * (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2))
    }

    internal fun round1(x: Double) = round(x * 10.0) / 10.0
    internal fun round2(x: Double) = round(x * 100.0) / 100.0

    /** Mean + population standard deviation of a sample; SD floored so a z-score never divides by zero.
     *  Used to derive the mock "stress" series the same way WhoopImporter.meanStd does. */
    private fun meanStd(a: List<Double>): Pair<Double, Double> {
        if (a.isEmpty()) return 0.0 to 1.0
        val m = a.sum() / a.size
        val v = a.sumOf { (it - m) * (it - m) } / a.size
        return m to maxOf(sqrt(v), 0.0001)
    }

    /**
     * The session span (seconds) for a night of [asleepMin] at [efficiencyPct]: in-bed is asleep over
     * efficiency, so the awake [stagesJson] fills the remainder with is the awake that efficiency claims.
     */
    internal fun inBedSecFor(asleepMin: Double, efficiencyPct: Double): Long =
        if (asleepMin <= 0.0 || efficiencyPct <= 0.0) 0L
        else Math.round(asleepMin / (efficiencyPct / 100.0) * 60.0)

    /**
     * The `efficiency` column's unit. Nights are drawn as a readable percent, but both `dailyMetric` and
     * `sleepSession` store a 0-1 fraction, and whoop-rs scores the Rest term as `efficiency * 100`, so a
     * percent saturates it at the maximum on every night instead of discriminating between them.
     */
    internal fun effFraction(efficiencyPct: Double): Double = efficiencyPct / 100.0

    /**
     * A plausible light→deep→rem cycle as TIMESTAMPED `{stage,start,end}` segments tiling
     * `[startTs, endTs]` — the shape the sleep timeline reads. Stage names match whoop-rs (`wake`).
     *
     * Each stage is laid at ITS OWN minutes, so decoding the result returns [deep] / [rem] / [light]
     * back; the span's remainder becomes the wake runs. Nothing is rescaled — stretching the cycle onto
     * the span is what made the hero card disagree with every column-reading screen.
     */
    internal fun stagesJson(deep: Double, rem: Double, light: Double, startTs: Long, endTs: Long): String {
        val asleepMin = deep + rem + light
        if (asleepMin <= 0.0 || endTs <= startTs) return "[]"
        // Whatever the span has left over the asleep minutes IS the night's awake time — the quantity the
        // efficiency column implies — laid as a sleep latency plus brief wakes rather than estimated at
        // display time. A span no longer than the stages leaves none.
        val wake = ((endTs - startTs) / 60.0 - asleepMin).coerceAtLeast(0.0) / 4.0
        val cycle = listOf(
            "wake" to wake, "light" to light * 0.35, "deep" to deep * 0.6, "light" to light * 0.30,
            "wake" to wake, "rem" to rem * 0.6, "deep" to deep * 0.4, "light" to light * 0.35,
            "wake" to wake, "rem" to rem * 0.4, "wake" to wake,
        ).filter { it.second > 0.0 }
        val arr = JSONArray()
        // Boundaries are CUMULATIVE minutes rounded once to the second, so per-segment rounding cannot
        // accumulate and the totals the readers decode are the totals that were asked for.
        var cumMin = 0.0
        var t = startTs
        cycle.forEachIndexed { i, (stage, min) ->
            cumMin += min
            val end = if (i == cycle.lastIndex) endTs
            else (startTs + Math.round(cumMin * 60.0)).coerceIn(t, endTs)
            if (end > t) arr.put(JSONObject().put("stage", stage).put("start", t).put("end", end))
            t = end
        }
        return arr.toString()
    }
}
