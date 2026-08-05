package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GET_BATTERY_PACK_INFO (151) has two answers, and the Devices card behaves oppositely on each: a
 * reply naming a pack fills the row, a reply naming none must clear it. Both frames below came off
 * one strap, pack attached then physically removed, so the mapping is pinned to real bytes.
 */
class BatteryPackPresenceTest {

    private val attachedHex =
        "aa01280001002de1245c9704010101f7381d2e3161574242354150303132363339" +
            "35000000e5020c01000000be577aee"

    private val absentHex =
        "aa01280001002de1240797040101000000000000000000000000000000000000" +
            "000000000000000000000000cf8e5340"

    private fun bytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun parse(hex: String) = RustAdapter.parseFrame(bytes(hex), DeviceFamily.WHOOP5)

    @Test
    fun attachedPackNamesItsChargeAndSerial() {
        val parsed = parse(attachedHex)
        assertEquals(true, parsed.crcOk)
        assertEquals("COMMAND_RESPONSE", parsed.typeName)
        assertEquals("GET_BATTERY_PACK_INFO(151)", parsed.parsed["resp_cmd"])
        assertEquals("SUCCESS(1)", parsed.parsed["result"])
        assertEquals(74.1, parsed.parsed["pack_soc_pct"] as Double, 1e-9)
        assertEquals("WBB5AP0126395", parsed.parsed["pack_serial"])
        assertEquals(773667063L, parsed.parsed["pack_id"])
        assertNull(parsed.parsed["pack_absent"])
    }

    /** The same command, same strap, pack removed: still SUCCESS, so only the zeroed pack block tells
     *  the two apart. Nothing here may carry a charge or a serial the card could keep showing. */
    @Test
    fun removedPackReportsAbsenceRatherThanAStaleReading() {
        val parsed = parse(absentHex)
        assertEquals(true, parsed.crcOk)
        assertEquals("GET_BATTERY_PACK_INFO(151)", parsed.parsed["resp_cmd"])
        assertEquals("SUCCESS(1)", parsed.parsed["result"])
        assertEquals(true, parsed.parsed["pack_absent"])
        assertNull(parsed.parsed["pack_soc_pct"])
        assertNull(parsed.parsed["pack_serial"])
        assertNull(parsed.parsed["pack_millivolts"])
    }
}
