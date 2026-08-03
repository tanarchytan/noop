package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The visual contract, enforced on `ui/` source text. A colour, a text size or an inter-element gap
 * that a call site spells out is a second source of truth for a token, and the theme flip cannot
 * reach it — which is exactly how the light scheme ended up painting dark cards.
 *
 * WHAT THIS GATE DOES NOT COVER, stated plainly so nobody reads a green run as more than it is:
 *  - The RIGHT token. `textSecondary` where `textTertiary` belongs passes; both are tokens.
 *  - Contrast. A token pair that is unreadable on the light canvas passes.
 *  - Card shape. A bespoke `clip` + `background` hero that never calls `NoopCard` passes; that is a
 *    composition question, not a token one, and no regex settles it.
 *  - Dp literals outside `spacedBy`. Around 800 remain and most are geometry (icon sizes, stroke
 *    widths, chart heights), so a blanket rule would be noise. Only the gap role is gated.
 *  - `widget/`. Glance renders in the launcher's process and cannot read `Palette` snapshot state,
 *    so those literals are correct there and the package is deliberately out of scope.
 *  - Anything computed at run time. This reads source, not the composed tree.
 */
class DesignContractTest {

    /** The two files that ARE the token definitions, so they hold the literals by definition. */
    private val tokenFiles = setOf("Theme.kt", "PaletteTokens.kt")

    /** The values `Metrics` already names. A gap spelled as one of these has a token going spare.
     *  A gap OFF the scale (3, 5, 7 …) is left alone — inventing a token to absorb it would invent
     *  a number. 20 is `screenRowSpacing`, the one inter-card vertical gap. */
    private val spacingTokens = mapOf(
        2 to "space2", 4 to "space4", 6 to "space6", 8 to "space8", 10 to "space10",
        12 to "space12", 14 to "space14", 16 to "space16", 18 to "space18", 24 to "space24",
        20 to "screenRowSpacing",
    )

    private val hexColor = Regex("""Color\(0x""")
    private val literalFontSize = Regex("""fontSize\s*=\s*\d+(?:\.\d+)?\.sp""")
    private val bareWhiteBlack = Regex("""\bColor\.(White|Black)\b""")
    private val literalGap = Regex("""spacedBy\((\d+)\.dp\)""")

    /**
     * The app's `ui` package. A miss THROWS rather than skipping: written with `assumeTrue`, a bad
     * path reports as a pass and the gate quietly stops gating.
     */
    private fun uiRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("no ui source root under ${userDir.absolutePath}; tried ${candidates.joinToString()}")
    }

    /** Blank out comments, keeping string literals and line count, so a comment that names a rule
     *  cannot trip the rule it names. */
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

    private fun uiSources(): List<Pair<File, String>> = uiRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it to stripComments(it.readText()) }
        .toList()

    /** Every offending line as `File.kt:12  the line`, so a failure names the edit to make. */
    private fun hits(
        skip: Set<String> = emptySet(),
        match: (line: String) -> Boolean,
    ): List<String> = uiSources()
        .filterNot { (f, _) -> f.name in skip }
        .flatMap { (f, text) ->
            text.lines().withIndex()
                .filter { (_, line) -> match(line) }
                .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim()}" }
        }
        .sorted()

    @Test
    fun uiSourcesAreActuallyScanned() {
        val sources = uiSources()
        assertTrue("expected the ui package's Kotlin sources, found ${sources.size}", sources.size > 50)
        assertTrue(
            "the token files must be in scope or every rule below is vacuous",
            sources.map { it.first.name }.containsAll(tokenFiles),
        )
    }

    /** A colour literal outside the token files cannot follow a theme flip. */
    @Test
    fun noHardcodedColourLiteral() {
        val offenders = hits(skip = tokenFiles) { hexColor.containsMatchIn(it) }
        assertEquals(
            "hardcoded colours in ui/ — add a token to PaletteTokens and read it off Palette:\n" +
                offenders.joinToString("\n") { "  $it" },
            emptyList<String>(), offenders,
        )
    }

    /**
     * A literal text size invents a step the scale does not define. A size DERIVED from a token or a
     * dimension (`(diameter.value * 0.36f).sp`) is fine and deliberately allowed: a ring's number has
     * to scale with the ring.
     */
    @Test
    fun noLiteralFontSize() {
        val offenders = hits(skip = tokenFiles) { literalFontSize.containsMatchIn(it) }
        assertEquals(
            "literal text sizes in ui/ — use a NoopType style, or add one if the role is new:\n" +
                offenders.joinToString("\n") { "  $it" },
            emptyList<String>(), offenders,
        )
    }

    /**
     * `Color.White` / `Color.Black` are only honest inside an explicit `Palette.isLight` fork, which
     * is the tree's idiom for the rare place a scheme genuinely has to branch. Bare, they are the
     * light-mode defect in one token.
     */
    @Test
    fun whiteAndBlackOnlyInsideAnExplicitSchemeFork() {
        val offenders = hits(skip = tokenFiles) {
            bareWhiteBlack.containsMatchIn(it) && !it.contains("Palette.isLight")
        }
        assertEquals(
            "bare white/black in ui/ — read a Palette token, or fork on Palette.isLight:\n" +
                offenders.joinToString("\n") { "  $it" },
            emptyList<String>(), offenders,
        )
    }

    /** A gap that matches a Metrics value has a token going spare; off-scale gaps are left alone. */
    @Test
    fun gapsOnTheMetricsScaleUseTheToken() {
        val offenders = hits(skip = tokenFiles) { line ->
            literalGap.findAll(line).any { it.groupValues[1].toInt() in spacingTokens }
        }
        assertEquals(
            "inter-element gaps spelled as dp where Metrics already names the value:\n" +
                offenders.joinToString("\n") { "  $it" },
            emptyList<String>(), offenders,
        )
    }
}
