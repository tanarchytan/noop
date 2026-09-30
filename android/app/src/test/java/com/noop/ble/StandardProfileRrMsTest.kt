package com.noop.ble

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0x2A37 R-R unit per family. Real bytes: flags 0x10 (u8 HR, R-R present), HR 0x3C (60 bpm), then
 * u16 LE words. 0x0200 = 512 raw: a 4.0 reads it as 1/1024 s = 500 ms; a 5/MG already sends ms = 512.
 * 0x0400 = 1024 raw: 1000 ms on a 4.0, 1024 ms on a 5/MG.
 */
class StandardProfileRrMsTest {
    private val packet = byteArrayOf(0x10, 0x3C, 0x00, 0x02, 0x00, 0x04)

    private fun words(): List<Int> =
        (2 until packet.size step 2).map { (packet[it].toInt() and 0xFF) or ((packet[it + 1].toInt() and 0xFF) shl 8) }

    @Test
    fun whoop4_appliesTheSpecOneOver1024() {
        assertEquals(listOf(500, 1000), words().map { standardProfileRrMs(it, DeviceFamily.WHOOP4) })
    }

    @Test
    fun whoop5_rawWordIsAlreadyMilliseconds() {
        assertEquals(listOf(512, 1024), words().map { standardProfileRrMs(it, DeviceFamily.WHOOP5) })
    }

    @Test
    fun whoop4_roundsRatherThanTruncates() {
        assertEquals(801, standardProfileRrMs(820, DeviceFamily.WHOOP4)) // 800.78
    }
}
