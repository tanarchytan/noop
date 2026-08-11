package com.noop.analytics

import com.noop.data.DailyMetric
import java.util.Locale

/**
 * On-device "Readiness" intelligence.
 *
 * Synthesizes a handful of established, non-medical sports-science signals from the daily-metrics
 * history into a single readiness read plus the drivers behind it. Pure, deterministic function of
 * the rows passed in — no networking, no strap commands, no state.
 *
 * Signals:
 * - **HRV readiness** — z-score of today's HRV against the personal trailing baseline. A drop of
 *   roughly half a standard deviation flags autonomic fatigue (Plews et al. 2013; Buchheit 2014).
 * - **Resting-HR drift** — elevated resting HR vs baseline is a classic overtraining / illness
 *   signal (Lamberts et al. 2004).
 * - **Respiratory-rate drift** — a rise in sleeping respiratory rate is an early illness signal.
 * - **Training Stress Balance (ACWR)** — acute (7-day) vs chronic (28-day) strain. The 0.8–1.3
 *   band is the "sweet spot"; >1.5 is associated with higher injury risk (Gabbett 2016).
 * - **Training monotony** — mean/SD of daily strain over a week; high monotony (low variety) is
 *   associated with higher strain and illness (Foster 1998).
 *
 * Not medical advice. These are approximations from a consumer strap; they describe trends in
 * *your own* data, nothing more.
 */
object ReadinessEngine {

    // MARK: Output types

    enum class Level {
        PRIMED,       // signals aligned, load supported
        BALANCED,     // nothing notable either way
        STRAINED,     // one meaningful signal down / load high
        RUNDOWN,      // several recovery signals down
        INSUFFICIENT, // not enough history yet
    }

    enum class Flag { GOOD, NEUTRAL, WATCH, BAD }

    /** Which signal a row reports on. The wording for each lives in the UI. */
    enum class Metric(val key: String) {
        HRV("hrv"), RESTING_HR("rhr"), RESP_RATE("respRate"), LOAD("acwr"), VARIETY("monotony"),
    }

    /** Which one-line read a signal resolves to. The wording for each lives in the UI. */
    enum class Detail {
        HRV_ABOVE, HRV_BELOW, HRV_SUPPRESSED,
        IN_NORMAL_RANGE,
        RHR_AT_OR_BELOW, RHR_HIGH, RHR_ELEVATED,
        RESP_RAISED, RESP_UP,
        LOAD_RAMPING_DOWN, LOAD_SWEET_SPOT, LOAD_BUILDING, LOAD_SPIKING,
        VARIETY_LOW,
    }

    /** Which numeric line sits under a signal. The wording and units live in the UI. */
    enum class EvidenceKind { VS_MS, VS_BPM, VS_RPM, ACUTE_CHRONIC, MONOTONY }

    /** The figures behind a signal, formatted here and worded by [EvidenceKind] in the UI. */
    data class Evidence(val kind: EvidenceKind, val first: String, val second: String = "")

    data class Signal(
        val metric: Metric,
        val detail: Detail,
        val flag: Flag,
        /** The figure [detail] embeds, when its wording carries one (the acute:chronic ratio). */
        val detailValue: String? = null,
        /** Rendered as a small caption under the signal; null when there is no figure to show. */
        val evidence: Evidence? = null,
    ) {
        /** Stable identity for the synthesis gates: "hrv" | "rhr" | "respRate" | "acwr" | "monotony". */
        val key: String get() = metric.key
    }

    /** Which of the six readiness reads this resolves to. The wording for each lives in the UI. */
    enum class Message { NO_DATA, THIN_HISTORY, RUNDOWN, STRAINED, PRIMED, BALANCED }

    data class Readiness(
        val level: Level,
        val message: Message,
        val signals: List<Signal>,
        /** Acute:chronic workload ratio (null if not enough strain history). */
        val acwr: Double?,
        /** Foster training monotony over the last week (null if not enough strain history). */
        val monotony: Double?,
    )

    // MARK: Tunables (named so the thresholds are auditable)

    private const val vitalsBaselineDays = 30   // days for HRV / RHR / RR baselines
    private const val minVitalsNights = 7       // need at least this many baseline nights
    private const val acuteWindow = 7
    private const val chronicWindow = 28
    private const val minChronic = 14       // need at least this much strain history for ACWR

