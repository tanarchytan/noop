package com.noop.ui

import com.noop.analytics.RustScores
import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.whoop_ffi.StrapVariant

/**
 * The 5-series device card label. The revision-to-variant table lives in whoop-rs; this pins that a
 * revision it can place narrows the combined label, and that anything else keeps it.
 */
class DeviceModelLabelTest {

    @Test
    fun anMgRevisionReadsAsTheMg() {
        for (rev in listOf("WS50_r00", "WS50_r03", " WS50_r99 ")) {
            assertEquals(rev, "WHOOP MG", fiveSeriesLabel(rev))
        }
    }

    @Test
    fun aFiveRevisionReadsAsTheFive() {
        for (rev in listOf("WG50_r45", "WG50_r52")) {
            assertEquals(rev, "WHOOP 5.0", fiveSeriesLabel(rev))
        }
    }

    @Test
    fun anUnreadOrUnplaceableRevisionKeepsTheCombinedLabelRatherThanGuessing() {
        for (rev in listOf(null, "", "   ", "WX99_r01", "5AG0268206")) {
            assertEquals("$rev", "WHOOP 5.0 / MG", fiveSeriesLabel(rev))
        }
    }

    @Test
    fun aFourPointZeroIsDeterminedWithoutARevisionAndAFiveSeriesIsNot() {
        assertEquals(StrapVariant.WHOOP4, RustScores.strapVariant(null, DeviceFamily.WHOOP4))
        assertEquals(StrapVariant.UNKNOWN, RustScores.strapVariant(null, DeviceFamily.WHOOP5))
    }
}
