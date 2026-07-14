package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure brand catalog: recognition + the byte-parity facts (sourceKind / idPrefix / capability /
 * tier). NOOP supports only WHOOP + Oura; WHOOP is detected by its own path (not an advertised-name
 * token), so the catalog carries exactly one recognised brand — the experimental Oura ring.
 */
class DeviceBrandCatalogTest {

    @Test
    fun recognisesOuraFromAdvertisedName() {
        assertEquals("Oura", DeviceBrandCatalog.specForAdvertisedName("Oura Ring")?.brand)
        assertEquals("Oura", DeviceBrandCatalog.specForAdvertisedName("OURA 4c")?.brand)
    }

    @Test
    fun unknownOrRemovedBrandNamesAreNull() {
        // The formerly-recognised generic straps / experimental families are no longer supported.
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Polar H10"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Garmin Forerunner 265"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Amazfit Helio Ring"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Mi Smart Band 8"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName("Acme HR 3000"))
        assertNull(DeviceBrandCatalog.specForAdvertisedName(""))
    }

    @Test
    fun ouraRoutingAndTierFacts() {
        val spec = requireNotNull(DeviceBrandCatalog.specForBrand("Oura")) { "missing catalog row for Oura" }
        assertEquals(SourceKind.oura, spec.sourceKind)
        assertEquals("oura", spec.idPrefix)
        assertFalse(spec.canStreamLiveHR)
        assertTrue(spec.isExperimentalTier)
    }

    @Test
    fun onlyOuraIsCatalogued() {
        assertEquals(listOf("Oura"), DeviceBrandCatalog.all.map { it.brand })
    }
}
