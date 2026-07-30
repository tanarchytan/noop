package com.noop.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** The bundle writer: inline entries ride verbatim, a streamed file is copied line by line through the
 *  PII sink, and a bundle with nothing in it is refused rather than written empty. */
class LogExportZipTest {

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val seen = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                seen[e.name] = zin.readBytes()
                e = zin.nextEntry
            }
        }
        return seen
    }

    @Test fun inlineEntriesRoundTrip() {
        val entries = listOf(
            "report.txt" to "hello report".toByteArray(),
            "meta.json" to "{\"schema\":1}".toByteArray(),
        )
        val out = ByteArrayOutputStream()
        assertTrue(LogExport.writeZip(out, entries))

        val seen = unzip(out.toByteArray())
        assertEquals(setOf("report.txt", "meta.json"), seen.keys)
        assertArrayEquals("hello report".toByteArray(), seen["report.txt"])
    }

    /** A stream is scrubbed AS IT COPIES: the assembler never sees these bytes, so this is the only
     *  redaction pass they get. */
    @Test fun aStreamedFileIsRedactedLineByLine() {
        val file = java.io.File.createTempFile("capture", ".jsonl")
        file.writeText(
            "{\"console\":\"connected to WHOOP 4C1594026 ok\"}\n" +
                "{\"mac\":\"DA:F7:41:80:FB:D4\"}\n",
        )
        val out = ByteArrayOutputStream()
        assertTrue(LogExport.writeZip(out, emptyList(), listOf("raw-frames.jsonl" to file)))

        val text = String(unzip(out.toByteArray())["raw-frames.jsonl"]!!)
        assertFalse(text.contains("4C1594026"))
        assertTrue(text.contains("WHOOP <serial>"))
        assertFalse(text.contains("DA:F7:41:80:FB:D4"))
        assertEquals(2, text.trim().lines().size)
        file.delete()
    }

    @Test fun anAbsentOrEmptyStreamIsSkipped() {
        val empty = java.io.File.createTempFile("empty", ".jsonl")
        val missing = java.io.File(empty.parentFile, "not-written.jsonl")
        val out = ByteArrayOutputStream()
        assertFalse(
            LogExport.writeZip(
                out,
                emptyList(),
                listOf("empty.jsonl" to empty, "missing.jsonl" to missing),
            ),
        )
        empty.delete()
    }

    @Test fun nothingToWriteReturnsFalse() {
        assertFalse(LogExport.writeZip(ByteArrayOutputStream(), emptyList()))
    }
}
