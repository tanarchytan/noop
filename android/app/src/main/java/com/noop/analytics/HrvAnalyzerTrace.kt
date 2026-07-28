package com.noop.analytics

// HrvAnalyzerTrace.kt - the HRV & Autonomic test-mode cleaning trace.
//
// Narrates one whoop-rs cleaning pass: the stage counts and thresholds come from RustScores, the
// RMSSD/SDNN from the same analyzeRaw the screen reads, so the trace cannot disagree with the value
// shown. No maths here, only formatting. Gated behind TestCentre.active(HRV) at the call site.

object HrvAnalyzerTrace {

    private fun r2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /**
     * Side-effect-free diagnostic twin of [RustScores.analyzeRaw]: the same result plus a cleaning trace
     * (stage counts, RMSSD/SDNN/meanNN, the clean-beat gate, the spot rejected-fraction gate). Counts and
     * thresholds come from whoop-rs, so the trace and the result cannot diverge.
     *
     * @param maxRejectedFraction spot-only ceiling; null (nightly/continuous default) skips the gate.
     * @param path "spot" for a live snapshot, "continuous" for the nightly windowed path.
     */
    fun analyzeTrace(
        rawRR: List<Double>,
        maxRejectedFraction: Double? = null,
        path: String = "spot",
    ): Pair<HrvResult, List<String>> {
        // The result the screen reads, verbatim, so the trace cannot diverge from it.
        val result = RustScores.analyzeRaw(rawRR, maxRejectedFraction)

        val lines = ArrayList<String>()
        val cfg = RustScores.hrvCleanCfg
        // Ungated stage counts: analyzeRaw reports nClean = 0 once a gate refuses, which is the case
        // this trace exists to explain.
        val counts = RustScores.cleanCounts(rawRR)
        val nInput = counts.nInput.toInt()
        val nClean = counts.nClean.toInt()
        val outOfRange = nInput - counts.nRanged.toInt()
        val ectopic = counts.nRanged.toInt() - nClean
        val rejectedFraction = if (nInput > 0) 1.0 - nClean.toDouble() / nInput.toDouble() else 0.0

        lines.add(
            "hrv path=$path nInput=$nInput nClean=$nClean " +
                "rejectedFraction=${r2(rejectedFraction)}",
        )
        lines.add(
            "hrv reject range=$outOfRange " +
                "(bounds ${cfg.rrMinMs}..${cfg.rrMaxMs}ms) " +
                "ectopic=$ectopic (Malik >${(cfg.ectopicThreshold * 100).toInt()}% of local median)",
        )

        // Clean-beat gate: the first reason analyzeRaw(...) returns an empty result.
        val minBeats = cfg.minBeats.toInt()
        val minBeatsCleared = nClean >= minBeats
        lines.add(
            "hrv minBeats need=$minBeats clean=$nClean " +
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
