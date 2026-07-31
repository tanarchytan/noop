package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SleepUiStructureTest {
    private val topLevelDeclaration = Regex(
        """(?m)^(?:(?:internal|private|public)\s+)?(?:(?:data|sealed|enum)\s+)?(?:(?:class|object|interface|(?:const\s+)?val|var)\s+([A-Za-z_][A-Za-z0-9_]*)|fun\s+(?:<[^>\r\n]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>,?.\s]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\()""",
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
            "SleepScreen.kt" to setOf("SleepScreen"),
            "SleepModels.kt" to setOf(
                "Stages", "Metric", "ImportedSleepSeries", "SleepModel", "HeroNight", "HeroDisplay",
                "selectNight", "mainSleepBlock", "scoredNightBlock", "mainSleepGroup", "mainSleepSpan",
                "consistencyNightSpans",
                "PRE_ONSET_STUB_MAX_MIN", "PRE_ONSET_STUB_ASLEEP_MAX_MIN",
                "PRE_ONSET_STUB_MINOR_FRAC", "PRE_ONSET_STUB_MINOR_ASLEEP_FLOOR_MIN",
                "decodedAsleepMinutes", "isPreOnsetAwakeStub", "sumGroupStages", "uiTzOffsetSec",
                "heroDisplay", "stagesFromSegments", "StageMins", "parseSessionStages",
                "buildSleepModel", "fallbackSleepModel", "metricAtDay", "consistencySeries", "mean",
                "stageSegments", "pctValue", "vsTypical", "debtCaption", "debtColor", "debtHeadline",
                "debtTag", "debtRead", "debtBalanceColor", "debtSigned", "durationText",
                "shortDayLabel", "sleepAverageOrNull", "clockLabel", "sessionClockLabel",
                "clockLabelFor", "localDayString", "clockTimeLabel",
            ),
            "SleepTimeline.kt" to setOf(
                "StageBreakdownRows", "StageBreakdownRow",
                "STAGE_ROW_SMOOTH_SEC", "StageTimeline", "StageTimelineRow", "StageRowTrack", "StageInsight",
                "stageInsightLine", "MotionStrip", "PersistedSegment",
                "parsePersistedSegments", "StageInterval", "stageIntervalsFromWeights",
                "displaySmoothed", "canonicalStage", "stageRowSpans",
                "nightSpanSec", "nightStageIntervals",
            ),
            "SleepHrChart.kt" to setOf(
                "HR_CHART_TARGET_POINTS", "HR_CHART_PAD_FRAC", "HR_CHART_PAD_MIN_SEC",
                "HR_CHART_PAD_MAX_SEC", "HR_TRACE_GAP_STEPS", "HR_AXIS_ROUND_BPM", "HR_AXIS_TICK_BPM",
                "hrChartWindow", "hrChartBucketSec", "windowFraction", "hrChartSeries",
                "hrAxisBounds", "hrAxisTicks",
                "hrTraceRuns", "stageBandsInWindow", "SleepHrChart", "HrBoundLabels",
            ),
            "Charts.kt" to setOf(
                "Sparkline", "LineChart", "LineSeries", "MultiLineChart",
                "BarChart", "Hypnogram", "SegmentBar",
                "HypnogramWithAxis", "ClockLabelRow",
                "TimelinePoint", "TimelineChart",
                "GlowEndCap", "TileSparkline",
                "timelineBucketSeconds", "zoomedWindow", "chartTimeTicks",
                "timestampFraction", "pannedWindow",
                "chartTickTimeFormat", "lineChartSelectionLabel", "stageColor",
                "seriesSummary", "hypnogramSummary", "pointsFor", "drawBaseline",
                "formatLineValue", "nearestIndexForX", "nearestBarIndexForX",
                "meanBucketDownsample", "drawRoundedTrack", "drawSegment",
            ),
            "SleepEditor.kt" to setOf("SleepUndoBanner", "NapRow", "NightNavHeader"),
            "SleepHero.kt" to setOf(
                "LIQUID_HERO_FILL", "LIQUID_HERO_RADIUS", "RestHero", "SleepHeroVessel",
                "sleepScoreWord", "restHeroSource", "Hero", "NapsCard", "MainSleepFooter",
                "mainSleepReasonText", "NapSummaryCell", "SleepWindowRow", "SleepTime",
            ),
            "SleepMetrics.kt" to setOf(
                "MetricGrid", "SleepDebtLedgerCard", "DebtDeltaBars", "StagesVsTypical", "Hairline",
                "StageRow", "drawRoundRectFill", "DurationTrend", "TrendPlaceholder", "DateAxisRow",
                "ChartCard", "ChartFooter", "SparkTile", "SleepEmptyState", "HoursVsNeededCard",
                "LegendDot", "SleepNightTiming", "SleepConsistencyCard", "SleepMetricRange", "SleepMetricSpec",
                "sleepMetricSpec", "buildSleepMetricPoints", "filterSleepMetricPoints",
                "SleepMetricDetailSheetContent",
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

        val screen = File(dir, "SleepScreen.kt").readText()
        assertTrue("SleepScreen.kt must remain orchestration-sized", screen.lineSequence().count() < 700)
    }
}
