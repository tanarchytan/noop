package com.noop.analytics

import kotlin.math.roundToInt

/*
 * BreathPacer.kt — turns a breathing pace into a deterministic inhale/exhale haptic cue list, felt
 * with eyes closed, screen off. Pure + unit-tested; the BLE layer maps each [BreathCue] onto
 * [WhoopBleClient.buzz] and schedules the gaps. No I/O here.
 *
 * Felt language: inhale onset = ONE light pulse (loops: 1), exhale onset = TWO pulses (loops: 2).
 * Each buzz is a fixed-length motor pulse — only loop count and timing can vary, so a cue is
 * encoded as "fire N loops at offset T".
 *
 * A cycle at `bpm` breaths/min lasts 60000/bpm ms; `inhaleFraction` splits it into inhale vs
 * exhale (the calming long-exhale ratio is ~0.4 inhale : 0.6 exhale).
 */

/** Which phase of the breath a cue marks. The on-screen orb is driven by the same phase clock. */
enum class BreathPhase {
    /** The start of an inhale — a single light pulse. */
    INHALE,

    /** The start of an exhale — a heavier (two-pulse) cue. */
    EXHALE,
}

/**
 * One element of a paced-breathing haptic schedule: fire [loops] buzz loops at [offsetMs] from session
 * start, marking the onset of [phase]. The BLE layer schedules the wait, then calls buzz.
 */
data class BreathCue(
    /** Milliseconds from the start of the session at which to fire this cue. */
    val offsetMs: Int,
    /** Which breath phase this cue marks (inhale = light, exhale = heavy). */
    val phase: BreathPhase,
    /** How many buzz loops to play — the felt-strength language (1 = inhale, 2 = exhale). */
    val loops: Int,
)

object BreathPacer {

    // ── Tunables (Breathe parity) ────────────────────────────────────────────
    /** Loops for an inhale onset — one light pulse, matching the Breathe screen. */
    const val INHALE_LOOPS: Int = 1

    /** Loops for an exhale onset — two pulses (heavier), matching the Breathe screen. */
    const val EXHALE_LOOPS: Int = 2

    /** Default inhale fraction of the cycle — the calming long-exhale ratio (~40:60) the "Relax" preset
     *  uses; exhale gets the remaining 0.6. */
    const val DEFAULT_INHALE_FRACTION: Double = 0.4

    /** Slowest / fastest paces the pacer will schedule, 3–12 breaths/min. Out-of-range `bpm` is
     *  clamped so the pacer never emits a zero or absurd offset. */
    const val MIN_BREATHS_PER_MIN: Double = 3.0
    const val MAX_BREATHS_PER_MIN: Double = 12.0

    // ── Pacer ────────────────────────────────────────────────────────────────

    /**
     * Build the haptic cue list for [cycles] breaths at [bpm] breaths/min: one inhale cue then one
     * exhale cue per cycle, in time order, splitting the cycle by [inhaleFraction]. Pure, like
     * [HapticClock]. [bpm] clamps to [MIN_BREATHS_PER_MIN, MAX_BREATHS_PER_MIN], [inhaleFraction] to (0.1, 0.9); cycles < 1 → empty.
     */
    fun schedule(bpm: Double, inhaleFraction: Double = DEFAULT_INHALE_FRACTION, cycles: Int): List<BreathCue> {
        if (cycles < 1) return emptyList()
        val safeBpm = bpm.coerceIn(MIN_BREATHS_PER_MIN, MAX_BREATHS_PER_MIN)
        val frac = inhaleFraction.coerceIn(0.1, 0.9)

        // Cycle length in ms; integer so offsets are exact, with no float drift.
        val cycleMs = (60_000.0 / safeBpm).roundToInt()
        val inhaleMs = (cycleMs.toDouble() * frac).roundToInt()

        val out = ArrayList<BreathCue>(cycles * 2)
        for (c in 0 until cycles) {
            val base = c * cycleMs
            out.add(BreathCue(offsetMs = base, phase = BreathPhase.INHALE, loops = INHALE_LOOPS))
            out.add(BreathCue(offsetMs = base + inhaleMs, phase = BreathPhase.EXHALE, loops = EXHALE_LOOPS))
        }
        return out
    }

    /**
     * Total scheduled duration (ms) of a [cycles]-breath session at [bpm] — [cycles] whole cycles. Handy
     * for the keep-awake / session-length UI without re-deriving the cycle math.
     */
    fun sessionDurationMs(bpm: Double, cycles: Int): Int {
        if (cycles < 1) return 0
        val safeBpm = bpm.coerceIn(MIN_BREATHS_PER_MIN, MAX_BREATHS_PER_MIN)
        val cycleMs = (60_000.0 / safeBpm).roundToInt()
        return cycleMs * cycles
    }
}