    // Resp-rate signal is sourced from either clean cloud RR or a higher-variance on-device RSA
    // estimate (no source flag on the field), so it uses wider z thresholds than HRV/RHR and a
    // physiologic sanity band. A single noisy RSA night should not reach BAD (which feeds recoveryDown).
    private const val respZWatch = 1.5      // raised vs HRV/RHR to absorb RSA night-to-night noise
    private const val respZBad = 2.0        // raised vs HRV/RHR so one off-night can't trigger RUNDOWN
    // Single canonical band, owned by the producer so the stored RSA value can't disagree with
    // this gate: SleepStager.respRateFromRR NaNs anything outside it before persisting.
    private val respPlausibleRange = SleepStager.respPlausibleRangeBpm // plausible sleeping RR (bpm)

    // MARK: Entry point

    /**
     * Evaluate readiness from daily metrics. [days] may be in any order; the most recent day is
     * treated as "today" unless [today] (a YYYY-MM-DD string) is given.
     *
     * [evaluateUncached] sorts the full daily history and walks trailing windows on every call, and
     * is read from Compose recompositions on each ~1 Hz live-HR tick, so the result is cached here.
     * Key is [today] plus an order-independent fingerprint over the readiness-relevant fields (day +
     * avgHrv/restingHr/respRateBpm/strain), so a new sync re-keys but a cosmetic reorder does not.
     * The cached value is a small immutable [Readiness]; no row data is retained.
     */
    fun evaluate(days: List<DailyMetric>, today: String? = null): Readiness {
        val key = readinessKey(today, days)
        synchronized(evaluateCacheLock) { evaluateCache[key] }?.let { return it }
        val result = evaluateUncached(days, today)   // computed OUTSIDE the lock (the expensive sort/walk)
        synchronized(evaluateCacheLock) { evaluateCache[key] = result }
        return result
    }

    private data class ReadinessKey(
        val today: String?, val count: Int, val minDay: Int, val maxDay: Int, val checksum: Long,
    )

    private const val EVALUATE_CACHE_CAP = 16

