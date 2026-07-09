package com.noop.bp

import java.util.concurrent.ConcurrentHashMap

/**
 * EXPERIMENTAL diagnostic for the on-device blood-pressure work: while a "live capture" is active, it
 * tallies the realtime frames the strap actually streams (by type) and tries to pull optical PPG
 * samples out of any realtime-raw (type-43) frame into [PpgBuffer]. This is how we find out — on a real
 * strap — whether the WHOOP 5.0 exposes a raw PPG waveform over BLE at all (it is NOT in the normal
 * historical offload), and, if so, in what layout.
 *
 * Read-only accounting; the only side effect is feeding [PpgBuffer] with a best-guess decode. All fields
 * are @Volatile so the BP screen can poll them from the UI thread while frames arrive on the BLE thread.
 */
object LivePpgProbe {
    @Volatile var active = false
        private set

    private val typeCounts = ConcurrentHashMap<String, Int>()

    /** First frame hex seen per record key (type / hist_vNN) — the sample we RE record layouts from. */
    private val firstHex = ConcurrentHashMap<String, String>()

    /** Distinct command-response outcomes seen, e.g. "ENABLE_OPTICAL_DATA=SUCCESS" — tells us which
     *  opcode the strap honors vs rejects. Insertion-ordered + deduped, capped so it stays readable. */
    private val responses = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile var lastRawType43Size = 0
    @Volatile var lastRawType43Hex = ""
    @Volatile var samplesFed = 0

    fun start() {
        typeCounts.clear(); firstHex.clear(); responses.clear()
        lastRawType43Size = 0; lastRawType43Hex = ""; samplesFed = 0
        active = true
    }

    fun stop() { active = false }

