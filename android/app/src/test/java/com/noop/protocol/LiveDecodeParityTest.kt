package com.noop.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live-inbound decode-border parity over a real 5.0 capture. Proves the rewired live path —
 * [RustAdapter.parseFrame] (whoop-rs is the sole bytes->values decoder) — reproduces the SAME
 * [ParsedFrame] contract the retired Kotlin [Framing.parseFrame] produced for every stored /
 * behaviourally-consumed key (handleFrame, extractStreams, the event payloadJSON, the backfill kick).
 * This is the guard that the seam swap in WhoopBleClient changed no stored value.
 *
 * Known, tolerated divergence: whoop-rs' GET_DATA_RANGE response decoder requires the full range
 * payload (`u32_at(p,7)?`), so a SHORT `PENDING` data-range ack decodes to null instead of carrying a
 * status label. That only drops a "waiting" LOG line — the SUCCESS ack (which fires sendHistoricalKick)
 * carries the range and decodes fine. The test asserts exactly this: zero mismatches on decoded frames,
 * every rust-undecoded frame is a bare non-SUCCESS status ack (no stored field), and at least one
 * data-range SUCCESS decoded.
 *
 * Self-skips unless WHOOP_CAPTURE points at the .jsonl (kept out of the repo; frames are real). Loads
 * the host libwhoop_ffi via JNA (buildRustHostDll), like the other FFI parity tests.
 */
class LiveDecodeParityTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    /** The behaviourally-consumed keys per type (handleFrame / extractStreams / event payloadJSON). */
    private val consumedKeys = mapOf(
        "REALTIME_DATA" to listOf("timestamp", "heart_rate", "rr_intervals"),
        "EVENT" to listOf("event", "event_timestamp", "battery_pct", "battery_mV", "battery_charging", "event_payload_hex"),
        "COMMAND_RESPONSE" to listOf("resp_cmd", "result", "battery_pct", "fw_version", "fw_harvard"),
        "CONSOLE_LOGS" to listOf("console"),
        "METADATA" to listOf("meta_type", "unix", "trim_cursor"),
    )

    /** Keys a bare status ack is allowed to carry (none of them stored) when whoop-rs can't decode it. */
    private val statusOnlyKeys = setOf("resp_cmd", "resp_seq", "result")

    @Test
    fun `rust parseFrame reproduces the kotlin ParsedFrame contract on real frames`() {
        val path = System.getenv("WHOOP_CAPTURE") ?: return // self-skip without the capture
        val file = File(path)
        if (!file.exists()) return

        var decodedCompared = 0
        var dataRangeSuccess = 0
        val mismatches = ArrayList<String>()
        val undecoded = ArrayList<String>()

        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val hexStr = JSONObject(line).optString("hex").takeIf { it.isNotEmpty() } ?: return@forEachLine
            val frame = hex(hexStr)

            val ref = Framing.parseFrame(frame, DeviceFamily.WHOOP5)
            val keys = consumedKeys[ref.typeName] ?: return@forEachLine
            if (ref.crcOk != true) return@forEachLine

            val got = RustAdapter.parseFrame(frame, DeviceFamily.WHOOP5)

            if (got.crcOk != true) {
                // whoop-rs declined to decode. Only acceptable when Kotlin extracted nothing stored — a
                // bare non-SUCCESS status ack (the short GET_DATA_RANGE PENDING case).
                val refResult = ref.parsed["result"] as? String
                val bareStatus = ref.typeName == "COMMAND_RESPONSE" &&
                    ref.parsed.keys.all { it in statusOnlyKeys } &&
                    refResult?.startsWith("SUCCESS") != true
                if (!bareStatus) undecoded.add("$hexStr -> kotlin ${ref.typeName} ${ref.parsed}")
                return@forEachLine
            }

            if (ref.typeName != got.typeName) mismatches.add("typeName $hexStr: ${ref.typeName} vs ${got.typeName}")
            for (k in keys) {
                if (ref.parsed[k] != got.parsed[k]) {
                    mismatches.add("$k @ ${ref.typeName} $hexStr: ${ref.parsed[k]} vs ${got.parsed[k]}")
                }
            }
            if (ref.typeName == "COMMAND_RESPONSE" &&
                (got.parsed["resp_cmd"] as? String)?.startsWith("GET_DATA_RANGE") == true &&
                (got.parsed["result"] as? String)?.startsWith("SUCCESS") == true
            ) {
                dataRangeSuccess++
            }
            decodedCompared++
        }

        println("LiveDecodeParity: $decodedCompared decoded frames matched; dataRangeSuccess=$dataRangeSuccess; " +
            "undecoded-with-stored=${undecoded.size}; mismatches=${mismatches.size}")
        assertTrue("stored-key mismatches:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
        assertTrue("rust failed to decode a frame carrying stored data:\n${undecoded.joinToString("\n")}", undecoded.isEmpty())
        assertTrue("expected decoded live frames in the capture", decodedCompared > 0)
        assertTrue("expected at least one GET_DATA_RANGE SUCCESS to decode (backfill kick intact)", dataRangeSuccess > 0)
    }
}
