package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #836 — [WhoopRepository.hrFingerprint] is the cheap whole-history raw-HR change-detector the 15-min idle
 * rescore gates on: `"maxRowid:lastInsertedTs"`. Stubbed through a Proxy [WhoopDao] (no Room), answering only the two
 * O(log n) queries the fingerprint reads. Mirrors the Swift WhoopStore.hrFingerprint semantics.
 */
class HrFingerprintTest {

    private fun repo(rowid: Long, ts: Long): WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "maxHrRowid" -> rowid
                "lastInsertedHrTs" -> ts
                else -> throw UnsupportedOperationException("hrFingerprint must not call ${method.name}")
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    @Test fun combinesRowidAndTs() = runBlocking {
        assertEquals("42:1700", repo(rowid = 42L, ts = 1700L).hrFingerprint())
    }

    // An empty store is a stable, non-null "0:0" (COALESCE), so a first run still differs from the unset
    // (null) watermark and scores; two empty reads match, so a genuinely empty store doesn't churn.
    @Test fun emptyStoreIsZeroZero() = runBlocking {
        assertEquals("0:0", repo(rowid = 0L, ts = 0L).hrFingerprint())
    }

    // A new sample (count up, later maxTs) moves the fingerprint, so the idle tick rescores.
    @Test fun newSampleMovesTheFingerprint() = runBlocking {
        val before = repo(rowid = 10L, ts = 1000L).hrFingerprint()
        val after = repo(rowid = 11L, ts = 1060L).hrFingerprint()
        assertEquals("10:1000", before)
        assertEquals("11:1060", after)
    }
}
