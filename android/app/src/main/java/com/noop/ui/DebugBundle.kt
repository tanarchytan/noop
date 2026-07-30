package com.noop.ui

import android.content.Context
import com.noop.BuildConfig
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ingest.HistoryRecordSink
import com.noop.ingest.RawSensorExport
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import com.noop.testcentre.TestBundleMeta
import com.noop.testcentre.TestDomain
import java.io.File

/**
 * Gathers the Debug screen's "Full debug export": the strap log and diagnostics inline, plus every
 * capture file the debug switch writes and the decoded-sensor CSV as streamed attachments. Assembly and
 * sharing live here so [TestCentreScreen] stays presentation only.
 */
object DebugBundle {

    /** One in-zip name per capture file the debug switch writes, newest generation last. */
    private val CAPTURE_FILES: List<Pair<String, String>> = listOf(
        "raw-frames.prev.jsonl" to "${WhoopBleClient.WHOOP5_CAPTURE_FILE}.1",
        "raw-frames.jsonl" to WhoopBleClient.WHOOP5_CAPTURE_FILE,
        "records.prev.jsonl" to HistoryRecordSink.PREV_FILE,
        "records.jsonl" to HistoryRecordSink.FILE,
        "events.prev.jsonl" to "${WhoopBleClient.WHOOP5_EVENT_LOG_FILE}.1",
        "events.jsonl" to WhoopBleClient.WHOOP5_EVENT_LOG_FILE,
        "deep-buffers.prev.jsonl" to "${WhoopBleClient.WHOOP5_DEEPBUFFER_FILE}.1",
        "deep-buffers.jsonl" to WhoopBleClient.WHOOP5_DEEPBUFFER_FILE,
    )

    /** The name the sensor CSV takes inside the bundle. */
    private const val SENSOR_CSV_ENTRY = "sensors.csv"

    /**
     * A bundle staged for the review gate: [inline] is already redacted, [streams] are scrubbed line by
     * line as they copy into the zip, and [gate] holds both so the review names every attachment.
     */
    class Staged(
        val inline: List<Pair<String, ByteArray>>,
        val streams: List<Pair<String, File>>,
        val gate: ReportReviewGate,
    )

    /**
     * The capture files that exist in [dir], as (in-zip name, file). Absent files are dropped rather than
     * shipped empty, so the bundle lists only what was really recorded.
     */
    fun captureStreams(dir: File): List<Pair<String, File>> =
        CAPTURE_FILES.map { (entry, name) -> entry to File(dir, name) }
            .filter { it.second.exists() && it.second.length() > 0L }

    /**
     * Assemble the full export. Reads the store (row counts, the sensor CSV), so callers run it off the
     * main thread. The log text comes from the live BLE client, which only the caller can reach.
     */
    suspend fun stage(
        context: Context,
        repo: WhoopRepository,
        logText: String,
        strapId: String,
    ): Staged {
        val inline = TestBundleAssembler.assemble(
            context,
            TestDomain.MASTER,
            logText,
            storageProbe(context, repo),
            strapModel(context),
        )
        val streams = ArrayList(captureStreams(context.filesDir))
        RawSensorExport.writeFile(context, repo, strapId)?.let { csv ->
            if (csv.length() > 0L) streams.add(SENSOR_CSV_ENTRY to csv)
        }
        val sizes = streams.map { (entry, file) -> entry to file.length() }
        return Staged(inline, streams, ReportReviewGate(inline, sizes))
    }

    /** Share the staged bundle once the gate is cleared. A cleared gate is the only path to a share. */
    fun share(context: Context, staged: Staged): File? {
        if (!staged.gate.isCleared) return null
        val name = LogExport.bundleName("debug", "android", BuildConfig.VERSION_NAME)
        return LogExport.shareBundle(context, name, staged.inline, staged.streams)
    }

    /**
     * The real storage figures for meta.json: the Room store on disk (with its -wal/-shm sidecars), the
     * per-table row counts, and the capture files' own footprint. A probe that reads nothing returns null
     * so meta keeps its honest zeroed block rather than a made-up figure.
     */
    private suspend fun storageProbe(context: Context, repo: WhoopRepository): TestBundleMeta.Storage? {
        val dbPath = context.getDatabasePath(WhoopDatabase.DB_NAME)
        var dbBytes = 0L
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = File(dbPath.path + suffix)
            if (f.exists()) dbBytes += f.length()
        }
        val rows = repo.storageRowCounts()
        val rawBytes = captureStreams(context.filesDir).sumOf { it.second.length() }
        if (dbBytes <= 0L && rows.isEmpty() && rawBytes <= 0L) return null
        return TestBundleMeta.Storage(
            dbBytes = dbBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rows = rows,
            rawCaptureBytes = rawBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    /** The connected strap's display name: the scan/connect path persists the detected family to this pref. */
    private fun strapModel(context: Context): String? =
        NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
            ?.let { name -> runCatching { WhoopModel.valueOf(name).displayName }.getOrNull() }
}
