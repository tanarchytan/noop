package com.noop.protocol

import uniffi.whoop_ffi.Gen
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.WhoopCodec

/**
 * Bridge to the Rust whoop-ffi codec (the from-scratch whoop-rs core, shared across apps). Native BLE
 * still owns the radio and feeds frame bytes here; nothing async or radio-bound crosses the FFI.
 */
object RustCodec {
    private val gen5 by lazy { WhoopCodec(Gen.GEN5) }
    private val gen4 by lazy { WhoopCodec(Gen.GEN4) }

    /** Decode one CRC-checked type-47 HISTORICAL_DATA frame to its per-second summary, or null. */
    fun decodeHistory(isGen5: Boolean, frame: ByteArray): HistorySummary? =
        (if (isGen5) gen5 else gen4).decodeHistory(frame)
}
