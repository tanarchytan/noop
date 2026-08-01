package com.noop.analytics

import kotlin.math.abs

// The Charge TERM-BREAKDOWN diagnostic for the Recovery test mode.
//
// Recomputes the four-plus-one weighted Charge terms from the same inputs RecoveryScorer.recovery
// reads, then reuses recovery(...) verbatim for the final score so the trace can never disagree
// with the number the dashboard shows. Pure and side-effect-free, so a fixture night pins the
// exact lines. Gated behind TestCentre.active(RECOVERY) at the call site (IntelligenceEngine
// recomputeRecovery); when the mode is off it is never called, so there is zero cost.

object RecoveryScorerTrace {

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /**
     * Diagnostic twin of [RecoveryScorer.recovery]: returns the SAME score recovery(...) would,
     * plus a per-term Charge breakdown (baseline, z*weight, renorm, final score) using the exact
     * same expressions, so the trace can never diverge from the headline. Names which term was nil.
     */
    fun recoveryTrace(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: BaselineState,
        rhrBaseline: BaselineState?,
        respBaseline: BaselineState?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        recoveryIndexSlope: Double? = null,
        effortBaseline: BaselineState? = null,
        priorDayEffort: Double? = null,
    ): Pair<Double?, List<String>> {
        val lines = ArrayList<String>()
        val nilTerms = ArrayList<String>()

        // The score the dashboard reads, verbatim, so the trace cannot diverge from it — the same
        // whoop-rs path the store sites use ([RustScores.recovery]). The per-term lines below are
        // recomputed locally purely for display; the headline number is the Rust score.
        val score = RustScores.recovery(
            hrv = hrv, rhr = rhr, resp = resp,
            hrvBaseline = hrvBaseline, rhrBaseline = rhrBaseline,
            respBaseline = respBaseline, sleepPerf = sleepPerf, skinTempDev = skinTempDev,
            recoveryIndexSlope = recoveryIndexSlope, effortBaseline = effortBaseline,
            priorDayEffort = priorDayEffort,
        )

        // Cold-start gate: HRV baseline not usable -> recovery() returns null before any term is built.
        if (!hrvBaseline.usable) {
            lines.add(
                "charge nilScore reason=hrvBaselineNotUsable " +
                    "hrvStatus=${hrvBaseline.status.raw} hrvNValid=${hrvBaseline.nValid} " +
                    "(need nValid>=${Baselines.minNightsSeed})",
            )
            return score to lines
        }

        // Per-driver baseline state lines (mean / spread / nValid / status).
        lines.add(
            "charge baseline hrv mean=${round2(hrvBaseline.baseline)} spread=${round2(hrvBaseline.spread)} " +
                "nValid=${hrvBaseline.nValid} status=${hrvBaseline.status.raw}",
        )
        rhrBaseline?.let { b ->
            lines.add(
                "charge baseline rhr mean=${round2(b.baseline)} spread=${round2(b.spread)} " +
                    "nValid=${b.nValid} status=${b.status.raw}",
            )
        }
        respBaseline?.let { b ->
            lines.add(
                "charge baseline resp mean=${round2(b.baseline)} spread=${round2(b.spread)} " +
                    "nValid=${b.nValid} status=${b.status.raw}",
            )
        }

        // Per-term z * weight, built with the EXACT expressions recovery(...) uses, in the SAME append order.
        val terms = ArrayList<Pair<Double, Double>>() // (z, weight)

        // Every WEIGHT / SCALE / centre constant goes through round2() too, not just the z-scores, so a
        // future non-round weight (e.g. 0.333) still renders consistently and the parity fixture
        // cannot silently desync.
        // HRV term: higher is better. (Always present once usable; the cold-start guard above returned.)
        val hrvZ = RecoveryScorer.zScore(hrv, hrvBaseline.baseline, hrvBaseline.spread)
        terms.add(hrvZ to RecoveryScorer.wHRV)
        lines.add("charge term hrv z=${round2(hrvZ)} w=${round2(RecoveryScorer.wHRV)} (higher HRV is better)")

        // RHR term: lower is better -> (mu - x) / sigma.
        if (rhrBaseline != null) {
            val z = RecoveryScorer.zScore(rhrBaseline.baseline, rhr, rhrBaseline.spread)
            terms.add(z to RecoveryScorer.wRHR)
            lines.add("charge term rhr z=${round2(z)} w=${round2(RecoveryScorer.wRHR)} (lower RHR is better)")
        } else {
            nilTerms.add("rhr")
        }

        // Resp term: lower is better, optional (needs BOTH the value and a baseline).
        if (resp != null && respBaseline != null) {
            val z = RecoveryScorer.zScore(respBaseline.baseline, resp, respBaseline.spread)
            terms.add(z to RecoveryScorer.wResp)
            lines.add("charge term resp z=${round2(z)} w=${round2(RecoveryScorer.wResp)} (lower resp is better)")
        } else {
            nilTerms.add("resp")
        }

        // Sleep-performance / Rest-quality term: no baseline needed, centered at sleepPerfCenter.
        if (sleepPerf != null) {
            val z = (sleepPerf - RecoveryScorer.sleepPerfCenter) / RecoveryScorer.sleepPerfScale
            terms.add(z to RecoveryScorer.wSleep)
            lines.add(
                "charge term sleepPerf z=${round2(z)} w=${round2(RecoveryScorer.wSleep)} " +
                    "(rest=${round2(sleepPerf)} center=${round2(RecoveryScorer.sleepPerfCenter)})",
            )
        } else {
            nilTerms.add("sleepPerf")
        }

        // Skin-temp term: SYMMETRIC penalty on |deviation|, added only when supplied.
        if (skinTempDev != null) {
            val z = -abs(skinTempDev) / RecoveryScorer.skinTempDevScale
            terms.add(z to RecoveryScorer.wSkinTemp)
            lines.add(
                "charge term skinTempDev z=${round2(z)} w=${round2(RecoveryScorer.wSkinTemp)} " +
                    "(dev=${round2(skinTempDev)}C penalty=-|dev|/${round2(RecoveryScorer.skinTempDevScale)})",
            )
        } else {
            nilTerms.add("skinTempDev")
        }

        // Recovery-Index term: overnight HR-decline slope, negative (declining) is better. Same append
        // order as recovery(...): after skin temp.
        if (recoveryIndexSlope != null) {
            val z = -recoveryIndexSlope / RecoveryScorer.recoveryIndexScaleBpmPerHr
            terms.add(z to RecoveryScorer.wRecoveryIndex)
            lines.add(
                "charge term recoveryIndex z=${round2(z)} w=${round2(RecoveryScorer.wRecoveryIndex)} " +
                    "(slope=${round2(recoveryIndexSlope)}bpm/hr, declining is better)",
            )
        } else {
            nilTerms.add("recoveryIndex")
        }

        // Activity-Balance term: previous-day Effort vs personal baseline, lower is better. Needs BOTH
        // the value and its baseline.
        if (priorDayEffort != null && effortBaseline != null) {
            val z = RecoveryScorer.zScore(effortBaseline.baseline, priorDayEffort, effortBaseline.spread)
            terms.add(z to RecoveryScorer.wActivityBalance)
            lines.add(
                "charge term activityBalance z=${round2(z)} w=${round2(RecoveryScorer.wActivityBalance)} " +
                    "(priorEffort=${round2(priorDayEffort)} baselineMean=${round2(effortBaseline.baseline)})",
            )
        } else {
            nilTerms.add("activityBalance")
        }

        // The nil terms that dropped out and forced the weight renormalization (the killer line).
        lines.add(
            "charge nilTerm dropped=[${nilTerms.joinToString(",")}] " +
                "(each dropped term renormalizes the remaining weights)",
        )

        // Renormalization: total surviving weight and the weighted composite z, the SAME math recovery(...)
        // runs to produce the logistic input.
        val totalWeight = terms.sumOf { it.second }
        val compositeZ = if (totalWeight > 0.0) terms.sumOf { it.first * it.second } / totalWeight else 0.0
        lines.add(
            "charge renorm totalWeight=${round2(totalWeight)} compositeZ=${round2(compositeZ)} " +
                "(z = sum(z*w)/sum(w))",
        )

        // Final logistic score + band, read from recovery(...) verbatim.
        if (score != null) {
            lines.add(
                "charge score=${round2(score)} band=${RecoveryScorer.band(score)} " +
                    "(logistic k=${round2(RecoveryScorer.logisticK)} z0=${round2(RecoveryScorer.logisticZ0)})",
            )
        } else {
            lines.add("charge nilScore reason=noValidTerms (no driver produced a usable term)")
        }

        return score to lines
    }
}
