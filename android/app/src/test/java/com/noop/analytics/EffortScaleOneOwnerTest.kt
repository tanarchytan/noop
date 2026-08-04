package com.noop.analytics

import com.noop.ui.UnitFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The WHOOP Day Strain (0-21) to Effort (0-maxStrain) conversion has ONE owner.
 *
 * It used to have five: [StrainScorer] did not hold it at all, while `MockSeeder.STRAIN_SCALE`,
 * `WhoopCsvImporter.DAY_STRAIN_TO_EFFORT_SCALE`, `UnitFormatter.EFFORT_SCALE_FACTOR` and two inline
 * literals in `WhoopCsvExporter` each restated it. Nothing held them together except three comments
 * promising they were "byte-identical" to each other, and one of those comments cited a class that
 * does not exist in this fork. The numbers agreed, so no test could have failed; the defect was that
 * the next edit to any one of them would have been silent.
 *
 * These tests pin the two properties that make it one owner: the scale top is whoop-rs's, and no
 * source file outside [StrainScorer] writes the ratio down again.
 */
class EffortScaleOneOwnerTest {

    /** The 100 in the conversion is whoop-rs's `strain::MAX_STRAIN`, not a Kotlin literal. */
    @Test
    fun theScaleTopComesFromWhoopRsAndNotFromALocalCopy() {
        assertEquals(RustScores.strainCfg.maxStrain, StrainScorer.maxStrain, 0.0)
        assertEquals(
            StrainScorer.maxStrain / StrainScorer.WHOOP_DAY_STRAIN_MAX,
            StrainScorer.whoopDayStrainToEffort,
            0.0,
        )
    }

    /** Both directions are the same two constants, so neither can drift from the other. */
    @Test
    fun theTwoDirectionsAreExactInversesOfEachOther() {
        assertEquals(1.0, StrainScorer.whoopDayStrainToEffort * StrainScorer.effortToWhoopDayStrain, 1e-15)
        assertEquals(
            StrainScorer.effortToWhoopDayStrain,
            1.0 / StrainScorer.whoopDayStrainToEffort,
            0.0,
        )
    }

    /** The display toggle reads the owner rather than carrying its own copy. */
    @Test
    fun theDisplayToggleReadsTheOwner() {
        assertEquals(StrainScorer.effortToWhoopDayStrain, UnitFormatter.EFFORT_SCALE_FACTOR, 0.0)
    }

    /**
     * The CSV boundary DIVIDES by [StrainScorer.whoopDayStrainToEffort] rather than multiplying by the
     * reciprocal, because `num()` prints full `toString` precision and the two are not the same
     * operation: on 76.193 Effort they write 16.00053 and 16.000529999999998 respectively.
     *
     * Neither direction is exactly lossless -- division round-trips 76.193 to 76.19300000000001 -- so
     * this asserts the property that actually holds: dividing recovers the original value exactly more
     * often than multiplying by the reciprocal does. Swept over 0.000..100.000 in milli-steps that is
     * 87,892 exact against 73,663.
     */
    @Test
    fun theExportBoundaryDividesBecauseItRoundTripsMoreFaithfully() {
        val k = StrainScorer.whoopDayStrainToEffort
        val f = StrainScorer.effortToWhoopDayStrain

        assertTrue(
            "multiplying by the reciprocal is not the same operation as dividing",
            (76.193 * f) != (76.193 / k),
        )

        var byDivision = 0
        var byReciprocal = 0
        for (i in 0..100_000) {
            val effort = i / 1000.0
            if ((effort / k) * k == effort) byDivision++
            if ((effort * f) * k == effort) byReciprocal++
        }
        assertEquals(87_892, byDivision)
        assertEquals(73_663, byReciprocal)
        assertTrue("division must be the more faithful inverse", byDivision > byReciprocal)
    }

    /**
     * No file outside StrainScorer.kt writes the ratio down again in CODE. Scans main sources for the
     * literal pair in either order, which is how all five copies were spelled. A sixth copy fails here
     * rather than agreeing silently until someone edits one of them.
     *
     * Comments are stripped first, both line and block: `IntelligenceEngine` names `strain*100/21` in
     * its KDoc precisely to say it must never do that, and a scan that cannot tell code from prose
     * would report the warning as the offence.
     */
    @Test
    fun noOtherSourceFileRestatesTheRatioInCode() {
        val main = File("src/main/java/com/noop")
        assertTrue("main sources not found at ${main.absolutePath}", main.isDirectory)
        val ratio = Regex("""100(\.0)?\s*/\s*21(\.0)?|21(\.0)?\s*/\s*100(\.0)?""")
        val block = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "StrainScorer.kt" }
            .filter { f ->
                val code = block.replace(f.readText(), " ")
                    .lineSequence()
                    .joinToString("\n") { it.substringBefore("//") }
                ratio.containsMatchIn(code)
            }
            .map { it.name }
            .toList()
        assertEquals(
            "these files restate the Day Strain ratio instead of reading StrainScorer",
            emptyList<String>(),
            offenders,
        )
    }
}
