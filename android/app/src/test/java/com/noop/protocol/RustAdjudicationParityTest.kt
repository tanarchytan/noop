package com.noop.protocol

import com.noop.data.StreamPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.PpgSample
import uniffi.whoop_ffi.Response
import uniffi.whoop_ffi.ppgHr
import kotlin.math.abs

/**
 * In-JVM host parity for the ADJUDICATED decode divergences (ppgHr sub-lag, event kind/JSON contract,
 * fractional battery f64). Loads the host libwhoop_ffi via JNA (jna.library.path → the sibling whoop-rs
 * release dir); self-skips when the dll is absent so CI never breaks.
 *
 * Verdicts under test:
 *  - ppgHr: winner = Kotlin sub-lag; whoop-rs now ports it → Rust FFI must equal Kotlin
 *    `PpgHr.estimate(subLagInterp = true)` window-for-window, and hit the golden anchor (bpm 78, the
 *    intended +2 delta vs the OLD integer-lag whoop-rs).
 *  - events: storage CONTRACT — the widened `Live.Event` fed through `RustAdapter.liveToBatch` must
 *    reproduce the Kotlin `NAME(raw)` kind + canonical sorted-key payloadJSON byte-for-byte.
 *  - battery: winner = f64 division. The FFI `Response.Battery.percent` (Gen4) and the event-derived
 *    battery row must land on the exact Double (999 → 99.9), not the f32-domain 99.90000152.
 */
