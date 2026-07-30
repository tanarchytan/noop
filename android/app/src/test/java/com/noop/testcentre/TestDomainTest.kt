package com.noop.testcentre

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the id set: the ids are the wire values stamped into meta.json and the log-line domain tags,
 *  so a rename or a reorder is a stored-data change. */
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
}
