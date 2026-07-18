package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connection & Sync line formatters + readout parsers (Test Centre). Pure JVM - no Robolectric, no
 * Mockito/MockK, no BLE - so fixtures pin the exact line shapes the Kotlin and Swift emitters share.
 * Twin of the Swift ConnectionTraceTests / ConnectionReadoutTests.
 */
class ConnectionTraceTest {

    @Test fun clockDriftLineHealthy() {
        val newest = 1_782_475_200L            // 2026-06-26 12:00:00 UTC
        val oldest = newest - 2 * 86_400L
        val wall = newest + 600L               // wall 10 min ahead of the newest record
        val line = ConnectionTrace.clockDriftLine(oldestUnix = oldest, newestUnix = newest, wallNowUnix = wall)
        assertTrue(line, line.startsWith("clockDrift newest=2026-06-26 12:00:00 "))
        assertTrue(line, line.contains("newestVsWall=-600s"))
        assertTrue(line, line.contains("spanDays=2"))
        assertTrue(line, line.endsWith("clockOk"))
        assertFalse(line, line.contains("FUTURE"))
    }

    @Test fun clockDriftLineFutureDated() {
        val wall = 1_782_475_200L
        val newest = wall + 3 * 86_400L        // strap thinks it banked 3 days into the future
        val line = ConnectionTrace.clockDriftLine(oldestUnix = null, newestUnix = newest, wallNowUnix = wall)
        assertTrue(line, line.contains("newestVsWall=+${3 * 86_400}s"))
        assertTrue(line, line.contains("FUTURE-DATED"))
        assertFalse(line, line.contains("oldest="))   // half range reply: no lower bound
    }

    @Test fun clockDriftLineWithinToleranceIsOk() {
        val wall = 1_782_475_200L
        val newest = wall + 60L                // 1 min ahead, inside the 120s default tolerance
        val line = ConnectionTrace.clockDriftLine(oldestUnix = null, newestUnix = newest, wallNowUnix = wall)
        assertTrue(line, line.endsWith("clockOk"))
    }

    @Test fun firmwareLine() {
        assertEquals("firmware layout=v25 decodable", ConnectionTrace.firmwareLine(25, true))
        assertEquals("firmware layout=v30 UNMAPPED (no motion/HR decoded)", ConnectionTrace.firmwareLine(30, false))
    }

    @Test fun noCursorLine() {
        assertEquals(
            "offload trim=0xFFFFFFFF noCursor (strap has no banked history to offload)",
            ConnectionTrace.noCursorLine(),
        )
    }

    // #990: the -363 d drift that used to print "clockOk". Beyond the 48 h behind-tolerance the line
    // must carry a clock warning naming the day count. Twin of the Swift vector.
    @Test fun clockDriftLineFarBehindIsWarning() {
        val wall = 1_782_475_200L
        val line = ConnectionTrace.clockDriftLine(
            oldestUnix = null, newestUnix = wall - 363L * 86_400L, wallNowUnix = wall,
        )
        assertTrue(line, line.contains("CLOCK-WARNING"))
        assertTrue(line, line.contains("363d behind wall"))
        assertFalse(line, line.contains("clockOk"))
    }

    @Test fun clockDriftLineBehindWithinToleranceStaysOk() {
        val wall = 1_782_475_200L
        val line = ConnectionTrace.clockDriftLine(
            oldestUnix = null, newestUnix = wall - 47L * 3_600L, wallNowUnix = wall,
        )
        assertTrue(line, line.endsWith("clockOk"))
    }

    // #987: an epoch-era newest (never-set RTC, ~1970/71) is the named RTC-EPOCH fault, never clockOk.
    @Test fun clockDriftLineEpochEraReadsRtcEpoch() {
        val line = ConnectionTrace.clockDriftLine(
            oldestUnix = null, newestUnix = 40_000_000L, wallNowUnix = 1_782_475_200L,  // 1971-04
        )
        assertTrue(line, line.contains("RTC-EPOCH"))
        assertFalse(line, line.contains("clockOk"))
    }
}
