package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TrendsUiStructureTest {
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
    fun trendsUiRemainsSplitByResponsibility() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)
        val expectedOwners = mapOf(
            "TrendsScreen.kt" to setOf(
                "TrendsScreen", "minWeekOffset",
                "WeeklyDigestNav", "WeekNavBar", "WeekInReviewCard", "PipScoreRow",
            ),
            "TrendsCharts.kt" to setOf(
                "LIQUID_HERO_FILL", "LIQUID_HERO_RADIUS", "TrendsRange", "ResolvedMetric", "resolveMetric", "windowPoints", "caption",
                "ChartCard", "HeadlineVessel", "ChangeChip", "ChartWithAxes", "prettyAxisDate",
                "MetricTrendCard", "periodChange", "ChartFooter", "RecoveryHistoryCard",
                "SparsePlaceholder", "EmptyTrends", "EM_DASH", "averageOrNull",
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
        val screen = File(dir, "TrendsScreen.kt").readText()
        assertTrue("TrendsScreen.kt must remain orchestration-sized", screen.lineSequence().count() < 600)
    }
}
