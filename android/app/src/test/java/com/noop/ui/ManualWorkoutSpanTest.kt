package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-minute floor: a captured session below it is discarded at save; the manual door agrees. */
class ManualWorkoutSpanTest {
    private val now = 1_700_000_000L

    @Test
    fun `a sub minute captured session is below the floor`() {
        assertTrue(WorkoutEditing.isBelowSessionFloor(0))
        assertTrue(WorkoutEditing.isBelowSessionFloor(30))
        assertTrue(WorkoutEditing.isBelowSessionFloor(59))
    }

    @Test
    fun `exactly a minute is kept`() {
        assertFalse(WorkoutEditing.isBelowSessionFloor(60))
        assertFalse(WorkoutEditing.isBelowSessionFloor(3600))
    }

    @Test
    fun `the floor is sixty seconds and the manual door rejects a zero minute entry`() {
        assertEquals(60L, WorkoutEditing.MIN_MANUAL_SPAN_SECONDS)
        assertNull(WorkoutEditing.buildManualRow("my-whoop", now - 7_200L, 0, "Run", null, null, now))
    }
}
