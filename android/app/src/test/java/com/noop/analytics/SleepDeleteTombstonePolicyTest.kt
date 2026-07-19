package com.noop.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tombstone-WRITE policy on delete (pure). A detected night writes a suppression tombstone so
 * recompute doesn't regenerate it; a `userEdited` night writes none, since it is never re-detected and
 * tombstoning it would wrongly block a real future night.
 */
class SleepDeleteTombstonePolicyTest {

    @Test fun detectedNightDeleteWritesATombstone() {
        assertTrue(
            "a detected night must be tombstoned so the recompute doesn't regenerate it",
            DismissedSleepGuard.writesTombstoneOnDelete(userEdited = false),
        )
    }

    @Test fun userEditedNightDeleteWritesNoTombstone() {
        assertFalse(
            "a hand-corrected night / manual nap is never re-detected, so it needs no tombstone",
            DismissedSleepGuard.writesTombstoneOnDelete(userEdited = true),
        )
    }
}
