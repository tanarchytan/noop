package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MoreUiStructureTest {
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
    fun moreUiRemainsSeparatedFromAppShell() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)

        val source = File(dir, "whoop/WhoopMoreScreen.kt")
        assertTrue("missing whoop/WhoopMoreScreen.kt", source.isFile)
        val actualDeclarations = topLevelDeclaration.findAll(source.readText())
            .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
            .toSet()
        assertEquals(
            "WhoopMoreScreen.kt declaration ownership changed",
            setOf(
                "MoreSection", "moreSections", "WhoopMoreScreen", "MoreEntryRow", "MoreVersionNote",
            ),
            actualDeclarations,
        )

        val appRoot = File(dir, "AppRoot.kt").readText()
        assertTrue("AppRoot.kt must remain shell-sized", appRoot.lineSequence().count() < 800)
    }

    /**
     * Hydration is opt-in and this page is its only door, so the row and the Settings toggle stand or
     * fall together: no reachable screen without the toggle, no toggle for an unreachable screen.
     */
    @Test
    fun hydrationRowIsTheOptInScreensOnlyDoor() {
        // Fails rather than assumes: a bad path would otherwise report as a pass and stop gating.
        val dir = uiSourceDir() ?: error("no ui source root under ${System.getProperty("user.dir")}")

        val source = File(dir, "whoop/WhoopMoreScreen.kt").readText()
        assertTrue("More page lost its Hydration row", source.contains("Destination.Hydration"))
        assertTrue(
            "the Hydration row must follow NoopPrefs.hydrationTracking",
            source.contains("NoopPrefs.hydrationTracking"),
        )
    }
}
