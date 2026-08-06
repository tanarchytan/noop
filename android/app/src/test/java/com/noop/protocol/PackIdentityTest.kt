package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.Gen
import uniffi.whoop_ffi.PackSignal

/**
 * The battery pack's firmware, off the two channels the strap volunteers it on. No command serves it,
 * so this is the only path to it — and the console half arrives in fixed-size chunks that split a
 * line mid-value, which is what a per-chunk match would read and display as a firmware.
 *
 * Every input below is verbatim from one real pack attach on a WHOOP MG.
 */
class PackIdentityTest {

    /** Fourteen consecutive 50-character console chunks, exactly as the strap sent them. */
    private val chunks = listOf(
        "42, 309949629: LISTENER: RTC sent to battery pack ",
        "poller - 1786003267\n 42, 309949669: NFC COMMS: HW ",
        "family : 12\n 42, 309949669: NFC COMMS: BT addr : f",
        "7381d2e3161\n 42, 309949669: NFC COMMS: HW version ",
        ": 13\n 42, 309949669: NFC COMMS: FW version: 3.30.5",
        ".0\n 42, 309949669: NFC COMMS: Colorway : 1\n 42, 30",
        "9949669: NFC COMMS: BPK SoC : 733\n 42, 309949669: ",
        "NFC COMMS: Unwrapping BPK HW information event : 2",
        "0\n 42, 309950329: LISTENER: Charging On\n 42, 30995",
        "0329: LISTENER: Battery Pack Installed\n 42, 309950",
        "329: LP5562: Setting current array to blue 0, gree",
        "n 175, red 0\n 42, 309955029: NFC COMMS: Unwrapping",
        " BPK SoC : 732 \n 42, 309975089: NFC COMMS: Unwrapp",
        "ing BPK SoC : 730 \n 42, 309995239: NFC COMMS: Unwr",
    )

    /** The type-54 HW-information event from the same attach, and the charge-only event beside it. */
    private val hwInfoHex =
        "aa0130000102aa8036961400433f746ab83e2000010c574242354150303132363339" +
            "35000000f7381d2e31610d031e050001dd020e2eb457"
    private val socHex = "aa0114000102a07036cd02005740746a1e05040001bf0200e32c7a9b"

    private fun bytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** The chunk that ends `FW version: 3.30.5` looks like a whole version on its own. Nothing may
     *  read it until the `.0` in the next chunk, and the line's newline after that. */
    @Test
    fun aFirmwareSplitAcrossTwoChunksIsNeverReadHalfFormed() {
        RustCodec.packReader().use { reader ->
            chunks.take(5).forEach { reader.pushConsole(it) }
            assertNull("3.30.5 reached the app as a firmware", reader.info()?.firmware)

            chunks.drop(5).take(1).forEach { reader.pushConsole(it) }
            assertEquals("3.30.5.0", reader.info()?.firmware)
        }
    }

    @Test
    fun theWholeConsoleAttachYieldsEveryFieldItCarries() {
        RustCodec.packReader().use { reader ->
            chunks.forEach { reader.pushConsole(it) }
            val info = reader.info()
            assertNotNull("the console attach produced no identity at all", info)
            assertEquals("3.30.5.0", info!!.firmware)
            assertEquals("f7381d2e3161", info.btAddr)
            assertEquals(12.toUByte(), info.hwFamily)
            assertEquals(13.toUByte(), info.hwVersion)
            assertEquals(1.toUByte(), info.colorway)
            assertEquals(73.0, info.socPct!!, 1e-9)
            // The console never names the serial; only the wire event does.
            assertNull(info.serial)
        }
    }

    /** A stream cut mid-value emits nothing: the line has no newline, so it was never a line. */
    @Test
    fun aStreamEndingMidValueEmitsNothing() {
        RustCodec.packReader().use { reader ->
            assertNull(reader.pushConsole(" 42, 1: NFC COMMS: FW version: 3.30.5"))
            assertNull(reader.pushConsole(".0"))
            assertNull("a complete value was read before its newline", reader.info())
            assertEquals("3.30.5.0", reader.pushConsole("\n")?.firmware)
        }
    }

    /** The other channel: one CRC-gated frame carries the same values plus the serial. */
    @Test
    fun theHardwareInformationEventCarriesTheSamePackAndItsSerial() {
        RustCodec.packReader().use { reader ->
            val info = identity(reader.pushFrame(Gen.GEN5, bytes(hwInfoHex)))
            assertEquals("3.30.5.0", info.firmware)
            assertEquals("WBB5AP0126395", info.serial)
            assertEquals("f7381d2e3161", info.btAddr)
            assertEquals(73.3, info.socPct!!, 1e-9)
            // Still an identity, but nothing moved — so a caller can stay quiet about it.
            val repeat = reader.pushFrame(Gen.GEN5, bytes(hwInfoHex))
            assertEquals(false, (repeat as PackSignal.Identity).changed)
        }
    }

