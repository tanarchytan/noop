package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StrapIdentity] — what a strap's own serial (GATT 0x2A25) means for the registry. A BLE address is
 * the phone's name for a band and changes; identifying by it is what orphaned a returning strap's
 * history behind a second row.
 *
 * The binding rule that cannot be relaxed: two DIFFERENT rows are never merged. A wrong merge fuses
 * two straps' histories and nothing can undo it.
 */
class StrapIdentityTest {

    private val serial = "5A00960910"
    private val oldAddress = "DA:F7:41:80:FB:D4"
    private val newAddress = "E4:0B:03:2B:C6:21"

    private fun row(id: String, address: String?, serial: String? = null, kind: SourceKind = SourceKind.liveBLE) =
        deviceRow(id, kind = kind).copy(peripheralId = address, serial = serial)

    /** THE swap case: the band is back on a NEW address and a row already carries its serial. */
    @Test
    fun aKnownSerialOnANewAddressReusesItsRow() {
        val rows = listOf(row("whoop-old", oldAddress, serial))
        assertEquals(
            StrapIdentity.Outcome.Reuse("whoop-old"),
            StrapIdentity.resolve(serial, newAddress, rows),
        )
    }

    /** Case is the strap's, not ours: a serial echoed in another case is the same strap. */
    @Test
    fun serialMatchingIsCaseInsensitive() {
        val rows = listOf(row("whoop-old", oldAddress, serial.lowercase()))
        assertEquals(
            StrapIdentity.Outcome.Reuse("whoop-old"),
            StrapIdentity.resolve(serial.uppercase(), newAddress, rows),
        )
    }

    /** An existing row with no serial learns the one the strap on its own address reports. */
    @Test
    fun anUnverifiedRowBackfillsTheSerialItIsConnectedTo() {
        val rows = listOf(row("whoop-old", oldAddress, serial = null))
        assertEquals(
            StrapIdentity.Outcome.Backfill("whoop-old"),
            StrapIdentity.resolve(serial, oldAddress, rows),
        )
    }

    /** A serial the registry has never seen binds to nothing: the caller mints a row for it. */
    @Test
    fun anUnknownSerialOnAnUnknownAddressBindsNothing() {
        assertEquals(StrapIdentity.Outcome.None, StrapIdentity.resolve(serial, newAddress, emptyList()))
    }

    /** Already bound — no write, and in particular no repeated re-point. */
    @Test
    fun aRowAlreadyCarryingItsOwnSerialIsLeftAlone() {
        val rows = listOf(row("whoop-old", oldAddress, serial))
        assertEquals(StrapIdentity.Outcome.None, StrapIdentity.resolve(serial, oldAddress, rows))
    }

    /** THE guard: the serial names one row and the address names another. Never merged — the outcome
     *  names both so it can be reported, and the caller writes nothing. */
    @Test
    fun twoDifferentRowsAreNeverMerged() {
        val rows = listOf(
            row("whoop-old", oldAddress, serial),
            row("whoop-new", newAddress, serial = null),
        )
        assertEquals(
            StrapIdentity.Outcome.Ambiguous("whoop-old", "whoop-new"),
            StrapIdentity.resolve(serial, newAddress, rows),
        )
    }

    /** The import sink is a pile of rows, so it never adopts a strap's serial even if an old install
     *  left an address on it. */
    @Test
    fun theImportSinkNeverBackfillsASerial() {
        val rows = listOf(row(WhoopRepository.WHOOP_SOURCE, oldAddress, serial = null))
        assertEquals(StrapIdentity.Outcome.None, StrapIdentity.resolve(serial, oldAddress, rows))
    }

    /** A serial that differs from a row's recorded one is a different strap, whatever address it is on:
     *  the row keeps its serial, and nothing binds. */
    @Test
    fun aDifferentSerialOnAKnownAddressBindsNothing() {
        val rows = listOf(row("whoop-old", oldAddress, "5A00000001"))
        assertEquals(StrapIdentity.Outcome.None, StrapIdentity.resolve(serial, oldAddress, rows))
    }
}
