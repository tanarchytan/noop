package com.noop.analytics

/**
 * A snapshot of the on-device DATA VOLUME: the read-set that backs the screens, so an
 * import-driven-lag report shows what it renders over, not just frame stats. Counts are read
 * from the STORE directly (never the view-model caches), so this type stays store-independent.
 * Written by [com.noop.data.WhoopRepository.dataVolumeSnapshot] and printed by the debug export.
 */
data class DataVolume(
    /** Total raw stream rows in the store (HR + RR + events + the biometric streams), the dominant cost. */
    val dbRows: Int,
    /** Number of distinct days that carry imported daily metrics. */
    val importedDays: Int,
    /** Total detected/recorded workout rows. */
    val workouts: Int,
    /** Rows touched by the most recent render the caller measured, or null when it hasn't measured one. */
    val lastRenderRows: Int?,
)
