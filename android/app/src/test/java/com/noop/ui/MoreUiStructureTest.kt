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

        val source = File(dir, "MoreScreen.kt")
        assertTrue("missing MoreScreen.kt", source.isFile)
        val actualDeclarations = topLevelDeclaration.findAll(source.readText())
            .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
            .toSet()
        assertEquals(
            "MoreScreen.kt declaration ownership changed",
            setOf(
                "DrawerGroup", "drawerGroups", "defaultExpandedHeaders", "MoreSectionPrefs",
                "MoreScreen", "MoreGroupHeader", "MoreRow",
            ),
            actualDeclarations,
        )

        val appRoot = File(dir, "AppRoot.kt").readText()
        assertTrue("AppRoot.kt must remain shell-sized", appRoot.lineSequence().count() < 800)
    }
}
