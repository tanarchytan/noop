package com.noop.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blood oxygen leaves the device only for a 5.0/MG, whose percent is the strap's own computed value.
 * The 4.0 figure is derived on this side and still unresolved, so it must not reach a shared health
 * store where another app would read it as measured.
 */
class HealthConnectSpo2ExportGateTest {

    @Test fun `a 4_0 never exports blood oxygen`() {
        assertFalse(HealthConnectWriter.spo2Exportable("4.0"))
        assertFalse(HealthConnectWriter.spo2Exportable("WHOOP 4.0"))
    }

    @Test fun `a 5_0 or MG exports blood oxygen`() {
        assertTrue(HealthConnectWriter.spo2Exportable("WHOOP 5.0 / MG"))
        assertTrue(HealthConnectWriter.spo2Exportable("WHOOP"))
    }

    @Test fun `an absent label does not export`() {
        // forRegistryModel resolves an unknown label to 5.0, which is right for skin-temp scaling and
        // wrong here: without a label we cannot rule out a 4.0.
        assertFalse(HealthConnectWriter.spo2Exportable(null))
    }
}
