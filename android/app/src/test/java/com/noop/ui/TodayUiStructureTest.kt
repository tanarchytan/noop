package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TodayUiStructureTest {
    private val topLevelDeclaration = Regex(
        """(?m)^(?:(?:internal|private|public)\s+)?(?:(?:(?:suspend|inline|operator|infix|tailrec)\s+)*)(?:(?:data|sealed|enum)\s+)?(?:(?:class|object|interface|(?:const\s+)?val|var)\s+([A-Za-z_][A-Za-z0-9_]*)|fun\s+(?:<[^>\r\n]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\()""",
    )

    private fun uiSourceDir(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        ).firstOrNull { it.isDirectory }
    }

    @Test
    fun todayUiRemainsSplitByResponsibility() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)

        val expectedOwners = mapOf(
            "TodayActions.kt" to setOf(
                "LiveSessionEntryCard", "QuickActionDisc",
            ),
            "TodayNavigation.kt" to setOf(
                "launchDayOffset", "dayNavOlder", "dayNavNewer", "dayNavCanGoNewer",
                "DAY_NAV_SWIPE_THRESHOLD_DP", "dayNavSwipeTarget",
            ),
            "TodayRecovery.kt" to setOf(
                "ChargeBreakdownSheet", "RecoveryDriversSection", "ChargeConfidencePill", "DriverRow",
                "RecoveryContributorsSection", "ContributorBar", "recoveryCalibrationNights",
                "recoveryChargeDrivers", "chargeConfidenceTier", "lastScoredRecoveryDay", "LastCharge",
                "lastChargeDateLabel", "CARRY_FRESHNESS_DAYS", "isCarryStale", "freshRestScore",
                "carriedCaption", "ScoreState", "scoreStateForToday", "CalibrationMilestonesCard",
                "milestoneTitle", "milestoneUnlocks",
                "CalibrationMilestoneRow", "RecordingState", "recordingStateFor",
                "dayOwnerSource", "provenanceBadgeLabel", "provenanceDisplayLabel",
                "todayProvenanceChipLabel", "heroSourceLabel", "scoreHeroSourceLabel",
                "provenanceLabelTint",
            ),
            "TodayMetrics.kt" to setOf(
                "MetricGrid", "KeyTileData", "KEY_TILE_RADIUS", "KeyTile", "workoutsAllSources", "ReadinessSection",
                "hrvReadinessWord", "hrvReadinessColor",
                "readinessHeadline", "readinessSummary",
                "readinessSignalLabel", "readinessSignalDetail", "readinessEvidenceLine",
                "readinessWord",
                "syncedFromSources", "METRICS_COLLAPSED_CAP", "readinessColor", "flagColor",
                "IllnessBanner", "sleepValue",
                "restStageLowConfidence", "buildingHint",
                "WeightTileText", "latestWeightKg", "stepsForDay", "weightTile", "intString", "NO_DATA",
                "STRESS_CALIBRATING", "workoutDateFmt", "workoutTimeFmt", "countDetail",
                "batteryPillTone", "workoutDuration", "workoutCaption", "grouped",
            ),
            "TodayHeartRate.kt" to setOf(
                "HrWindow", "hrWindowKeeps", "label", "HrWindowPills", "HeartRateTrendCard", "HrTimeAxisLabels",
                "hrChartTransformGestures", "HR_MARKER_LANE", "HR_MARKER_MAX_LANES",
                "OverviewHRChart", "hrHoursMinutes", "hrPeakIn", "HrMarker",
                "HrMarkerPills", "glyphOffset",
                "ChartMarkerPill", "WorkoutGlyph",
            ),
            "TodayFooter.kt" to setOf(
                "lastWorkoutsFeed",
            ),
        )

        expectedOwners.forEach { (name, expectedDeclarations) ->
            val source = File(dir, name)
            assertTrue("missing $name", source.isFile)
            val actualDeclarations = topLevelDeclaration.findAll(source.readText())
                .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .toSet()
            assertEquals("$name declaration ownership changed", expectedDeclarations, actualDeclarations)
        }

        val home = File(dir, "whoop/WhoopHomeScreen.kt")
        assertTrue("missing whoop/WhoopHomeScreen.kt", home.isFile)
        assertTrue(
            "WhoopHomeScreen.kt must remain orchestration-sized",
            home.readText().lineSequence().count() < 1_800,
        )
    }
}
