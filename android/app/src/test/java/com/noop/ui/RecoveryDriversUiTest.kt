package com.noop.ui

import com.noop.R
import com.noop.ui.TemperatureUnit
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Today "What shaped it" wiring: [recoveryChargeDrivers] (folds the visible history
 * into baselines, then defers to [chargeDriverRows]) and [chargeConfidenceTier] (surfaces the
 * existing ScoreConfidence). Pure JVM, no Robolectric. Mirrors the iOS chargeDrivers wiring tests.
 */
class RecoveryDriversUiTest {

    private fun day(
        d: String,
        hrv: Double? = 55.0,
        rhr: Int? = 55,
        resp: Double? = 15.0,
        recovery: Double? = null,
        efficiency: Double? = 0.9,
        sleepMin: Double? = 450.0,
        skinTempDevC: Double? = null,
    ) = DailyMetric(
        deviceId = "my-whoop-noop", day = d, avgHrv = hrv, restingHr = rhr, respRateBpm = resp,
        recovery = recovery, efficiency = efficiency, totalSleepMin = sleepMin, skinTempDevC = skinTempDevC,
    )

    /** A history long enough to make the HRV baseline usable, plus a scored "today". */
    private fun scoredHistory(): List<DailyMetric> {
        val past = (1..10).map { day("2026-01-%02d".format(it), hrv = 50.0 + (it % 3)) }
        val today = day("2026-01-20", hrv = 62.0, rhr = 51, resp = 15.0, recovery = 64.0, skinTempDevC = 0.2)
        return past + today
    }

    @Test fun scoredDayProducesDriverRows() {
        val days = scoredHistory()
        val drivers = recoveryChargeDrivers(days, days.last(), TemperatureUnit.CELSIUS)
        assertTrue("a usable baseline should yield driver rows", drivers.isNotEmpty())
        val labels = drivers.map { it.labelRes }
        assertTrue(labels.contains(R.string.charge_driver_hrv))
        assertTrue(labels.contains(R.string.charge_driver_resting_hr))
        // Skin-temp was supplied on the scored day, so its row is present.
        assertTrue(labels.contains(R.string.charge_driver_skin_temp))
    }

    @Test fun coldStartHistoryProducesNoRows() {
        // Two nights only: the HRV baseline is not usable yet, so there are no honest drivers.
        val days = listOf(
            day("2026-01-01", hrv = 55.0),
            day("2026-01-02", hrv = 58.0, recovery = null),
        )
        assertTrue(recoveryChargeDrivers(days, days.last(), TemperatureUnit.CELSIUS).isEmpty())
    }

    @Test fun confidenceTierIsSurfaced() {
        val days = scoredHistory()
        // A scored day on a now-usable baseline surfaces a non-calibrating tier.
        val tier = chargeConfidenceTier(days, days.last())
        assertTrue(tier == ScoreConfidence.BUILDING || tier == ScoreConfidence.SOLID)
        // A day with no recovery number surfaces CALIBRATING.
        assertEquals(ScoreConfidence.CALIBRATING, chargeConfidenceTier(days, day("2026-01-21", recovery = null)))
    }

    @Test fun nullDayProducesNoRows() {
        assertTrue(recoveryChargeDrivers(scoredHistory(), null, TemperatureUnit.CELSIUS).isEmpty())
    }

    @Test fun ouraDriverRowsSurfaceFromPersistedInputs() {
        // A usable history (strain present, so the effort baseline is usable) with a display day carrying
        // the persisted slope + prior-day Effort yields the two Oura driver rows.
        val past = (1..10).map {
            DailyMetric(
                deviceId = "my-whoop-noop", day = "2026-02-%02d".format(it),
                avgHrv = 50.0 + (it % 3), restingHr = 55, respRateBpm = 15.0,
                efficiency = 0.9, totalSleepMin = 450.0, strain = 40.0 + (it % 5),
            )
        }
        val today = DailyMetric(
            deviceId = "my-whoop-noop", day = "2026-02-20",
            avgHrv = 62.0, restingHr = 51, respRateBpm = 15.0, recovery = 64.0,
            efficiency = 0.9, totalSleepMin = 450.0, strain = 55.0,
            recoveryIndexSlope = -3.0, priorDayEffort = 75.0,
        )
        val labels = recoveryChargeDrivers(past + today, today, TemperatureUnit.CELSIUS).map { it.labelRes }
        assertTrue(labels.contains(R.string.charge_driver_recovery_index))
        assertTrue(labels.contains(R.string.charge_driver_activity_balance))
    }
}
