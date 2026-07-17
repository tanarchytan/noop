package com.noop.analytics

/*
 * StressIndex.kt, Baevsky's Stress Index (SI), a histogram-based autonomic-balance metric.
 *
 *     SI = AMo / (2 * Mo * MxDMn)
 *
 *   • Mo    (mode, s)            : R-R histogram bin centre with the highest count.
 *   • AMo   (amplitude of mode,%): share of intervals in the modal bin, a tall narrow peak (rigid,
 *                                  sympathetically driven) gives a high AMo.
 *   • MxDMn (variation range, s) : max R-R minus min R-R, a wide range (flexible, vagal) lowers SI.
 *
 * High SI = tall/narrow/low-range histogram = rigid rhythm = high sympathetic stress; low SI = broad/flat
 * = relaxed. R-R in SECONDS, AMo as a percentage (0–100), so SI is dimensionless. APPROXIMATE, non-clinical.
 *
 * Analytics cutover (Tier 1): the SI math now lives only in whoop-rs physio-algo (`stress_index` /
 * `stress_components`), reached through [RustScores.stressIndex] / [RustScores.stressComponents]. What
 * survives here is [Components], the display DTO the Stress screen reads, plus the [MIN_BEATS] gate value:
 * the Rust door enforces that gate internally (returning null below it), so no production path reads the
 * constant — it stays as the documented threshold the stress parity / coach tests assert against. The
 * bit-for-bit parity of the Rust output with the deleted Kotlin twin is pinned by RustStressParityTest
 * before this deletion landed.
 */
object StressIndex {

    /** Minimum clean intervals before an SI is computed (the histogram's honest-data gate). */
    const val MIN_BEATS: Int = 20

    /** Intermediate histogram terms, exposed so the UI can show the "why" behind an SI. */
    data class Components(
        val moSec: Double,
        val aMoPercent: Double,
        val mxDMnSec: Double,
        val si: Double,
    )
}