    /** Access-order LRU (cap [EVALUATE_CACHE_CAP]); every access is under [evaluateCacheLock] because
     *  a LinkedHashMap in access order mutates on `get`. */
    private val evaluateCache: LinkedHashMap<ReadinessKey, Readiness> =
        object : LinkedHashMap<ReadinessKey, Readiness>(EVALUATE_CACHE_CAP, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReadinessKey, Readiness>): Boolean =
                size > EVALUATE_CACHE_CAP
        }
    private val evaluateCacheLock = Any()

    /** Order-independent fingerprint of the readiness-relevant columns — a new sync re-keys, a
     *  cosmetic reorder does not. A collision only costs one extra recompute. FNV-style commutative
     *  fold over (day, avgHrv, restingHr, respRateBpm, strain). */
    private fun readinessKey(today: String?, days: List<DailyMetric>): ReadinessKey {
        var sum = 1469598103934665603L
        var minDay = 0
        var maxDay = 0
        for ((i, d) in days.withIndex()) {
            var h = d.day.hashCode().toLong()
            h = h * 1099511628211L xor (d.avgHrv ?: -1.0).toRawBits()
            h = h * 1099511628211L xor (d.restingHr?.toLong() ?: Long.MAX_VALUE)
            h = h * 1099511628211L xor (d.respRateBpm ?: -1.0).toRawBits()
            h = h * 1099511628211L xor (d.strain ?: -1.0).toRawBits()
            sum = sum xor h   // commutative fold → order-independent
            val dh = d.day.hashCode()
            if (i == 0) { minDay = dh; maxDay = dh } else { minDay = minOf(minDay, dh); maxDay = maxOf(maxDay, dh) }
        }
        return ReadinessKey(today, days.size, minDay, maxDay, sum)
    }

    /** The uncached readiness synthesis. [days] may be in any order; the most recent day is "today"
     *  unless [today] is given. */
    private fun evaluateUncached(days: List<DailyMetric>, today: String? = null): Readiness {
        val sorted = days.sortedBy { it.day }
        // With an explicit [today] (dashboard's device-local day key), use only that day's row: a
        // stale import has no row for today, so the card reads "insufficient" instead of synthesizing
        // off the newest stored (possibly months-old) row. With no [today], fall back to the latest row.
        val latest = if (today != null) sorted.firstOrNull { it.day == today } else sorted.lastOrNull()
        if (latest == null) {
            return Readiness(
                level = Level.INSUFFICIENT,
                message = Message.NO_DATA,
                signals = emptyList(), acwr = null, monotony = null,
            )
        }
        val history = sorted.filter { it.day < latest.day }   // everything before today

        val signals = mutableListOf<Signal>()

        // HRV readiness ------------------------------------------------------
        val hrvSignal = zSignal(
            value = latest.avgHrv,
            baseline = history.takeLast(vitalsBaselineDays).mapNotNull { it.avgHrv },
            metric = Metric.HRV, evidenceKind = EvidenceKind.VS_MS, decimals = 0,
            higherIsBetter = true,
            good = Detail.HRV_ABOVE,
            neutral = Detail.IN_NORMAL_RANGE,
            watch = Detail.HRV_BELOW,
            bad = Detail.HRV_SUPPRESSED,
        )
        if (hrvSignal != null) signals.add(hrvSignal)

        // Resting-HR drift ---------------------------------------------------
        val rhrSignal = zSignal(
            value = latest.restingHr?.toDouble(),
            baseline = history.takeLast(vitalsBaselineDays).mapNotNull { it.restingHr?.toDouble() },
            metric = Metric.RESTING_HR, evidenceKind = EvidenceKind.VS_BPM, decimals = 0,
            higherIsBetter = false,
            good = Detail.RHR_AT_OR_BELOW,
            neutral = Detail.IN_NORMAL_RANGE,
            watch = Detail.RHR_HIGH,
            bad = Detail.RHR_ELEVATED,
        )
        if (rhrSignal != null) signals.add(rhrSignal)

        // Respiratory-rate drift (illness early signal) ----------------------
        // respRateBpm may be clean cloud data or a higher-variance on-device RSA estimate (no source
        // flag), so gate conservatively: minVitalsNights + sd>0, a plausible sleeping-RR band (~8-25 bpm),
        // and wider z thresholds (WATCH 1.5 / BAD 2.0) so one noisy night can't trigger BAD/recoveryDown.
        val rr = latest.respRateBpm
        if (rr != null && rr in respPlausibleRange) {
            val base = history.takeLast(vitalsBaselineDays).mapNotNull { it.respRateBpm }
            val m = mean(base)
            val sd = sampleSD(base)
            if (base.size >= minVitalsNights && m != null && m in respPlausibleRange && sd != null && sd > 0) {
                val z = (rr - m) / sd
                val respEvidence = Evidence(EvidenceKind.VS_RPM, fmt(rr, 1), fmt(m, 1))
                if (z >= respZBad) {
                    signals.add(
                        Signal(
                            metric = Metric.RESP_RATE, detail = Detail.RESP_UP, flag = Flag.BAD,
                            evidence = respEvidence,
                        )
                    )
                } else if (z >= respZWatch) {
                    signals.add(
                        Signal(
                            metric = Metric.RESP_RATE, detail = Detail.RESP_RAISED, flag = Flag.WATCH,
                            evidence = respEvidence,
                        )
                    )
                }
            }
        }

        // Training Stress Balance (ACWR) + monotony --------------------------
        val strainSeries = sorted.mapNotNull { it.strain }
        var acwr: Double? = null
        var monotony: Double? = null
        if (strainSeries.size >= minChronic) {
            val acute = mean(strainSeries.takeLast(acuteWindow))!!
            val chronic = mean(strainSeries.takeLast(chronicWindow))!!
            if (chronic > 0) {
                val ratio = acute / chronic
                acwr = ratio
                signals.add(acwrSignal(ratio, acute = acute, chronic = chronic))
            }
            // Foster monotony over the last week of strain.
            val week = strainSeries.takeLast(acuteWindow)
            val sd = sampleSD(week)
            val m = mean(week)
            if (week.size >= 4 && sd != null && sd > 0 && m != null) {
                val mono = m / sd
                monotony = mono
                if (mono >= 2.0) {
                    signals.add(
                        Signal(
                            metric = Metric.VARIETY, detail = Detail.VARIETY_LOW, flag = Flag.WATCH,
                            evidence = Evidence(EvidenceKind.MONOTONY, fmt(mono, 1)),
                        )
                    )
                }
            }
        }

        val (level, message) = synthesize(
            signals = signals,
            hasHistory = history.isNotEmpty() || acwr != null,
        )
        return Readiness(
            level = level, message = message,
            signals = signals, acwr = acwr, monotony = monotony,
        )
    }

    // MARK: Signal builders

    /** Build a z-score signal for a metric where the baseline is the trailing window. */
    private fun zSignal(
        value: Double?, baseline: List<Double>,
        metric: Metric, evidenceKind: EvidenceKind, decimals: Int, higherIsBetter: Boolean,
        good: Detail, neutral: Detail,
        watch: Detail, bad: Detail,
    ): Signal? {
        if (value == null || baseline.size < minVitalsNights) return null
        val m = mean(baseline) ?: return null
        val sd = sampleSD(baseline) ?: return null
        if (sd <= 0) return null
        // Orient z so positive always means "better".
        val z = (if (higherIsBetter) (value - m) else (m - value)) / sd
        val flag: Flag
        val detail: Detail
        when {
            z >= 0.5 -> { flag = Flag.GOOD; detail = good }
            z >= -0.5 -> { flag = Flag.NEUTRAL; detail = neutral }
            z >= -1.0 -> { flag = Flag.WATCH; detail = watch }
            else -> { flag = Flag.BAD; detail = bad }
        }
        // The numbers behind the read: today's value and the baseline mean, worded by the UI.
        return Signal(
            metric = metric, detail = detail, flag = flag,
            evidence = Evidence(evidenceKind, fmt(value, decimals), fmt(m, decimals)),
        )
    }

    /**
     * Format a metric value with the given number of decimals. Locale.US pins the separator to ".".
     * The 0-decimal case rounds half-away-from-zero (`Math.round`); the >0 case rounds half-to-even
     * ("%.Nf") — the two branches disagree at an exact .5, so don't unify them without checking callers.
     */
    private fun fmt(x: Double, decimals: Int): String =
        if (decimals == 0) Math.round(x).toString()
        else String.format(Locale.US, "%.${decimals}f", x)

    private fun acwrSignal(ratio: Double, acute: Double, chronic: Double): Signal {
        // Route through the Locale.US-pinned [fmt] helper so a comma-decimal device locale can't
        // render "1,15" for the ratio.
        val pct = fmt(ratio, 2)
        // Evidence: the two strain loads the ratio is built from, 1 dp each.
        val evidence = Evidence(EvidenceKind.ACUTE_CHRONIC, fmt(acute, 1), fmt(chronic, 1))
        val (detail, flag) = when {
            ratio < 0.8 -> Detail.LOAD_RAMPING_DOWN to Flag.WATCH
            ratio < 1.3 -> Detail.LOAD_SWEET_SPOT to Flag.GOOD
            ratio < 1.5 -> Detail.LOAD_BUILDING to Flag.WATCH
            else -> Detail.LOAD_SPIKING to Flag.BAD
        }
        return Signal(
            metric = Metric.LOAD, detail = detail, flag = flag, detailValue = pct, evidence = evidence,
        )
    }

    // MARK: Synthesis

    private fun synthesize(signals: List<Signal>, hasHistory: Boolean): Pair<Level, Message> {
        if (!hasHistory || signals.isEmpty()) {
            return Pair(Level.INSUFFICIENT, Message.THIN_HISTORY)
        }
        val bad = signals.filter { it.flag == Flag.BAD }
        val watch = signals.filter { it.flag == Flag.WATCH }
        val good = signals.filter { it.flag == Flag.GOOD }
        val recoveryDown = signals.any { it.key in listOf("hrv", "rhr", "respRate") && it.flag == Flag.BAD }
        val loadHigh = signals.any { it.key == "acwr" && it.flag == Flag.BAD }

        if (bad.size >= 2 || (recoveryDown && loadHigh)) return Pair(Level.RUNDOWN, Message.RUNDOWN)
        if (recoveryDown || loadHigh || bad.size >= 1) return Pair(Level.STRAINED, Message.STRAINED)
        if (good.size >= 2 && watch.isEmpty()) return Pair(Level.PRIMED, Message.PRIMED)
        return Pair(Level.BALANCED, Message.BALANCED)
    }

    // MARK: Stats helpers

    fun mean(xs: List<Double>): Double? =
        if (xs.isEmpty()) null else RustScores.mean(xs)

    /** Sample standard deviation (n-1). null for fewer than 2 points. */
    fun sampleSD(xs: List<Double>): Double? =
        if (xs.size < 2) null else RustScores.sampleSD(xs)
}
