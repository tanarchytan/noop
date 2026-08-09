package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Devices card's state-pill priority (#221): "Connected · not paired" must beat "Active · Live"
 * but yield to a reboot's "Reconnecting…". Mirrors the Swift `DevicePillStateTests` exactly — a silent
 * reorder on either platform would otherwise only be caught by eyeballing a screenshot.
 *
 * The pill now carries a resource id, so the priority is pinned by the id each branch picks and the
 * wording by [coreStrings] reading it back out of strings_core.xml.
 */
class DevicePillStateTest {

    /** Every `<string name="x">y</string>` in the app's strings_core.xml. */
    private val core: Map<String, String> = run {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val res = listOf(userDir, File(userDir, "app"), File(userDir, "android/app"))
            .map { File(it, "src/main/res/values/strings_core.xml") }
            .firstOrNull { it.isFile }
            ?: error("strings_core.xml not found from ${userDir.absolutePath}")
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(res.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    @Test
    fun theWordingOnEveryPillAndFootnoteIsStillWhatItWas() {
        mapOf(
            "devices2_pill_removed" to "Removed",
            "devices2_pill_paired" to "Paired",
            "devices2_pill_reconnecting" to "Reconnecting…",
            "devices2_pill_bond_refused" to "Connected · not paired",
            "devices2_pill_charging_live" to "Charging · Live",
            "devices2_pill_active_live" to "Active · Live",
            "devices2_pill_active" to "Active",
            "devices2_last_seen_removed" to "Removed · data kept",
            "devices2_last_seen_bond_refused" to "Connected, but not paired",
            "devices2_last_seen_connected" to "Connected now",
            "devices2_last_seen" to "Last seen %1\$s",
            "devices2_history_layout" to "v%1\$d history",
        ).forEach { (key, copy) -> assertEquals("$key changed", copy, core[key]) }
        // A resource id that never resolved to a real number would make every branch below agree.
        assertTrue(
            "pill resource ids must be distinct",
            setOf(
                R.string.devices2_pill_removed, R.string.devices2_pill_paired,
                R.string.devices2_pill_reconnecting, R.string.devices2_pill_bond_refused,
                R.string.devices2_pill_charging_live, R.string.devices2_pill_active_live,
                R.string.devices2_pill_active,
            ).size == 7,
        )
    }

    @Test
    fun bondRefused_beatsActiveLive_butYieldsToReconnecting() {
        assertEquals(
            R.string.devices2_pill_bond_refused,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
        assertEquals(
            R.string.devices2_pill_reconnecting,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
    }

    @Test
    fun normalConnect_isUnaffected() {
        assertEquals(
            R.string.devices2_pill_active_live,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true,
            ).label,
        )
        assertEquals(
            R.string.devices2_pill_active,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
    }

    @Test
    fun nonActiveAndArchived() {
        assertEquals(
            R.string.devices2_pill_paired,
            devicePillState(
                isArchived = false, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
        assertEquals(
            R.string.devices2_pill_removed,
            devicePillState(
                isArchived = true, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
    }

    @Test
    fun charging_replacesActiveLive_butYieldsToBothWarnings() {
        assertEquals(
            R.string.devices2_pill_charging_live,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true, isCharging = true,
            ).label,
        )
        assertEquals(
            R.string.devices2_pill_bond_refused,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true, isCharging = true,
            ).label,
        )
        assertEquals(
            R.string.devices2_pill_reconnecting,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = false, isLiveConnected = true, isCharging = true,
            ).label,
        )
        // A charge report with the link down is not a live state — the strap clears the flag on
        // disconnect, so this can only be a stale read and must not claim "Charging · Live".
        assertEquals(
            R.string.devices2_pill_active,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false, isCharging = true,
            ).label,
        )
    }

    @Test
    fun historyLayoutLine_formatsObservedHistoricalRecordVersion() {
        assertEquals(R.string.devices2_history_layout, historyLayoutLine(25))
        assertEquals("v25 history", String.format(core.getValue("devices2_history_layout"), 25))
        assertNull(historyLayoutLine(null))
    }

    @Test
    fun powerPackLine_namesOnlyAPackTheStrapActuallyReported() {
        assertEquals("PowerPack · BPK-0001", powerPackLine(67.0, "BPK-0001"))
        // A charge with no serial still names the pack; a blank serial must not read as one.
        assertEquals("PowerPack", powerPackLine(67.0, null))
        assertEquals("PowerPack", powerPackLine(67.0, "  "))
        // No charge means the strap reported no pack, so the line is absent rather than invented.
        assertNull(powerPackLine(null, null))
        assertNull(powerPackLine(null, "BPK-0001"))
    }

    @Test
    fun powerPackLine_showsTheFirmwareAheadOfTheSerialAndKeepsBoth() {
        assertEquals(
            "PowerPack · FW 3.30.5.0 · WBB5AP0126395",
            powerPackLine(73.3, "WBB5AP0126395", "3.30.5.0"),
        )
        assertEquals("PowerPack · FW 3.30.5.0", powerPackLine(73.3, null, "3.30.5.0"))
        assertEquals("PowerPack · WBB5AP0126395", powerPackLine(73.3, "WBB5AP0126395", "  "))
        // A remembered firmware must never name a pack the strap is not reporting a charge for.
        assertNull(powerPackLine(null, "WBB5AP0126395", "3.30.5.0"))
    }
}
