package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every UI entry point is still reached by something. Three shipped capabilities were lost to a commit
 * that deleted the surface calling them and left the capability behind, and each was invisible to this
 * suite: the Live Session entry card, the battery-optimisation prompt, and the strap rename.
 *
 * A declaration whose name appears exactly ONCE across the whole module is that shape exactly — the one
 * occurrence is its own declaration, so nothing calls it, not even a test. Comments are stripped (a KDoc
 * `[Name]` link mentions a name without using it) and string literals are kept, so a name reached
 * reflectively still reads as live; the test errs towards calling dead code live.
 *
 * [TodayUiStructureTest] asserts which FILE declares a symbol and stayed green through all three, because
 * declaring a composable and placing it are different things. This asserts the second one.
 */
class UiEntryPointReachabilityTest {

    /**
     * Reached by TESTS but by no screen, measured 2026-07-31. Every one is a pure presentation helper
     * left behind when the composable that called it was rewritten, so its test still passes while the
     * app never runs it. Recorded rather than deleted because removing each one also removes the test
     * that pins its behaviour, and that is a verdict per helper, not a sweep. A NEW orphan fails.
     */
    private val allowed = setOf(
        // Today: helpers the liquid rewrite inlined.
        "buildingHint", "restStageLowConfidence", "dayNavCanGoNewer",
        "recordingStateFor", "dayOwnerSource", "provenanceBadgeLabel",
        // Sleep + Health presentation.
        "mainSleepReasonText", "filterVitalPoints", "Hypnogram",
        // Journal + day arithmetic.
        "journalDisplayName", "mergeJournalCatalog", "logicalDayStartEpochSecond",
        // The only writers for two lists whose readers ARE used; the caller was lost in a source scrub,
        // so deleting these would hide the missing wire rather than close it.
        "saveCustomJournalQuestions", "saveHiddenJournalQuestions",
        // The Settings Strap card's status line, orphaned when that screen was stripped.
        "strapStatusDetail",
    )

    private val composable = Regex("""@Composable[\s\S]{0,200}?\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+(?:<[^>]*>)?\.)?([A-Z]\w*)\s*\(""")
    private val topLevelFun = Regex("""(?m)^(?:internal\s+|private\s+|public\s+)?(?:suspend\s+)?fun\s+(?:<[^>]*>\s*)?([a-z]\w*)\s*\(""")
    private val word = Regex("""[A-Za-z_]\w*""")

    /**
     * The app's `src/main/java/com/noop`. Gradle runs unit tests with `user.dir` at the module, but the
     * candidates cover a run from the project root too. A miss THROWS rather than skipping: this test
     * used to be written with `assumeTrue`, and a null lookup then reports as a pass.
     */
    private fun sourceRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, "src/main/java/com/noop"),
            File(userDir, "app/src/main/java/com/noop"),
            File(userDir, "android/app/src/main/java/com/noop"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("no app source root under ${userDir.absolutePath}; tried ${candidates.joinToString()}")
    }

    /** Blank out comments, keeping string literals and line count. A non-greedy block-comment regex
     *  eats the rest of a file the moment a string literal holds the closing delimiter. */
    private fun stripComments(t: String): String {
        val out = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            when {
                t.startsWith("\"\"\"", i) -> {
                    val j = t.indexOf("\"\"\"", i + 3).let { if (it < 0) t.length else it + 3 }
                    out.append(t, i, j); i = j
                }
                t[i] == '"' -> {
                    var j = i + 1
                    while (j < t.length && t[j] != '"') j += if (t[j] == '\\') 2 else 1
                    val end = minOf(j + 1, t.length)
                    out.append(t, i, end); i = end
                }
                t.startsWith("//", i) -> {
                    val j = t.indexOf('\n', i).let { if (it < 0) t.length else it }
                    repeat(j - i) { out.append(' ') }; i = j
                }
                t.startsWith("/*", i) -> {
                    val j = t.indexOf("*/", i).let { if (it < 0) t.length else it + 2 }
                    for (k in i until j) out.append(if (t[k] == '\n') '\n' else ' ')
                    i = j
                }
                else -> { out.append(t[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun sources(): Map<File, String> = sourceRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "whoop_ffi.kt" }
        .associateWith { stripComments(it.readText()) }

    private fun tokenCounts(sources: Map<File, String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        sources.values.forEach { t ->
            word.findAll(t).forEach { counts[it.value] = (counts[it.value] ?: 0) + 1 }
        }
        return counts
    }

    @Test
    fun everyComposableIsPlacedSomewhere() {
        val sources = sources()
        assertTrue("expected the app's Kotlin sources, found ${sources.size}", sources.size > 100)
        val counts = tokenCounts(sources)

        val orphans = sources.flatMap { (file, text) ->
            composable.findAll(text).map { it.groupValues[1] to file }
        }.filter { (name, _) -> name !in allowed && counts[name] == 1 }
            .map { (name, file) -> "$name (${file.name})" }
            .distinct()
            .sorted()

        assertEquals(
            "composables nothing places — a screen deleted the call and left the card:\n" +
                orphans.joinToString("\n") { "  $it" },
            emptyList<String>(), orphans,
        )
    }

    /**
     * The same test for `ui/`'s plain top-level functions. `startOrResumeLiveSession` was reached only by
     * the deleted card, so it is this shape rather than the composable one, and every screen action that
     * starts, writes or exports something lives here.
     */
    @Test
    fun everyUiTopLevelFunctionIsCalledSomewhere() {
        val sources = sources().filterKeys { it.parentFile.name == "ui" }
        assertTrue("expected the ui package, found ${sources.size} files", sources.size > 50)
        val counts = tokenCounts(sources())

        val orphans = sources.flatMap { (file, text) ->
            topLevelFun.findAll(text).map { it.groupValues[1] to file }
        }.filter { (name, _) -> name !in allowed && counts[name] == 1 }
            .map { (name, file) -> "$name (${file.name})" }
            .distinct()
            .sorted()

        assertEquals(
            "ui functions nothing calls:\n" + orphans.joinToString("\n") { "  $it" },
            emptyList<String>(), orphans,
        )
    }
}
