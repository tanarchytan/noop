package com.noop.analytics

import com.noop.data.HrSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Post-cutover regression guard for the Recovery / Charge score. The Kotlin composite scorer has been
 * deleted (recovery now routes through the whoop-rs physio-algo FFI, [RustScores.recovery]); its
 * bit-for-bit parity with the old Kotlin twin was PROVEN before deletion, the empirical analog of the
 * decode cutover's 869/869. This file keeps guarding the routed path two ways:
 *  - the composite [RustScores.recovery] is replayed over the SAME real fixture cases the correctness
 *    pin uses ([com.noop.analytics.agreement.RecoveryAgreementTest], recovery_cases.json) and asserted
 *    against the independent numpy reference in that fixture (the stored DailyMetric.recovery is written
 *    straight from it at AnalyticsEngine.kt:506, with NO rounding);
 *  - the auxiliary recovery FFI fns ([RustScores.band], [RustScores.recoveryIndexSlope],
 *    [RustScores.bankedNights]) are still pinned bit-for-bit against their KEPT Kotlin twins
 *    ([RecoveryScorer.band] / [RecoveryScorer.recoveryIndexSlope] / [RecoveryScorer.bankedNights]) on
 *    the same/crafted inputs — a true Kotlin-vs-Rust parity assertion.
 *
 * A drift here is FAIL / BLOCKED, to be reported as a whoop-rs fix request (the 17 traps — term
 * insertion order, logisticK=1.6/z0=-0.20, omit-and-renormalize nulls, baseline fold order, sleep term
 * = rest/100 not efficiency — all live in whoop-rs's lane). It is NEVER weakened here. Loads the host
 * libwhoop_ffi via JNA (jna.library.path / buildRustHostDll).
 */
class RustRecoveryParityTest {

    // Same fixture + resolution as RecoveryAgreementTest (local-only; test SKIPS when absent).
    private val path: String = (System.getProperty("noop.hrvGoldFixtures")
        ?: "C:/Users/DavidGillot/Projects/whoop/whoop data/datasets/agreement-fixtures") + "/recovery_cases.json"

    private fun baseline(o: JSONObject, meanKey: String, spreadKey: String): RecoveryScorer.DriverBaseline? =
        if (o.isNull(meanKey)) null
        else RecoveryScorer.DriverBaseline(mean = o.getDouble(meanKey), spread = o.getDouble(spreadKey))

    private fun optD(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.getDouble(k)

    /** Bit-for-bit nullable-Double equality — the stored-column contract. */
    private fun assertSameDouble(msg: String, expected: Double?, actual: Double?) {
        if (expected == null || actual == null) {
            assertEquals(msg, expected, actual) // one null → both must be null
            return
        }
        assertEquals("$msg (raw bits)", expected.toRawBits(), actual.toRawBits())
    }

    @Test
    fun `recovery composite matches the pinned reference across fixture cases`() {
        val f = File(path)
        assumeTrue("recovery fixture absent (local-only), skipping: $path", f.exists())
        val arr = org.json.JSONArray(f.readText())

        var scored = 0
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)

            // Store-site driver inputs (mirrors the AnalyticsEngine.kt:506 store call): hrv, rhr, resp,
            // the three baselines, sleepPerf, skinTempDev, hrvBaselineUsable. The dormant Oura terms
            // (recoveryIndexSlope/effort) stay at their null defaults, exactly as the store site leaves them.
            val rustVal = RustScores.recovery(
                hrv = c.getDouble("hrv"),
                rhr = c.getDouble("rhr"),
                resp = optD(c, "resp"),
                hrvBaseline = baseline(c, "hrvBaselineMean", "hrvBaselineSpread"),
                rhrBaseline = baseline(c, "rhrBaselineMean", "rhrBaselineSpread"),
                respBaseline = baseline(c, "respBaselineMean", "respBaselineSpread"),
                sleepPerf = optD(c, "sleepPerf"),
                skinTempDev = optD(c, "skinTempDev"),
                hrvBaselineUsable = c.getBoolean("hrvBaselineUsable"),
            )

            // The Kotlin composite is gone; guard the routed Rust value against the fixture's independent
            // numpy reference (the exact z-score + logistic replication), the same pin RecoveryAgreementTest
            // holds, including the null-semantics (cold-start / dropped-term renormalisation).
            if (c.isNull("expectedRecovery")) {
                assertEquals("case $i expected null recovery", null, rustVal)
            } else {
                val exp = c.getDouble("expectedRecovery")
                requireNotNull(rustVal) { "case $i: rust returned null, expected $exp" }
                assertEquals("case $i recovery (stored DailyMetric.recovery)", exp, rustVal, 1e-6)
            }
            scored++
        }
        assertTrue("no cases scored", scored > 0)
    }

    @Test
    fun `recovery band matches rust ffi across the color thresholds`() {
        // Feed identical scores into both band fns to isolate the band logic (red<34, yellow<67,
        // green) from the composite — including the exact threshold values and their < boundaries.
        val scores = listOf(
            0.0, 33.999, 34.0, 34.001, 50.0,
            66.999, 67.0, 67.001, 85.0, 100.0,
            RecoveryScorer.bandRedMax, RecoveryScorer.bandYellowMax,
        )
        for (s in scores) {
            assertEquals("band at $s", RecoveryScorer.band(s), RustScores.band(s))
        }
    }

    @Test
    fun `recovery index slope matches rust ffi on a crafted declining night`() {
        // Deterministic in-bed window with a clear HR decline (well over recoveryIndexMinBins of
        // 5-min bins), so both paths fit the same least-squares slope over identical bin means.
        val dev = "d"
        val start = 1_749_513_600L + 3_600L
        val durS = 40 * 60 // 40 min → 8 five-minute bins > the 6-bin floor
        val end = start + durS
        val hr = ArrayList<HrSample>(durS)
        for (i in 0 until durS) {
            // 72 → 60 bpm linear decline across the window (integer bpm, identical on both sides).
            val bpm = (72.0 - 12.0 * (i.toDouble() / durS.toDouble())).roundToInt()
            hr.add(HrSample(dev, start + i, bpm))
        }
        val kotlinSlope = RecoveryScorer.recoveryIndexSlope(hr, start, end)
        val rustSlope = RustScores.recoveryIndexSlope(hr, start, end)
        assertSameDouble("recovery index slope", kotlinSlope, rustSlope)
    }

    /**
     * Frozen, byte-identical copy of the DELETED `RecoveryScorer.recovery` composite (the primary
     * `DriverBaseline` overload) — same term insertion order, same weights/constants (read live off the
     * surviving [RecoveryScorer]), same `sumOf` left-to-right f64 accumulation, same drop-and-renormalise
     * null semantics, same `100/(1+exp(-K(z-Z0)))` logistic and `max(0, min(100, ·))` clamp, computed via
     * the SURVIVING [RecoveryScorer.zScore] and Kotlin `exp`. This is the exact value the pre-cutover Kotlin
     * scorer wrote to the RAW (un-rounded) `DailyMetric.recovery` column, restated locally the same way
     * [RustStressParityTest] restates the deleted Baevsky histogram. The routed [RustScores.recovery] must
     * reproduce it at machine precision.
     */
    private fun refRecovery(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: RecoveryScorer.DriverBaseline?,
        rhrBaseline: RecoveryScorer.DriverBaseline?,
        respBaseline: RecoveryScorer.DriverBaseline?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        hrvBaselineUsable: Boolean = true,
        recoveryIndexSlope: Double? = null,
        effortBaseline: RecoveryScorer.DriverBaseline? = null,
        priorDayEffort: Double? = null,
    ): Double? {
        if (!hrvBaselineUsable) return null
        val terms = ArrayList<Pair<Double, Double>>()
        hrvBaseline?.let { b -> terms.add(RecoveryScorer.zScore(hrv, b.mean, b.spread) to RecoveryScorer.wHRV) }
        rhrBaseline?.let { b -> terms.add(RecoveryScorer.zScore(b.mean, rhr, b.spread) to RecoveryScorer.wRHR) }
        if (resp != null && respBaseline != null) {
            terms.add(RecoveryScorer.zScore(respBaseline.mean, resp, respBaseline.spread) to RecoveryScorer.wResp)
        }
        if (sleepPerf != null) {
            terms.add(((sleepPerf - RecoveryScorer.sleepPerfCenter) / RecoveryScorer.sleepPerfScale) to RecoveryScorer.wSleep)
        }
        if (skinTempDev != null) {
            terms.add((-abs(skinTempDev) / RecoveryScorer.skinTempDevScale) to RecoveryScorer.wSkinTemp)
        }
        if (recoveryIndexSlope != null) {
            terms.add((-recoveryIndexSlope / RecoveryScorer.recoveryIndexScaleBpmPerHr) to RecoveryScorer.wRecoveryIndex)
        }
        if (priorDayEffort != null && effortBaseline != null) {
            terms.add(RecoveryScorer.zScore(effortBaseline.mean, priorDayEffort, effortBaseline.spread) to RecoveryScorer.wActivityBalance)
        }
        if (terms.isEmpty()) return null
        val totalWeight = terms.sumOf { it.second }
        if (totalWeight <= 0.0) return null
        val z = terms.sumOf { it.first * it.second } / totalWeight
        val score = 100.0 / (1.0 + exp(-RecoveryScorer.logisticK * (z - RecoveryScorer.logisticZ0)))
        return max(0.0, min(100.0, score))
    }

    /**
     * DURABILITY PIN (closes the recovery HIGH). The correctness pin above asserts the routed score against
     * an independent numpy reference at 1e-6 — loose enough that a future whoop-rs edit could silently drift
     * the RAW stored `DailyMetric.recovery` Double (written un-rounded at AnalyticsEngine.kt:507 /
     * IntelligenceEngine.recomputeRecovery:1291) yet still pass. This pins [RustScores.recovery] to the EXACT
     * value the deleted Kotlin twin produced ([refRecovery]) at machine precision. The composite is pure
     * arithmetic bar one transcendental, so the driver z and the weighted mean are bit-identical on both
     * sides; the only admissible residual is the last-ulp gap between Kotlin `exp` and Rust `f64::exp`
     * (bounded ≈ 1e-13 for a 0–100 score), so the tolerance is [machinePrecision], ~1e6 tighter than the
     * gold pin. Any structural drift (a changed weight, term order, renormalisation, or clamp) exceeds it and
     * fails — to be reported as a whoop-rs fix, never widened. CI-safe: crafted in-memory cases run without
     * the local fixture.
     */
    @Test
    fun `recovery matches the exact deleted Kotlin composite at machine precision`() {
        val machinePrecision = 1e-12
        val b = { mean: Double, spread: Double -> RecoveryScorer.DriverBaseline(mean, spread) }

        // (label, () -> refValue, () -> rustValue) over the full term matrix + the null-semantics edges.
        data class Case(val label: String, val ref: () -> Double?, val rust: () -> Double?)
        val cases = listOf(
            Case(
                "all-terms typical night",
                { refRecovery(55.0, 52.0, 14.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.9, 0.4) },
                { RustScores.recovery(55.0, 52.0, 14.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.9, 0.4) },
            ),
            Case(
                "skin-temp term dropped",
                { refRecovery(61.0, 48.0, 13.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.95, null) },
                { RustScores.recovery(61.0, 48.0, 13.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.95, null) },
            ),
            Case(
                "resp + sleep dropped, rhr baseline null",
                { refRecovery(40.0, 60.0, null, b(50.0, 5.0), null, null, null, 0.2) },
                { RustScores.recovery(40.0, 60.0, null, b(50.0, 5.0), null, null, null, 0.2) },
            ),
            Case(
                "Oura terms (recovery-index slope + prior-day effort) folded in",
                {
                    refRecovery(
                        52.0, 53.0, 15.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.88, 0.1,
                        recoveryIndexSlope = -3.0, effortBaseline = b(40.0, 12.0), priorDayEffort = 70.0,
                    )
                },
                {
                    RustScores.recovery(
                        52.0, 53.0, 15.0, b(50.0, 5.0), b(55.0, 3.0), b(14.5, 1.0), 0.88, 0.1,
                        recoveryIndexSlope = -3.0, effortBaseline = b(40.0, 12.0), priorDayEffort = 70.0,
                    )
                },
            ),
            Case(
                "cold-start (hrv baseline not usable) → null",
                { refRecovery(60.0, 50.0, null, b(50.0, 5.0), null, null, null, hrvBaselineUsable = false) },
                { RustScores.recovery(60.0, 50.0, null, b(50.0, 5.0), null, null, null, hrvBaselineUsable = false) },
            ),
            Case(
                "no scorable driver (every term drops) → null",
                { refRecovery(60.0, 50.0, null, null, null, null, null) },
                { RustScores.recovery(60.0, 50.0, null, null, null, null, null) },
            ),
        )

        var maxErr = 0.0
        var bitForBit = true
        for (c in cases) {
            val ref = c.ref()
            val rust = c.rust()
            if (ref == null || rust == null) {
                assertEquals("${c.label}: null-semantics must agree", ref, rust)
                continue
            }
            val err = abs(ref - rust)
            maxErr = max(maxErr, err)
            if (ref.toRawBits() != rust.toRawBits()) bitForBit = false
            assertTrue(
                "${c.label}: routed recovery ($rust) drifted from the deleted Kotlin composite ($ref) by " +
                    "$err > $machinePrecision — structural parity break, report as a whoop-rs fix",
                err <= machinePrecision,
            )
        }
        println("[recovery] machine-precision pin vs deleted Kotlin composite: maxErr=$maxErr bitForBit=$bitForBit")
    }

    @Test
    fun `banked nights matches rust ffi`() {
        // Mix of null, clearly-in-range, and clearly-out-of-range nightly HRV so both sides apply
        // the SAME hrv-config validity predicate (in-range count, not just non-null).
        val nightly = listOf(
            45.0, null, 60.0, 2.0 /* implausibly low */, 30.0,
            9_999.0 /* implausibly high */, null, 55.0, 40.0, 50.0,
        )
        assertEquals("banked nights", RecoveryScorer.bankedNights(nightly), RustScores.bankedNights(nightly))
    }
}
