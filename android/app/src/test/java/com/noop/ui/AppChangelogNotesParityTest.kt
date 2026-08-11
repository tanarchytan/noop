package com.noop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-app What's New sheet against the notes file it is generated from.
 *
 * `Tools/appchangelog-gen.py` writes [AppChangelog] out of `docs/releases/v<VER>.md`. Nothing held the
 * two equal, so an edited notes file left the sheet showing an older set of items under the same version.
 */
class AppChangelogNotesParityTest {

    /** The notes file for the version the sheet says it is showing. */
    private val notes: String = run {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val name = "docs/releases/v${AppChangelog.CURRENT_VERSION}.md"
        listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$name not found from ${userDir.absolutePath}")
    }

    /** The front matter, which ends at the first line that is only three dashes. */
    private val frontMatter: String = notes.substringBefore("\n---")

    /** A scalar under `whatsnew`, double quotes stripped. */
    private fun value(key: String): String =
        Regex("""^ +$key: "(.*)"$""", RegexOption.MULTILINE)
            .find(frontMatter)?.groupValues?.get(1)
            ?: error("whatsnew.$key missing from the front matter")

    /** `whatsnew.items` in file order, with the YAML escapes undone. */
    private fun items(): List<String> =
        Regex("""^ +- "(.*)"$""", RegexOption.MULTILINE)
            .findAll(frontMatter.substringAfter("items:"))
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()

    @Test
    fun theSheetShowsWhatTheNotesFileSays() {
        val shown = AppChangelog.releases.first()
        assertEquals(AppChangelog.CURRENT_VERSION, shown.version)
        assertEquals(value("title"), shown.title)
        assertEquals(value("date"), shown.date)
        assertEquals(items(), shown.items)
    }
}
