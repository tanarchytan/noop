package com.noop.analytics

import com.noop.ui.chargeDriverRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SHARED-CONTRACT Charge "What shaped it" driver rows (whoop-rs scores them,
 * [chargeDriverRows] words them). Proves: every present term yields exactly one honest row; a missing input yields NO row (never a
 * fabricated zero); deltaPoints sign tracks the signal direction; the cold-start gate yields an empty
 * list; and no row carries an em-dash. Pure-JVM, no Robolectric. Mirrors the iOS chargeDrivers tests.
 */
class ChargeDriversTest {

    /** A usable baseline with a given mean and Gaussian sigma (spread is internal abs-dev units). */
    private fun baseline(mean: Double, sigma: Double, nValid: Int = 14): BaselineState =
        BaselineState(
            baseline = mean, spread = sigma / 1.253, nValid = nValid, nightsSinceUpdate = 0,
            status = if (nValid >= 14) BaselineStatus.TRUSTED else BaselineStatus.PROVISIONAL,
        )

    @Test fun allTermsPresentYieldOneRowEachInOrder() {
        val drivers = chargeDriverRows(
            hrv = 62.0, rhr = 51.0, resp = 15.0,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = baseline(16.0, 2.0),
            sleepPerf = 0.9, skinTempDev = 0.3,
        )
        // All five present terms produce one row each (order is biggest-mover-first, asserted below).
        assertEquals(
            setOf("Heart rate variability", "Resting heart rate", "Respiratory rate", "Sleep quality", "Skin temperature"),
            drivers.map { it.label }.toSet(),
        )
        // Rows are sorted biggest-mover-first, matching the Swift twin.
        val magnitudes = drivers.map { kotlin.math.abs(it.deltaPoints) }
        assertEquals(magnitudes.sortedDescending(), magnitudes)
        // Every row carries a non-blank value + verdict (never fabricated-empty). HRV / resting HR /
        // respiration name a learned baseline; Sleep + Skin temp intentionally carry an empty baseline
        // (no learned per-night baseline), exactly as the Swift twin does.
        drivers.forEach {
            assertTrue(it.valueText.isNotBlank())
            assertTrue(it.verdict.isNotBlank())
        }
        listOf("Heart rate variability", "Resting heart rate", "Respiratory rate").forEach { label ->
            assertTrue(drivers.first { it.label == label }.baselineText.isNotBlank())
        }
        // The HRV row names the night's value + the personal baseline it was scored against.
        val hrv = drivers.first { it.label == "Heart rate variability" }
        assertEquals("62 ms", hrv.valueText)
        assertEquals("50 ms baseline", hrv.baselineText)
    }

    @Test fun missingInputYieldsNoRowNotAFakeZero() {
        // No resp value, no resp baseline, no skin-temp -> those rows are absent entirely.
        val drivers = chargeDriverRows(
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = null, respBaseline = null,
            sleepPerf = 0.85, skinTempDev = null,
        )
        val labels = drivers.map { it.label }
        assertTrue(labels.contains("Heart rate variability"))
        assertTrue(labels.contains("Sleep quality"))
        assertFalse(labels.contains("Resting heart rate"))
        assertFalse(labels.contains("Respiratory rate"))
        assertFalse(labels.contains("Skin temperature"))
    }

    @Test fun deltaSignTracksDirection() {
        // HRV well above baseline -> lifts Charge (positive). RHR well above baseline (worse) -> pulls down.
        val drivers = chargeDriverRows(
            hrv = 80.0, rhr = 70.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = null, skinTempDev = null,
        )
        val hrv = drivers.first { it.label == "Heart rate variability" }
        val rhr = drivers.first { it.label == "Resting heart rate" }
        assertTrue("HRV above baseline should lift Charge", hrv.deltaPoints > 0)
        assertTrue("Elevated resting HR should pull Charge down", rhr.deltaPoints < 0)
        assertTrue(hrv.verdict.contains("supporting recovery"))
        assertTrue(rhr.verdict.contains("limiting recovery"))
    }

