package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hero rings replayed their fill on every scroll: the "already filled" flag lived in `remember`
 * inside a LazyColumn item, so recycling the item rebuilt it at zero. The frame now lives in
 * [RingFillMemory], hoisted above the list. These pin the memory's behaviour and the wiring that uses it.
 */
class RingFillMemoryTest {

    @Test
    fun anUnseenRingStartsEmptySoItStillFillsIn() {
        val memory = RingFillMemory()
        assertEquals(RingFillStart(0f, 0f), memory.startFor("home.Charge"))
    }

    @Test
    fun aReturningRingStartsWhereItLeftSoNothingRefills() {
        val memory = RingFillMemory()
        memory.record("home.Charge", 0.62f, 62f)
        // Start == target, so the ring re-enters composition already filled and animates nowhere.
        assertEquals(RingFillStart(0.62f, 62f), memory.startFor("home.Charge"))
    }

    @Test
    fun aNewScoreAnimatesFromTheOldFrameNotFromZero() {
        val memory = RingFillMemory()
        memory.record("home.Charge", 0.62f, 62f)
        val start = memory.startFor("home.Charge")
        assertTrue("a new score must animate from the old one, not from an empty ring", start.fraction > 0f)
        assertEquals(0.62f, start.fraction, 0f)
        assertEquals(62f, start.value, 0f)
    }

    @Test
    fun eachRingKeepsItsOwnFrame() {
        val memory = RingFillMemory()
        memory.record("home.Charge", 0.62f, 62f)
        assertEquals(RingFillStart(0f, 0f), memory.startFor("home.Effort"))
    }

    @Test
    fun aMidFillFrameIsBankedSoTheReturnResumes() {
        val memory = RingFillMemory()
        memory.record("home.Rest", 0.30f, 30f)
        memory.record("home.Rest", 0.55f, 55f)
        assertEquals(RingFillStart(0.55f, 55f), memory.startFor("home.Rest"))
    }

    /**
     * The wiring half: the fill state has to be read from the hoisted memory, not remembered inside the
     * ring, or the holder above is dead weight and the scroll replays the fill again.
     */
    @Test
    fun glowRingReadsTheHoistedMemoryAndKeepsNoLocalIntroGate() {
        val components = uiSource("Components.kt")
        assertTrue(
            "GlowRing must read the hoisted fill memory",
            components.contains("LocalRingFillMemory.current"),
        )
        assertTrue(
            "GlowRing must not re-introduce a local intro gate",
            !components.contains("var started by remember"),
        )
        assertTrue(
            "the fill must start from the banked frame",
            components.contains("Animatable(memory.startFor(fillKey).fraction)"),
        )
    }

    /** The hero row and the row it collapses into must key the SAME frame, or the collapse refills. */
    @Test
    fun theHeroAndItsPinnedTwinShareOneFrame() {
        val header = uiSource("whoop/WhoopHomeHeader.kt")
        assertEquals(
            "both home ring rows must key the same fill memory",
            2,
            Regex("""fillKey = homeRingFillKey\(ring\)""").findAll(header).count(),
        )
    }

    /** A miss THROWS rather than skipping: a null lookup under `assumeTrue` reports as a pass. */
    private fun uiSource(relative: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val roots = listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        )
        val dir = roots.firstOrNull { it.isDirectory }
            ?: error("no ui source root under ${userDir.absolutePath}; tried ${roots.joinToString()}")
        val file = File(dir, relative)
        if (!file.isFile) error("missing ${file.absolutePath}")
        return file.readText()
    }
}
