package com.noop.testcentre

import android.content.Context
import android.os.Build
import com.noop.BuildConfig
import com.noop.CrashCapture
import com.noop.ble.redactStrapLogPii

/**
 * Gathers the inline bundle files (report.txt, last-crash.txt, meta.json), re-runs the redaction pass over
 * EVERY one of them, applies the 20 MB cap, and returns them ready to zip. The single scrub point: the
 * WhoopBleClient.log() sink scrubs its own lines, and re-running it here covers everything else, stamped
 * as meta.redaction. The streamed capture files are scrubbed line by line by LogExport.writeZip instead.
 */
object TestBundleAssembler {

    const val REDACTION_VERSION = "v2"

    /**
     * Re-run the redaction sink over every entry. Text entries are decoded UTF-8, scrubbed via the same
     * redactStrapLogPii used by the live log sink, and re-encoded. raw-capture is where the embedded
     * serials live; report.txt and meta.json have no PII shapes so they pass through unchanged.
     */
    fun redactEntries(entries: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> =
        entries.map { (name, data) ->
            // BINARY entries (the Display mode's screenshot.png) must NOT be decoded as text and re-encoded
            // - that would corrupt the PNG. Redaction scrubs text identifiers, not pixels, so a binary
            // entry passes through untouched (the Swift twin guards the same way via the UTF-8 decode
            // returning nil). Only text entries are scrubbed.
            if (isBinaryEntry(name)) {
                name to data
            } else {
                name to redactStrapLogPii(String(data)).toByteArray()
            }
        }

    /** A bundle entry that is binary (image bytes), never text to scrub. screenshot.png is the only one;
     *  a .jsonl entry stays text (JSON lines) and is still scrubbed. */
    private fun isBinaryEntry(name: String): Boolean = name == DisplayScreenshot.BUNDLE_NAME

    /**
     * Hard cap the inline entries at [capBytes] (20 MB default) by trimming the LARGEST one to the budget
     * the others leave it, keeping its most-recent tail. A backstop: report.txt rides a bounded ring buffer
     * and meta.json is small, so nothing normally reaches the cap. Returns the capped entries plus whether
     * truncation happened, which the caller writes to meta.truncated.
     */
    fun capEntries(
        entries: List<Pair<String, ByteArray>>,
        capBytes: Int = 20 * 1024 * 1024,
    ): Pair<List<Pair<String, ByteArray>>, Boolean> {
        val total = entries.sumOf { it.second.size }
        if (total <= capBytes) return entries to false
        val biggest = entries.maxByOrNull { it.second.size }?.first ?: return entries to false
        val others = entries.filter { it.first != biggest }.sumOf { it.second.size }
        val budget = maxOf(0, capBytes - others)
        var truncated = false
        val capped = entries.map { (name, data) ->
            if (name == biggest && data.size > budget) {
                truncated = true
                name to data.copyOfRange(data.size - budget, data.size)  // keep the tail
            } else {
                name to data
            }
        }
        return capped to truncated
    }

    // assemble (the inline half of the debug export; DebugBundle adds the streamed capture files) --------

    /**
     * Gather the inline files for [profile], redact every one, cap them, then build and append meta.json
     * (carrying the truncated flag from the cap) and redact-pass it too. Returns the final, already-redacted
     * and already-capped entries ready to zip.
     *
     * [logText] is the live strap-log tail so the assembler stays off the BLE client and is testable; the
     * header and last crash are added here to match the strap-log file.
     *
     * [storage] / [strapModel] are the caller's REAL probes (the row counts are suspend store reads, so the
     * sync assembler cannot run them itself). null means the probe could not run: meta then carries the
     * zeroed block, which stays honest, because zeros mean "nothing readable" and never a made-up figure.
     */
    fun assemble(
        context: Context,
        profile: TestDomain,
        logText: String,
        storage: TestBundleMeta.Storage? = null,
        strapModel: String? = null,
    ): List<Pair<String, ByteArray>> {
        val tc = TestCentre.from(context)
        // The set of currently-active domains drives the report-completeness guard. MASTER turns every
        // domain on (TestCentre.active resolves that), so a master report checks every mapped trace.
        val activeDomains = ReportCompleteness.killerTokens.keys
            .filter { tc.active(it) }
            .toSet()

        // 1. report.txt: header (app + Android diagnostics) + the strap-log body, the same shape the
        //    strap-log share writes. Already scrubbed by the log() sink; the redactEntries pass re-scrubs.
        val header = buildString {
            appendLine("NOOP strap log")
            appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER})")
            for (line in AndroidDiagnostics.summaryLines(context)) appendLine(line)
            appendLine("-".repeat(40))
        }
        val body = logText.ifBlank {
            "(strap log is empty, connect to your strap, reproduce the issue, then export again)"
        }
        // CAPTURE-completeness: append the "Capture check" section so report.txt itself states, per active
        // domain, whether its killer trace landed. Computed over the header+body that will ship (the guard
        // reads exactly what the maintainer reads). Byte-identical section to the Swift twin.
        val reportBody = header + "\n" + body
        val captureCheck = ReportCompleteness.captureCheckSection(reportBody, activeDomains)
        val reportText = reportBody + "\n" + captureCheck
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries.add("report.txt" to reportText.toByteArray())

