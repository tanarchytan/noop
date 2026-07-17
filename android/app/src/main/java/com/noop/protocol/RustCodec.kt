package com.noop.protocol

import uniffi.whoop_ffi.Gen
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.HrvReadinessInfo
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.PpgEstimate
import uniffi.whoop_ffi.PpgFrame
import uniffi.whoop_ffi.PpgSample
import uniffi.whoop_ffi.Response
import uniffi.whoop_ffi.RrRun
import uniffi.whoop_ffi.WhoopCodec
import uniffi.whoop_ffi.hrvReadiness
import uniffi.whoop_ffi.hrvRmssdGapAware
import uniffi.whoop_ffi.ppgHr

/**
 * Bridge to the Rust whoop-ffi codec (the from-scratch whoop-rs core, shared across apps). Native BLE
 * still owns the radio and feeds frame bytes here; nothing async or radio-bound crosses the FFI. The full
 * WhoopCodec (decode history/live/response + the command-frame builders) and the metric free functions
 * are also available directly under `uniffi.whoop_ffi`.
 */
object RustCodec {
    private val gen5 by lazy { WhoopCodec(Gen.GEN5) }
    private val gen4 by lazy { WhoopCodec(Gen.GEN4) }
    private fun codec(isGen5: Boolean) = if (isGen5) gen5 else gen4

    /** Decode one type-47 HISTORICAL_DATA frame to its full per-second summary, or null. */
    fun decodeHistory(isGen5: Boolean, frame: ByteArray): HistorySummary? = codec(isGen5).decodeHistory(frame)

    /** Decode one live-notify frame (realtime HR/R-R, on-wrist r22, event/battery, console), or null. */
    fun decodeLive(isGen5: Boolean, frame: ByteArray): Live? = codec(isGen5).decodeLive(frame)

    /** Decode one command response (identity/battery/clock/data-range/firmware), or null. */
    fun decodeResponse(isGen5: Boolean, frame: ByteArray): Response? = codec(isGen5).decodeResponse(frame)

    /** Decode one v26 optical-PPG frame (24 samples + unix), or null. WHOOP 5/MG only. */
    fun decodePpg(frame: ByteArray): PpgFrame? = gen5.decodePpgFrame(frame)

    /** Per-second HR from the concatenated v26 PPG samples (sub-lag autocorrelation). */
    fun ppgEstimates(samples: List<PpgSample>): List<PpgEstimate> = ppgHr(samples)

    /** Gap-aware, artifact-corrected nightly RMSSD (ms) from per-record R-R runs. */
    fun rmssd(runs: List<RrRun>): Double? = hrvRmssdGapAware(runs)

    /** HRV-readiness over a nightly RMSSD series (oldest to newest). */
    fun readiness(nightlyRmssd: List<Double?>): HrvReadinessInfo? = hrvReadiness(nightlyRmssd)
}
