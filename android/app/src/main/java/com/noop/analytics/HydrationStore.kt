package com.noop.analytics

import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopRepository
import java.util.TimeZone

/**
 * HydrationStore — the logging + read seam for the Hydration tracker (opt-in, local-only).
 *
 * The day total is banked in the generic metric-series store under [KEY], keyed by the device's
 * local calendar day, via the same table + upsert path every other generic daily series uses. That
 * table holds one row per (deviceId, day, key), so a log reads the day's running total and
 * re-upserts total + amount rather than appending a row. Everything stays on-device, nothing synced.
 *
 * [ts] (a wall-clock unix second) selects which local day a log lands on; the goal itself comes
 * from the pure [HydrationGoal] engine, never from here.
 */
object HydrationStore {

    /**
     * Bumped on every mutation ([log] / [set]; [remove] routes through [set]). Hydration writes
     * never touch the flows Today already collects, so the dashboard card would otherwise sit
     * stale until an unrelated sync; Today keys its hydration re-read on this instead.
     */
    val mutationSeq = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The generic metric-series key the day total is banked under. */
    const val KEY: String = "hydration"

    /** The source/device id the hydration total is written under — its own local-only source so it is
     *  never confused with strap-imported or computed metrics. */
    const val SOURCE_ID: String = "hydration"

    /** Seconds EAST of UTC for the device's current zone — the offset [AnalyticsEngine.dayString] needs
     *  to bucket a timestamp on the LOCAL calendar day (matches the dashboard's local "today" read). */
    private fun localOffsetSec(atMillis: Long = System.currentTimeMillis()): Long =
        (TimeZone.getDefault().getOffset(atMillis) / 1000).toLong()

    /** The LOCAL yyyy-MM-dd day key for a unix-seconds [ts] (defaults to now). */
    fun dayKey(ts: Long = System.currentTimeMillis() / 1000L): String =
        AnalyticsEngine.dayString(ts, localOffsetSec(ts * 1000L))

    /**
     * Log [amountMl] of fluid for the local day containing [ts] (defaults to now). Reads the day's
     * current total and upserts total + amount under [SOURCE_ID]/[KEY], so repeated taps accumulate.
     * A non-positive amount is a no-op. Each tap is an additive log; there is no idempotency.
     */
    suspend fun log(repo: WhoopRepository, amountMl: Int, ts: Long = System.currentTimeMillis() / 1000L): Double {
        if (amountMl <= 0) return total(repo, ts)
        val day = dayKey(ts)
        val current = total(repo, ts)
        val next = current + amountMl
        repo.upsertMetricSeries(listOf(MetricSeriesRow(SOURCE_ID, day, KEY, next)))
        mutationSeq.value += 1   // tell Today's card directly (see mutationSeq)
        return next
    }

    /**
     * Pure clamp behind [set]: a day total can never be negative. Factored out so the correction
     * math is unit-testable without a Room/repo stand-in. Returns [totalMl] floored at 0.0.
     */
    fun clampedTotal(totalMl: Double): Double = totalMl.coerceAtLeast(0.0)

    /**
     * Pure result of removing [amountMl] from a [currentTotalMl]: a non-positive amount is a no-op
     * (the current total, still clamped at 0), otherwise the difference floored at 0 so an
     * over-subtraction lands on an empty day rather than a negative total. The testable core of [remove].
     */
    fun afterRemoving(currentTotalMl: Double, amountMl: Int): Double =
        if (amountMl <= 0) clampedTotal(currentTotalMl) else clampedTotal(currentTotalMl - amountMl)

    /**
     * Set the day total directly to [totalMl] for the local day containing [ts], clamped at 0 (a
     * negative target lands on 0, never a negative total). The correction seam behind the detail
     * screen's delete/undo affordances: the schema banks ONE additive total per (source, day, key)
     * row, so an entry isn't separately addressable - removing or editing a log adjusts the day total.
     */
    suspend fun set(repo: WhoopRepository, totalMl: Double, ts: Long = System.currentTimeMillis() / 1000L): Double {
        val day = dayKey(ts)
        val next = clampedTotal(totalMl)
        repo.upsertMetricSeries(listOf(MetricSeriesRow(SOURCE_ID, day, KEY, next)))
        mutationSeq.value += 1   // edits/deletes route through here too
        return next
    }

    /**
     * Remove [amountMl] from the local day's running total (the undo / delete-a-log path for the
     * detail screen). Subtracts the amount and clamps at 0 so the total never goes negative; a
     * non-positive amount is a no-op. Built on [set] + [afterRemoving] so the correction math is shared.
     */
    suspend fun remove(repo: WhoopRepository, amountMl: Int, ts: Long = System.currentTimeMillis() / 1000L): Double {
        if (amountMl <= 0) return total(repo, ts)
        return set(repo, afterRemoving(total(repo, ts), amountMl), ts)
    }

    /** The total fluid (ml) logged for the local day containing [ts] (defaults to now), or 0.0 when
     *  nothing has been logged that day. */
    suspend fun total(repo: WhoopRepository, ts: Long = System.currentTimeMillis() / 1000L): Double {
        val day = dayKey(ts)
        return repo.metricSeries(SOURCE_ID, KEY, day, day).firstOrNull()?.value ?: 0.0
    }

    /**
     * The last [days] local-day totals up to and including today, OLDEST first, as (dayKey, ml) pairs —
     * one entry per calendar day with 0.0 for days that have no log. Backs the detail screen's 7-day
     * mini bar history. [days] is clamped ≥ 1.
     */
    suspend fun history(
        repo: WhoopRepository,
        days: Int = 7,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<Pair<String, Double>> {
        val n = days.coerceAtLeast(1)
        val from = nowSec - (n - 1).toLong() * 86_400L
        val fromKey = dayKey(from)
        val toKey = dayKey(nowSec)
        // One ranged read; project onto the full day grid so empty days read as 0 rather than vanishing.
        val byDay = repo.metricSeries(SOURCE_ID, KEY, fromKey, toKey).associate { it.day to it.value }
        return (0 until n).map { i ->
            val key = dayKey(nowSec - (n - 1 - i).toLong() * 86_400L)
            key to (byDay[key] ?: 0.0)
        }
    }
}
