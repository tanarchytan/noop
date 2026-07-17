package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHOOP 4.0 **v25** historical layout (issue #30) reaching the streams through [extractHistoricalStreams].
 * Three REAL v25 records (faklei, App 1.92, 2026-06-11), 84 bytes each; `unix` @11 (u32 LE) and the DSP
 * gravity vector (|gravity| ≈ 1 g). The record bytes→values decode now runs through whoop-rs; this pins
 * the app-side plumbing that a v25 record is NOT treated as undecodable and its gravity survives to a row.
 */
class Whoop4HistoricalV25Test {
    private fun bytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private val records = listOf(
        "aa50000c2f1900006800007dff2a6a20430900433103007e026502ba026c022eff70f996f879fad6fd8300d6017e0267027201be00290258030e05c507f00c030ead11cb15791500d2553c9003000000d6393716",
        "aa50000c2f1900016800007eff2a6a283e0900a0ad03007a0e880698018bfff5fb61eee9f2a7fa2bfe1af5fdf618fdf0f9c2fb0804510a14046a004dffd0ff6dfdddfd670183014e071a3f9003000000587bbabf",
        "aa50000c2f1900026800007fff2a6a38390900729103003608a2fd0104850d4f1bd21aa60f080d850edb116b0f160b7d063f06ab04d5041704a4045f04f003f5ffd7ff7efe73ffa8b2333e9003010000fa54e5e9",
    ).map { bytes(it) }

    @Test fun v25NotRejected() {
        assertTrue("v25 records carry gravity and must not be treated as undecodable",
            rejectedHistoricalRecords(records, DeviceFamily.WHOOP4).isEmpty())
    }

    @Test fun v25ProducesGravityStream() {
        // clockRef 0/0: v25 records carry their own real unix, so no wall-clock offset is applied.
        val streams = extractHistoricalStreams(records, deviceClockRef = 0, wallClockRef = 0, family = DeviceFamily.WHOOP4)
        assertEquals(records.size, streams.gravity.size)
    }
}
