package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lowest-precedence [WhoopRepository.mergeDaily] gapFill bucket: a Health Connect daily row
 * fills a column no strap source carries and never replaces one that does. The headline case is a real
 * pair of rows measured from a user's backup on 2026-07-31, where the phone's HRV was displayed over
 * the strap's.
 */
class HealthConnectGapFillPrecedenceTest {

    // The two rows that day, verbatim from the backup: HC's aggregate and the strap's computed night.
    private val hcRow = DailyMetric(
        deviceId = "health-connect",
        day = "2026-07-31",
        totalSleepMin = 512.0,
        restingHr = 61,
        avgHrv = 31.3,
        respRateBpm = 13.6,
    )

    private val strapRow = DailyMetric(
        deviceId = "whoop-FC:D8:D3:76:BD:CF-noop",
        day = "2026-07-31",
        totalSleepMin = 515.15,
        efficiency = 0.9414577685723858,
        deepMin = 112.5,
        remMin = 161.5,
        lightMin = 241.15,
        disturbances = 16,
        restingHr = 58,
        avgHrv = 35.738466532051405,
        recovery = 0.4727622435063018,
        strain = 0.0,
        exerciseCount = 0,
        respRateBpm = 13.333333333333334,
    )

    @Test
    fun strapVitalsWin_overHealthConnectOnADayBothCover() {
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(strapRow),
            gapFill = listOf(hcRow),
        )

        assertEquals(1, merged.size)
        // The defect: the phone's 31.3 was displayed over the strap's direct R-R measurement.
        assertEquals(35.738466532051405, merged[0].avgHrv!!, 0.0)
        assertEquals(58, merged[0].restingHr)
        assertEquals(13.333333333333334, merged[0].respRateBpm!!, 0.0)
        assertEquals(515.15, merged[0].totalSleepMin!!, 0.0)
        // The strap's own sleep block is untouched.
        assertEquals(0.9414577685723858, merged[0].efficiency!!, 0.0)
        assertEquals(112.5, merged[0].deepMin!!, 0.0)
    }

    @Test
    fun healthConnectFillsADayNoStrapSourceCovers() {
        // The 17 days this user's phone covers before any strap sample exists must stay on the dashboard.
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = emptyList(),
            gapFill = listOf(hcRow.copy(day = "2026-07-04")),
        )
        assertEquals(1, merged.size)
        assertEquals(31.3, merged[0].avgHrv!!, 0.0)
        assertEquals(512.0, merged[0].totalSleepMin!!, 0.0)
        assertEquals("health-connect", merged[0].deviceId)
    }

    @Test
    fun healthConnectFillsOnlyColumnsNothingAboveCarries() {
        // A strap night that scored sleep but banked no vitals: HRV/RHR/respiration fill, sleep does not.
        val sleepOnly = DailyMetric(
            deviceId = "my-whoop-noop",
            day = "2026-07-31",
            totalSleepMin = 515.15,
            efficiency = 0.94,
            deepMin = 112.5,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(sleepOnly),
            gapFill = listOf(hcRow),
        )
        assertEquals(31.3, merged[0].avgHrv!!, 0.0)
        assertEquals(61, merged[0].restingHr)
        assertEquals(13.6, merged[0].respRateBpm!!, 0.0)
        // The scored night keeps its own total: a bare phone aggregate never sits beside measured stages.
        assertEquals(515.15, merged[0].totalSleepMin!!, 0.0)
    }

    @Test
    fun bareSleepTotalNeverLandsBesideMeasuredStages() {
        // Stages present, total absent (a shape no writer produces, so the guard is asserted directly):
        // the phone total must still be refused, or the row would read as an internally inconsistent night.
        val stagesNoTotal = DailyMetric(
            deviceId = "my-whoop-noop", day = "2026-07-31", deepMin = 112.5, remMin = 161.5,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(stagesNoTotal),
            gapFill = listOf(hcRow),
        )
        assertNull(merged[0].totalSleepMin)
        assertEquals(31.3, merged[0].avgHrv!!, 0.0)
    }

    @Test
    fun sleepTotalFillsARowWithNoSleepSignalAtAll() {
        val vitalsOnly = DailyMetric(deviceId = "my-whoop-noop", day = "2026-07-31", strain = 12.0)
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(vitalsOnly),
            gapFill = listOf(hcRow),
        )
        assertEquals(512.0, merged[0].totalSleepMin!!, 0.0)
    }

    @Test
    fun bloodOxygenFillsOnlyWhereTheStrapRecordedNone() {
        // The importer's SpO2 top-up used to insert a "my-whoop" row, which outranked a strap SpO2 held
        // under "<strapId>-noop". Banked under the phone's own source it does what it always claimed to.
        val strapWithSpo2 = strapRow.copy(spo2Pct = 96.0)
        val filled = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(strapWithSpo2),
            gapFill = listOf(hcRow.copy(spo2Pct = 91.0)),
        )
        assertEquals(96.0, filled[0].spo2Pct!!, 0.0)

        val gapped = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(strapRow),
            gapFill = listOf(hcRow.copy(spo2Pct = 91.0)),
        )
        assertEquals(91.0, gapped[0].spo2Pct!!, 0.0)
    }

    @Test
    fun healthConnectNeverOutranksAWhoopImportEither() {
        // A genuine WHOOP export under "my-whoop" is the top bucket; the phone still only fills gaps.
        val csvRow = DailyMetric(
            deviceId = "my-whoop", day = "2026-07-31",
            totalSleepMin = 480.0, efficiency = 0.92, deepMin = 90.0, restingHr = 55,
        )
        val merged = WhoopRepository.mergeDaily(
            imported = listOf(csvRow),
            computed = emptyList(),
            gapFill = listOf(hcRow),
        )
        assertEquals(55, merged[0].restingHr)
        assertEquals(480.0, merged[0].totalSleepMin!!, 0.0)
        // A vital the export does not carry is still filled — complement, not override.
        assertEquals(31.3, merged[0].avgHrv!!, 0.0)
    }

    @Test
    fun absentGapFillBucketIsByteIdentical() {
        // Every existing caller passed no gapFill; the default must not move a single value.
        val csvRow = DailyMetric(
            deviceId = "my-whoop", day = "2026-07-31", totalSleepMin = 480.0, efficiency = 0.92,
        )
        val withDefault = WhoopRepository.mergeDaily(imported = listOf(csvRow), computed = listOf(strapRow))
        val withEmpty = WhoopRepository.mergeDaily(
            imported = listOf(csvRow), computed = listOf(strapRow), gapFill = emptyList(),
        )
        assertEquals(withDefault, withEmpty)
    }

    @Test
    fun multiDayMergeStaysDayAscending() {
        val merged = WhoopRepository.mergeDaily(
            imported = emptyList(),
            computed = listOf(strapRow),
            gapFill = listOf(hcRow.copy(day = "2026-07-04"), hcRow.copy(day = "2026-08-02")),
        )
        assertEquals(listOf("2026-07-04", "2026-07-31", "2026-08-02"), merged.map { it.day })
        assertTrue(merged.zipWithNext().all { (a, b) -> a.day < b.day })
    }
}
