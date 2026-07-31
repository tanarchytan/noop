package com.noop.ingest

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Health Connect day-keying rule: a record is keyed to the local day of its OWN zone
 * offset, not the phone's current one. Health Connect stores an offset on every record; keying a
 * ten-year backfill by [ZoneId.systemDefault] instead puts records near midnight on the wrong day
 * whenever the offset in force then differs from now (a DST change, travel).
 *
 * Covers the pure [HealthConnectImporter.localDay] mapper plus a source guard that no `dayOf` call
 * site drops the offset argument. No Room / Context / Health Connect client needed.
 */
class HealthConnectZoneOffsetTest {

    private val phone = ZoneId.of("UTC")

    @Test
    fun recordOffsetDecidesTheDayNotThePhoneZone() {
        // 23:30 UTC. At +02:00 that is 01:30 the NEXT local day.
        val t = Instant.parse("2026-03-28T23:30:00Z")
        assertEquals("2026-03-29", HealthConnectImporter.localDay(t, ZoneOffset.ofHours(2), phone))
        // The phone's zone must not decide it.
        assertEquals("2026-03-28", HealthConnectImporter.localDay(t, null, phone))
    }

    @Test
    fun aWesternOffsetKeepsTheRecordOnThePreviousDay() {
        // 00:30 UTC at -05:00 is 19:30 the PREVIOUS local day.
        val t = Instant.parse("2026-07-04T00:30:00Z")
        assertEquals("2026-07-03", HealthConnectImporter.localDay(t, ZoneOffset.ofHours(-5), phone))
        assertEquals("2026-07-04", HealthConnectImporter.localDay(t, null, phone))
    }

    @Test
    fun twoRecordsOneOffsetApartSplitAcrossDays() {
        // The DST case: the same wall-clock night banked under +01:00 and +02:00.
        val winter = Instant.parse("2026-01-15T23:30:00Z")
        val summer = Instant.parse("2026-07-15T23:30:00Z")
        assertEquals("2026-01-16", HealthConnectImporter.localDay(winter, ZoneOffset.ofHours(1), phone))
        assertEquals("2026-07-16", HealthConnectImporter.localDay(summer, ZoneOffset.ofHours(2), phone))
        // Keyed by a single summer-time phone offset, the WINTER record moves a day.
        val phoneSummer = ZoneId.of("UTC+02:00")
        assertEquals("2026-01-16", HealthConnectImporter.localDay(winter, null, phoneSummer))
    }

    @Test
    fun fallbackIsUsedOnlyWhenTheRecordCarriesNoOffset() {
        val t = Instant.parse("2026-05-10T22:00:00Z")
        assertEquals("2026-05-11", HealthConnectImporter.localDay(t, null, ZoneId.of("UTC+03:00")))
        // An explicit offset always wins over the fallback, even when they disagree.
        assertEquals("2026-05-10", HealthConnectImporter.localDay(t, ZoneOffset.ofHours(0), ZoneId.of("UTC+03:00")))
    }

    /**
     * Every `dayOf(` call in the importer must pass the record's offset. The defect this pins was
     * exactly a day key computed with no offset at all, which no value-level test can see because
     * the helper is correct and simply never told.
     */
    @Test
    fun noDayOfCallSiteDropsTheOffset() {
        val src = File("src/main/java/com/noop/ingest/HealthConnectImporter.kt")
        assertTrue("importer source not found at ${src.absolutePath}", src.exists())
        val offenders = src.readLines().withIndex().filter { (_, line) ->
            val t = line.trim()
            // The declaration itself is not a call site.
            !t.startsWith("fun dayOf(") && !t.startsWith("*") && !t.startsWith("//") &&
                Regex("""dayOf\([^,)]*\)""").containsMatchIn(line)
        }.map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertEquals("dayOf() called without a zone offset: $offenders", emptyList<String>(), offenders)
    }
}
