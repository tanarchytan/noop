package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Icon resolution for the WHOOP-parity sports. Compares ImageVector.name (stable "Filled.X"). */
class SportIconTest {

    /** Muay Thai was missing from a martial-arts token list that already had jiu, judo and karate. */
    @Test fun muayThai_resolvesWithTheOtherMartialArts() {
        assertEquals(sportIcon("Judo").name, sportIcon("Muay Thai").name)
        assertEquals(sportIcon("Jiu jitsu").name, sportIcon("Muay Thai").name)
    }

    /** "skydiving" contains "ski"; it must not take the downhill-skiing glyph. */
    @Test fun skydiving_doesNotTakeTheSkiGlyph() {
        assertNotEquals(sportIcon("Skiing").name, sportIcon("Skydiving").name)
    }
}
