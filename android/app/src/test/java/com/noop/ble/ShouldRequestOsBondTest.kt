package com.noop.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A strap with no OS bond has no link key, so the encrypted handshake write is refused (status 5/15)
 * and the bond watchdog bounces the link after 7s. Asking the OS to pair is what raises the system
 * prompt and lets the handshake happen at all.
 */
class ShouldRequestOsBondTest {

    @Test
    fun anUnbondedStrapIsAskedToPair() {
        assertTrue(shouldRequestOsBond(BluetoothDevice.BOND_NONE))
    }

    /** Already bonded: it has a link key, and re-requesting would be churn on a working link. */
    @Test
    fun anAlreadyBondedStrapIsLeftAlone() {
        assertFalse(shouldRequestOsBond(BluetoothDevice.BOND_BONDED))
    }

    /** Bonding in flight: the OS prompt is already up, so a second request would double it. */
    @Test
    fun aStrapMidPairingIsNotAskedTwice() {
        assertFalse(shouldRequestOsBond(BluetoothDevice.BOND_BONDING))
    }
}
