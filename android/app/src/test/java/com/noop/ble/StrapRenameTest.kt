package com.noop.ble

import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [writesStrapName] — which rename also writes the strap's Bluetooth advertising name. One rename sets
 * both the registry row and the band's name, so the gate has to be exact: [WhoopBleClient.renameStrap]
 * writes to whichever strap the client holds, so letting a non-active row through would rename the band
 * on the wrist while the user was editing a different device.
 */
class StrapRenameTest {

    private fun row(
        id: String = "my-whoop",
        brand: String = "WHOOP",
        status: String = DeviceStatus.active.name,
        sourceKind: String = SourceKind.liveBLE.name,
    ) = PairedDeviceRow(
        id = id, brand = brand, model = "WHOOP 4.0", nickname = null,
        sourceKind = sourceKind, capabilities = "hr",
        status = status, addedAt = 100, lastSeenAt = 100, peripheralId = "AA:BB:CC:DD:EE:FF",
    )

    @Test
    fun theActiveWhoopTakesTheNameOverTheWire() {
        assertTrue(writesStrapName(row()))
        assertTrue(writesStrapName(row(id = "whoop-2", brand = "WHOOP")))
    }

    @Test
    fun aWhoopThatIsNotTheActiveStrapStaysLocal() {
        assertFalse(writesStrapName(row(id = "whoop-2", status = DeviceStatus.paired.name)))
        assertFalse(writesStrapName(row(id = "whoop-2", status = DeviceStatus.archived.name)))
    }

    /** The seeded `my-whoop` id reads as WHOOP whatever its brand says, so an archived one must still
     *  be refused — that row is the one most likely to be renamed while another strap is connected. */
    @Test
    fun theSeededRowIsNotExemptFromTheActiveCheck() {
        assertFalse(writesStrapName(row(status = DeviceStatus.archived.name)))
    }

    @Test
    fun aNonWhoopSourceNeverReachesTheWire() {
        assertFalse(writesStrapName(row(id = "oura-1", brand = "Oura")))
        assertFalse(writesStrapName(row(id = "import-1", brand = "Apple", sourceKind = SourceKind.fileImport.name)))
    }

    /** Every outcome has to be able to explain itself: the UI shows [StrapRename.message] verbatim, and
     *  the same string is what lands in [LiveState.renameStatus]. */
    @Test
    fun everyOutcomeCarriesItsOwnWording() {
        assertEquals(StrapRename.entries.size, StrapRename.entries.map { it.message }.toSet().size)
        StrapRename.entries.forEach { assertTrue(it.name, it.message.isNotBlank()) }
    }

    /** Only [StrapRename.Sent] may claim the strap took the name; the rest must say the rename is local,
     *  which is what stops a 5/MG rename from reporting a wire write that never happened. */
    @Test
    fun onlySentClaimsTheStrapTookIt() {
        assertTrue(StrapRename.Sent.message.contains("strap"))
        listOf(StrapRename.NotWhoop4, StrapRename.NotConnected).forEach {
            assertTrue(it.name, it.message.contains("Saved on this phone"))
        }
    }
}