class RustAdjudicationParityTest {

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
        return out
    }

    private fun fixture(): JSONObject =
        JSONObject(javaClass.classLoader!!.getResourceAsStream("real_frames.json")!!.bufferedReader().readText())

    /** Decode a v26 optical PPG frame the way both decoders do: unix u32 LE @15, 24 i16 samples @27:75.
     *  (Mirrors `decodeWhoop5HistoricalV26` / Rust `records::decode` so both HR estimators see identical
     *  samples — the JSON `samples` field is incomplete for some frames, so decode from the hex.) */
    private fun v26(frame: ByteArray): Pair<Long, List<Int>>? {
        if (frame.size < 75) return null
        if ((frame[8].toInt() and 0xFF) != PacketType.HISTORICAL_DATA.rawValue || (frame[9].toInt() and 0xFF) != 26) return null
        val unix = ((frame[15].toInt() and 0xFF) or ((frame[16].toInt() and 0xFF) shl 8) or
            ((frame[17].toInt() and 0xFF) shl 16) or ((frame[18].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
        val samples = ArrayList<Int>(24)
        var off = 27
        while (off + 1 < 75) {
            samples.add(((frame[off].toInt() and 0xFF) or ((frame[off + 1].toInt() and 0xFF) shl 8)).toShort().toInt())
            off += 2
        }
        return unix to samples
    }

    private fun assumeFfiLoadable() {
        val ok = try {
            RustCodec.decodeHistory(true, byteArrayOf(0xAA.toByte(), 0x01, 0x74)); true
        } catch (t: Throwable) {
            false
        }
        Assume.assumeTrue("libwhoop_ffi not loadable in this JVM (host dll absent) — adjudication parity skipped", ok)
    }

    /** The Kotlin STORING decode of one 5.0 EVENT frame → (kind, canonical payloadJSON), mirroring the
     *  offload extractor (`Framing.parseFrame` → residual minus event/event_timestamp → encodePayload). */
    private fun kotlinEvent(frame: ByteArray): Pair<String, String> {
        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP5)
        val kind = (parsed.parsed["event"] as? String) ?: ""
        val residual = LinkedHashMap(parsed.parsed)
        residual.remove("event")
        residual.remove("event_timestamp")
        return kind to StreamPersistence.encodePayload(residual)
    }

    // ---- #1 ppgHr: Rust sub-lag == Kotlin sub-lag, and hits the golden anchor -----------------------

    @Test
    fun ppgHrMatchesKotlinSubLagAndGoldenAnchor() {
        assumeFfiLoadable()
        val root = fixture()
        val frames = root.getJSONArray("ppg_frames")

        val rustSamples = ArrayList<PpgSample>()
        val kotlinSamples = ArrayList<PpgHr.Sample>()
        for (i in 0 until frames.length()) {
            val (unix, samples) = v26(hex(frames.getJSONObject(i).getString("hex"))) ?: continue
            for (v in samples) {
                rustSamples.add(PpgSample(unix, v))
                kotlinSamples.add(PpgHr.Sample(unix, v))
            }
        }

        val rust = ppgHr(rustSamples)
        val kotlin = PpgHr.estimate(kotlinSamples, subLagInterp = true)

        val report = StringBuilder("\n==== ppgHr sub-lag: Rust(FFI) vs Kotlin(subLagInterp=true) ====\n")
        report.append("  rust count=${rust.size}  kotlin count=${kotlin.size}\n")
        assertEquals("estimate count differs", kotlin.size, rust.size)

        val mism = ArrayList<String>()
        for (k in rust.indices) {
            val r = rust[k]
            val kt = kotlin[k]
            val ok = r.ts == kt.ts && r.bpm == kt.bpm && abs(r.conf - kt.conf) < 1e-6
            if (!ok) mism.add("[$k] rust(ts=${r.ts},bpm=${r.bpm},conf=${r.conf}) kotlin(ts=${kt.ts},bpm=${kt.bpm},conf=${kt.conf})")
        }
        // Golden anchor from the shared fixture: the adjudicated (sub-lag) first estimate.
        val golden = root.getJSONObject("ppg_hr").getJSONObject("first")
        val goldenBpm = golden.getInt("bpm")
        val goldenCount = root.getJSONObject("ppg_hr").getInt("estimate_count")
        report.append("  golden first bpm=$goldenBpm (fixture)  count=$goldenCount\n")
        report.append("  rust  first: ts=${rust.first().ts} bpm=${rust.first().bpm} conf=${rust.first().conf}\n")
        report.append("  kotlin first: ts=${kotlin.first().ts} bpm=${kotlin.first().bpm} conf=${kotlin.first().conf}\n")
        if (mism.isNotEmpty()) report.append("  MISMATCHES:\n" + mism.joinToString("\n") { "    $it" } + "\n")
        println(report.toString())

        assertEquals("rust estimate count != golden", goldenCount, rust.size)
        assertEquals("rust first bpm != adjudicated golden", goldenBpm, rust.first().bpm)
        assertEquals("kotlin sub-lag first bpm != adjudicated golden", goldenBpm, kotlin.first().bpm)
        assertEquals("Rust ppgHr diverges from Kotlin sub-lag (see report)", emptyList<String>(), mism)
    }

    // ---- #2 events: widened Live.Event → adapter row == Kotlin storage contract ---------------------

    @Test
    fun eventRowsMatchKotlinStorageContract() {
        assumeFfiLoadable()
        val root = fixture()
        val evFrames = root.getJSONArray("event_frames")
        assertTrue("no event fixtures", evFrames.length() > 0)

        val report = StringBuilder("\n==== EVENT storage contract: adapter(Live.Event) vs Kotlin ====\n")
        val mism = ArrayList<String>()
        for (i in 0 until evFrames.length()) {
            val ef = evFrames.getJSONObject(i)
            val name = ef.getString("name")
            val bytes = hex(ef.getString("hex"))
            val ts = ef.getLong("timestamp")

            val live = RustCodec.decodeLive(true, bytes)
            if (live !is Live.Event) {
                mism.add("[$name] Rust did not decode a Live.Event (got $live)")
                continue
            }
            val batch = RustAdapter.liveToBatch(live, ts)
            val rustKind = batch.events.single().kind
            val rustJson = batch.events.single().payloadJSON

            val (kKind, kJson) = kotlinEvent(bytes)
            report.append("  [$name]\n")
            report.append("      kind   kotlin=$kKind rust=$rustKind\n")
            report.append("      json   kotlin=$kJson\n             rust  =$rustJson\n")
            if (rustKind != kKind) mism.add("[$name].kind kotlin=$kKind rust=$rustKind")
            if (rustJson != kJson) mism.add("[$name].json kotlin=$kJson rust=$rustJson")

            // BATTERY_LEVEL also fans a battery row; check the fractional soc lands on the exact f64.
            if (kKind.startsWith("BATTERY_LEVEL")) {
                val row = batch.battery.single()
                val expectPct = ef.getJSONObject("battery").getDouble("soc_percent")
                report.append("      battery soc rust=${row.soc} (expect $expectPct)  mv=${row.mv} charging=${row.charging}\n")
                if (row.soc != expectPct) mism.add("[$name].battery.soc rust=${row.soc} expect=$expectPct")
            }
        }
        println(report.toString())
        assertEquals("event storage-contract mismatches (see report)", emptyList<String>(), mism)
    }

    // ---- #3 fractional battery: FFI Response.Battery divides in f64 ----------------------------------

    @Test
    fun gen4BatteryResponseDividesInF64() {
        assumeFfiLoadable()
        // Gen4 GET_BATTERY_LEVEL response carrying raw deci-% 999 → the exact Double 99.9 (the f32-domain
        // division would give 99.90000152587891). Wire generated from the whoop-rs framing encoder.
        val wire = hex("aa0f00c324001a0000e70300000000bcd4cd28")
        val resp = RustCodec.decodeResponse(false, wire)
        assertTrue("expected Response.Battery, got $resp", resp is Response.Battery)
        val pct = (resp as Response.Battery).percent
        println("\n==== Gen4 battery f64 ====\n  rust percent=$pct   f32-domain=${(999.0f / 10.0f).toDouble()}\n")
        assertEquals("Gen4 battery not the exact f64 value", 99.9, pct, 0.0)
        assertTrue("f32-domain division must NOT be exact (proves the f64 win)", (999.0f / 10.0f).toDouble() != 99.9)
    }
}
