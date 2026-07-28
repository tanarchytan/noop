package com.noop.ingest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sink writes one JSON object per frame. A malformed line corrupts an offload without failing
 * anything on the phone, so the shapes that can break it are pinned: byte packs, non-finite doubles,
 * interval lists, a device id carrying quotes, and the undecodable frame that has no fields at all.
 */
class HistoryRecordSinkTest {

    private val frame = byteArrayOf(0x2f, 0x00, 0x12, 0xff.toByte())

    @Test
    fun everyDecodedFieldSurvivesAsJson() {
        val o = JSONObject(
            HistoryRecordSink.line(
                "whoop-AA:BB",
                linkedMapOf(
                    "unix" to 1_784_141_340L,
                    "heart_rate" to 63,
                    "rr_intervals" to listOf(602, 613),
                    "optical_signal_poor" to false,
                    "skin_temp_c" to 33.03,
                    "unpinned" to byteArrayOf(0x00, 0x0f, 0xff.toByte()),
                ),
                frame,
            ),
        )
        assertEquals("whoop-AA:BB", o.getString("device"))
        assertEquals(1_784_141_340L, o.getLong("unix"))
        assertEquals(63, o.getInt("heart_rate"))
        assertEquals(613, o.getJSONArray("rr_intervals").getInt(1))
        assertFalse(o.getBoolean("optical_signal_poor"))
        assertEquals(33.03, o.getDouble("skin_temp_c"), 1e-9)
        assertEquals("000fff", o.getString("unpinned"))
    }

    /** The whole point: whatever the codec did not name is still recoverable from the frame. */
    @Test
    fun theFrameBytesAreAlwaysCarriedVerbatim() {
        val o = JSONObject(HistoryRecordSink.line("d", linkedMapOf("heart_rate" to 63), frame))
        assertEquals("2f0012ff", o.getString("frame"))
    }

    @Test
    fun aFrameWithNoDecoderIsCapturedAndFlagged() {
        val o = JSONObject(HistoryRecordSink.line("d", null, frame))
        assertEquals("2f0012ff", o.getString("frame"))
        assertFalse(o.getBoolean("decoded"))
        assertEquals("d", o.getString("device"))
    }

    @Test
    fun aDecodedFrameCarriesNoDecodedFlag() {
        assertFalse(JSONObject(HistoryRecordSink.line("d", linkedMapOf("a" to 1), frame)).has("decoded"))
    }

    @Test
    fun anAbsentFieldIsOmittedRatherThanWrittenNull() {
        val o = JSONObject(HistoryRecordSink.line("d", linkedMapOf("a" to 1, "b" to null), frame))
        assertTrue(o.has("a"))
        assertFalse(o.has("b"))
    }

    @Test
    fun aNonFiniteDoubleBecomesNullBecauseJsonHasNoNaN() {
        val line = HistoryRecordSink.line("d", linkedMapOf("x" to Double.NaN, "y" to Double.POSITIVE_INFINITY), frame)
        assertTrue(JSONObject(line).isNull("x"))
        assertTrue(JSONObject(line).isNull("y"))
    }

    @Test
    fun aQuoteInTheDeviceIdDoesNotBreakTheLine() {
        val o = JSONObject(HistoryRecordSink.line("we\"ird\\", linkedMapOf("a" to 1), frame))
        assertEquals("we\"ird\\", o.getString("device"))
    }

    /** A decoded field named `frame` or `device` must not be able to shadow the two we control. */
    @Test
    fun theReservedKeysAreNotShadowedByADecodedField() {
        val o = JSONObject(
            HistoryRecordSink.line("real", linkedMapOf("device" to "fake", "frame" to "dead"), frame),
        )
        assertEquals("real", o.getString("device"))
        assertEquals("2f0012ff", o.getString("frame"))
    }

    @Test
    fun anEmptyFrameStillProducesAValidLine() {
        val o = JSONObject(HistoryRecordSink.line("d", null, ByteArray(0)))
        assertEquals("", o.getString("frame"))
    }
}
