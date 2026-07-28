package com.noop.analytics

/*
 * HrZones.kt — the 5 heart-rate zone bands for a known max HR (display helper).
 *
 *   Zone 1 (50–60% HRmax) — very light / recovery
 *   Zone 2 (60–70% HRmax) — light / fat-burn
 *   Zone 3 (70–80% HRmax) — moderate / aerobic
 *   Zone 4 (80–90% HRmax) — hard / threshold
 *   Zone 5 (90–100% HRmax) — maximum
 *
 * Independent of the HRR-based strain math in StrainScorer. Named [HrZones], not
 * Zones, to avoid clashing with the Zones object in Analytics.kt.
 */

/** A single heart-rate zone: a bpm interval [lower, upper). */
data class HrZone(
    /** Zone number 1..5. */
    val number: Int,
    /** Lower bound (bpm), inclusive. */
    val lower: Double,
    /** Upper bound (bpm); exclusive except for the top zone where it is inclusive. */
    val upper: Double,
    /** Fraction-of-HRmax lower bound (e.g. 0.50 for Zone 1). */
    val lowerPct: Double,
    /** Fraction-of-HRmax upper bound (e.g. 0.60 for Zone 1). */
    val upperPct: Double,
)

/** Five HR zones derived from a max HR, plus the max HR itself and its source. */
data class HrZoneSet(
    /** The five zones, z1..z5, in ascending order. */
    val zones: List<HrZone>,
    /** Max HR (bpm) the zones were built from. */
    val maxHR: Double,
    /** "tanaka" (age formula) or "manual" (caller override). */
    val source: String,
) {
    /** Return the zone number (1..5) for a bpm value, or 0 when below Zone 1. */
    fun zoneNumber(bpm: Double): Int {
        for (z in zones) {
            // Top zone is inclusive at its upper edge so HRmax itself lands in z5.
            if (z.number == 5) {
                if (bpm >= z.lower) return 5
            } else if (bpm >= z.lower && bpm < z.upper) {
                return z.number
            }
        }
        return 0
    }
}

/*
 * Age-derived zones + time-in-zone live in whoop-rs physio-algo (hr_zones_for_age /
 * hr_time_in_zone via RustScores), parity-verified by RustHrZonesParityTest. This file
 * keeps only the display builder: a zone set from an already-known max HR.
 */
object HrZones {

    /** %HRmax band edges for zones 1..5: [0.50, 0.60, 0.70, 0.80, 0.90, 1.00]. */
    val zoneEdges: List<Double> = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)

    /** Build the 5-zone set directly from a known max HR. */
    fun zones(maxHR: Double, source: String = "manual"): HrZoneSet {
        val built = ArrayList<HrZone>(5)
        for (i in 0 until 5) {
            val loPct = zoneEdges[i]
            val hiPct = zoneEdges[i + 1]
            built.add(
                HrZone(
                    number = i + 1,
                    lower = loPct * maxHR,
                    upper = hiPct * maxHR,
                    lowerPct = loPct,
                    upperPct = hiPct,
                )
            )
        }
        return HrZoneSet(zones = built, maxHR = maxHR, source = source)
    }
}
