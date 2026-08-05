package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SleepUiStructureTest {
    private val topLevelDeclaration = Regex(
        """(?m)^(?:(?:internal|private|public)\s+)?(?:suspend\s+)?(?:(?:data|sealed|enum)\s+)?(?:(?:class|object|interface|(?:const\s+)?val|var)\s+([A-Za-z_][A-Za-z0-9_]*)|fun\s+(?:<[^>\r\n]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\()""",
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
    fun sleepUiRemainsSplitByResponsibility() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)

        val expectedOwners = mapOf(
            "SleepNightScreen.kt" to setOf(
                "SleepNightScreen", "loadSleeps", "provisionalNap", "restHeroSource",
                "SLEEP_STRESS_ROW_LIMIT", "loadSleepStress",
            ),
            "SleepNightHeader.kt" to setOf(
                "SleepNightHeader", "nightOffsetLabel", "SleepWindowRow", "SleepTime",
            ),
            "SleepNaps.kt" to setOf(
                "SleepUndoBanner", "NapsCard", "NapRow", "MainSleepFooter", "mainSleepReasonText",
                "NapSummaryCell",
            ),
            "SleepEditSheet.kt" to setOf(
                "SleepEditField", "SleepEditSheetContent", "SleepEditRow", "SleepEditChip",
                "SleepEditAction", "SleepEditPicker", "editDateLabel", "SleepEditTarget",
            ),
            "SleepModels.kt" to setOf(
                "Stages", "Metric", "ImportedSleepSeries", "SleepModel", "HeroNight", "HeroDisplay",
                "selectNight", "mainSleepBlock", "scoredNightBlock", "mainSleepGroup", "mainSleepSpan",
                "NightSlot", "consistencyNightSlots",
                "PRE_ONSET_STUB_MAX_MIN", "PRE_ONSET_STUB_ASLEEP_MAX_MIN",
                "PRE_ONSET_STUB_MINOR_FRAC", "PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN",
                "decodedAsleepMinutes", "isPreOnsetAwakeStub", "sumGroupStages", "uiTzOffsetSec",
                "heroDisplay", "stagesFromSegments", "StageMins", "parseSessionStages",
                "buildSleepModel", "fallbackSleepModel",
                "TREND_WINDOW_NIGHTS", "calendarWindow", "trendWindowDays",
                "metricAtDay", "consistencySeries", "mean",
                "pctValue",
                "debtRead", "debtBalanceColor", "debtSigned", "durationText",
                "clockLabel", "sessionClockLabel",
                "clockLabelFor", "localDayString", "clockTimeLabel",
            ),
            "SleepStageTimeline.kt" to setOf(
                "STAGE_ROW_SMOOTH_SEC", "PersistedSegment", "parsePersistedSegments",
                "StageInterval", "stageIntervalsFromWeights", "displaySmoothed", "canonicalStage",
                "stageRowSpans", "nightSpanSec", "nightStageIntervals",
            ),
            "SleepStagesCard.kt" to setOf(
                "STAGE_ORDER", "NO_SHARE", "stagePercentByLabel",
                "SleepStagesCard", "SleepStagesHeadline", "SleepStageRows", "SleepStageRow",
                "SleepStageDot", "SleepStageTrack", "TypicalMarkKey", "SleepMotionStrip", "SleepStageInsight",
                "SleepStagesFooter", "stageMinutes", "stageRowColor",
            ),
            "SleepPerformanceCard.kt" to setOf(
                "SLEEP_VESSEL_DIAMETER", "DRIVER_TIERS",
                "SleepDriver", "SleepPerformanceCard", "SleepScoreVessel", "SleepDriverRow",
                "SleepDriverStrip", "driverTierColor", "driverTierWord",
                "SleepDriverLegend",
            ),
            "SleepHrChart.kt" to setOf(
                "HR_CHART_TARGET_POINTS", "HR_CHART_PAD_FRAC", "HR_CHART_PAD_MIN_SEC",
                "HR_CHART_PAD_MAX_SEC", "HR_TRACE_GAP_STEPS", "HR_AXIS_ROUND_BPM", "HR_AXIS_TICK_BPM",
                "hrChartWindow", "hrChartBucketSec", "windowFraction", "hrChartSeries",
                "hrAxisBounds", "hrAxisTicks",
                "hrTraceRuns", "stageBandsInWindow", "SleepHrChart", "HrBoundLabels",
            ),
            "Charts.kt" to setOf(
                "LineChart",
                "BarChart", "Hypnogram", "SegmentBar",
                "ClockLabelRow",
                "TimelinePoint", "TimelineChart",
                "GlowEndCap", "TileSparkline",
                "timelineBucketSeconds", "zoomedWindow", "chartTickTime", "chartTimeTicks",
                "timestampFraction", "pannedWindow",
                "chartTickTimeFormat", "lineChartSelectionLabel", "stageColor",
                "seriesSummary", "hypnogramSummary", "pointsFor", "drawBaseline",
                "formatLineValue", "nearestIndexForX", "nearestBarIndexForX",
                "meanBucketDownsample", "drawRoundedTrack", "drawSegment",
                "WEEK_BAR_SLOT_FRACTION", "WEEK_MARKER_RADIUS", "WEEK_MARKER_STROKE",
                "WeekLineSeries", "labelTop", "labelSides", "WeekStackSegment", "LegendMark",
                "ChartLegend", "LegendMarkGlyph", "WeekChartFrame", "WeekDayLabels",
                "drawCappedBar", "drawRingMarker", "drawRuns",
                "SLOT_LABEL_PLATE_PAD", "SLOT_LABEL_PLATE_CORNER", "drawSlotLabel",
                "weekSummary", "weekPoints",
                "BandedWeekBarChart", "WeekBarChart", "WeekLineChart", "WeekDualLineChart",
                "WeekLinePlot",
                "WeekStackedBarChart", "DualAxisTrendChart",
            ),
            "SleepEmptyState.kt" to setOf("SleepEmptyState"),
            "SleepTrendCards.kt" to setOf(
                "TIME_IN_BED_CHART_HEIGHT", "SLEEP_TREND_NIGHTS", "SleepTimeInBedCard",
                "SleepPerformanceTrendCard", "SLEEP_PERFORMANCE_SCALE_MAX",
                "SleepEfficiencyTrendCard", "SleepTrendShell", "trendDayLabel",
                "SLEEP_STRESS_CHART_HEIGHT", "sleepStressLow", "sleepStressMedium",
                "sleepStressHigh", "sleepStressLegend", "SleepStressNight", "SleepStressCard",
                "SleepStressLegend", "sleepStressDescription",
                "SleepHoursVsNeededCard", "SleepRestorativeCard", "MINUTES_PER_HOUR", "hoursText",
            ),
            "SleepNeedCard.kt" to setOf(
                "NEED_BAR_HEIGHT", "DEBT_STRIP_HEIGHT", "LEDGER_SWATCH", "SleepNeedCard",
                "NeedVersusBar", "NeedLedgerRow", "DebtBalanceStrip",
            ),
            "SleepScheduleCard.kt" to setOf(
                "SCHEDULE_MIN_HOURS", "SCHEDULE_HOUR_STEP", "scheduleHourSpan", "scheduleHourLines",
                "SCHEDULE_CHART_HEIGHT", "SCHEDULE_Y_GUTTER_PX", "SECONDS_PER_HOUR", "SleepScheduleNight",
                "SleepScheduleCard", "scheduleHourLabel", "optimalSleepBand", "sleepScheduleNights",
                "drawHabitualBand",
            ),
            "SleepMetricDetail.kt" to setOf(
                "SleepMetricRange", "SleepMetricSpec", "sleepMetricSpec", "buildSleepMetricPoints",
                "SleepMetricDetailSheetContent",
            ),
            "ChartCard.kt" to setOf("ChartCard", "ChartCardFooter", "CardHairline"),
        )

        expectedOwners.forEach { (name, expectedDeclarations) ->
            val source = File(dir, name)
            assertTrue("missing $name", source.isFile)
            val actualDeclarations = topLevelDeclaration.findAll(source.readText())
                .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .toSet()
            assertEquals("$name declaration ownership changed", expectedDeclarations, actualDeclarations)
        }

        val screen = File(dir, "SleepNightScreen.kt").readText()
        assertTrue("SleepNightScreen.kt must remain orchestration-sized", screen.lineSequence().count() < 700)
    }
}
