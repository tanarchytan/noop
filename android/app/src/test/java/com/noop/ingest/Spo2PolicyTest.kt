package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule decides whether a strap's blood oxygen crosses the app boundary in either direction, so
 * the export side and the Health Connect gap-fill cannot drift apart.
 */
class Spo2PolicyTest {

    @Test fun `a 4_0 is not trusted in either direction`() {
        assertFalse(Spo2Policy.trusted("4.0"))
        assertFalse(Spo2Policy.trusted("WHOOP 4.0"))
    }

    @Test fun `a 5_0 or MG is trusted`() {
        assertTrue(Spo2Policy.trusted("WHOOP 5.0 / MG"))
        assertTrue(Spo2Policy.trusted("WHOOP"))
    }

    @Test fun `an absent label is not trusted`() {
        // forRegistryModel resolves an unknown label to 5.0, which is right for skin-temp scaling and
        // wrong here: without a label we cannot rule out a 4.0.
        assertFalse(Spo2Policy.trusted(null))
    }

    @Test fun `the export gate is the same rule, not a copy of it`() {
        for (model in listOf("4.0", "WHOOP 4.0", "WHOOP 5.0 / MG", "WHOOP", null)) {
            assertEquals("model=$model", Spo2Policy.trusted(model), HealthConnectWriter.spo2Exportable(model))
        }
    }
}