        // last-crash.txt: only if a crash was captured (degrade gracefully, never fabricate).
        var crashWasCaptured = false
        CrashCapture.lastCrash(context)?.let { crash ->
            entries.add("last-crash.txt" to crash.toByteArray())
            crashWasCaptured = true
        }

        // 1b. A DISPLAY-profile bundle carries a screenshot. The binary PNG is kept OUT of the redact pass
        //     (redaction scrubs text identifiers, not pixels) and is named by the review gate, so the user
        //     can cancel rather than share it. Any other profile never grabs a shot.
        val wantsShot = profile == TestDomain.DISPLAY
        val shot: Pair<String, ByteArray>? = if (wantsShot) {
            DisplayScreenshot.capturePNG(context)?.let { png -> DisplayScreenshot.BUNDLE_NAME to png }
        } else {
            null
        }

        // 2. Redact every gathered TEXT file, then cap. The screenshot rides the cap input (NOT the redact
        //    input) so its bytes count against the ceiling, shrinking the trimmed entry rather than
        //    breaching it.
        val redacted = redactEntries(entries)
        val (capped, truncated) = capEntries(redacted + listOfNotNull(shot))
        val out = ArrayList(capped)

        // 3. meta.json: the machine-readable tie. Answers + startedAt come off the single TestCentre
        //    surface; storage + strapModel are the caller's real probes, and a null probe falls back to the
        //    zeroed block, where zeros mean "unreadable" rather than a fabricated figure.
        val started = tc.startedAt(profile)?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .format(java.util.Date(it * 1000L))
        }
        val meta = TestBundleMeta(
            schema = 1,
            appVersion = BuildConfig.VERSION_NAME,
            platform = "Android",
            osVersion = Build.VERSION.RELEASE ?: "?",
            strapModel = strapModel,
            source = listOf("Live Bluetooth"),
            testProfile = profile.id,
            profileStartedAt = started,
            questionnaire = tc.answers(profile),
            build = TestBundleMeta.Build(channel = "GitHub", signed = false),
            storage = storage ?: TestBundleMeta.Storage(dbBytes = 0, rows = emptyMap(), rawCaptureBytes = 0),
            redaction = REDACTION_VERSION,
            truncated = truncated,
            // The same completeness guard, machine-readable, computed over the SHIPPING report.txt so
            // meta.capture_check and the report.txt section can never disagree.
            captureCheck = ReportCompleteness.captureCheckMeta(reportText, activeDomains).let {
                TestBundleMeta.CaptureCheck(traces = it.traces, complete = it.complete)
            },
        )
        // meta.json has no PII shapes, but route it through the same sink so every inline entry has passed
        // one scrub point.
        out += redactEntries(listOf("meta.json" to meta.encoded().toByteArray()))

        // 4. Bundle robustness check over the FINAL bytes: report.txt + meta.json present and non-empty, the
        //    screenshot honoured when expected, a captured crash attached, and no raw MAC / serial surviving
        //    redaction in any text entry. Its PII-free summary line is appended to report.txt so the verdict
        //    ships with the bundle. The scan reads the bundle WITHOUT that line, so it cannot self-reference.
        val robustness = BundleRobustness.verify(
            entries = out,
            expectScreenshot = wantsShot,
            crashWasCaptured = crashWasCaptured,
        )
        val reportIdx = out.indexOfFirst { it.first == "report.txt" }
        if (reportIdx >= 0) {
            val withVerdict = String(out[reportIdx].second) + "\n" + robustness.summaryLine(out.size)
            out[reportIdx] = redactEntries(listOf("report.txt" to withVerdict.toByteArray())).first()
        }
        return out
    }
}
