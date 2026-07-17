package com.noop.analytics

import com.noop.data.HrSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * App-side parity gate for routing Day Strain ("Effort") through whoop-rs. The 0–100 logarithmic
 * cardiovascular-load score is about to move from the Kotlin [StrainScorer] to physio-algo (via the
 * [RustScores] bridge → uniffi → whoop-rs `strain_score`); this pins that swap by replaying the SAME
 * HR series through BOTH paths and asserting the returned value is bit-for-bit identical — the exact
 * Double that [com.noop.analytics.AnalyticsEngine] stores in `DailyMetric.strain` (and per-bout
 * `WorkoutRow.strain`). Loads the host libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll),
 * so the Rust leg decodes+scores through the real compiled FFI, not a stub.
 *
 * A score PASSES only on exact equality. The likely divergence points (all in whoop-rs' lane, so a
 * FAIL here is a legitimate fix-request, not something to weaken the assertion around):
 *   • PIN #5   — maxStrain = 100.0 and the log-map denominator D = 7201.0 must be byte-identical.
 *   • trap #2  — [StrainScorer.trimpToStrain] rounds the 2-dp result half-UP via `roundToLong`; the
 *                Rust twin must round the same way (a half-even twin would drift at ties).
 *   • float accumulation order — Banister sums `duration·x·0.64·e^{b·x}` sample-by-sample; the Rust
 *                loop must accumulate in the identical order or the last ULP diverges.
 *   • trap #17 (numpy-linear percentile) is NOT on this path: `strain()` takes an explicit `maxHR`
 *                (the store site passes [StrainScorer.tanakaHRmax]) and falls back to `220−age`, never
 *                to the observed-percentile HRmax — that helper ([StrainScorer.estimateHRmax]) is a
 *                Tier-2 concern used elsewhere, so it is out of scope for the stored strain value.
 *
 * Equality is asserted on the raw IEEE-754 bits (see [assertBitEq]) — the strictest reading of "the
 * exact Room column value": −0.0 ≠ 0.0, and no silent tolerance is smuggled in.
 */
class RustStrainParityTest {

    // ── bit-exact comparison of the stored Double? ──────────────────────────────

    private fun assertBitEq(msg: String, kotlin: Double?, rust: Double?) {
        if (kotlin == null || rust == null) {
            assertEquals(msg, kotlin as Any?, rust as Any?) // both must be null, or fail loudly
            return
        }
        assertEquals(
            "$msg (kotlin=$kotlin rust=$rust)",
            java.lang.Double.doubleToLongBits(kotlin),
            java.lang.Double.doubleToLongBits(rust),
        )
    }

    /** Run the Kotlin scorer and the Rust FFI on the identical inputs, assert the stored value matches. */
    private fun assertStrainParity(
        label: String,
        hr: List<HrSample>,
        maxHR: Double?,
        restingHR: Double,
        method: StrainScorer.Method,
        sex: String,
    ) {
        // The denominator the store site uses is StrainScorer.strainDenominator (default arg on
        // StrainScorer.strain); the FFI takes it explicitly, so pass the Rust-side default and let the
        // PIN test below prove the two constants agree.
        val kotlinVal = StrainScorer.strain(hr, maxHR, restingHR, method, sex, StrainScorer.strainDenominator)
        val rustVal = RustScores.strain(hr, maxHR, restingHR, method, sex, RustScores.strainDenominator())
        assertBitEq("$label [$method sex=$sex maxHR=$maxHR rhr=$restingHR n=${hr.size}]", kotlinVal, rustVal)
    }

    // ── deterministic real-shaped HR days (no RNG → reproducible, identical to both legs) ───────────

    private val dev = "t"
    private val start = 1_783_651_476L

    /**
     * A dense 1 Hz day with a warm-up → hard workout block → cool-down → easy-evening envelope so the
     * bpm series crosses every Edwards %HRR zone and oscillates inside the top block — that makes the
     * accumulated TRIMP a "messy" real number and stresses the 2-dp half-up rounding + Banister's
     * exp-sum ordering.
     */
    private fun workoutDay(n: Int = 4800): List<HrSample> = (0 until n).map { i ->
        val bpm = when {
            i < 600 -> 55                       // warm rest
            i < 1800 -> 55 + (i - 600) / 12     // ramp 55 → 155
            i < 3000 -> 150 + (i / 7) % 30      // hard block, 150..179
            i < 4200 -> 150 - (i - 3000) / 12   // cool-down 150 → 50
            else -> 60 + i % 11                 // easy evening
        }
        HrSample(dev, start + i, bpm)
    }

    /** A sparse 30 s-cadence stream (the WHOOP 5/MG live-HR rate, #482 path). */
    private fun sparseDay(bpm: (Int) -> Int, n: Int, stepS: Int = 30): List<HrSample> =
        (0 until n).map { HrSample(dev, start + (it * stepS).toLong(), bpm(it)) }

    // ── tests ───────────────────────────────────────────────────────────────────

