package com.noop.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noop.BuildConfig
import com.noop.R
import java.io.File

/**
 * The share layer behind the Debug screen's two exports: the strap log on its own, and the streamed
 * debug bundle [DebugBundle] assembles. [com.noop.ble.WhoopBleClient] keeps the strap log in an
 * in-memory ring buffer (`exportLogText()`); this writes it to cache/logs and fires a share sheet.
 */
object LogExport {

    /**
     * A `yyMMdd-HHmm` wall-clock stamp for export filenames, so several exports in a row sort and never
     * collide (`noop-strap-log-260617-1042.txt`). Locale-independent, so the stamp is device-stable.
     */
    fun timestamp(): String =
        java.text.SimpleDateFormat("yyMMdd-HHmm", java.util.Locale.US)
            .format(System.currentTimeMillis())

    /**
     * A full `YYYYMMDD-HHMMSS` wall-clock stamp for the SCHEDULED daily auto-export (maddognik), so
     * a day-after-day run drops sortable, second-precise, non-colliding files:
     * `noop-straplog-20260617-070000.txt` (and the raw `.bin` alongside). Distinct from [timestamp]
     * (minute-precision, for interactive shares) because the scheduler can fire twice in the same minute
     * across a reschedule and we never want one auto-export to clobber another. Locale-independent so the
     * stamp is identical on every device. Injectable epoch purely for the unit test.
     */
    fun exportStamp(nowMs: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(nowMs)

    /** The scheduled-export filenames, kept together so the formatter + extensions live in one place. */
    fun strapLogFilename(nowMs: Long = System.currentTimeMillis()) = "noop-straplog-${exportStamp(nowMs)}.txt"
    fun rawCaptureFilename(nowMs: Long = System.currentTimeMillis()) = "noop-straplog-${exportStamp(nowMs)}.bin"

    /**
     * Self-describing bundle filename: `noop-<profile>-<platform>-v<version>-<yyMMdd-HHmm>.zip`, so the
     * kind, platform and version are readable before the zip is opened. Injectable epoch for the test.
     */
    fun bundleName(profile: String, platform: String, version: String, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyMMdd-HHmm", java.util.Locale.US).format(nowMs)
        return "noop-$profile-$platform-v$version-$stamp.zip"
    }

    /**
     * Write one bundle into [out]: [inline] entries verbatim (already redacted by their builder), then each
     * of [streams] copied line by line through the PII sink. Streaming keeps a capture file of any size off
     * the heap. Returns false when there was nothing to write.
     */
    fun writeZip(
        out: java.io.OutputStream,
        inline: List<Pair<String, ByteArray>>,
        streams: List<Pair<String, File>> = emptyList(),
    ): Boolean {
        val present = streams.filter { it.second.exists() && it.second.length() > 0L }
        if (inline.isEmpty() && present.isEmpty()) return false
        java.util.zip.ZipOutputStream(out).use { zos ->
            for ((name, data) in inline) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            for ((name, file) in present) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                // Bound to one line at a time; flushed rather than closed so the zip stream stays open.
                val w = java.io.BufferedWriter(java.io.OutputStreamWriter(zos, Charsets.UTF_8))
                file.bufferedReader().use { r ->
                    r.forEachLine { line ->
                        w.write(com.noop.ble.redactStrapLogPii(line))
                        w.write("\n")
                    }
                }
                w.flush()
                zos.closeEntry()
            }
        }
        return true
    }

