package com.noop.testcentre

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mandatory, non-skippable review gate. A fresh or cancelled gate never clears, only an explicit
 * confirm clears, and the preview states everything that is about to leave the phone: the text inline and
 * each streamed attachment by name and size.
 */
class ReportReviewGateTest {

    private fun sampleEntries(): List<Pair<String, ByteArray>> =
        listOf("report.txt" to "NOOP strap log\nline 1\nline 2".toByteArray())

    @Test
    fun freshGateIsNotCleared() {
        assertFalse(ReportReviewGate(sampleEntries()).isCleared)
    }

    @Test
    fun previewShowsTheReportText() {
        val gate = ReportReviewGate(sampleEntries())
        assertTrue(gate.previewText.contains("line 1"))
        assertTrue(gate.previewText.contains("line 2"))
    }

    @Test
    fun previewNamesEveryAttachmentWithItsSize() {
        val gate = ReportReviewGate(
            sampleEntries(),
            listOf("records.jsonl" to 26_870_400L, "events.jsonl" to 4096L),
        )
        val preview = gate.previewText
        assertTrue(preview.contains("=== report.txt ==="))
        assertTrue(preview.contains("records.jsonl  25.6 MB"))
        assertTrue(preview.contains("events.jsonl  4 KB"))
    }

    @Test
    fun confirmClearsAndCancelDoesNot() {
        val gate = ReportReviewGate(sampleEntries())
        gate.cancel()
        assertFalse(gate.isCleared)
        gate.confirm()
        assertTrue(gate.isCleared)
    }
}