    @Test
    fun `PIN 5 - denominator and scale constants match the FFI`() {
        assertBitEq("strain denominator (D = 7201.0)", StrainScorer.strainDenominator, RustScores.strainDenominator())
        // maxStrain (100.0) is baked into strain_score on both sides; the day-parity tests below prove
        // the full 100 × ln(t+1)/ln(D) map end-to-end. Pin the Kotlin constant here as the local anchor.
        assertEquals(100.0, StrainScorer.maxStrain, 0.0)
    }

    @Test
    fun `store-site config parity on a real-shaped workout day`() {
        // The exact combo AnalyticsEngine stores: Edwards, sex from profile, maxHR = tanakaHRmax(age),
        // restingHR = daily RHR (fell back to a plausible value here), default denominator.
        val hr = workoutDay()
        assertStrainParity("store-site", hr, StrainScorer.tanakaHRmax(33.0), 52.0, StrainScorer.Method.EDWARDS, "male")
    }

    @Test
    fun `edwards parity is sex-independent and matches across HRmax and RHR`() {
        val hr = workoutDay()
        for (sex in listOf("male", "female")) {
            assertStrainParity("edwards", hr, 184.9, 52.0, StrainScorer.Method.EDWARDS, sex)
            assertStrainParity("edwards-alt", hr, 190.0, 48.0, StrainScorer.Method.EDWARDS, sex)
        }
    }

    @Test
    fun `banister exp-sum parity holds for both coefficient branches`() {
        // Banister picks b = 1.92 (male) vs 1.67 (female) and accumulates e^{b·x} per sample; this pins
        // the accumulation order + coefficient selection against a last-ULP drift.
        val hr = workoutDay()
        assertStrainParity("banister-male", hr, 184.9, 52.0, StrainScorer.Method.BANISTER, "male")
        assertStrainParity("banister-female", hr, 184.9, 52.0, StrainScorer.Method.BANISTER, "female")
    }

    @Test
    fun `sparse 30s cadence parity (#482 live-strap path)`() {
        // 40 samples × 30 s spans ≥ minSpanSeconds so it scores rather than returning null; the inferred
        // per-sample duration (0.5 min from the first two ts) must match on both sides.
        val hr = sparseDay({ 150 + it % 25 }, n = 40)
        assertNotNull("sparse day should score (spans enough time)", StrainScorer.strain(hr, 184.9, 52.0))
        assertStrainParity("sparse", hr, 184.9, 52.0, StrainScorer.Method.EDWARDS, "male")
        assertStrainParity("sparse-banister", hr, 184.9, 52.0, StrainScorer.Method.BANISTER, "male")
    }

    @Test
    fun `honest-zero and null-gate parity`() {
        // A light day (all bpm below zone 1) scores an honest 0.0 on both paths — NOT null, NOT fabricated.
        val light = (0 until 1200).map { HrSample(dev, start + it, 100) } // 100 bpm, maxHR 184 rest 60 → below z1
        assertStrainParity("light-day-zero", light, 184.0, 60.0, StrainScorer.Method.EDWARDS, "male")

        // Too few samples / invalid HRR → both return null (the bit-eq helper asserts both-null).
        val tooFew = (0 until 599).map { HrSample(dev, start + it, 140) }
        assertStrainParity("too-few-null", tooFew, 184.0, 60.0, StrainScorer.Method.EDWARDS, "male")
        val invalidHrr = workoutDay(700)
        assertStrainParity("invalid-hrr-null", invalidHrr, 60.0, 60.0, StrainScorer.Method.EDWARDS, "male")
    }

    // ── real captured R-R → HR, parity on a genuine WHOOP 5.0 night (local-only, SKIPS in CI) ───────

    private val rrFixturePath: String =
        System.getProperty("noop.rrFixture") ?: "C:/Users/DavidGillot/Projects/whoop/whoop data/rr-real-fixture.json"

    @Test
    fun `parity on real captured WHOOP 5_0 nights (rr-derived HR)`() {
        val f = File(rrFixturePath)
        assumeTrue("real R-R fixture absent (local-only), skipping: $rrFixturePath", f.exists())

        val nights = JSONObject(f.readText()).getJSONArray("nights")
        var scored = 0
        for (i in 0 until nights.length()) {
            val night = nights.getJSONObject(i)
            val rrJson = night.getJSONArray("rr")
            // Derive an HR sample per real beat (bpm = round(60000/rrMs)) at the beat's real ts — a
            // genuine, temporally-varied HR series (identical to both legs, which is all parity needs).
            val hr = ArrayList<HrSample>(rrJson.length())
            for (j in 0 until rrJson.length()) {
                val beat = rrJson.getJSONArray(j)
                val ms = beat.getInt(1)
                if (ms <= 0) continue
                hr.add(HrSample(dev, beat.getLong(0), (60_000.0 / ms).roundToInt()))
            }
            if (hr.size < StrainScorer.minReadings) continue
            for (method in StrainScorer.Method.entries) {
                assertStrainParity("real-night-${night.optString("date", "$i")}", hr,
                    StrainScorer.tanakaHRmax(33.0), 50.0, method, "male")
            }
            scored++
        }
        assumeTrue("no scoreable real nights in fixture", scored > 0)
        println("[strain] real-night parity: $scored WHOOP 5.0 nights, bit-identical Kotlin vs Rust")
    }
}
