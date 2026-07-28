package com.noop.analytics

/**
 * On-demand "take an HRV reading now" — the single-value spot RMSSD path.
 *
 * Wraps [RustScores.analyzeRaw] for the live, user-triggered snapshot over ~60 s of R-R intervals, so the spot
 * value, its honesty gate, and its data-quality caveat live in one tested place.
 *
 *     RMSSD = sqrt( mean( (RR[i+1] - RR[i])^2 ) )   in ms
 *
 * Uses the SAME (n-1) denominator as the nightly HRV path, not a population (n) one — otherwise the
 * same beats would read a few percent lower than the overnight figure. Returns a value only when
 * enough clean beats survive (the whoop-rs clean-beat floor); otherwise [Insufficient] with the
 * survived/needed counts, never a fabricated value. The caveat ([caveatFor]) is source-aware: optical
 * PPG (WHOOP 5/MG) is noisier than a chest strap's electrical R-R.
 */
object SpotHrvReading {

    /** Where the live R-R intervals came from — drives the honesty caveat (optical PPG is noisier). */
    enum class Source {
        /** WHOOP 5/MG: R-R is derived from the optical PPG waveform — beat-to-beat, but noisier. */
        OPTICAL_PPG,

        /** WHOOP 4 or a chest strap (e.g. Polar H10) over the standard 0x2A37 profile — electrical R-R. */
        CHEST_STRAP,

        /** Source not known (generic / unspecified strap). */
        UNKNOWN,
    }

    /** Outcome of an on-demand spot reading. */
    sealed interface Outcome {
        /** A trustworthy spot value: [rmssdMs] (ms), mean [hrBpm] (or null), and the clean-beat [beats]
         *  used. Backed by the full [HrvResult] for callers that want SDNN / pNN50 too. */
        data class Reading(
            val rmssdMs: Double,
            val hrBpm: Double?,
            val beats: Int,
            val full: HrvResult,
        ) : Outcome

        /** Not enough clean beats to report honestly — carries how many survived vs how many are needed
         *  so the UI can guide the user ("sit still and try again"). */
        data class Insufficient(val clean: Int, val needed: Int, val input: Int) : Outcome
    }

    /**
     * Compute a spot HRV reading from raw R-R intervals (ms) using the same cleaning + RMSSD
     * pipeline as nightly HRV (range filter, Malik ectopic rejection, (n-1) RMSSD). Returns
     * [Outcome.Insufficient] instead of a number when too few clean beats survive.
     *
     * @param rrMs the raw R-R intervals in milliseconds, in capture order (untrusted BLE input — the
     *   range filter bounds-checks each against the whoop-rs R-R bounds).
     * @param maxRejectedFraction the spot honesty gate — refuse the reading when more than this
     *   fraction of beats was dropped as noise, even if the clean-beat floor is cleared.
     *   Defaults to the whoop-rs spot ceiling; the nightly windowed
     *   path does not use this.
     */
    fun compute(
        rrMs: List<Int>,
        maxRejectedFraction: Double = RustScores.hrvCleanCfg.spotMaxRejectedFraction,
    ): Outcome {
        val result = RustScores.analyzeRaw(rrMs.map { it.toDouble() }, maxRejectedFraction)
        val rmssd = result.rmssd
        return if (rmssd == null) {
            Outcome.Insufficient(
                clean = result.nClean,
                needed = RustScores.hrvCleanCfg.minBeats.toInt(),
                input = result.nInput,
            )
        } else {
            Outcome.Reading(
                rmssdMs = rmssd,
                hrBpm = meanHrFromNN(result.meanNN),
                beats = result.nClean,
                full = result,
            )
        }
    }

    /** Mean heart rate (bpm) from the mean NN interval (ms): 60000 / meanNN. null when missing or <= 0. */
    fun meanHrFromNN(meanNN: Double?): Double? =
        if (meanNN == null || meanNN <= 0.0) null else 60_000.0 / meanNN

    /**
     * Honest, source-aware caveat for a spot reading. Plain text, US-neutral, no em-dashes. Always
     * states the two universal limits (a 60 s spot is not the overnight baseline; it needs enough clean
     * beats) and adds the source-specific noise note for an optical-PPG strap.
     */
    fun caveatFor(source: Source): String {
        val base =
            "This is a spot reading over a short, still capture, not your overnight HRV baseline. " +
                "Take it seated, still, and at a consistent time of day for comparable numbers, and " +
                "only a reading with enough clean beats is shown."
        return when (source) {
            Source.OPTICAL_PPG ->
                base + " On a WHOOP 5.0/MG the intervals come from the optical pulse signal, which is " +
                    "noisier than a chest strap, so treat the number as a rough estimate."
            Source.CHEST_STRAP, Source.UNKNOWN -> base
        }
    }
}
