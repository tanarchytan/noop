package com.noop.ble

import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.protocol.DeviceFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.zip.CRC32

/**
 * The trim cursor, pinned VALUE-wise end to end. A real WHOOP 5 HISTORY_END goes into [Backfiller.ingest]
 * and the number reaching [TrimCursorStore.set], [Backfiller.lastAckedTrim] and the ack callback must be
 * the frame's own cursor, unshifted - that cursor is what the strap deletes history by.
 *
 * The session tally tests cover row counts and log wording only, so the cursor's value itself reached no
 * assertion: shifting it by one left every test green.
 */
class BackfillerTrimCursorTest {

    /** Real worn WHOOP 5 HISTORY_END: meta_type 2, unix 1784236473, trim cursor 113405. */
    private val historyEndHex =
        "aa011c00010023d1319102b949596a705d3b000000fdba010010000000000000f269faec"

    private val trim = 113_405L

    private fun bytes(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    /** Overwrite frame bytes from [at] and re-stamp the CRC32 over frame[8 until size-4] so the gate passes. */
    private fun patched(vararg pairs: Pair<Int, Int>): ByteArray {
        val f = bytes(historyEndHex)
        for ((at, v) in pairs) f[at] = v.toByte()
        val end = f.size - 4
        val c = CRC32().apply { update(f, 8, end - 8) }.value
        for (i in 0..3) f[end + i] = ((c shr (8 * i)) and 0xFF).toByte()
        return f
    }

    /** Records the ordered side effects the safe-trim invariant is about. */
    private class Recorder {
        val steps = ArrayList<String>()
        val cursor = ArrayList<Pair<String, Long>>()
        var ackedTrim: Long? = null
        var ackedEndData: ByteArray? = null

        val store = object : TrimCursorStore {
            override suspend fun set(name: String, value: Long) {
                cursor.add(name to value)
                steps.add("cursor=$value")
            }

            override suspend fun get(name: String): Long? = cursor.lastOrNull { it.first == name }?.second
        }
    }

    /** A repository whose DAO throws: a metadata-only END must never reach the store. */
    private fun repository(): WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, _ -> throw AssertionError("a metadata-only END must not touch the store: ${method.name}") }
        return WhoopRepository(dao as WhoopDao)
    }

    private fun backfiller(r: Recorder) = Backfiller(
        repository = repository(),
        deviceId = "my-whoop",
        cursorStore = r.store,
        ackTrim = { t, endData ->
            r.ackedTrim = t
            r.ackedEndData = endData
            r.steps.add("ack=$t")
        },
    )

    @Test
    fun theAckedCursorIsTheFramesOwnCursor() = runBlocking {
        val r = Recorder()
        val b = backfiller(r)
        b.begin(DeviceFamily.WHOOP5)
        b.ingest(bytes(historyEndHex))

        assertEquals(listOf(Backfiller.STRAP_TRIM_CURSOR to trim), r.cursor)
        assertEquals(trim, r.ackedTrim)
        assertEquals(trim, b.lastAckedTrim)
        // The verbatim 8-byte end_data the high-freq-sync ack form requires (frame[21:29] on 5/MG).
        assertEquals("fdba010010000000", r.ackedEndData!!.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theCursorIsPersistedBeforeTheAck() = runBlocking {
        val r = Recorder()
        val b = backfiller(r)
        b.begin(DeviceFamily.WHOOP5)
        b.ingest(bytes(historyEndHex))
        // Durability first: a crash between the two must resume from the right place, never past it.
        assertEquals(listOf("cursor=$trim", "ack=$trim"), r.steps)
    }

    @Test
    fun theSentinelCursorSurvivesAsUnsigned() = runBlocking {
        // 0xFFFFFFFF is the strap's no-flash-cursor sentinel. It must reach the store and the ack as
        // 4294967295, not as a sign-extended -1.
        val r = Recorder()
        val b = backfiller(r)
        b.begin(DeviceFamily.WHOOP5)
        b.ingest(patched(21 to 0xFF, 22 to 0xFF, 23 to 0xFF, 24 to 0xFF))
        assertEquals(listOf(Backfiller.STRAP_TRIM_CURSOR to 0xFFFFFFFFL), r.cursor)
        assertEquals(0xFFFFFFFFL, r.ackedTrim)
    }

    @Test
    fun aStartOrCompleteFrameAdvancesNothing() = runBlocking {
        for (metaType in listOf(1, 3)) {
            val r = Recorder()
            val b = backfiller(r)
            b.begin(DeviceFamily.WHOOP5)
            b.ingest(patched(10 to metaType))
            assertTrue("meta_type $metaType must not write a cursor", r.cursor.isEmpty())
            assertNull("meta_type $metaType must not ack", r.ackedTrim)
            assertNull(b.lastAckedTrim)
        }
    }

    @Test
    fun aGarbledFrameCannotAdvanceTheCursor() = runBlocking {
        // Same frame with the cursor bytes moved and the CRC32 left stale: a garbled or forged peer must
        // not be able to trim the strap past history we never stored.
        val forged = bytes(historyEndHex)
        forged[21] = 0x00
        forged[22] = 0x00
        val r = Recorder()
        val b = backfiller(r)
        b.begin(DeviceFamily.WHOOP5)
        b.ingest(forged)
        assertTrue(r.cursor.isEmpty())
        assertNull(r.ackedTrim)
    }
}
