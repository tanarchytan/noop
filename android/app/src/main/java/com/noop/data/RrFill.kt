package com.noop.data

import uniffi.whoop_ffi.rrFillCfg

/**
 * Predicate tail shared by every marking UPDATE (binds `:fill`, `:maxHr`). No same-second HR is not
 * flagged, matching `is_rr_fill(_, None)`.
 */
internal const val RR_FILL_MARK_WHERE =
    "rrMs = :fill AND tsSuspect = 0 AND EXISTS (SELECT 1 FROM hrSample h " +
        "WHERE h.deviceId = rrInterval.deviceId AND h.ts = rrInterval.ts AND h.bpm < :maxHr)"

/**
 * Marks the 500 ms R-R filler (`rrInterval.tsSuspect = 1`); never deletes. The rule and its two constants
 * come from the Rust `rrFillCfg`, bound into the SQL. Scoring reads skip marked rows ([WhoopDao.rrIntervals]).
 */
internal object RrFill {

    /** Whole-table backfill (migration, import). Idempotent. */
    const val MARK_ALL_SQL = "UPDATE rrInterval SET tsSuspect = 1 WHERE $RR_FILL_MARK_WHERE"

    private val cfg by lazy { rrFillCfg() }

    /** whoop-rs `FILL_RR_MS`. */
    val fillMs: Int get() = cfg.fillRrMs.toInt()

    /** whoop-rs `FILL_MAX_HR_BPM`, exclusive. */
    val maxHrBpm: Int get() = cfg.fillMaxHrBpm.toInt()

    /** Both constants as binds, in the order [RR_FILL_MARK_WHERE] names them: `:fill`, `:maxHr`. */
    fun binds(): Array<Any> = arrayOf(fillMs, maxHrBpm)
}
