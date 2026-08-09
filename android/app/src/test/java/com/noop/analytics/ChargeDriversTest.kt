package com.noop.analytics

import com.noop.R
import com.noop.ui.TemperatureUnit
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
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 62.0, rhr = 51.0, resp = 15.0,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = baseline(16.0, 2.0),
            sleepPerf = 0.9, skinTempDev = 0.3,
        )
        // All five present terms produce one row each (order is biggest-mover-first, asserted below).
        assertEquals(
            setOf(
                R.string.charge_driver_hrv, R.string.charge_driver_resting_hr,
                R.string.charge_driver_respiratory, R.string.charge_driver_sleep,
                R.string.charge_driver_skin_temp,
            ),
            drivers.map { it.labelRes }.toSet(),
        )
        // Rows are sorted biggest-mover-first, matching the Swift twin.
        val magnitudes = drivers.map { kotlin.math.abs(it.deltaPoints) }
        assertEquals(magnitudes.sortedDescending(), magnitudes)
        // Every row carries a non-blank value + a verdict key (never fabricated-empty). HRV / resting HR /
        // respiration name a learned baseline; Sleep + Skin temp intentionally carry no baseline figure
        // (no learned per-night baseline), exactly as the Swift twin does.
        drivers.forEach {
            assertTrue(it.valueText.isNotBlank())
            assertTrue(it.verdictRes != 0)
        }
        listOf(
            R.string.charge_driver_hrv, R.string.charge_driver_resting_hr, R.string.charge_driver_respiratory,
        ).forEach { label ->
            val row = drivers.first { it.labelRes == label }
            assertTrue(row.baselineValue.isNotBlank())
            assertEquals(R.string.charge_driver_baseline, row.baselineRes)
        }
        // The HRV row names the night's value + the personal baseline it was scored against.
        val hrv = drivers.first { it.labelRes == R.string.charge_driver_hrv }
        assertEquals("62 ms", hrv.valueText)
        assertEquals("50 ms", hrv.baselineValue)
        assertEquals(R.string.charge_driver_baseline, hrv.baselineRes)
    }

    @Test fun missingInputYieldsNoRowNotAFakeZero() {
        // No resp value, no resp baseline, no skin-temp -> those rows are absent entirely.
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = null, respBaseline = null,
            sleepPerf = 0.85, skinTempDev = null,
        )
        val labels = drivers.map { it.labelRes }
        assertTrue(labels.contains(R.string.charge_driver_hrv))
        assertTrue(labels.contains(R.string.charge_driver_sleep))
        assertFalse(labels.contains(R.string.charge_driver_resting_hr))
        assertFalse(labels.contains(R.string.charge_driver_respiratory))
        assertFalse(labels.contains(R.string.charge_driver_skin_temp))
    }

    @Test fun deltaSignTracksDirection() {
        // HRV well above baseline -> lifts Charge (positive). RHR well above baseline (worse) -> pulls down.
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 80.0, rhr = 70.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = null, skinTempDev = null,
        )
        val hrv = drivers.first { it.labelRes == R.string.charge_driver_hrv }
        val rhr = drivers.first { it.labelRes == R.string.charge_driver_resting_hr }
        assertTrue("HRV above baseline should lift Charge", hrv.deltaPoints > 0)
        assertTrue("Elevated resting HR should pull Charge down", rhr.deltaPoints < 0)
        assertEquals(R.string.charge_verdict_above_supporting, hrv.verdictRes)
        assertEquals(R.string.charge_verdict_above_limiting, rhr.verdictRes)
    }

    @Test fun skinTempIsARelativeDeviationNeverAbsolute() {
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 50.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = null, skinTempDev = 0.4,
        )
        val skin = drivers.first { it.labelRes == R.string.charge_driver_skin_temp }
        // The sign carries the deviation; the reference it is measured against is the baseline line.
        assertTrue("skin temp must read as a +/- deviation", skin.valueText.contains("+0.4"))
        assertEquals(R.string.charge_driver_vs_baseline, skin.baselineRes)
        // The symmetric penalty never lifts Charge.
        assertTrue(skin.deltaPoints <= 0)
    }

    @Test fun recoveryIndexAndActivityBalanceRowsAppearWhenSupplied() {
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = 0.85, skinTempDev = null,
            recoveryIndexSlope = -3.0,                 // declining overnight -> supports recovery
            effortBaseline = baseline(40.0, 15.0),
            priorDayEffort = 75.0,                     // a hard day yesterday -> limits recovery
        )
        val ri = drivers.first { it.labelRes == R.string.charge_driver_recovery_index }
        val ab = drivers.first { it.labelRes == R.string.charge_driver_activity_balance }
        assertTrue("a declining overnight HR should lift Charge", ri.deltaPoints > 0)
        assertEquals(R.string.charge_verdict_index_supporting, ri.verdictRes)
        assertTrue("a harder-than-normal day yesterday should pull Charge down", ab.deltaPoints < 0)
        assertEquals(R.string.charge_verdict_activity_limiting, ab.verdictRes)
    }

    @Test fun ouraTermsDropTheirRowsWhenInputMissing() {
        // Slope null -> no Recovery index row; effort value present but its baseline null -> no Activity
        // balance row (needs BOTH), matching recovery(...)'s drop discipline.
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 55.0, rhr = 55.0, resp = null,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = null, sleepPerf = 0.85, skinTempDev = null,
            recoveryIndexSlope = null,
            effortBaseline = null, priorDayEffort = 75.0,
        )
        val labels = drivers.map { it.labelRes }
        assertFalse(labels.contains(R.string.charge_driver_recovery_index))
        assertFalse(labels.contains(R.string.charge_driver_activity_balance))
    }

    @Test fun coldStartYieldsEmptyDrivers() {
        val coldHRV = BaselineState(
            baseline = 50.0, spread = 5.0, nValid = 2, nightsSinceUpdate = 0,
            status = BaselineStatus.CALIBRATING,
        )
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 60.0, rhr = 50.0, resp = null,
            hrvBaseline = coldHRV, rhrBaseline = null, respBaseline = null,
            sleepPerf = 0.9, skinTempDev = null,
        )
        assertTrue(drivers.isEmpty())
    }

    /** The figures Kotlin still formats. The words moved to strings_core.xml, and
     *  `ChargeDriversGoldenTest.noDriverCopyCarriesAnEmDash` checks them there. */
    @Test fun noRowCarriesAnEmDash() {
        val drivers = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
            hrv = 62.0, rhr = 51.0, resp = 15.0,
            hrvBaseline = baseline(50.0, 6.0),
            rhrBaseline = baseline(55.0, 3.0),
            respBaseline = baseline(16.0, 2.0),
            sleepPerf = 0.9, skinTempDev = -0.5,
        )
        drivers.forEach { d ->
            val all = "${d.valueText}${d.baselineValue}"
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
