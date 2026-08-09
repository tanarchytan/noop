package com.noop.ui

import com.noop.R
import com.noop.analytics.ReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A1/S4/S5 parity twins of the iOS TodayChargeTapCollapseTests: the one-word readiness read kept on the
 * hero (#205), the collapsed "Synced from: ..." footer summary (S5), and the metrics-grid overflow cap
 * (S5). These mirror the Swift TodayView.readinessWord / syncedFromSummary / metricsCollapsedCap EXACTLY,
 * so a drift in either platform's labels/numbers fails here.
 */
class TodayChargeTapCollapseTest {

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

    /** The footer as the screen assembles it: the source names joined into the "Synced from" copy. */
    private fun summary(names: List<String>): String =
        if (names.isEmpty()) core.getValue("core_no_sources_yet")
        else String.format(core.getValue("core_synced_from"), names.joinToString(", "))

    @Test
    fun readinessWord_mapsEveryLevel() {
        assertEquals(R.string.core_readiness_push, readinessWord(ReadinessEngine.Level.PRIMED))
        assertEquals(R.string.core_readiness_maintain, readinessWord(ReadinessEngine.Level.BALANCED))
        assertEquals(R.string.core_readiness_rest, readinessWord(ReadinessEngine.Level.STRAINED))
        assertEquals(R.string.core_readiness_rest, readinessWord(ReadinessEngine.Level.RUNDOWN))
        // The words themselves, where they now live.
        assertEquals("Push", core["core_readiness_push"])
        assertEquals("Maintain", core["core_readiness_maintain"])
        assertEquals("Rest", core["core_readiness_rest"])
    }

    @Test
    fun readinessWord_insufficientHasNoWord() {
        assertNull(readinessWord(ReadinessEngine.Level.INSUFFICIENT))
    }

    @Test
    fun syncedFromSummary_listsOnlySourcesWithData() {
        assertEquals(
            "Synced from: WHOOP, Apple Watch",
            summary(syncedFromSources(hasWhoop = true, hasApple = true, hasXiaomi = false)),
        )
        assertEquals(
            "Synced from: WHOOP",
            summary(syncedFromSources(hasWhoop = true, hasApple = false, hasXiaomi = false)),
        )
        assertEquals(
            "Synced from: WHOOP, Apple Watch, Mi Band",
            summary(syncedFromSources(hasWhoop = true, hasApple = true, hasXiaomi = true)),
        )
    }

    @Test
    fun syncedFromSummary_appleHealthReadsAsAppleWatch() {
        assertEquals(
            "Synced from: Apple Watch",
            summary(syncedFromSources(hasWhoop = false, hasApple = true, hasXiaomi = false)),
        )
    }

    @Test
    fun syncedFromSummary_healthConnectReadsAsHealthConnect() {
        // #176: a Health-Connect-only user must NOT see "Synced from: Apple Watch".
        assertEquals(
            "Synced from: Health Connect",
            summary(
                syncedFromSources(hasWhoop = false, hasApple = false, hasHealthConnect = true, hasXiaomi = false),
            ),
        )
        assertEquals(
            "Synced from: WHOOP, Health Connect",
            summary(
                syncedFromSources(hasWhoop = true, hasApple = false, hasHealthConnect = true, hasXiaomi = false),
            ),
        )
        assertEquals(
            "Synced from: WHOOP, Apple Watch, Health Connect",
            summary(
                syncedFromSources(hasWhoop = true, hasApple = true, hasHealthConnect = true, hasXiaomi = false),
            ),
        )
    }

    @Test
    fun syncedFromSummary_noSourcesIsHonest() {
        assertEquals(
            "No sources yet",
            summary(syncedFromSources(hasWhoop = false, hasApple = false, hasXiaomi = false)),
        )
    }

    @Test
    fun metricsCollapsedCap_isSixTilesThreeRows() {
        assertEquals(6, METRICS_COLLAPSED_CAP)
    }

    @Test
    fun metricsCollapse_keepsLeadingTilesInOrder() {
        // The collapse slices from the FRONT of the saved order, so a pinned/selected tile is never
        // dropped or reordered (#251); only the tail folds. Mirrors MetricGrid's take(cap).
        val saved = (0 until 10).toList()
        val visible = if (saved.size <= METRICS_COLLAPSED_CAP) saved else saved.take(METRICS_COLLAPSED_CAP)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), visible)
    }
}
