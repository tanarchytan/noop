package com.noop.ble

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live 0x2A37 R-R is banked only when the historical offload is not supplying it. The record
 * carries every beat stamped by the strap, so storing the live copy alongside it banks each beat
 * twice under two clocks.
 */
class PersistsLiveRrTest {

    @Test
    fun aStrapWhoseOffloadDelivers_doesNotBankTheLiveCopy() {
        assertFalse(persistsLiveRr(DeviceFamily.WHOOP5, historyEmpty = false))
        assertFalse(persistsLiveRr(DeviceFamily.WHOOP4, historyEmpty = false))
    }

    /** The one case where the live profile is the only source there is. */
    @Test
    fun a5MgWithAnEmptyOffload_keepsTheLiveCopy() {
        assertTrue(persistsLiveRr(DeviceFamily.WHOOP5, historyEmpty = true))
    }

    /** The empty-offload state is 5/MG-only; a 4.0 never reaches it, so the family gate must hold. */
    @Test
    fun theEmptyOffloadStateDoesNotLeakToA40() {
        assertFalse(persistsLiveRr(DeviceFamily.WHOOP4, historyEmpty = true))
    }
}
