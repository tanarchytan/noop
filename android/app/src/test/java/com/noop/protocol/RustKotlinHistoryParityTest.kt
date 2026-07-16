package com.noop.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import uniffi.whoop_ffi.HistorySummary
import kotlin.math.abs

/**
 * Field-by-field parity: the Rust codec (via [RustCodec]/FFI) must decode the shared captured type-47
 * HISTORICAL_DATA fixtures to the same stored values the Kotlin decoder ([decodeHistorical]) does.
 *
 * Mechanism: an in-JVM host test. The host build of libwhoop_ffi (whoop_ffi.dll) is loaded by JNA from
 * the sibling whoop-rs release dir (jna.library.path, set in build.gradle testOptions). When that lib is
 * not loadable (CI / no sibling checkout) the whole test self-skips via Assume, so it never breaks the
 * build gate; it runs for real where the host lib exists.
 *
 * Comparison is at the STORED-VALUE level per the byte-identity contract: hr==0 is dropped on both sides
 * (the extract path stores bpm != 0), rr 0 is dropped, gravity is the f32->Double widen. Floats are held
 * to the fixtures' own 1e-4 tolerance; every integer field is exact. Divergences are collected and
 * printed, and the ones outside the contract fail the test.
 */
class RustKotlinHistoryParityTest {

