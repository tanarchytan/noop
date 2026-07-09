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
        if (rawTypeByte == 43) {
            lastRawType43Size = frame.size
            if (lastRawType43Hex.isEmpty()) {
                lastRawType43Hex = frame.joinToString("") { "%02x".format(it) }.take(120)
            }
            // Best-guess decode: try the WHOOP 5 v26 optical layout (24 i16 LE @ [27:75]). If the live
            // frame uses a different layout this yields noise, but the sample count + hex head above let
            // us correct the layout from a real capture. fs=24 matches PpgHr / PpgBuffer.
            if (frame.size >= 75) {
                val samples = IntArray(24) { i ->
                    val lo = frame[27 + i * 2].toInt() and 0xFF
                    val hi = frame[27 + i * 2 + 1].toInt()
                    (hi shl 8) or lo
                }
                PpgBuffer.record(samples.toList(), 24)
                samplesFed += samples.size
            }
        }
    }

    /** One-line "type=count, …" tally for the BP screen readout. */
    fun typeSummary(): String =
        typeCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
            .ifEmpty { "none yet" }

    /** Which enable opcodes the strap accepted/rejected (e.g. "ENABLE_OPTICAL_DATA=SUCCESS"). */
    fun responseSummary(): String =
        synchronized(responses) { responses.joinToString("; ") }.ifEmpty { "(no command responses yet)" }

    /** Full multi-line report for the strap log / "Share strap log": tally, responses, and a first-frame
     *  hex sample of every record kind — everything needed to RE an unlocked record layout offline. */
    fun fullReport(): String = buildString {
        appendLine("=== BP LIVE-PPG PROBE REPORT ===")
        appendLine("frames: ${typeSummary()}")
        appendLine("responses: ${responseSummary()}")
        appendLine("type-43: ${lastRawType43Size}B  PPG-fed: $samplesFed")
        firstHex.entries.sortedBy { it.key }.forEach { (k, hex) ->
            appendLine("sample[$k] ${hex.take(160)}")
        }
        appendLine("=== END PROBE REPORT ===")
    }
}
