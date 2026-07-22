package com.noop.analytics

import com.noop.data.RrInterval

/*
 * Frequency-domain HRV (LF / HF / LF-HF / total power) over an R-R series. The Lomb-Scargle estimator,
 * Task-Force bands and span gates live in whoop-rs (physio-algo hrv_freq); this is the app-side entry that
 * shapes RrInterval into the raw ms series and routes to RustScores. [Bands] is the value StressScreen reads.
 */
object HrvFreqDomain {

    /**
     * Frequency-domain HRV over a window. [lf] / [lfhf] are null when span < 250 s (60..250 s gives HF
     * only). [hf] and [totalPower] are present whenever the result is non-null (span >= 60 s). ms^2.
     */
    data class Bands(
        val lf: Double?,
        val hf: Double,
        val lfhf: Double?,
        val totalPower: Double,
    )

    /** Frequency-domain HRV from R-R intervals (each with a ts in seconds and rrMs), sorted by ts. The
     *  clean + tachogram + Lomb-Scargle all run in whoop-rs. null when too few clean beats or span < 60 s. */
    fun freqDomain(rr: List<RrInterval>): Bands? =
        freqDomainRaw(rr.sortedBy { it.ts }.map { it.rrMs.toDouble() })

    /** As [freqDomain] but from a raw, time-ordered R-R series in milliseconds. Routed to whoop-rs. */
    fun freqDomainRaw(rawRR: List<Double>): Bands? = RustScores.freqDomain(rawRR)
}
