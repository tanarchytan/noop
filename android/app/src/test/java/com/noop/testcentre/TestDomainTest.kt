package com.noop.testcentre

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the id set + github labels. On the Android-only noop-tan fork the four never-wired Phase-1
 *  placeholders (notifications / sources / stress / longevity) were dropped — a deliberate divergence
 *  from the Swift TestDomain, so this asserts the noop-tan set, not byte-for-byte upstream parity. */
class TestDomainTest {

    @Test fun fullIdSet() {
        assertEquals(
            listOf(
                "universal", "sleep", "connection", "workouts", "display", "import",
                "steps", "battery", "recovery", "hrv", "master",
            ),
            TestDomain.values().map { it.id },
        )
    }

    @Test fun githubLabels() {
        assertEquals("test:all", TestDomain.MASTER.githubLabel)
        assertEquals("test:sleep", TestDomain.SLEEP.githubLabel)
        assertEquals("test:battery", TestDomain.BATTERY.githubLabel)
        assertEquals("test:import", TestDomain.IMPORT.githubLabel)
    }
}
