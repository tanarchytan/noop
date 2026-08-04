package com.noop.analytics

/*
 * VitalBands.kt — the Health Monitor's seam onto whoop-rs banding, plus the calendar padding and
 * the wording its tiles read.
 *
 * whoop-rs decides in-range: the wearer's own trailing baseline once trusted, the typical-adult
 * window before that and again whenever a wear gap makes the baseline stale. The enums here carry
 * the wire strings that answer comes back as, so a tile can colour and caption it.
 *
 * Outputs are APPROXIMATE and not medical advice.
 */
object VitalBands {

    enum class Band(val raw: String) {
        IN_RANGE("inRange"), OUT_OF_RANGE("outOfRange"), NO_DATA("noData")
    }

    /** How the band was judged — drives the tile's caption wording. */
    enum class Basis(val raw: String) { PERSONAL("personal"), POPULATION("population") }

    data class Result(val band: Band, val basis: Basis, val nights: Int)

    /** The typical-adult window for a vital key, or null when the key names no vital. */
    fun typicalRange(vital: String): ClosedFloatingPointRange<Double>? =
        uniffi.whoop_ffi.vitalTypicalRange(vital)?.let { it.min..it.max }

    /**
     * Band a single vital [value].
     *
     * [history] is nightly values oldest→newest EXCLUDING the displayed day (null = missing
     * night; run [calendarSeries] first to pad real wear gaps so staleness sees them).
     * [populationRange] is the typical-adult fallback used until the baseline is trusted.
     * A null [cfg] disables the personal path entirely (SpO₂ stays population-only — there is
     * no SpO₂ MetricCfg and an absolute floor is meaningful regardless of personal history).
     */
    fun band(
        value: Double?,
        history: List<Double?>,
        populationRange: ClosedFloatingPointRange<Double>,
        cfg: MetricCfg?,
    ): Result {
        val r = uniffi.whoop_ffi.vitalBand(
            value, history,
            uniffi.whoop_ffi.TypicalRangeInfo(
                min = populationRange.start, max = populationRange.endInclusive,
            ),
            cfg?.let { RustScores.metricCfgInfo(it) },
        )
        return Result(
            band = Band.entries.first { it.raw == r.band },
            basis = Basis.entries.first { it.raw == r.basis },
            nights = r.nights,
        )
    }

    // ── Skin temp (mixed semantics: absolute °C from CSV import vs ±°C on-device deviation) ──

    /** Whether a skin-temp value is an ABSOLUTE wrist temperature rather than a ±°C deviation.
     *  The CSV export stores absolute °C while the on-device pipeline stores a deviation, so a
     *  merged series is bimodal and the displayed value picks which kind its history keeps. */
    fun isAbsoluteSkinTemp(v: Double): Boolean = uniffi.whoop_ffi.skinTempIsAbsolute(v)

    /** The skin-temp value a reading tile should surface: absolute °C if the row has it, else the
     *  ±°C deviation. The single value-source for every "current skin temp" surface. */
    fun skinTempDisplay(absC: Double?, devC: Double?): Double? = absC ?: devC

    /** Format a skin-temp reading: unsigned when absolute (34.0°), signed when a deviation (+1.2°),
     *  split by [isAbsoluteSkinTemp]. The single formatter for Home, Health, and Compare. */
    fun formatSkinTemp(v: Double): String =
        String.format(java.util.Locale.US, if (isAbsoluteSkinTemp(v)) "%.1f°" else "%+.1f°", v)

    /** Keep only history entries of the SAME kind (absolute vs deviation) as the displayed
     *  [value]; entries of the other kind become null (missing nights) so the baseline isn't
     *  folded across two incompatible scales. */
    fun skinTempHistory(value: Double, history: List<Double?>): List<Double?> =
        uniffi.whoop_ffi.skinTempHistory(value, history)

    // ── Calendar padding ────────────────────────────────────────────────────────────────────

    /** Calendar-align (day, value) rows keyed "yyyy-MM-dd" into a nightly series with null for
     *  every absent day, so the baseline's staleness logic sees real wear gaps (otherwise a user
     *  returning after months would be banded against an ancient still-"trusted" baseline).
     *  Malformed keys are dropped; last write wins for a duplicated day. */
    fun calendarSeries(rows: List<Pair<String, Double?>>): List<Double?> {
        val parsed = rows.mapNotNull { (day, v) ->
            runCatching { java.time.LocalDate.parse(day) }.getOrNull()?.let { it to v }
        }
        val first = parsed.minOfOrNull { it.first } ?: return emptyList()
        val last = parsed.maxOfOrNull { it.first } ?: return emptyList()
        val byDay = parsed.associate { it.first to it.second }
        val out = ArrayList<Double?>()
        var d = first
        while (!d.isAfter(last)) {
            out.add(byDay[d])
            d = d.plusDays(1)
        }
        return out
    }
}