    @Test fun skinTempIsARelativeDeviationNeverAbsolute() {
        val drivers = chargeDriverRows(
            hrv = 50.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = null, skinTempDev = 0.4,
        )
        val skin = drivers.first { it.label == "Skin temperature" }
        assertTrue("skin temp must read as a +/- deviation", skin.valueText.contains("vs baseline"))
        assertTrue(skin.valueText.contains("+0.4"))
        // The symmetric penalty never lifts Charge.
        assertTrue(skin.deltaPoints <= 0)
    }

    @Test fun recoveryIndexAndActivityBalanceRowsAppearWhenSupplied() {
        val drivers = chargeDriverRows(
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = 0.85, skinTempDev = null,
            recoveryIndexSlope = -3.0,                 // declining overnight -> supports recovery
            effortBaseline = baseline(40.0, 15.0),
            priorDayEffort = 75.0,                     // a hard day yesterday -> limits recovery
        )
        val ri = drivers.first { it.label == "Recovery index" }
        val ab = drivers.first { it.label == "Activity balance" }
        assertTrue("a declining overnight HR should lift Charge", ri.deltaPoints > 0)
        assertTrue(ri.verdict.contains("supporting recovery"))
        assertTrue("a harder-than-normal day yesterday should pull Charge down", ab.deltaPoints < 0)
        assertTrue(ab.verdict.contains("limiting recovery"))
    }

    @Test fun ouraTermsDropTheirRowsWhenInputMissing() {
        // Slope null -> no Recovery index row; effort value present but its baseline null -> no Activity
        // balance row (needs BOTH), matching recovery(...)'s drop discipline.
        val drivers = chargeDriverRows(
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = 0.85, skinTempDev = null,
            recoveryIndexSlope = null,
            effortBaseline = null, priorDayEffort = 75.0,
        )
        val labels = drivers.map { it.label }
        assertFalse(labels.contains("Recovery index"))
        assertFalse(labels.contains("Activity balance"))
    }

    @Test fun coldStartYieldsEmptyDrivers() {
        val coldHRV = BaselineState(
            baseline = 50.0, spread = 5.0, nValid = 2, nightsSinceUpdate = 0,
            status = BaselineStatus.CALIBRATING,
        )
        val drivers = chargeDriverRows(
            hrv = 60.0, rhr = 50.0, resp = null,
            hrvBaseline = coldHRV, rhrBaseline = null, respBaseline = null,
            sleepPerf = 0.9, skinTempDev = null,
        )
        assertTrue(drivers.isEmpty())
    }

    @Test fun noRowCarriesAnEmDash() {
        val drivers = chargeDriverRows(
            hrv = 62.0, rhr = 51.0, resp = 15.0,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = baseline(16.0, 2.0),
            sleepPerf = 0.9, skinTempDev = -0.5,
        )
        drivers.forEach { d ->
            val all = "${d.label}${d.valueText}${d.baselineText}${d.verdict}"
            assertFalse("driver row must not contain an em-dash", all.contains("\u2014"))
        }
    }

    @Test fun chargeConfidenceTierIsSurfacedNotRecomputed() {
        // A present score on a trusted baseline surfaces SOLID; a null score surfaces CALIBRATING.
        assertEquals(ScoreConfidence.SOLID, ScoreConfidence.forCharge(60.0, baseline(50.0, 6.0, nValid = 20)))
        assertEquals(ScoreConfidence.CALIBRATING, ScoreConfidence.forCharge(null, baseline(50.0, 6.0)))
        assertNull(RustScores.recovery(
            hrv = 60.0, rhr = 50.0, resp = null,
            hrvBaseline = BaselineState(50.0, 5.0, 2, 0, BaselineStatus.CALIBRATING),
            rhrBaseline = null, respBaseline = null, sleepPerf = 0.9,
        ))
    }
}
