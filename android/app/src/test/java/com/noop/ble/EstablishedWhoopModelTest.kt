package com.noop.ble

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The last-device pref must record what service discovery established, never a default or a stale link. */
class EstablishedWhoopModelTest {

    @Test
    fun beforeDiscovery_nothingIsEstablished_whateverTheFamilyHolds() {
        assertNull(establishedWhoopModel(familyEstablished = false, family = DeviceFamily.WHOOP4))
        assertNull(establishedWhoopModel(familyEstablished = false, family = DeviceFamily.WHOOP5))
    }

    @Test
    fun afterDiscovery_whoop4MapsToTheFourOhPick() {
        assertEquals(WhoopModel.WHOOP4, establishedWhoopModel(true, DeviceFamily.WHOOP4))
    }

    @Test
    fun afterDiscovery_whoop5MapsToTheFiveMgPick() {
        assertEquals(WhoopModel.WHOOP5_MG, establishedWhoopModel(true, DeviceFamily.WHOOP5))
    }
}
