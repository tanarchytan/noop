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

    /**
     * The Debug screen tells the user this capture costs about 25 MB a night, so the line width that figure
     * rests on is pinned here. Input: one real worn v18 record as whoop-rs decodes it (the field map and the
     * 124-byte frame), written out at one record per strap-second over an eight-hour night.
     */
    @Test
    fun theNightlyVolumeMatchesWhatTheDebugScreenStates() {
        val fields = linkedMapOf<String, Any?>(
            "hist_version" to 18, "unix" to 1_780_916_150, "heart_rate" to 102,
            "rr_intervals" to listOf(602, 613), "skin_temp_raw" to 3057,
            "step_motion_counter" to 50, "activity_class" to 0, "sleep_state" to 0,
            "gravity_x" to -0.7251733541488647, "gravity_y" to 0.4944165050983429,
            "gravity_z" to 0.4968554675579071, "dynamic_acceleration_g" to 0.009159564971923828,
            "temp_aux_1_raw" to 247, "temp_aux_2_raw" to 265, "record_index" to 25_443_699L,
            "sleep_state_raw" to 0, "optical_baseline_a" to 101, "optical_baseline_b" to 111,
            "optical_amp_a" to 30, "optical_amp_b" to 30, "optical_signal_poor" to false,
            "raw_u8_28" to 141, "raw_u8_29" to 101, "raw_u16_30" to 25_444,
            "raw_f32_105" to -5.230665683746338, "raw_u16_26" to 2683,
            "unpinned" to ByteArray(13) { it.toByte() },
        )
        val bytesPerRecord = HistoryRecordSink.line("DA:F7:41:80:FB:D4", fields, ByteArray(124)).length + 1
        val mbPerNight = bytesPerRecord * 28_800.0 / 1_048_576.0
        assertTrue(
            "a night of records is $mbPerNight MB; the Debug screen says about 25",
            mbPerNight > 20.0 && mbPerNight < 30.0,
        )
    }
}
