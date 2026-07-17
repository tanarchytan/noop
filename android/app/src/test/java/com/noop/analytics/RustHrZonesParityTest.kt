package com.noop.analytics

import com.noop.data.HrSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * Post-cutover regression guard for the **HR-zones** route (analytics cutover, Tier 1). The Kotlin scorer
 * `HrZones.zones(age)` / `HrZones.timeInZone` (with its `tanakaMaxHR` + `medianInterval` helpers and the
 * `TimeInZone` result type) has been DELETED; the age-derived zone split and time-in-zone now score only in
 * whoop-rs physio-algo, reached via [RustScores.hrZonesForAge] / [RustScores.hrTimeInZone] →
 * `hr_zones_for_age` / `hr_time_in_zone`. Their bit-for-bit parity with the Kotlin output was PROVEN before
 * deletion (the empirical analog of the decode cutover's 869/869); this guard keeps the Rust leg pinned to
 * that frozen spec so a later whoop-rs change can't silently drift it.
 *
 * The Kotlin twin is gone, so the reference here is restated locally: [refZones] rebuilds the age→zone set
 * through the SURVIVING `HrZones.zones(maxHR, source)` primitive (the frontend display helper the cutover
 * kept), and [refTimeInZone] is a byte-identical copy of the deleted accumulator (hold-until-next duration,
 * tail = median inter-sample gap, per-sample gap capped at that median, below-Zone-1 bucket). Both reuse the
 * surviving [HrZoneSet.zoneNumber], so the reference stays identical to the deleted scorer.
 *
 * Traps this pins (per the cutover scope): (a) **Tanaka HRmax** `208 − 0.7·age` — a non-integer age is used
 * so `maxHR` and all ten zone edges are irrational-ish doubles, catching any float-formula drift; (b) the
 * **`medianInterval` upper-middle tie-break** (`gaps[size/2]`, NOT averaging the two middles) — the crafted
 * stream has an EVEN gap count with distinct gaps so upper-middle ≠ mean-of-middles, so a Rust that averaged
 * would diverge the tail credit + the gap cap and fail; (c) the per-sample **gap cap at the median** and the
 * below-Zone-1 bucket. `HrZones` applies no rounding to its raw output, so the stored doubles are exact and
 * the tie-break is the live trap. A nonzero delta is a FAIL to report as a whoop-rs fix request, NOT a
 * tolerance to widen.
 *
 * The FFI leg decodes+scores through real Rust via JNA (buildRustHostDll = `cargo build -p whoop-ffi`,
 * jna.library.path). Bit-for-bit doubles are compared via raw IEEE-754 bits so +0.0/-0.0/NaN can't slip.
 */
class RustHrZonesParityTest {

    private val dev = "hrz"

    // ── frozen kotlin reference (the deleted scorer, restated locally) ────────

    /** Seconds in each of the five zones plus below-Zone-1; the deleted `TimeInZone`, restated locally. */
    private class RefTiz(val seconds: List<Double>, val belowZone1: Double)

    /**
     * Age→zone set, byte-identical to the deleted `HrZones.zones(age, maxHROverride)`: Tanaka HRmax
     * `208 − 0.7·age` (or the override) fed to the surviving `HrZones.zones(maxHR, source)` builder.
     */
    private fun refZones(age: Double, override: Double?): HrZoneSet {
        val maxHR = override ?: (208.0 - 0.7 * age)
        val source = if (override != null) "manual" else "tanaka"
        return HrZones.zones(maxHR, source)
    }

    /** Median plausible (0, 300 s) inter-sample gap, upper-middle tie-break; deleted `medianInterval`. */
    private fun refMedianInterval(sorted: List<HrSample>): Double {
        if (sorted.size < 2) return 1.0
        val gaps = ArrayList<Double>()
        for (i in 1 until sorted.size) {
            val g = (sorted[i].ts - sorted[i - 1].ts).toDouble()
            if (g > 0 && g < 300) gaps.add(g)
        }
        if (gaps.isEmpty()) return 1.0
        gaps.sort()
        return maxOf(gaps[gaps.size / 2], 1.0)
    }

    /** Hold-until-next time-in-zone accumulation, byte-identical to the deleted `HrZones.timeInZone`. */
    private fun refTimeInZone(hr: List<HrSample>, zoneSet: HrZoneSet): RefTiz {
        val sorted = hr.sortedBy { it.ts }
        val zoneSeconds = DoubleArray(5)
        var below = 0.0
        if (sorted.isEmpty()) return RefTiz(zoneSeconds.toList(), 0.0)
        val tailDuration = refMedianInterval(sorted)
        for (i in sorted.indices) {
            val dur: Double = if (i < sorted.size - 1) {
                val gap = (sorted[i + 1].ts - sorted[i].ts).toDouble()
                if (gap > 0) min(gap, tailDuration) else tailDuration
            } else {
                tailDuration
            }
            val z = zoneSet.zoneNumber(sorted[i].bpm.toDouble())
            if (z >= 1) zoneSeconds[z - 1] += dur else below += dur
        }
        return RefTiz(zoneSeconds.toList(), below)
    }

    // ── bit-exact helpers ────────────────────────────────────────────────────

    private fun assertBits(msg: String, expected: Double, actual: Double) =
        assertEquals(
            msg,
            java.lang.Double.doubleToRawLongBits(expected),
            java.lang.Double.doubleToRawLongBits(actual),
        )

    /** Assert the frozen [HrZoneSet] reference and the FFI zone set are field-for-field bit-identical. */
    private fun assertZoneSetParity(age: Double, override: Double? = null) {
        val k = refZones(age, override)
        val r = RustScores.hrZonesForAge(age = age, maxHrOverride = override)
        assertBits("maxHR (age=$age override=$override)", k.maxHR, r.maxHr)
        assertEquals("source (age=$age override=$override)", k.source, r.source)
        assertEquals("zone count", k.zones.size, r.zones.size)
        for (i in k.zones.indices) {
            assertEquals("zone $i number", k.zones[i].number, r.zones[i].number.toInt())
            assertBits("zone $i lower", k.zones[i].lower, r.zones[i].lower)
            assertBits("zone $i upper", k.zones[i].upper, r.zones[i].upper)
            assertBits("zone $i lowerPct", k.zones[i].lowerPct, r.zones[i].lowerPct)
            assertBits("zone $i upperPct", k.zones[i].upperPct, r.zones[i].upperPct)
        }
    }

    /** Assert time-in-zone (seconds + below) and the serialized `zonesJSON` are bit/byte-identical. */
    private fun assertTimeInZoneParity(label: String, hr: List<HrSample>, age: Double, override: Double? = null) {
        val zoneSet = refZones(age, override)
        val k = refTimeInZone(hr, zoneSet)
        val r = RustScores.hrTimeInZone(hr, age = age, maxHrOverride = override)
        assertEquals("$label seconds length", k.seconds.size, r.seconds.size)
        for (i in k.seconds.indices) assertBits("$label zone ${i + 1} seconds", k.seconds[i], r.seconds[i])
        assertBits("$label belowZone1", k.belowZone1, r.belowZone1)
        // Stored-column form: the `{zone1..zone5}` object (WhoopCsvImporter.zonesJson shape). Identical
        // seconds → identical percentages → byte-identical JSON; this pins the persisted representation.
        assertEquals("$label zonesJSON bytes", zonesJson(k.seconds), zonesJson(r.seconds))
    }

    /** `{zone1..zone5}` percent-of-counted-time object, JSONObject-formatted exactly like the store site. */
    private fun zonesJson(seconds: List<Double>): String {
        val total = seconds.sum()
        val obj = JSONObject()
        for (i in 0 until 5) obj.put("zone${i + 1}", if (total > 0.0) seconds[i] / total * 100.0 else 0.0)
        return obj.toString()
    }

    // ── crafted golden stream (always runs) ──────────────────────────────────

    /**
     * Seven samples with timestamps 0,1,3,6,10,15,21 → SIX inter-sample gaps [1,2,3,4,5,6] (even count,
     * distinct). `medianInterval` = `gaps[3]` = 4 s (upper-middle); a mean-of-middles tie-break would give
     * 3.5 and change both the tail credit and the gap cap. bpm values walk below-Zone-1 → Zone 5 so every
     * bucket is exercised, and the last two gaps (5,6) are capped down to 4.
     */
    private fun goldenStream(): List<HrSample> = listOf(
        HrSample(dev, 0L, 80),   // below Zone 1
        HrSample(dev, 1L, 100),  // Zone 1
        HrSample(dev, 3L, 120),  // Zone 2
        HrSample(dev, 6L, 140),  // Zone 3
        HrSample(dev, 10L, 155), // Zone 4
        HrSample(dev, 15L, 175), // Zone 5
        HrSample(dev, 21L, 185), // Zone 5 (above HRmax → inclusive top)
    )

    @Test
    fun `rust hr-zones reproduce the Tanaka age zone set bit-for-bit`() {
        // Non-integer age → maxHR = 208 − 0.7·34.5 = 183.85 and ten fractional edges.
        assertZoneSetParity(age = 34.5)
    }

    @Test
    fun `rust hr-zones honour a manual max-HR override bit-for-bit`() {
        assertZoneSetParity(age = 34.5, override = 191.0)
    }

    @Test
    fun `rust time-in-zone matches the golden stream through the FFI`() {
        assertTimeInZoneParity("golden", goldenStream(), age = 34.5)
    }

    // ── real captured night (skips when the local fixture is absent) ──────────

    private val rrFixture: String = System.getProperty("noop.rrRealFixture")
        ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json"

    /**
     * Real WHOOP capture: the R-R nights in `rr-real-fixture.json` become an HR stream (bpm = 60000/rrMs
     * per beat, keeping same-second duplicates so the sub-second gap=0 guard is exercised on real data),
     * then the frozen Kotlin reference and the Rust FFI must agree bit-for-bit over the whole night.
     */
    @Test
    fun `rust time-in-zone matches a real captured night through the FFI`() {
        val f = File(rrFixture)
        assumeTrue("rr-real fixture absent (local-only), skipping: $rrFixture", f.exists())
        val root = JSONObject(f.readText())
        val nights = root.getJSONArray("nights")
        assumeTrue("fixture has no nights", nights.length() > 0)

        var scored = 0
        for (n in 0 until nights.length()) {
            val night = nights.getJSONObject(n)
            val rr = night.optJSONArray("rr") ?: continue
            val hr = ArrayList<HrSample>(rr.length())
            for (i in 0 until rr.length()) {
                val beat = rr.getJSONArray(i)
                val ts = beat.getLong(0)
                val rrMs = beat.getInt(1)
                if (rrMs <= 0) continue
                hr.add(HrSample(dev, ts, 60_000 / rrMs))
            }
            if (hr.isEmpty()) continue
            assertTimeInZoneParity("night $n", hr, age = 34.5)
            scored++
        }
        assumeTrue("fixture present but no scoreable nights", scored > 0)
    }
}
