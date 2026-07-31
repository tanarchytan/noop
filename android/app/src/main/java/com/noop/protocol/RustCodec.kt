package com.noop.protocol

import uniffi.whoop_ffi.Gen
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.MetadataInfo
import uniffi.whoop_ffi.PpgEstimate
import uniffi.whoop_ffi.PpgFrame
import uniffi.whoop_ffi.PpgSample
import uniffi.whoop_ffi.Response
import uniffi.whoop_ffi.RrRun
import uniffi.whoop_ffi.WhoopCodec
import uniffi.whoop_ffi.dataRangeNewest as ffiDataRangeNewest
import uniffi.whoop_ffi.dataRangeOldest as ffiDataRangeOldest
import uniffi.whoop_ffi.dataRangePagesBehind as ffiDataRangePagesBehind
import uniffi.whoop_ffi.hrvRmssdGapAware
import uniffi.whoop_ffi.ppgHr

/** The codec generation a strap family speaks — the one [DeviceFamily] → [Gen] mapping. */
val DeviceFamily.gen: Gen
    get() = when (this) {
        DeviceFamily.WHOOP4 -> Gen.GEN4
        DeviceFamily.WHOOP5 -> Gen.GEN5
    }

/**
 * Bridge to the Rust whoop-ffi codec (the from-scratch whoop-rs core, shared across apps). Native BLE
 * still owns the radio and feeds frame bytes here; nothing async or radio-bound crosses the FFI. The full
 * WhoopCodec (decode history/live/response + the command-frame builders) and the metric free functions
 * are also available directly under `uniffi.whoop_ffi`.
 */
object RustCodec {
    private val gen5 by lazy { WhoopCodec(Gen.GEN5) }
    private val gen4 by lazy { WhoopCodec(Gen.GEN4) }
    private fun codec(gen: Gen) = if (gen == Gen.GEN5) gen5 else gen4

    /** Decode one type-47 HISTORICAL_DATA frame to its full per-second summary, or null. */
    fun decodeHistory(gen: Gen, frame: ByteArray): HistorySummary? = codec(gen).decodeHistory(frame)

    /** Decode one live-notify frame (realtime HR/R-R, on-wrist r22, event/battery, console), or null. */
    fun decodeLive(gen: Gen, frame: ByteArray): Live? = codec(gen).decodeLive(frame)

    /** Decode one command response (identity/battery/clock/data-range/firmware), or null. */
    fun decodeResponse(gen: Gen, frame: ByteArray): Response? = codec(gen).decodeResponse(frame)

    /** Decode one METADATA frame's offload-state fields (meta_type/unix/trim_cursor/crc_ok), or null.
     *  Drives the historical-offload trim: a wrong trim would delete un-drained history. */
    fun decodeMetadata(gen: Gen, frame: ByteArray): MetadataInfo? = codec(gen).decodeMetadata(frame)

    /** Newest plausible unix banked (GET_DATA_RANGE), scanning every offset, preferring non-future. */
    fun dataRangeNewest(frame: ByteArray, wallNowUnix: Long, futureSkewSeconds: Long): Long? =
        ffiDataRangeNewest(frame, wallNowUnix.toULong(), futureSkewSeconds.toULong())?.toLong()

    /** Oldest plausible unix banked (backlog depth), aligned-from-7 grid. */
    fun dataRangeOldest(frame: ByteArray): Long? = ffiDataRangeOldest(frame)?.toLong()

    /** Pages banked but not yet sent, from the strap's own ring cursors. Diagnostic only. */
    fun dataRangePagesBehind(frame: ByteArray): Long? = ffiDataRangePagesBehind(frame)?.toLong()

    /** Decode one v26 optical-PPG frame (24 samples + unix), or null. WHOOP 5/MG only. */
    fun decodePpg(frame: ByteArray): PpgFrame? = gen5.decodePpgFrame(frame)

    /** Per-second HR from the concatenated v26 PPG samples (sub-lag autocorrelation). */
    fun ppgEstimates(samples: List<PpgSample>): List<PpgEstimate> = ppgHr(samples)

    /** Gap-aware, artifact-corrected nightly RMSSD (ms) from per-record R-R runs. */
    fun rmssd(runs: List<RrRun>): Double? = hrvRmssdGapAware(runs)

    // --- Outbound command frames: whoop-rs builds every frame's bytes (envelope + CRC + payload). Kotlin
    //     keeps only the send policy (seq counter, 5/MG allow-list, opt-in gates, R22 ordering). ---

    /** Generic COMMAND frame for [cmd]+[payload]; null if the codec refuses a destructive opcode. */
    fun commandFrame(gen: Gen, seq: Int, cmd: Int, payload: ByteArray): ByteArray? =
        codec(gen).commandFrame(seq.toUByte(), cmd.toUByte(), payload)

    /** 5/MG one-shot maverick buzz (the notify preset). */
    fun buzzFrame(seq: Int): ByteArray = gen5.buzzFrame(seq.toUByte())

    /** GET_BATTERY_LEVEL (also the 4.0 bond-establishing write). */
    fun getBatteryFrame(gen: Gen, seq: Int): ByteArray = codec(gen).getBatteryFrame(seq.toUByte())

    /** SET_CLOCK 8-byte form (newer firmware). */
    fun setClockFrame(gen: Gen, seq: Int, nowUnix: Long): ByteArray =
        codec(gen).setClockFrame(seq.toUByte(), nowUnix.toUInt())

    /** SET_CLOCK legacy 9-byte form (older 4.0 firmware). */
    fun setClockLegacyFrame(gen: Gen, seq: Int, nowUnix: Long): ByteArray =
        codec(gen).setClockLegacyFrame(seq.toUByte(), nowUnix.toUInt())

    /** SET_ALARM_TIME 5/MG rev4 body. */
    fun alarmSetFrame(seq: Int, wakeEpochMs: Long, alarmId: Int = 1): ByteArray =
        gen5.alarmSetFrame(seq.toUByte(), wakeEpochMs.toULong(), alarmId.toUByte())

    /** SET_ALARM_TIME WHOOP 4.0 9-byte body. */
    fun alarmSetFrameGen4(seq: Int, wakeEpochSec: Long): ByteArray =
        gen4.alarmSetFrameGen4(seq.toUByte(), wakeEpochSec.toUInt())

    /** DISABLE_ALARM 5/MG rev2 form. */
    fun alarmDisableFrame(seq: Int): ByteArray = gen5.alarmDisableFrame(seq.toUByte())

    /** SET_ADVERTISING_NAME (WHOOP 4.0 strap rename; the name is clamped to 24 UTF-8 bytes in-codec). */
    fun advertisingNameFrame(seq: Int, name: String): ByteArray = gen4.advertisingNameFrame(seq.toUByte(), name)

    /** SET_CONFIG for one named 5/MG feature flag (the R22 sequence). */
    fun setConfigFrame(seq: Int, name: String, value: Int): ByteArray =
        gen5.setConfigFrame(seq.toUByte(), name, value.toUByte())

}
