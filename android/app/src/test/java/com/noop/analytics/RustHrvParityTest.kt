package com.noop.analytics

import com.noop.data.RrInterval
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Golden-value parity for the nightly HRV / RMSSD score (stored as `DailyMetric.avgHrv` /
 * `SleepSession.avgHrv`). Sibling of [RustSleepStagerParityTest]: it runs the Kotlin scorer
 * ([HrvAnalyzer.analyzeRaw] → the gap-aware RMSSD that IntelligenceEngine stores) AND the whoop-rs
 * FFI ([RustScores.rmssdGapAware] → uniffi → physio-algo) on the SAME R-R and asserts the two are
 * bit-for-bit identical — the empirical parity gate before the Kotlin math is deleted.
 *
 * The traps this pins (per the cutover scope):
 *   #3  rmssdGapAware divides by the CONTIGUOUS-pair COUNT, not n-1: any splice the two sides disagree
 *       on changes the divide-base and the value.
 *   #1  the Malik ectopic filter's local median uses averaging-of-middles on an even window
 *       ([HrvAnalyzer.median]); a different tie-break drops a different beat.
 *   range + ectopic drop bookkeeping: the FFI must reproduce Kotlin's exact keep/drop mask (which beats
 *   survive, and where the successive-difference splices land) or the RMSSD diverges.
 *
 * assertions are EXACT (raw IEEE-754 long bits) — a 1-ULP drift is a FAIL, not a pass, because the stored
 * Room column is the exact Double the scorer returns. A FAIL here is a legitimate outcome to report as a
 * whoop-rs twin-alignment request, NOT something to weaken.
 *
 * Loads the host libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll), same as the sleep twin.
 * The crafted-golden case always runs; the two real-fixture legs SKIP when the local (uncommitted,
 * PII-bearing) fixtures are absent so CI stays green.
 */
class RustHrvParityTest {

    private val dev = "d"

    /** Both must be null together, or both non-null and byte-identical (raw long bits). */
    private fun assertRmssdParity(msg: String, kotlin: Double?, rust: Double?) {
        if (kotlin == null || rust == null) {
            assertEquals("$msg null-ness (Kotlin vs Rust)", kotlin, rust)
            return
        }
        assertEquals(
            "$msg RMSSD bits (Kotlin=$kotlin Rust=$rust)",
            java.lang.Double.doubleToLongBits(kotlin),
            java.lang.Double.doubleToLongBits(rust),
        )
    }

    /** Kotlin STORED value: the exact expression IntelligenceEngine persists as avgHrv (analyzeRaw.rmssd). */
    private fun kotlinStored(rows: List<RrInterval>): Double? =
        HrvAnalyzer.analyzeRaw(rows.map { it.rrMs.toDouble() }).rmssd

    // ── crafted golden (always runs, no fixture) ─────────────────────────────

    /**
     * A frozen, artifact-free dense night: smooth RSA + slow drift, every beat in [300,2000], every
     * successive delta well under the ectopic thresholds, unique 1 Hz timestamps (no >5 s run break).
     * On this shape both algorithms reduce to sqrt(Σ successive² / (n-1)) over the identical beat order,
     * so a PASS here isolates the bridge/FFI plumbing — any drift on the real-fixture legs is then purely
     * the cleaning/gap divergence, not a mapping bug.
     */
    private fun goldenNight(): List<RrInterval> {
        val start = 1_749_513_600L
        val n = 240
        val rows = ArrayList<RrInterval>(n)
        for (i in 0 until n) {
            val rsa = 35.0 * sin(2.0 * Math.PI * i / 12.0)   // respiratory sinus arrhythmia
            val drift = 10.0 * sin(2.0 * Math.PI * i / 300.0) // slow trend
            val rr = (900.0 + rsa + drift).roundToInt()       // ints: this is the stored rrMs column type
            rows.add(RrInterval(dev, start + i, rr))
        }
        return rows
    }

