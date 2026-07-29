package com.noop.analytics.agreement

import com.noop.analytics.RustScores
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * R-R version rundown. Replays identical synthetic sleep windows through the THREE storage/cleaning
 * generations and reports how each one's computed RMSSD deviates from the clean-rhythm reference.
 *
 * Each version is reproduced from the CURRENT tree's own public functions, so every column is the real
 * noop code path, not a re-implementation:
 *   ryanbr base : rmssdRaw(cleanRR( dedup-by-(ts,rrMs) ))   value-only key drops equal same-second beats
 *   + seq key   : rmssdRaw(cleanRR( all beats ))            keeps them, still splices across a dropped beat
 *   rr-opt      : RustScores.analyzeRaw( all beats ).rmssd  keeps them AND skips the diff across a drop
 *
 * Beats are stamped off a running beat clock into per-second reports, so a timestamp follows from the
 * intervals before it and an equal pair shares a second exactly when it really falls inside one. That is
 * the shape the storage key and the cleaning are scored on. The report seam is NOT scored here and
 * cannot be: [RustScores.analyzeRaw] takes bare intervals, with no timestamps for a seam rule to read.
 * Real reports are scored in RealDataRundownTest.
 *
 * Reference = RMSSD of the artifact-free rounded RR (the true rhythm). This is a synthetic yardstick,
 * NOT a WHOOP label: real WHOOP agreement needs a captured R-R night paired to the WHOOP CSV export.
 *
 * The test writes build/rr-rundown.md and asserts the accuracy ordering improves monotonically
 * (ryanbr error >= +seq error >= rr-opt error) across the whole set.
 */
class RrVersionRundownTest {

    private data class Beat(val ts: Long, val rrMs: Int)

    /** One generated window: the stream as stored, the artifact-free rhythm, and that rhythm's RMSSD. */
    private data class Window(val captured: List<Beat>, val clean: List<Beat>, val reference: Double)

    private fun rmssd(x: List<Double>): Double = RustScores.rmssdRaw(x) ?: Double.NaN

    /** Old storage: PK (deviceId,ts,rrMs) + INSERT IGNORE keeps the first (ts,rrMs) and drops the rest. */
    private fun dedupTsRr(beats: List<Beat>): List<Beat> {
        val seen = HashSet<Pair<Long, Int>>()
        val out = ArrayList<Beat>(beats.size)
        for (b in beats) if (seen.add(b.ts to b.rrMs)) out.add(b)
        return out
    }

    private fun vRyanbr(beats: List<Beat>): Double =
        rmssd(RustScores.cleanRR(dedupTsRr(beats).map { it.rrMs.toDouble() }))

    private fun vTwoPr(beats: List<Beat>): Double =
        rmssd(RustScores.cleanRR(beats.map { it.rrMs.toDouble() }))

    private fun vRrOpt(beats: List<Beat>): Double =
        RustScores.analyzeRaw(beats.map { it.rrMs.toDouble() }).rmssd ?: Double.NaN

    /**
     * One ~5-minute sleep window. Builds a clean rhythm (respiratory sinus arrhythmia + slow drift +
     * noise), injects [nEqualDup] equal SUCCESSIVE intervals into it, then stamps every beat with the
     * wall-second its running beat time lands in. An equal pair shares a timestamp exactly when it really
     * falls inside one second. [nEctopic] out-of-range beats (the optical artifacts the range filter
     * removes) go on the captured copy only.
     */
    private fun genWindow(seed: Long, nBeats: Int, baseRr: Double, nEqualDup: Int, nEctopic: Int): Window {
        val rng = Random(seed)
        val rr = ArrayList<Int>(nBeats)
        for (i in 0 until nBeats) {
            val rsa = 35.0 * sin(2.0 * Math.PI * i / 12.0)      // ~breathing modulation
            val drift = 20.0 * sin(2.0 * Math.PI * i / 400.0)   // slow trend
            val noise = rng.nextGaussian() * 12.0
            rr.add((baseRr + rsa + drift + noise).coerceIn(400.0, 1300.0).roundToInt())
        }
        repeat(nEqualDup) {
            val idx = rng.nextInt(rr.size - 1)
            rr[idx + 1] = rr[idx]
        }
        var tMs = 0L
        val clean = rr.map { ms -> tMs += ms; Beat(tMs / 1000L, ms) }
        val captured = ArrayList(clean)
        repeat(nEctopic) {
            val idx = 1 + rng.nextInt(captured.size - 2)
            captured[idx] = captured[idx].copy(rrMs = 5000)
        }
        return Window(captured, clean, rmssd(clean.map { it.rrMs.toDouble() }))
    }