    /** A normalized, decoder-agnostic view of the stored history fields we compare across the two codecs. */
    private data class Norm(
        val version: Int,
        val unix: Long,
        val hr: Int?,
        val rr: List<Int>,
        val gravity: List<Double>?,
        val skinTempRaw: Int?,
        val spo2Red: Int?,
        val spo2Ir: Int?,
        val respRaw: Int?,
        val steps: Int?,
        val activityClass: Int?,
        val sleepState: Int?,
    )

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
        return out
    }

    private fun readFixtures(resource: String): List<Triple<String, DeviceFamily, ByteArray>> {
        val txt = javaClass.classLoader!!.getResourceAsStream(resource)!!.bufferedReader().readText()
        val frames: JSONArray = JSONObject(txt).getJSONArray("frames")
        val out = ArrayList<Triple<String, DeviceFamily, ByteArray>>()
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val fam = f.getString("family").lowercase()
            val family = if (fam == "gen5" || fam == "whoop5") DeviceFamily.WHOOP5 else DeviceFamily.WHOOP4
            out.add(Triple("$resource:${f.getString("name")}", family, hex(f.getString("hex"))))
        }
        return out
    }

    private fun kotlinNorm(map: Map<String, Any?>): Norm {
        @Suppress("UNCHECKED_CAST")
        val rr = (map["rr_intervals"] as? List<Int>) ?: emptyList()
        val gx = map["gravity_x"] as? Double
        val gy = map["gravity_y"] as? Double
        val gz = map["gravity_z"] as? Double
        val gravity = if (gx != null && gy != null && gz != null) listOf(gx, gy, gz) else null
        val hrRaw = map["heart_rate"] as? Int
        return Norm(
            version = map["hist_version"] as Int,
            unix = (map["unix"] as Int).toLong() and 0xFFFFFFFFL,
            hr = if (hrRaw == null || hrRaw == 0) null else hrRaw,
            rr = rr,
            gravity = gravity,
            skinTempRaw = map["skin_temp_raw"] as? Int,
            spo2Red = map["spo2_red"] as? Int,
            spo2Ir = map["spo2_ir"] as? Int,
            respRaw = map["resp_rate_raw"] as? Int,
            steps = map["step_motion_counter"] as? Int,
            activityClass = map["activity_class"] as? Int,
            sleepState = map["sleep_state"] as? Int,
        )
    }

    private fun rustNorm(s: HistorySummary): Norm = Norm(
        version = s.version.toInt(),
        unix = s.unix.toLong(),
        hr = s.heartRate?.toInt()?.let { if (it == 0) null else it },
        rr = s.rrIntervals.map { it.toInt() },
        gravity = s.gravity?.map { it.toDouble() },
        skinTempRaw = s.skinTempRaw?.toInt(),
        spo2Red = s.spo2Red?.toInt(),
        spo2Ir = s.spo2Ir?.toInt(),
        respRaw = s.respRaw?.toInt(),
        steps = s.steps?.toInt(),
        activityClass = s.activityClass?.toInt(),
        sleepState = s.sleepState?.toInt(),
    )

    private fun gravityEqual(a: List<Double>?, b: List<Double>?, tol: Double): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a.size != b.size) return false
        return a.indices.all { abs(a[it] - b[it]) <= tol }
    }

    private fun gravityExact(a: List<Double>?, b: List<Double>?): Boolean = a == b

    @Test
    fun historyDecodeParity() {
        // Load the host FFI once; if the native lib can't be loaded here (no sibling whoop-rs build,
        // CI without the dll), skip rather than fail the build gate. A short frame decodes to null (not a
        // throw), which still proves the native library loaded.
        val ffiOk = try {
            RustCodec.decodeHistory(true, byteArrayOf(0xAA.toByte(), 0x01, 0x74)); true
        } catch (t: Throwable) {
            false
        }
        Assume.assumeTrue("libwhoop_ffi not loadable in this JVM (host dll absent) — parity test skipped", ffiOk)

        // Merge both shared fixture files, dedup by frame bytes (real_frames.json is a subset of the oracle
        // by hex, but include both explicitly as the task's shared goldens).
        val seen = HashSet<String>()
        val fixtures = (readFixtures("decoder_oracle.json") + readFixtures("real_frames.json"))
            .filter { seen.add(it.first.substringAfter(':') + ":" + it.third.joinToString("") { b -> "%02x".format(b) }) }

        val report = StringBuilder("\n==== Rust(FFI) vs Kotlin HISTORICAL_DATA parity ====\n")
        val blocking = ArrayList<String>()
        val floatWidth = ArrayList<String>()
        val presenceDiff = ArrayList<String>()
        var compared = 0

        for ((name, family, bytes) in fixtures) {
            val isGen5 = family == DeviceFamily.WHOOP5
            val kMap = decodeHistorical(bytes, family)
            val rSummary: HistorySummary? = try {
                RustCodec.decodeHistory(isGen5, bytes)
            } catch (t: Throwable) {
                report.append("  [$name] Rust decode threw: ${t.message}\n"); null
            }

            // Both null (e.g. a version neither stores) → agree, nothing to compare.
            if (kMap == null && rSummary == null) {
                report.append("  [$name] both null (not stored) — OK\n"); continue
            }
            if (kMap == null || rSummary == null) {
                val msg = "[$name] decode-presence mismatch: kotlin=${kMap != null} rust=${rSummary != null}"
                report.append("  $msg\n"); blocking.add(msg); continue
            }

            compared++
            val k = kotlinNorm(kMap)
            val r = rustNorm(rSummary)
            report.append("  [$name]\n")

            fun cmp(field: String, kv: Any?, rv: Any?) {
                val ok = kv == rv
                report.append("      %-14s kotlin=%-14s rust=%-14s %s\n".format(field, kv, rv, if (ok) "OK" else "MISMATCH"))
                if (!ok) blocking.add("[$name].$field kotlin=$kv rust=$rv")
            }
            cmp("version", k.version, r.version)
            cmp("unix", k.unix, r.unix)
            cmp("hr", k.hr, r.hr)
            cmp("rr", k.rr, r.rr)
            cmp("skinTempRaw", k.skinTempRaw, r.skinTempRaw)
            cmp("spo2Red", k.spo2Red, r.spo2Red)
            cmp("spo2Ir", k.spo2Ir, r.spo2Ir)
            cmp("respRaw", k.respRaw, r.respRaw)
            cmp("steps", k.steps, r.steps)
            cmp("activityClass", k.activityClass, r.activityClass)
            cmp("sleepState", k.sleepState, r.sleepState)

            // Gravity: presence must agree; values within the fixtures' 1e-4 tolerance; exactness reported.
            val presentOk = (k.gravity != null) == (r.gravity != null)
            if (!presentOk) {
                val msg = "[$name].gravity presence kotlin=${k.gravity} rust=${r.gravity}"
                report.append("      gravity        PRESENCE-DIFF kotlin=${k.gravity} rust=${r.gravity}\n")
                presenceDiff.add(msg)
            } else if (k.gravity != null) {
                val within = gravityEqual(k.gravity, r.gravity, 1e-4)
                val exact = gravityExact(k.gravity, r.gravity)
                report.append(
                    "      gravity        kotlin=${k.gravity} rust=${r.gravity} " +
                        "${if (within) "OK(<=1e-4)" else "MISMATCH"}${if (within && !exact) " [float-width, not byte-exact]" else ""}\n",
                )
                if (!within) blocking.add("[$name].gravity kotlin=${k.gravity} rust=${r.gravity}")
                if (within && !exact) floatWidth.add("[$name].gravity kotlin=${k.gravity} rust=${r.gravity}")
            }

            // spo2_pct is Rust-only (5.0 sleep SpO2 the app does not store today) — informational, not a mismatch.
            rSummary.spo2Pct?.let { report.append("      (rust-only spo2_pct=$it — not stored by Kotlin, informational)\n") }
        }

        report.append("\n  compared=$compared  blocking-mismatches=${blocking.size}  " +
            "gravity-presence-diffs=${presenceDiff.size}  gravity-float-width-only=${floatWidth.size}\n")
        if (presenceDiff.isNotEmpty()) report.append("  GRAVITY PRESENCE DIFFS (gate divergence, see notes):\n" + presenceDiff.joinToString("\n") { "    $it" } + "\n")
        if (floatWidth.isNotEmpty()) report.append("  GRAVITY FLOAT-WIDTH (within 1e-4, not byte-exact):\n" + floatWidth.joinToString("\n") { "    $it" } + "\n")
        println(report.toString())

        // Gate: on these fixtures the two codecs are fully byte-identical — every integer field exact,
        // every gravity vector bit-exact (not merely within 1e-4). Assert that strong result. Rust's v18
        // |g|-gate could in principle drop a sub-plausible off-wrist gravity that Kotlin keeps, but every
        // captured off-wrist frame here still reads |g| ~ 1, so no presence divergence occurs. If a future
        // fixture trips the gate (presenceDiff non-empty) or exposes an f32/f64 width gap (floatWidth
        // non-empty), this fails and the wiring adapter must reconcile it.
        assertTrue("no fixtures were compared", compared > 0)
        assertEquals("byte-identity field mismatches (see printed report)", emptyList<String>(), blocking)
        assertEquals("gravity presence divergences (Rust |g|-gate vs Kotlin ungated)", emptyList<String>(), presenceDiff)
        assertEquals("gravity float-width (non-byte-exact) divergences", emptyList<String>(), floatWidth)
    }
}