    /**
     * Stage the bundle under cache/logs (the FileProvider path) as [name] and fire the share chooser,
     * returning the file or null. [inline] must already be redacted; [streams] are scrubbed as they copy.
     */
    fun shareBundle(
        context: Context,
        name: String,
        inline: List<Pair<String, ByteArray>>,
        streams: List<Pair<String, File>> = emptyList(),
    ): File? =
        runCatching {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, name)
            val wrote = file.outputStream().buffered().use { writeZip(it, inline, streams) }
            if (!wrote) {
                file.delete()
                return null
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, fileUri(context, file))
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.logexport_share_bundle)),
            )
            file
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.logexport_bundle_failed, it.message.toString()),
                Toast.LENGTH_LONG,
            ).show()
        }.getOrNull()

    /**
     * Mirror the latest strap-log tail into the durable [StrapLogBuffer]. Called from the same UI
     * actions that ship a log interactively, AND on demand by [DebugExportScheduler] before a scheduled
     * write, so the 24h rolling buffer that the background worker reads is kept current even though the
     * worker can't reach the live BLE client. REPLACE semantics: `logText` is the client's authoritative
     * recent window, so we overwrite rather than append (no overlap duplication).
     */
    fun mirrorToRollingBuffer(logText: String) {
        StrapLogBuffer.replaceWith(logText)
    }

    /**
     * The SCHEDULED daily debug export: write the rolling-buffer strap log — plus the raw 5/MG
     * capture alongside as a `.bin`, if one exists — into the app-private export dir under a timestamped
     * name, returning the files written (log first). Unlike the interactive share paths this fires no
     * chooser: it runs from a [androidx.work.Worker] with no UI, leaving a dated pair on disk the user can
     * pick up later from Settings or a file manager. Reuses [StrapLogBuffer.snapshot] for the body so the
     * scheduled file matches what an interactive share would have shown.
     *
     * [logText] is the live tail if the scheduler could reach the BLE client; when it can't, it passes the
     * empty string and we fall back to the rolling buffer alone. Best-effort: returns an empty list on
     * failure rather than throwing into the worker.
     */
    suspend fun writeScheduledExport(context: Context, logText: String, nowMs: Long = System.currentTimeMillis()): List<File> =
        runCatching {
            if (logText.isNotBlank()) StrapLogBuffer.replaceWith(logText, nowMs)
            val body = StrapLogBuffer.snapshot(nowMs)

            val dir = exportDir(context)
            val out = arrayListOf<File>()

            val dynamic = com.noop.testcentre.AndroidDiagnostics.dynamicLines(context)
            val header = buildString {
                appendLine("NOOP strap log (scheduled debug export)")
                appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER})")
                for (line in com.noop.testcentre.AndroidDiagnostics.summaryLines(context)) appendLine(line)
                for (line in dynamic) appendLine(line)
                appendLine("─".repeat(40))
            }
            val text = body.ifBlank { "(rolling strap-log buffer is empty; connect to your strap so lines accrue)" }
            val logFile = File(dir, strapLogFilename(nowMs))
            logFile.writeText(header + "\n" + text)
            out.add(logFile)

            // The raw capture (JSONL of every backfilled frame) copied alongside as a matching `.bin` so the
            // scheduled drop is self-contained. Only present once the debug switch has seen a history sync.
            val main = File(context.filesDir, com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE)
            val prev = File(context.filesDir, "${com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE}.1")
            if (main.exists() || prev.exists()) {
                val rawFile = File(dir, rawCaptureFilename(nowMs))
                rawFile.outputStream().bufferedWriter().use { w ->
                    for (f in listOf(prev, main)) if (f.exists()) f.bufferedReader().use { r -> r.copyTo(w) }
                }
                out.add(rawFile)
            }
            // The decoded-record JSONL from the same capture switch: every field whoop-rs produced for
            // each second, which the stream tables cannot carry because they only store named columns.
            val recMain = File(context.filesDir, com.noop.ingest.HistoryRecordSink.FILE)
            val recPrev = File(context.filesDir, com.noop.ingest.HistoryRecordSink.PREV_FILE)
            if (recMain.exists() || recPrev.exists()) {
                val recFile = File(dir, "noop-records-${exportStamp(nowMs)}.jsonl")
                recFile.outputStream().bufferedWriter().use { w ->
                    for (f in listOf(recPrev, recMain)) if (f.exists()) f.bufferedReader().use { r -> r.copyTo(w) }
                }
                out.add(recFile)
            }
            out.toList()
        }.getOrDefault(emptyList())

    /** App-private export dir for the scheduled drops — under the same cache/logs tree the FileProvider
     *  already grants, so a future "open last export" share works without a manifest change. */
    private fun exportDir(context: Context): File =
        File(context.cacheDir, "logs").apply { mkdirs() }

    /**
     * Build the shareable strap-log file (header + body + last crash) under cache/logs and return it, so
     * the Strap debug export and the bundle's own copy of the log read the same content.
     */
    private suspend fun writeStrapLogFile(context: Context, logText: String): File {
        // Mirror every interactively-shared tail into the durable rolling buffer so the scheduled
        // background export has a current source even when the live BLE client is gone.
        mirrorToRollingBuffer(logText)
        val dynamic = com.noop.testcentre.AndroidDiagnostics.dynamicLines(context)
        val header = buildString {
            appendLine("NOOP strap log")
            appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER})")
            for (line in com.noop.testcentre.AndroidDiagnostics.summaryLines(context)) appendLine(line)
            for (line in dynamic) appendLine(line)
            appendLine("─".repeat(40))
        }
        val body = logText.ifBlank { "(strap log is empty; connect to your strap, reproduce the issue, then share again)" }

        // Append the last captured crash (if any) so a device-specific crash like the Insights
        // tab arrives with its real stack trace instead of being unreachable.
        val crash = com.noop.CrashCapture.lastCrash(context)
        val crashSection = if (crash != null) "\n\n${"─".repeat(40)}\nLast crash:\n$crash" else ""

        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "noop-strap-log-${timestamp()}.txt")
        file.writeText(header + "\n" + body + crashSection)
        return file
    }

    private fun fileUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    suspend fun shareStrapLog(context: Context, logText: String) {
        runCatching {
            val file = writeStrapLogFile(context, logText)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, fileUri(context, file))
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logexport_strap_log_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.logexport_share_strap_log)),
            )
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.logexport_share_failed, it.message.toString()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
