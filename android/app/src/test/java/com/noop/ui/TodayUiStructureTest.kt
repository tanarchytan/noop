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
            "TodayScreen.kt" to setOf(
                "CARD_SCORES_BUILDING", "CARD_NEW_HERE", "CARD_CALIBRATING",
                "CARD_CALIBRATION_MILESTONES", "CARD_CARRIED_SLEEP",
                "todayDidSnapToTodayThisLaunch", "LIQUID_HERO_FILL", "LIQUID_HERO_RADIUS",
                "TodayLiveSnapshot", "TodayScreen",
            ),
            "TodayActions.kt" to setOf(
                "WorkoutInProgressCard", "LiveSessionEntryCard", "TodayCardDismissButton",
                "QuickActionDisc",
            ),
            "TodayNavigation.kt" to setOf(
                "launchDayOffset", "dayNavOlder", "dayNavNewer", "dayNavCanGoNewer",
                "DAY_NAV_SWIPE_THRESHOLD_DP", "dayNavSwipeTarget", "LiquidTodayHeader",
                "SyncStatusChip", "ChipCapsule", "shortSyncAgo", "LiquidBatteryRing", "LiquidWordmark",
            ),
            "TodayHero.kt" to setOf(
                "ScoreHeroRow", "HeroRingColumn", "HeroScoreVessel", "SynthesisHeroCard",
                "ReadinessHeroPill", "RingEmptyOverlay", "RingNoData", "RingNeedsTrackedNight",
                "HeroMetricRows", "heroVitalsLastNightLine", "HeroVitalRow",
            ),
            "TodayCards.kt" to setOf(
                "LIQUID_PURPLE", "YourCardsSection", "sleepSourceSubtitle", "dashboardCardMetricKey",
                "dashboardCardDestination", "dashboardCardTint", "dashboardCardFraction",
                "dashboardCardValue", "DashboardCardRow", "intStringGrouped",
                "DashboardCardsEditorDialog", "EditableDashboardCard",
            ),
            "TodayRecovery.kt" to setOf(
                "ChargeBreakdownSheet", "RecoveryDriversSection", "ChargeConfidencePill", "DriverRow",
                "RecoveryContributorsSection", "ContributorBar", "recoveryCalibrationNights",
                "recoveryChargeDrivers", "chargeConfidenceTier", "lastScoredRecoveryDay", "LastCharge",
                "lastChargeDateLabel", "CARRY_FRESHNESS_DAYS", "isCarryStale", "freshRestScore",
                "carriedCaption", "ScoreState", "scoreStateForToday", "CalibrationMilestonesCard",
                "CalibrationMilestoneRow", "ScoreStateNote", "RecordingState", "recordingStateFor",
                "dayOwnerSource", "provenanceBadgeLabel", "provenanceDisplayLabel",
                "todayProvenanceChipLabel", "heroSourceLabel", "scoreHeroSourceLabel",
                "provenanceLabelTint",
            ),
            "TodayMetrics.kt" to setOf(
                "MetricGrid", "KeyTileData", "LiquidKeyTile", "workoutsAllSources", "ReadinessSection", "readinessWord",
                "syncedFromSummary", "METRICS_COLLAPSED_CAP", "readinessColor", "flagColor",
                "IllnessBanner", "synthesisWord", "synthesisDetail", "sleepValue",
                "restStageLowConfidence", "buildingHint",
                "WeightTileText", "latestWeightKg", "stepsForDay", "weightTile", "intString", "NO_DATA",
                "STRESS_CALIBRATING", "workoutDateFmt", "workoutTimeFmt", "countDetail",
                "batteryPillTone", "workoutDuration", "workoutCaption", "grouped", "EditableMetric",
                "KeyMetricsEditorDialog",
            ),
            "TodayHeartRate.kt" to setOf(
                "HrWindow", "hrWindowKeeps", "HrWindowPills", "HeartRateTrendCard", "HrTimeAxisLabels",
                "hrChartTransformGestures", "OverviewHRChart", "hrHoursMinutes", "hrPeakIn", "markerOffset", "glyphOffset",
                "ChartMarkerPill", "WorkoutGlyph",
            ),
            "TodayFooter.kt" to setOf(
                "TodayFooterState", "lastWorkoutsFeed", "TodayWorkoutsSection", "TodaySourcesSection",
                "SourceRow",
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

        val screen = File(dir, "TodayScreen.kt").readText()
        assertTrue("TodayScreen.kt must remain orchestration-sized", screen.lineSequence().count() < 1_800)
    }
}
