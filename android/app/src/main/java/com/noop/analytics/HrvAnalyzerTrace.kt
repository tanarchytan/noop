package com.noop.analytics

// HrvAnalyzerTrace.kt - the HRV & Autonomic test-mode cleaning trace.
//
// Recomputes the cleaning-pipeline counts (range filter, Malik ectopic rejection, the minBeats gate,
// the spot rejected-fraction gate) from the same raw RR the analyzer reads, then reuses analyzeRaw(...)
// verbatim so the trace can never disagree with the RMSSD/SDNN the screen shows. Pure and side-effect
// free, so a fixture beat series pins the exact lines. Gated behind TestCentre.active(HRV) at the call
// site; when the mode is off it is never called.

object HrvAnalyzerTrace {

    private fun r2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /**
     * Side-effect-free diagnostic twin of [HrvAnalyzer.analyzeRaw]: returns the same result plus a
     * cleaning trace (counts, RMSSD/SDNN/meanNN, the [HrvAnalyzer.MIN_BEATS] gate, the spot
     * rejected-fraction gate), recomputed with the exact same filters so trace and result can never diverge.
     *
     * @param maxRejectedFraction spot-only ceiling; null (nightly/continuous default) skips the gate.
     * @param path "spot" for a live snapshot, "continuous" for the nightly windowed path.
     */
    fun analyzeTrace(
        rawRR: List<Double>,
        maxRejectedFraction: Double? = null,
        path: String = "spot",
    ): Pair<HrvAnalyzer.HrvResult, List<String>> {
        // The result the screen reads, verbatim, so the trace cannot diverge from it.
        val result = HrvAnalyzer.analyzeRaw(rawRR, maxRejectedFraction)

        val lines = ArrayList<String>()
        val nInput = rawRR.size

        // Stage counts: range filter then Malik ectopic rejection (the SAME order cleanRR runs).
        val ranged = HrvAnalyzer.rangeFilter(rawRR)
        val clean = HrvAnalyzer.rejectEctopic(ranged)
        val outOfRange = nInput - ranged.size
        val ectopic = ranged.size - clean.size
        val rejectedFraction = if (nInput > 0) 1.0 - clean.size.toDouble() / nInput.toDouble() else 0.0

        lines.add(
            "hrv path=$path nInput=$nInput nClean=${clean.size} " +
                "rejectedFraction=${r2(rejectedFraction)}",
        )
        lines.add(
            "hrv reject range=$outOfRange " +
                "(bounds ${HrvAnalyzer.RR_MIN_MS.toInt()}..${HrvAnalyzer.RR_MAX_MS.toInt()}ms) " +
                "ectopic=$ectopic (Malik >${(HrvAnalyzer.ECTOPIC_THRESHOLD * 100).toInt()}% of local median)",
        )

        // minBeats gate: the first reason analyzeRaw(...) returns an empty result.
        val minBeatsCleared = clean.size >= HrvAnalyzer.MIN_BEATS
        lines.add(
            "hrv minBeats need=${HrvAnalyzer.MIN_BEATS} clean=${clean.size} " +
                if (minBeatsCleared) "CLEARED" else "FAILED",
        )

        // Spot honesty gate: only when a ceiling is supplied and minBeats cleared.
        if (maxRejectedFraction != null && minBeatsCleared) {
            val gatePass = !(rejectedFraction > maxRejectedFraction)
            lines.add(
                "hrv spotGate maxRejectedFraction=${r2(maxRejectedFraction)} " +
                    "rejectedFraction=${r2(rejectedFraction)} ${if (gatePass) "PASS" else "FAIL"}",
            )
        }

        // RMSSD / SDNN / meanNN read from the verbatim result (null when a gate refused the reading).
        val rmssd = result.rmssd
        val sdnn = result.sdnn
        val mean = result.meanNN
        if (rmssd != null && sdnn != null && mean != null) {
            lines.add("hrv rmssd=${r2(rmssd)}ms sdnn=${r2(sdnn)}ms meanNN=${r2(mean)}ms")
        } else {
            lines.add("hrv result=nil (a gate above refused the reading)")
        }

        return result to lines
    }
}
