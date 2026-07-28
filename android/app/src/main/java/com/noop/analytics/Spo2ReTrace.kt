package com.noop.analytics

/*
 * Connection-mode SpO2 reverse-engineering dump.
 *
 * WHOOP 4.0's historical decode maps the raw red/IR PPG channels (spo2_red@68 / spo2_ir@70 on
 * the v24 layout), but NOOP nulls spo2Pct on purpose: a calibrated % needs the dense
 * dual-wavelength waveform plus WHOOP's proprietary calibration curve, and guessing would
 * manufacture a plausible-but-wrong health number. So this dumps full historical records (hex)
 * plus mapped SpO2 channels, log-only and gated behind Test Centre Connection mode, so an offline
 * pass can correlate a byte against the SpO2 the WHOOP app shows. A record dumps whether or not
 * it carries SpO2 channels, so absence is provable too — never a fabricated number.
 *
 * Pure formatter: no IO, no state, no PII.
 */
object Spo2ReTrace {

    /** Max records dumped per offload session. A handful is enough for an offline correlation pass and
     *  keeps the strap log bounded; the Backfiller counter spans chunks and resets per session. */
    const val MAX_SAMPLES = 8

    /**
     * One record's RE line: mapped SpO2 channels + timestamp + layout version, then the full frame hex
     * (no prefix cap — the unmapped tail of a ~84 B v24 record is where a banked SpO2 would sit).
     * Absent channels render "null"; takes already-extracted ints (matches ConnectionTrace's style).
     */
    fun recordLine(frame: ByteArray, version: Int?, unix: Int?, red: Int?, ir: Int?, skinRaw: Int?): String {
        val hex = frame.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        fun f(v: Int?): String = v?.toString() ?: "null"
        return "spo2re v=${f(version)} unix=${f(unix)} red=${f(red)} ir=${f(ir)} " +
            "skinRaw=${f(skinRaw)} len=${frame.size} raw=$hex"
    }
}
