package com.noop.analytics

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [Baselines.deviceEraEpoch] (#459): the recalibration epoch at the latest
 * device-era boundary in a source-tagged nightly history, so a baseline never mixes two brands'
 * incompatible HRV scales (Oura RMSSD ~120-155 ms vs WHOOP ~72-112 ms across a switch). Mirrors the
 * Swift DeviceEraEpochTests so both platforms compute the SAME epoch for the same history.
 */
class DeviceEraEpochTest {

    /** The registry a WHOOP user carries: the import sink is a real 5-series row, so a WHOOP night
     *  names an era rather than riding whichever one it lands in. */
    private val whoopRegistry = mapOf("my-whoop" to DeviceFamily.WHOOP5)

    private fun epochOfDayUTC(day: String): Double =
        java.time.LocalDate.parse(day)
            .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()

    // ── single brand → no epoch (byte-identical fold for every single-device user) ──────────────

    @Test fun emptyHistoryIsZero() {
        assertEquals(0.0, Baselines.deviceEraEpoch(emptyList()), 0.0)
    }

    @Test fun oneWhoopBrandAcrossManyIdsIsZero() {
        // The canonical import, the active strap, and the "-noop" computed sibling ALL bucket to
        // "whoop", so a normal WHOOP user (whose nights span all three ids) gets no epoch → unchanged.
        val days = listOf(
            "2026-01-01" to "my-whoop",
            "2026-01-02" to "my-whoop-noop",
            "2026-01-03" to "whoop-EA:DC:0C:67:20:04",
            "2026-01-04" to "my-whoop",
        )
        assertEquals(0.0, Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    @Test fun oneWearableBrandOnlyIsZero() {
        // A pure Oura importer (never paired a WHOOP) has one brand → no boundary.
        val days = (1..40).map { "2026-01-%02d".format(it) to "oura-import" }
        assertEquals(0.0, Baselines.deviceEraEpoch(days), 0.0)
    }

    // ── the reported case: Oura era then WHOOP era, no overlap ──────────────────────────────────

    @Test fun ouraThenWhoopReturnsFirstWhoopDay() {
        val days = buildList {
            for (d in 1..15) add("2026-01-%02d".format(d) to "oura-import")
            for (d in 16..31) add("2026-01-%02d".format(d) to "my-whoop")
        }
        // Epoch = start of the first WHOOP night, so foldHistory drops every Oura night before it.
        assertEquals(epochOfDayUTC("2026-01-16"), Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    @Test fun unsortedInputIsSortedInternally() {
        val days = listOf(
            "2026-01-16" to "my-whoop",
            "2026-01-02" to "oura-import",
            "2026-01-31" to "my-whoop",
            "2026-01-01" to "oura-import",
            "2026-01-20" to "my-whoop",
        )
        assertEquals(epochOfDayUTC("2026-01-16"), Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    @Test fun whoopThenOuraReturnsFirstOuraDay() {
        // Symmetry: whichever brand is NEWEST defines the current era; the epoch opens that era.
        val days = buildList {
            for (d in 1..10) add("2026-02-%02d".format(d) to "my-whoop")
            for (d in 11..20) add("2026-02-%02d".format(d) to "oura-import")
        }
        assertEquals(epochOfDayUTC("2026-02-11"), Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    @Test fun onlyTheLatestBoundaryMatters_ouraWhoopOura() {
        // Two switches: the epoch is the LATEST era's start (the second Oura run), not the first.
        val days = buildList {
            for (d in 1..5) add("2026-03-%02d".format(d) to "oura-import")
            for (d in 6..10) add("2026-03-%02d".format(d) to "my-whoop")
            for (d in 11..15) add("2026-03-%02d".format(d) to "oura-import")
        }
        assertEquals(epochOfDayUTC("2026-03-11"), Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    @Test fun fitbitAndGarminAreDistinctBrands() {
        val days = buildList {
            for (d in 1..5) add("2026-04-%02d".format(d) to "garmin-import")
            for (d in 6..10) add("2026-04-%02d".format(d) to "fitbit-import")
        }
        assertEquals(epochOfDayUTC("2026-04-06"), Baselines.deviceEraEpoch(days), 0.0)
    }

    @Test fun sameDayMixedBrandTieBreaksDeterministically() {
        // An overlap night carrying BOTH an Oura and a WHOOP row on the same day: the (day, sourceId)
        // total order must resolve the tie identically to the Swift twin, so the epoch never diverges by
        // platform. "my-whoop" < "oura-import" lexically, so on the last day the WHOOP row sorts last and
        // defines the current brand; the boundary lands where the last pure-Oura day gives way.
        val days = listOf(
            "2026-06-01" to "oura-import",
            "2026-06-02" to "oura-import",
            "2026-06-03" to "my-whoop",   // overlap day: both brands present
            "2026-06-03" to "oura-import",
            "2026-06-04" to "my-whoop",
        )
        // After the (day, sourceId) total sort the overlap day orders my-whoop < oura-import, so the LAST
        // row on 2026-06-03 is oura-import. The current-brand (whoop) suffix walk therefore breaks at that
        // 06-03 oura row, and the era opens at 2026-06-04 — the same result the Swift twin computes.
        assertEquals(epochOfDayUTC("2026-06-04"), Baselines.deviceEraEpoch(days, whoopRegistry), 0.0)
    }

    // ── brand bucketing ────────────────────────────────────────────────────────────────────────

    @Test fun eraBucketKeysAWhoopStrapOnItsFamilyAndSeparatesWearables() {
        val fam = mapOf(
            "my-whoop" to DeviceFamily.WHOOP5,
            "whoop-AA:BB:CC:DD:EE:FF" to DeviceFamily.WHOOP5,
            "whoop-4" to DeviceFamily.WHOOP4,
        )
        assertEquals("whoop-WHOOP5", Baselines.eraBucket("my-whoop", fam))
        assertEquals("the computed sibling shares its strap's row", "whoop-WHOOP5", Baselines.eraBucket("my-whoop-noop", fam))
        assertEquals("whoop-WHOOP4", Baselines.eraBucket("whoop-4", fam))
        assertNull("an Apple/HC rider is era-neutral", Baselines.eraBucket("apple-health", fam))
        assertNull("so is a source with no registry row", Baselines.eraBucket("whoop-gone", fam))
        assertEquals("oura", Baselines.eraBucket("oura-import", fam))
        assertEquals("fitbit", Baselines.eraBucket("fitbit-import", fam))
        assertEquals("garmin", Baselines.eraBucket("garmin-import", fam))
    }

    // -- family boundary: 4.0 <-> 5.0/MG opens an era, 5.0 <-> MG does not ------------------------

    private val fiveAndMg = mapOf(
        "whoop-5" to DeviceFamily.WHOOP5,
        "whoop-mg" to DeviceFamily.WHOOP5,
        "whoop-4" to DeviceFamily.WHOOP4,
    )

    /** A 4.0 -> 5.0/MG swap crosses the AFE (MAX86171 -> MAX86176), so the baseline re-seeds. */
    @Test fun aFourZeroToFiveSwapOpensAnEra() {
        val days = buildList {
            for (d in 1..10) add("2026-07-%02d".format(d) to "whoop-4")
            for (d in 11..20) add("2026-07-%02d".format(d) to "whoop-5")
        }
        assertEquals(epochOfDayUTC("2026-07-11"), Baselines.deviceEraEpoch(days, fiveAndMg), 0.0)
    }

    /** And the other way, so neither direction folds two optical scales into one baseline. */
    @Test fun aFiveToFourZeroSwapOpensAnEra() {
        val days = buildList {
            for (d in 1..10) add("2026-07-%02d".format(d) to "whoop-5")
            for (d in 11..20) add("2026-07-%02d".format(d) to "whoop-4")
        }
        assertEquals(epochOfDayUTC("2026-07-11"), Baselines.deviceEraEpoch(days, fiveAndMg), 0.0)
    }

    /** 5.0 and MG share the MAX86176 - the ECG electrode is not in the PPG path - so swapping between
     *  them must NOT re-seed. Without this a user alternating two 5-series straps never forms one. */
    @Test fun aFiveToMgSwapOpensNoEra() {
        val days = buildList {
            for (d in 1..10) add("2026-07-%02d".format(d) to "whoop-5")
            for (d in 11..20) add("2026-07-%02d".format(d) to "whoop-mg")
        }
        assertEquals(0.0, Baselines.deviceEraEpoch(days, fiveAndMg), 0.0)
    }

    /** Two straps of one family alternating day by day: still one era. */
    @Test fun twoStrapsOfOneFamilyAlternatingOpenNoEra() {
        val days = (1..20).map { "2026-07-%02d".format(it) to if (it % 2 == 0) "whoop-5" else "whoop-mg" }
        assertEquals(0.0, Baselines.deviceEraEpoch(days, fiveAndMg), 0.0)
    }

    /** The family comes from the ONE canonical resolver, so both stored spellings of a 4.0 land in the
     *  same era and a single-spelling check cannot silently miss a strap. */
    @Test fun bothStoredSpellingsOfAFourZeroAreOneEra() {
        val fam = mapOf(
            "whoop-wizard" to DeviceFamily.forRegistryModel("4.0"),
            "whoop-other" to DeviceFamily.forRegistryModel("WHOOP 4.0"),
        )
        assertEquals(DeviceFamily.WHOOP4, fam["whoop-wizard"])
        assertEquals(DeviceFamily.WHOOP4, fam["whoop-other"])
        val days = buildList {
            for (d in 1..10) add("2026-07-%02d".format(d) to "whoop-wizard")
            for (d in 11..20) add("2026-07-%02d".format(d) to "whoop-other")
        }
        assertEquals(0.0, Baselines.deviceEraEpoch(days, fam), 0.0)
    }

    /** A neutral rider inside an era rides it: an Apple/HC day between two strap days of one family
     *  must not truncate the era it sits in. */
    @Test fun aNeutralRiderDoesNotTruncateTheEra() {
        val days = buildList {
            for (d in 1..5) add("2026-07-%02d".format(d) to "whoop-4")
            add("2026-07-06" to "whoop-5")
            add("2026-07-07" to "apple-health")
            add("2026-07-08" to "whoop-5")
        }
        assertEquals(epochOfDayUTC("2026-07-06"), Baselines.deviceEraEpoch(days, fiveAndMg), 0.0)
    }

    /** With no family map at all every WHOOP id is neutral, so nothing opens an era off a strap alone
     *  - the fail-safe when the registry cannot be read. */
    @Test fun noFamilyMapMeansNoStrapBoundary() {
        val days = buildList {
            for (d in 1..10) add("2026-07-%02d".format(d) to "whoop-4")
            for (d in 11..20) add("2026-07-%02d".format(d) to "whoop-5")
        }
        assertEquals(0.0, Baselines.deviceEraEpoch(days), 0.0)
    }

    // ── the epoch actually re-seeds foldHistory across the scale jump ────────────────────────────

    @Test fun epochDropsPreSwitchNightsFromTheFold() {
        // 15 Oura nights at ~135 ms then 6 WHOOP nights at ~90 ms. Folding the WHOLE history anchors
        // the baseline high (Oura-inflated); applying the era epoch drops the Oura nights so the
        // baseline re-learns from the WHOOP era and no longer reads the WHOOP nights as suppressed.
        val ouraVals = (1..15).map { 135.0 }
        val whoopVals = (1..6).map { 90.0 }
        val values: List<Double?> = ouraVals + whoopVals
        val dayKeys = buildList {
            for (d in 1..15) add("2026-05-%02d".format(d))
            for (d in 16..21) add("2026-05-%02d".format(d))
        }
        val sourceDays = buildList {
            for (d in 1..15) add("2026-05-%02d".format(d) to "oura-import")
            for (d in 16..21) add("2026-05-%02d".format(d) to "my-whoop")
        }
        val eraEpoch = Baselines.deviceEraEpoch(sourceDays, whoopRegistry)
        val mixed = Baselines.foldHistory(values, dayKeys, Baselines.hrvCfg, 0.0)
        val gated = Baselines.foldHistory(values, dayKeys, Baselines.hrvCfg, eraEpoch)
        assertTrue("era-gated baseline sits near the WHOOP era, not the Oura-inflated mean",
            gated.baseline < mixed.baseline)
        assertTrue("era-gated baseline is close to the WHOOP nightly level", gated.baseline <= 100.0)
        // The final WHOOP night reads far LESS suppressed against the era baseline than the mixed one.
        assertTrue(
            "z of a 90 ms WHOOP night is higher (less negative) against the era baseline",
            Baselines.deviation(90.0, gated).z > Baselines.deviation(90.0, mixed).z,
        )
    }
}