    /** Beats the value-only key drops: a repeat of an (ts, rrMs) already seen in the window. */
    private fun droppedByValueKey(beats: List<Beat>): Int = beats.size - dedupTsRr(beats).size


    @Test
    fun rundown() {
        val rows = StringBuilder()
        rows.append("# R-R version rundown (synthetic reference nights)\n\n")
        rows.append("Reference = RMSSD of the artifact-free true rhythm. Values in ms. err = |version - reference|.\n\n")
        rows.append("| # | baseRR | inj | drop | ect | ref | ryanbr | err | +seq | err | rr-opt | err |\n")
        rows.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")

        var sumRef = 0.0
        var errRyanbr = 0.0
        var errTwoPr = 0.0
        var errRrOpt = 0.0
        var dropped = 0
        val n = 24
        for (i in 0 until n) {
            val baseRr = 600.0 + (i % 6) * 110.0           // 600..1150 ms (100..52 bpm)
            val nBeats = (300000.0 / baseRr).roundToInt()  // ~5 minutes of beats
            val inj = 3 + (i % 5) * 2                        // 3..11 equal successive intervals
            val ect = 2 + (i % 4) * 2                        // 2..8 out-of-range artifacts
            val w = genWindow(seed = 1000L + i, nBeats = nBeats, baseRr = baseRr, nEqualDup = inj, nEctopic = ect)
            val beats = w.captured
            val ref = w.reference
            val drop = droppedByValueKey(beats)
            dropped += drop
            val vR = vRyanbr(beats); val vP = vTwoPr(beats); val vO = vRrOpt(beats)
            sumRef += ref; errRyanbr += abs(vR - ref); errTwoPr += abs(vP - ref); errRrOpt += abs(vO - ref)
            rows.append(
                "| ${i + 1} | ${baseRr.roundToInt()} | $inj | $drop | $ect | ${f(ref)} | ${f(vR)} | ${f(abs(vR - ref))} | " +
                    "${f(vP)} | ${f(abs(vP - ref))} | ${f(vO)} | ${f(abs(vO - ref))} |\n"
            )
        }
        val maeR = errRyanbr / n; val maeP = errTwoPr / n; val maeO = errRrOpt / n
        rows.append("\n## Mean absolute error vs true rhythm (ms), over $n windows\n\n")
        rows.append("| version | MAE | vs ryanbr |\n|---|---|---|\n")
        rows.append("| ryanbr base | ${f(maeR)} | - |\n")
        rows.append("| + seq key | ${f(maeP)} | ${pct(maeR, maeP)} |\n")
        rows.append("| rr-opt (gap-aware) | ${f(maeO)} | ${pct(maeR, maeO)} |\n")
        rows.append("\nMean reference RMSSD ${f(sumRef / n)} ms. Beats the value-only key drops: $dropped.\n")

        File("build").mkdirs()
        val out = File("build/rr-rundown.md")
        out.writeText(rows.toString())
        println("RR-RUNDOWN written to ${out.absolutePath}")
        println("MAE ryanbr=${f(maeR)} +seq=${f(maeP)} rr-opt=${f(maeO)} dropped=$dropped")

        // Without beats the value-only key drops, the first column measures nothing.
        assertTrue("corpus must carry same-second equal beats", dropped > 0)
        // Accuracy must improve monotonically as each fix lands.
        assertTrue("seq key should not worsen error", maeP <= maeR + 1e-9)
        assertTrue("gap-aware should not worsen error", maeO <= maeP + 1e-9)
        assertTrue("rr-opt should be the most accurate", maeO <= maeR + 1e-9)
    }

    private fun f(x: Double): String = if (x.isNaN()) "nan" else String.format("%.2f", x)
    private fun pct(base: Double, v: Double): String =
        if (base <= 0) "-" else String.format("%+.1f%%", (v - base) / base * 100.0)
}
