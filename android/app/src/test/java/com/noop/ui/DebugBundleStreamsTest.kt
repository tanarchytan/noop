package com.noop.ui

import com.noop.ble.WhoopBleClient
import com.noop.ingest.HistoryRecordSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The full export must carry every file the debug switch writes: the raw frames, the decoded records, the
 * event frames and the deep buffers, both generations of each. A stream added to the writers and forgotten
 * here would leave data on the phone with no way off it, so the file set is asserted by name.
 */
class DebugBundleStreamsTest {

    private fun dirWith(vararg names: String): File {
        val dir = java.nio.file.Files.createTempDirectory("filesDir").toFile()
        for (n in names) File(dir, n).writeText("{\"line\":1}\n")
        return dir
    }

    @Test
    fun everyCaptureFileTheSwitchWritesIsCarried() {
        val dir = dirWith(
            WhoopBleClient.WHOOP5_CAPTURE_FILE,
            "${WhoopBleClient.WHOOP5_CAPTURE_FILE}.1",
            HistoryRecordSink.FILE,
            HistoryRecordSink.PREV_FILE,
            WhoopBleClient.WHOOP5_EVENT_LOG_FILE,
            "${WhoopBleClient.WHOOP5_EVENT_LOG_FILE}.1",
            WhoopBleClient.WHOOP5_DEEPBUFFER_FILE,
            "${WhoopBleClient.WHOOP5_DEEPBUFFER_FILE}.1",
        )
        val entries = DebugBundle.captureStreams(dir).map { it.first }
        assertEquals(
            listOf(
                "raw-frames.prev.jsonl", "raw-frames.jsonl",
                "records.prev.jsonl", "records.jsonl",
                "events.prev.jsonl", "events.jsonl",
                "deep-buffers.prev.jsonl", "deep-buffers.jsonl",
            ),
            entries,
        )
        dir.deleteRecursively()
    }

    /** An absent or zero-length file is dropped, so the bundle lists only what was really recorded. */
    @Test
    fun onlyTheFilesThatHoldSomethingAreCarried() {
        val dir = dirWith(HistoryRecordSink.FILE)
        File(dir, WhoopBleClient.WHOOP5_CAPTURE_FILE).writeText("")
        val entries = DebugBundle.captureStreams(dir)
        assertEquals(listOf("records.jsonl"), entries.map { it.first })
        assertTrue(entries.single().second.length() > 0L)
        dir.deleteRecursively()
    }

    @Test
    fun aPhoneWithNoCaptureYieldsNoStreams() {
        val dir = dirWith()
        assertTrue(DebugBundle.captureStreams(dir).isEmpty())
        dir.deleteRecursively()
    }
}