    @Test
    fun theChargeEventCarriesOnlyTheChargeAndACorruptFrameCarriesNothing() {
        RustCodec.packReader().use { reader ->
            val info = identity(reader.pushFrame(Gen.GEN5, bytes(socHex)))
            assertEquals(70.3, info.socPct!!, 1e-9)
            assertNull(info.firmware)
        }
        RustCodec.packReader().use { reader ->
            val corrupt = bytes(hwInfoHex).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
            assertNull("a bad CRC decoded into an identity", reader.pushFrame(Gen.GEN5, corrupt))
            assertNull(reader.info())
        }
    }

    /** The values a frame carried, or a failure naming what came back instead. */
    private fun identity(signal: PackSignal?): uniffi.whoop_ffi.PackInfo {
        assertTrue("expected pack values, got $signal", signal is PackSignal.Identity)
        return (signal as PackSignal.Identity).info
    }

    /** The two channels are independent encodings of one attach, so they must agree field for field. */
    @Test
    fun theEventAndTheConsoleAgree() {
        RustCodec.packReader().use { fromWire ->
            RustCodec.packReader().use { fromText ->
                val wire = identity(fromWire.pushFrame(Gen.GEN5, bytes(hwInfoHex)))
                chunks.take(8).forEach { fromText.pushConsole(it) }
                val text = fromText.info()!!
                assertEquals(wire.firmware, text.firmware)
                assertEquals(wire.btAddr, text.btAddr)
                assertEquals(wire.hwFamily, text.hwFamily)
                assertEquals(wire.hwVersion, text.hwVersion)
                assertEquals(wire.colorway, text.colorway)
                assertEquals(wire.socPct!!, text.socPct!!, 1e-9)
            }
        }
    }

    /** A type-54 frame must reach the router by name, or nothing feeds the reader. */
    @Test
    fun aPackEventIsClassifiedRatherThanLeftUnknown() {
        assertEquals("PUFFIN_EVENTS_FROM_STRAP", RustAdapter.parseFrame(bytes(hwInfoHex), DeviceFamily.WHOOP5).typeName)
        assertEquals("PUFFIN_EVENTS_FROM_STRAP", RustAdapter.parseFrame(bytes(socHex), DeviceFamily.WHOOP5).typeName)
    }

    /** The strap's own attach/detach, and its unprompted pack block filled and zeroed. Real frames
     *  from two straps; these are what let presence stop waiting on a poll. */
    private val attachedHex = "aa0110000100208130e41500cfd7576a140e0000d3b899e3"
    private val detachedHex = "aa011000010020813018160047d8576a701d00008065c557"
    private val infoPresentHex =
        "aa012c0001002cd130e56d00cfd7576aae271c0001ccb7a6dc16095742423541" +
            "50303131333635350000004903010c00ba269563"
    private val infoZeroedHex =
        "aa012c0001002cd1308c6d00208d526af5681c00010000000000000000000000" +
            "00000000000000000000000000010e005b84085f"

    @Test
    fun theStrapStatesPresenceOutrightSoNoPollHasToNoticeIt() {
        RustCodec.packReader().use { reader ->
            assertEquals(PackSignal.Attached, reader.pushFrame(Gen.GEN5, bytes(attachedHex)))
            assertEquals(PackSignal.Detached, reader.pushFrame(Gen.GEN5, bytes(detachedHex)))
            // A zeroed pack block is the strap saying there is no pack, never a pack at 0%.
            assertEquals(PackSignal.Detached, reader.pushFrame(Gen.GEN5, bytes(infoZeroedHex)))
            assertNull("a presence signal invented an identity", reader.info())
        }
    }

    @Test
    fun theUnpromptedPackBlockCarriesTheSameFieldsTheReplyDoes() {
        RustCodec.packReader().use { reader ->
            val sig = reader.pushFrame(Gen.GEN5, bytes(infoPresentHex))
            assertTrue("the pack block did not read as an identity", sig is PackSignal.Identity)
            val info = (sig as PackSignal.Identity).info
            assertEquals("WBB5AP0113655", info.serial)
            assertEquals("ccb7a6dc1609", info.btAddr)
            assertEquals(84.1, info.socPct!!, 1e-9)
        }
    }

    /** One WPT_HEALTH frame exists and nothing decodes it: a single sample cannot pin a layout, so
     *  it surfaces named with its raw body instead of an invented value. It is also the pack's OWN
     *  event vocabulary, which reuses the strap's numbers for other things — reading the two tables
     *  as one would make a crashed pack look like an attach. */
    @Test
    fun anUndecodedPackEventSurfacesNamedWithItsRawBody() {
        val wptHealthHex = "aa0118000102a320366713007d94526a7a340800010042010100000037d1d87e"
        RustCodec.packReader().use { reader ->
            val sig = reader.pushFrame(Gen.GEN5, bytes(wptHealthHex))
            assertTrue("WPT_HEALTH did not surface as an undecoded pack event", sig is PackSignal.Undecoded)
            assertEquals("WPT_HEALTH", (sig as PackSignal.Undecoded).name)
            assertEquals("00420101000000", sig.body)
            assertNull("an undecoded event invented an identity", reader.info())
        }
    }
}