    /**
     * Called per inbound frame while active. [typeName] is the decoded packet-type name (may be null if
     * the frame didn't parse); [rawTypeByte] is the packet-type byte peeked straight from the wire, so a
     * frame is still counted even when it fails to decode. When it's a realtime-raw (43) frame we record
     * its size + a hex head and attempt to lift PPG samples into [PpgBuffer].
     */
    fun observe(frame: ByteArray, typeName: String?, rawTypeByte: Int, respInfo: String? = null) {
        if (!active) return
        val key = typeName ?: "type-$rawTypeByte"
        typeCounts.merge(key, 1) { a, b -> a + b }
        // Break HISTORICAL_DATA (47) down by layout version (@9 on 5/MG) — after the R22 unlock we're
        // watching for a NEW version to appear, which would be where the deeper (raw-PPG) records ride.
        val hexKey = if (rawTypeByte == 47 && frame.size > 9) {
            val vk = "hist_v${frame[9].toInt() and 0xFF}"
            typeCounts.merge(vk, 1) { a, b -> a + b }
            vk
        } else key
        // Keep the first full-frame hex per record kind — the sample we RE the layout from.
        firstHex.putIfAbsent(hexKey, frame.joinToString("") { "%02x".format(it) })
        if (respInfo != null && responses.size < 24) responses.add(respInfo)
        // Fusion capture: stream BOTH the coarse v20 (25 Hz, robust beat detection) and the fine v21
        // (100 Hz, morphology), tagged by version + record unix so the PC aligns + fuses them. v20 also
        // feeds the app's PpgBuffer (the stable HR channel).
        if (rawTypeByte == 47 && frame.size > 9) {
            val ver = frame[9].toInt() and 0xFF
            val unix = ((frame[15].toInt() and 0xFF) or ((frame[16].toInt() and 0xFF) shl 8) or
                ((frame[17].toInt() and 0xFF) shl 16) or ((frame[18].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
            if (ver == 20) decodeV20FixedCh0(frame)?.let { s ->
                PpgBuffer.record(s, 25); v20Fed += s.size; streamSink?.invoke(20, unix, s)
            }
            if (ver == 21) decodeV21All(frame)?.let { s -> streamSink?.invoke(21, unix, s) }
        }
        if (rawTypeByte == 43) {
            lastRawType43Size = frame.size
            if (lastRawType43Hex.isEmpty()) {
                lastRawType43Hex = frame.joinToString("") { "%02x".format(it) }.take(120)
            }
        }
    }

    @Volatile var v20Fed = 0

    /** Live PC-stream sink: (version 20|21, recordUnix, samples). Set by the server's `stream` handler. */
    @Volatile var streamSink: ((Int, Long, List<Int>) -> Unit)? = null

    /** v21 all 3 AC-coupled optical channels @100 Hz (16-bit LE at offsets 28/228/428, 100 each) —
     *  300 samples = [chA][chB][chC], so the PC can pick/fuse the strongest without another rebuild. */
    private fun decodeV21All(frame: ByteArray): List<Int>? {
        if (frame.size < 628) return null
        val out = ArrayList<Int>(300)
        for (off in intArrayOf(28, 228, 428)) {
            for (k in 0 until 100) {
                val o = off + k * 2
                val v = (frame[o].toInt() and 0xFF) or ((frame[o + 1].toInt() and 0xFF) shl 8)
                out.add(if (v >= 32768) v - 65536 else v)
            }
        }
        return out
    }

    /** v20 channel 0: 25 samples of 24-bit LE at the fixed sub-block offset 47 (green PPG @ 25 Hz). */
    private fun decodeV20FixedCh0(frame: ByteArray): List<Int>? {
        val off = 47
        if (frame.size < off + 100) return null
        return (0 until 25).map { k ->
            val o = off + k * 4
            (frame[o].toInt() and 0xFF) or ((frame[o + 1].toInt() and 0xFF) shl 8) or
                ((frame[o + 2].toInt() and 0xFF) shl 16)
        }
    }

    /** First clean run of 24-bit LE samples (stride 4, 4th byte 0) — channel 0 (green PPG) of a v20. */
    private fun decodeV20Channel0(frame: ByteArray): List<Int>? {
        var i = 21
        while (i < frame.size - 8) {
            val w0 = (frame[i].toInt() and 0xFF) or ((frame[i + 1].toInt() and 0xFF) shl 8) or
                ((frame[i + 2].toInt() and 0xFF) shl 16)
            if (frame[i + 3].toInt() == 0 && w0 != 0 && frame[i + 7].toInt() == 0) {
                val out = ArrayList<Int>(25)
                var j = i
                while (j < frame.size - 4 && frame[j + 3].toInt() == 0 && out.size < 25) {
                    val v = (frame[j].toInt() and 0xFF) or ((frame[j + 1].toInt() and 0xFF) shl 8) or
                        ((frame[j + 2].toInt() and 0xFF) shl 16)
                    if (v == 0) break
                    out.add(v); j += 4
                }
                if (out.size >= 16) return out
            }
            i++
        }
        return null
    }

    /** One-line "type=count, …" tally for the BP screen readout. */
    fun typeSummary(): String =
        typeCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
            .ifEmpty { "none yet" }

    /** Full untruncated first-frame hex for one record key (e.g. "hist_v20"), for offline layout RE. */
    fun hexFor(key: String): String = firstHex[key] ?: "(no frame seen for $key)"

    /** Which enable opcodes the strap accepted/rejected (e.g. "ENABLE_OPTICAL_DATA=SUCCESS"). */
    fun responseSummary(): String =
        synchronized(responses) { responses.joinToString("; ") }.ifEmpty { "(no command responses yet)" }

    /** Full multi-line report for the strap log / "Share strap log": tally, responses, and a first-frame
     *  hex sample of every record kind — everything needed to RE an unlocked record layout offline. */
    fun fullReport(): String = buildString {
        appendLine("=== BP LIVE-PPG PROBE REPORT ===")
        appendLine("frames: ${typeSummary()}")
        appendLine("responses: ${responseSummary()}")
        appendLine("type-43: ${lastRawType43Size}B  PPG-fed: $samplesFed  v20-fed: $v20Fed")
        // Validate the v20 decode: recover HR from the buffered green PPG. A sane resting HR = real PPG.
        val win = PpgBuffer.window(12)
        val feat = win?.let { BpEngine.extractFeatures(it, PpgBuffer.sampleRateHz) }
        appendLine("buffer=${win?.size ?: 0}@${PpgBuffer.sampleRateHz}Hz  HR=${feat?.hr?.let { "%.1f".format(it) } ?: "n/a"}  pulses=${feat?.pulses ?: 0}")
        firstHex.entries.sortedBy { it.key }.forEach { (k, hex) ->
            appendLine("sample[$k] ${hex.take(160)}")
        }
        appendLine("=== END PROBE REPORT ===")
    }
}
