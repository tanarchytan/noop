package com.noop.ui

import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the By-Day source badge (Sleep overhaul §2.6). The card used to hard-code "NOOP-computed" on
 * EVERY row — even days an import won the dashboard merge — so a user couldn't tell a strap-scored
 * night from an imported one. The badge now derives from the merged DailyMetric's WINNING deviceId:
 *   - computed "<id>-noop"  → "On-device"
 *   - imported WHOOP export → "Whoop"
 *   - apple-health          → "Apple Health"
 *   - health-connect        → "Health Connect"
 * Each phone store is named for what it is: they are separate sources, an Android install can hold
 * either, and only Health Connect can supply a day on its own. Mirrors WorkoutSourceLabelTest.
 */
class IntelligenceDaySourceBadgeTest {

    @Test
    fun computedNoopRow_isOnDevice() {
        assertEquals("On-device", daySourceBadge("my-whoop-noop").first)
    }

    @Test
    fun anyNoopSuffix_isOnDevice() {
        // A non-default strap id keeps the "-noop" computed suffix convention.
        assertEquals("On-device", daySourceBadge("strap-abc123-noop").first)
    }

    @Test
    fun whoopImportRow_isWhoop() {
        // The merged row keeps the imported "my-whoop" id when a WHOOP export wins the merge.
        assertEquals("Whoop", daySourceBadge("my-whoop").first)
    }

    @Test
    fun appleHealthRow_isAppleHealth() {
        assertEquals("Apple Health", daySourceBadge(WhoopRepository.APPLE_HEALTH_SOURCE).first)
    }

    /**
     * Health Connect names itself. It shared Apple Health's badge while it only held body metrics; it
     * now supplies whole days of sleep and vitals on a phone that may never have seen an Apple export,
     * and every other surface in the app already calls it "Health Connect".
     */
    @Test
    fun healthConnectRow_isHealthConnect() {
        assertEquals("Health Connect", daySourceBadge(WhoopRepository.HEALTH_CONNECT_SOURCE).first)
        assertEquals(
            "the badge must not invent its own wording for a source the app already names",
            com.noop.analytics.FusionSource.HEALTH_CONNECT.displayName,
            daySourceBadge(WhoopRepository.HEALTH_CONNECT_SOURCE).first,
        )
    }

    /** The two phone stores are distinct sources, so their badges must not collapse into one. */
    @Test
    fun theTwoPhoneStoresAreLabelledApart() {
        assertEquals(
            false,
            daySourceBadge(WhoopRepository.APPLE_HEALTH_SOURCE).first ==
                daySourceBadge(WhoopRepository.HEALTH_CONNECT_SOURCE).first,
        )
    }

    @Test
    fun computedTintDiffersFromImportTint() {
        // Computed rows keep the charge tint; imports use the accent tint so they stand out.
        assertEquals(Palette.chargeColor, daySourceBadge("my-whoop-noop").second)
        assertEquals(Palette.accent, daySourceBadge("my-whoop").second)
    }

    @Test
    fun noBadgeLabelCarriesAnEmDash() {
        for (id in listOf("my-whoop-noop", "my-whoop", "apple-health", "health-connect")) {
            assertEquals(false, daySourceBadge(id).first.contains("—"))
        }
    }
}
