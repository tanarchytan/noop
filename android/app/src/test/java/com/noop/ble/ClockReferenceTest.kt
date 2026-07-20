package com.noop.ble

import com.noop.protocol.ParsedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockReferenceTest {
    private fun clockFrame(
        clock: Any? = 1_800_000_000,
        ok: Boolean = true,
        crcOk: Boolean? = true,
    ) = ParsedFrame(
        ok = ok,
        crcOk = crcOk,
        typeName = "COMMAND_RESPONSE",
        parsed = mapOf("clock" to clock),
    )

    @Test
    fun startsWithIdentityReference() {
        val reference = ClockReference(initialWall = 1_800_000_100)

        assertEquals(ClockRef(device = 1_800_000_100, wall = 1_800_000_100), reference.current)
        assertFalse(reference.correlated)
    }

    @Test
    fun acceptsFirstValidClockResponse() {
        val reference = ClockReference(initialWall = 1_800_000_100)

        val accepted = reference.accept(clockFrame(clock = 1_700_000_000), wall = 1_800_000_200)

        assertEquals(ClockRef(device = 1_700_000_000, wall = 1_800_000_200), accepted)
        assertEquals(accepted, reference.current)
        assertTrue(reference.correlated)
    }

    @Test
    fun rejectsInvalidResponses() {
        val reference = ClockReference(initialWall = 1_800_000_100)

        assertNull(reference.accept(clockFrame(ok = false), wall = 1_800_000_200))
        assertNull(reference.accept(clockFrame(crcOk = false), wall = 1_800_000_200))
        assertNull(reference.accept(clockFrame(clock = null), wall = 1_800_000_200))
        assertNull(reference.accept(clockFrame(clock = "invalid"), wall = 1_800_000_200))
        assertFalse(reference.correlated)
    }

    @Test
    fun keepsFirstReplyPerConnection() {
        val reference = ClockReference(initialWall = 1_800_000_100)
        val first = reference.accept(clockFrame(clock = 1_700_000_000), wall = 1_800_000_200)

        assertNull(reference.accept(clockFrame(clock = 1_700_000_001), wall = 1_800_000_201))
        assertEquals(first, reference.current)
    }

    @Test
    fun resetClearsCorrelationAndRefreshesIdentity() {
        val reference = ClockReference(initialWall = 1_800_000_100)
        reference.accept(clockFrame(clock = 1_700_000_000), wall = 1_800_000_200)

        reference.reset(wall = 1_800_000_300)

        assertEquals(ClockRef(device = 1_800_000_300, wall = 1_800_000_300), reference.current)
        assertFalse(reference.correlated)
        assertEquals(
            ClockRef(device = 1_700_000_010, wall = 1_800_000_310),
            reference.accept(clockFrame(clock = 1_700_000_010), wall = 1_800_000_310),
        )
    }
}