    @Test
    fun `rust rmssd matches the kotlin stored value on the clean golden night`() {
        val rows = goldenNight()
        val kotlin = kotlinStored(rows)
        val rust = RustScores.rmssdGapAware(rows)
        assertNotNull("golden Kotlin RMSSD should be computable", kotlin)
        assertTrue("golden RMSSD should be positive", (kotlin ?: 0.0) > 0.0)
        assertRmssdParity("golden night", kotlin, rust)
    }

    // ── real backup nights (rr-real-fixture.json) ────────────────────────────

    /**
     * The truest stored-path shape: real captured R-R (integer ts+rrMs, multiple beats per second) from a
     * noop backup, the same fixture [com.noop.analytics.agreement.RealDataRundownTest] replays. Each night
     * is scored WHOLE (the avgHrv window), not per-bucket. Local-only, skipped when absent.
     */
    @Test
    fun `rust rmssd matches the kotlin stored value on real backup nights`() {
        val path = System.getProperty("noop.rrFixture")
            ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json"
        val f = File(path)
        assumeTrue("real R-R fixture absent (local-only), skipping: $path", f.exists())

        val nights = JSONObject(f.readText()).getJSONArray("nights")
        var scored = 0
        for (i in 0 until nights.length()) {
            val o = nights.getJSONObject(i)
            val rrJson = o.getJSONArray("rr")
            val rows = ArrayList<RrInterval>(rrJson.length())
            for (j in 0 until rrJson.length()) {
                val p = rrJson.getJSONArray(j)
                rows.add(RrInterval(dev, p.getLong(0), p.getInt(1)))
            }
            if (rows.isEmpty()) continue
            val date = o.optString("date", "night$i")
            assertRmssdParity("real night $date (${rows.size} beats)", kotlinStored(rows), RustScores.rmssdGapAware(rows))
            scored++
        }
        assertTrue("fixture present but no night scored", scored > 0)
    }

    // ── gold agreement-fixtures (reused from HrvGoldAgreementTest) ────────────

    /**
     * The GalaxyPPG / AAUWSS gold NN windows [com.noop.analytics.agreement.HrvGoldAgreementTest] loads.
     * Those windows are already-clean NN arrays; here they are mapped to the stored column type (integer
     * rrMs, realistic cumulative-sum timestamps so consecutive beats stay inside one <5 s run) and pushed
     * through BOTH scorers. Local-only, skipped when absent.
     */
    @Test
    fun `rust rmssd matches the kotlin stored value on gold nn windows`() {
        val dir = System.getProperty("noop.hrvGoldFixtures")
            ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/agreement-fixtures"
        val fixtures = File(dir).listFiles { fl -> fl.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("gold HRV fixtures absent (local-only), skipping: $dir", fixtures.isNotEmpty())

        var windows = 0
        for (fl in fixtures) {
            val text = fl.readText().trimStart()
            if (!text.startsWith("{")) continue      // array fixtures belong to other agreement tests
            val obj = JSONObject(text)
            if (!obj.has("windows")) continue
            val src = obj.optString("source", fl.nameWithoutExtension)
            val ws = obj.getJSONArray("windows")
            for (i in 0 until ws.length()) {
                val w = ws.getJSONObject(i)
                val nnArr = w.optJSONArray("nn") ?: continue   // e.g. optical-vs-gold windows carry no NN
                if (nnArr.length() < 2) continue
                val rows = ArrayList<RrInterval>(nnArr.length())
                var tSec = 0.0
                for (j in 0 until nnArr.length()) {
                    val ms = nnArr.getDouble(j)
                    val rr = ms.roundToInt()             // stored rrMs is Int; identical input to both sides
                    rows.add(RrInterval(dev, 1_749_513_600L + tSec.toLong(), rr))
                    tSec += ms / 1000.0
                }
                assertRmssdParity("$src window $i (${rows.size} beats)", kotlinStored(rows), RustScores.rmssdGapAware(rows))
                windows++
            }
        }
        assertTrue("fixtures present but no gold window scored", windows > 0)
    }
}
