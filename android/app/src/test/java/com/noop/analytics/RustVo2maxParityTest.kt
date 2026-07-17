package com.noop.analytics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Post-cutover regression guard for the VO2max / Fitness Age score. The Kotlin scorer
 * ([FitnessAgeEngine.estimateVO2max] / `fitnessAge` / `compute`) has been deleted — VO2max and the
 * self-consistent Fitness Age now route through the whoop-rs physio-algo FFI
 * ([RustScores.vo2maxEstimate] / [RustScores.fitnessAgeCompute] -> uniffi/JNA), and the store path
 * ([IntelligenceEngine.fitnessAgeRows]) writes the two Room columns `fitness_age` (= res.fitnessAge) and
 * `vo2max_est` (= res.vo2max) straight from it. Bit-for-bit parity with the old Kotlin twin was PROVEN
 * before deletion (the empirical analog of the decode cutover's 869/869); this file keeps guarding the
 * routed path two ways:
 *  - [Ref] is an independent, frozen re-encoding of the Nes-2011 waist-variant coefficients (the same
 *    published model, not the deleted production code) — the oracle the routed Rust FFI must equal
 *    BIT-FOR-BIT over the store-path derivation (median resting-HR, strain -> PA-index) across the
 *    golden weeks and the real captured nights;
 *  - the frozen Swift-parity anchor numbers (46.275 / 37.72 ml/kg/min, reference-person Fitness Age ==
 *    chrono age, the [20,80] clamps, the null gate) are asserted directly against the Rust FFI, so the
 *    reference itself stays pinned to the known-good cross-platform values.
 *
 * Equality against [Ref] is exact (delta 0.0): both sides evaluate the identical coefficient expression
 * in identical operation order over the same f64 inputs, so the IEEE-754 bits must match. A drift is
 * FAIL / BLOCKED, reported as a whoop-rs fix request — never weakened here. Loads the host libwhoop_ffi
 * via JNA (jna.library.path / buildRustHostDll).
 *
 * PARTIAL ROUTE (documented): the FFI takes `pa_index` PRE-COMPUTED (physio-algo's
 * `physical_activity_index_from_strain` is not yet exported — Tier-2 FFI gap #5), so
 * [FitnessAgeEngine.physicalActivityIndexFromStrain] stays Kotlin and is fed identically to both legs.
 */
class RustVo2maxParityTest {

    /**
     * Independent Nes-2011 waist-variant oracle (frozen coefficients). Replaces the deleted Kotlin
     * scorer as the bit-for-bit reference: same coefficient values, same operation order, same clamp.
     */
    private object Ref {
        private const val menIntercept = 100.27; private const val menAge = 0.296
        private const val menWC = 0.369; private const val menRHR = 0.155; private const val menPAI = 0.226
        private const val womenIntercept = 74.74; private const val womenAge = 0.247
        private const val womenWC = 0.259; private const val womenRHR = 0.114; private const val womenPAI = 0.198
        private const val restingHRReference = 65.0; private const val paiReference = 5.0
        private const val minAge = 20.0; private const val maxAge = 80.0

        // (intercept, ageC, wcC, rhrC, paiC) for the user's sex.
        private fun coeffs(sex: String): DoubleArray =
            if (sex.lowercase() == "female") doubleArrayOf(womenIntercept, womenAge, womenWC, womenRHR, womenPAI)
            else doubleArrayOf(menIntercept, menAge, menWC, menRHR, menPAI)

        fun vo2max(age: Double, sex: String, waistCm: Double, restingHR: Double, paIndex: Double): Double {
            val c = coeffs(sex)
            return c[0] - c[1] * age + c[4] * paIndex - c[2] * waistCm - c[3] * restingHR
        }

        fun fitnessAge(age: Double, sex: String, restingHR: Double, paIndex: Double): Double {
            val c = coeffs(sex)
            val fa = age + (c[3] * (restingHR - restingHRReference) - c[4] * (paIndex - paiReference)) / c[1]
            return fa.coerceIn(minAge, maxAge)
        }

        /** (fitnessAge, vo2max?) or null on the same gate the store path used (age<=0 || rhr<=0). */
        fun compute(age: Double, sex: String, restingHR: Double, paIndex: Double, waistCm: Double?): Pair<Double, Double?>? {
            if (age <= 0 || restingHR <= 0) return null
            val vo2 = if (waistCm != null && waistCm > 0) vo2max(age, sex, waistCm, restingHR, paIndex) else null
            return fitnessAge(age, sex, restingHR, paIndex) to vo2
        }
    }

    /** One week of the gate inputs [IntelligenceEngine.fitnessAgeRows] consumes, plus the profile. */
    private data class Week(
        val label: String,
        val age: Double,
        val sex: String,
        val waistCm: Double?,      // profile.waistCm > 0 ? value : null
        val rhrNights: List<Int>,  // DailyMetric.restingHr per gate night (Int column)
        val strainDays: List<Double>, // DailyMetric.strain per gate day
    )

    /** Byte-for-byte copy of IntelligenceEngine.medianOfDoubles (private there). */
    private fun medianOfDoubles(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Run the SAME derivation fitnessAgeRows does (median RHR + strain->PA-index), then compare the two
     * stored columns Rust-vs-reference with exact equality. PA-index is Kotlin on both legs (Tier-2 gap).
     */
    private fun assertWeekParity(w: Week) {
        val rhrs = w.rhrNights.map { it.toDouble() }
        val strains = w.strainDays.filter { it >= 30.0 }
        val meanStrain = if (strains.isEmpty()) 0.0 else strains.average()
        val restingHR = medianOfDoubles(rhrs)
        val paIndex = FitnessAgeEngine.physicalActivityIndexFromStrain(strains.size, meanStrain)
        val waist = w.waistCm?.takeIf { it > 0 }

        val ref = Ref.compute(w.age, w.sex, restingHR, paIndex, waistCm = waist)
        val rs = RustScores.fitnessAgeCompute(w.age, w.sex, restingHR, paIndex, waistCm = waist)

        if (ref == null) {
            assertNull("${w.label}: reference null but Rust non-null", rs)
            return
        }
        assertNotNull("${w.label}: Rust null but reference non-null", rs)

        // fitness_age Room column — the headline stored Double.
        assertEquals("${w.label}: fitness_age", ref.first, rs!!.fitnessAge, 0.0)

        // vo2max_est Room column — only written when a waist is present; must agree incl. null-ness.
        if (ref.second == null) {
            assertNull("${w.label}: vo2max_est should be null", rs.vo2max)
        } else {
            assertNotNull("${w.label}: vo2max_est unexpectedly null in Rust", rs.vo2max)
            assertEquals("${w.label}: vo2max_est (compute)", ref.second!!, rs.vo2max!!, 0.0)
            // Cross-check the standalone estimator against the same inputs the store path used.
            assertEquals(
                "${w.label}: vo2max_est (standalone)",
                ref.second!!,
                RustScores.vo2maxEstimate(w.age, w.sex, waist!!, restingHR, paIndex),
                0.0,
            )
        }
    }

    /**
     * Golden weeks spanning the documented input space: both sexes, non-binary, waist present/absent,
     * even/odd RHR-night counts (exercising the median /2.0 branch), the [20,80] clamp boundaries, empty
     * strains (PA-index 0), and the null-gate (age <= 0).
     */
    private val goldenWeeks = listOf(
        Week("fit-male-waist", 40.0, "male", 90.0,
            rhrNights = listOf(48, 50, 49, 51, 47, 50), strainDays = listOf(62.0, 71.0, 40.0, 58.0, 15.0, 66.0)),
        Week("avg-female-waist", 40.0, "female", 80.0,
            rhrNights = listOf(64, 66, 65, 67, 65), strainDays = listOf(45.0, 52.0, 38.0)),
        Week("male-no-waist", 33.0, "male", null,
            rhrNights = listOf(55, 57, 56, 58), strainDays = listOf(60.0, 61.0, 59.0, 62.0)),
        Week("nonbinary-waist", 45.0, "nonbinary", 88.0,
            rhrNights = listOf(60, 62, 61), strainDays = listOf(50.0, 55.0)),
        Week("unfit-clamp-high", 75.0, "male", 110.0,
            rhrNights = listOf(120, 118, 121), strainDays = emptyList()),
        Week("veryfit-clamp-low", 25.0, "male", 78.0,
            rhrNights = listOf(35, 34, 36, 35), strainDays = listOf(90.0, 92.0, 88.0, 91.0, 95.0, 89.0, 90.0)),
        Week("no-active-days", 50.0, "female", 82.0,
            rhrNights = listOf(70, 72), strainDays = listOf(12.0, 20.0)), // all < 30 -> filtered empty -> PA 0
        Week("null-gate-age0", 0.0, "male", 90.0,
            rhrNights = listOf(50, 51), strainDays = listOf(60.0)),
    )

    @Test
    fun `rust vo2max and fitness age match the reference stored columns bit-for-bit`() {
        for (w in goldenWeeks) assertWeekParity(w)
    }

    /**
     * Frozen Swift-parity anchors, now asserted against the Rust FFI (they moved here when the Kotlin
     * scorer's own unit test was retired): the published VO2max figures, the reference-person identity
     * (average fitness -> chrono age), the [20,80] clamps, waist null-ness, and the null gate.
     */
    @Test
    fun `rust matches the frozen swift-parity anchor numbers`() {
        assertEquals(46.275, RustScores.vo2maxEstimate(40.0, "male", 90.0, 65.0, 5.0), 1e-3)
        assertEquals(37.72, RustScores.vo2maxEstimate(40.0, "female", 80.0, 65.0, 5.0), 1e-3)

        assertEquals(40.0, RustScores.fitnessAgeCompute(40.0, "male", 65.0, 5.0)!!.fitnessAge, 1e-9)
        assertEquals(55.0, RustScores.fitnessAgeCompute(55.0, "female", 65.0, 5.0)!!.fitnessAge, 1e-9)
        assertEquals(28.33, RustScores.fitnessAgeCompute(40.0, "male", 50.0, 10.0)!!.fitnessAge, 0.05)
        assertEquals(50.15, RustScores.fitnessAgeCompute(40.0, "male", 80.0, 2.0)!!.fitnessAge, 0.05)
        assertEquals(80.0, RustScores.fitnessAgeCompute(75.0, "male", 120.0, 0.0)!!.fitnessAge, 1e-9)
        assertEquals(20.0, RustScores.fitnessAgeCompute(25.0, "male", 35.0, 15.0)!!.fitnessAge, 1e-9)

        val ref = RustScores.fitnessAgeCompute(40.0, "male", 65.0, 5.0)!!
        assertEquals(0.0, ref.deltaYears, 1e-9)
        assertNull("no waist -> null vo2max", ref.vo2max)
        assertEquals(46.275, RustScores.fitnessAgeCompute(40.0, "male", 65.0, 5.0, waistCm = 90.0)!!.vo2max!!, 1e-3)
        assertTrue("nonbinary -> lower confidence", RustScores.fitnessAgeCompute(40.0, "nonbinary", 60.0, 6.0)!!.lowerConfidence)
        assertNull("age<=0 -> null", RustScores.fitnessAgeCompute(0.0, "male", 65.0, 7.5))
        assertNull("rhr<=0 -> null", RustScores.fitnessAgeCompute(40.0, "male", 0.0, 7.5))
    }

    // ── Real-data leg (David's own captured nights) ───────────────────────────────────────────────
    // Feeds a REAL resting-HR series derived from the local rr-real-fixture (per-night 60000/median(RR),
    // rounded to the Int the restingHr column stores) into the same VO2max/Fitness-Age store path, so the
    // parity is proven on real device numbers, not only crafted ones. The fixture holds personal
    // biometrics, so it is NOT committed — the test reads an absolute path (override -Dnoop.rrFixture=...)
    // and SKIPS when absent, exactly like the agreement suite, so it never runs in CI.
    private val rrFixturePath: String =
        System.getProperty("noop.rrFixture") ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json"

    @Test
    fun `rust matches reference on resting-HR derived from real captured nights`() {
        val f = File(rrFixturePath)
        assumeTrue("real R-R fixture absent (local-only), skipping: $rrFixturePath", f.exists())

        val nights = JSONObject(f.readText()).getJSONArray("nights")
        val rhrNights = ArrayList<Int>(nights.length())
        for (i in 0 until nights.length()) {
            val rrJson = nights.getJSONObject(i).getJSONArray("rr")
            val ms = ArrayList<Double>(rrJson.length())
            for (j in 0 until rrJson.length()) ms.add(rrJson.getJSONArray(j).getDouble(1))
            if (ms.isEmpty()) continue
            rhrNights.add(Math.round(60_000.0 / medianOfDoubles(ms)).toInt())
        }
        assumeTrue("no usable nights in fixture", rhrNights.isNotEmpty())

        // A fixed representative profile + weekly strain; the RHR series is the real, moving input.
        val w = Week("real-rr-nights", age = 30.0, sex = "male", waistCm = 84.0,
            rhrNights = rhrNights, strainDays = listOf(55.0, 62.0, 40.0, 58.0, 66.0))
        assertWeekParity(w)
        println("[vo2max-parity] real leg: ${rhrNights.size} nights, median RHR=${medianOfDoubles(rhrNights.map { it.toDouble() })}")
        assertTrue(rhrNights.isNotEmpty())
    }
}
