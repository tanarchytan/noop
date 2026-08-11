package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [relativeAgoBucket], the pure seam behind the Live "History synced N ago"
 * sync-status line. Buckets to just-now / minutes / hours / days; clamps future times. The wording
 * lives in resources, so this pins the bucket and the count, which is the whole decision.
 */
class RelativeAgoTest {

    private val now = 1_781_000_000L

    private fun ago(sec: Long) = relativeAgoBucket(now - sec, now)

    @Test fun underAMinuteIsJustNow() {
        assertEquals(AgoUnit.NOW to 0, ago(0))
        assertEquals(AgoUnit.NOW to 0, ago(59))
    }

    @Test fun minutes() {
        assertEquals(AgoUnit.MINUTES to 1, ago(60))
        assertEquals(AgoUnit.MINUTES to 5, ago(5 * 60))
        assertEquals(AgoUnit.MINUTES to 59, ago(59 * 60))
    }

    @Test fun hours() {
        assertEquals(AgoUnit.HOURS to 1, ago(3600))
        assertEquals(AgoUnit.HOURS to 23, ago(23 * 3600))
    }

    @Test fun days() {
        assertEquals(AgoUnit.DAYS to 1, ago(86_400))
        assertEquals(AgoUnit.DAYS to 3, ago(3 * 86_400))
    }

    @Test fun futureTimestampClampsToJustNow() {
        // A strap-clock skew could put lastSyncAt slightly in the future; never render negative.
        assertEquals(AgoUnit.NOW to 0, relativeAgoBucket(now + 500, now))
    }
}
