package com.noop.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The scope-resolved reads must take NO device-id parameter. A read that accepts one can be handed the
 * wrong id — which is how a second paired strap's days became invisible, and how three call sites came
 * to pass a hardcoded "my-whoop". The scope is derived from the registry inside the repository, so the
 * guard is the SIGNATURE: this scans WhoopRepository.kt and fails if any of these declarations grows an
 * id parameter back.
 */
class ReadScopeApiAuditTest {

    /** The reads whose sources come from [WhoopRepository.importedSourceIds]. */
    private val scopeResolvedReads = listOf(
        "daysMerged", "daysMergedFlow", "recentDaysMergedFlow",
        "sleepSessionsMerged", "sleepSessionsUnion", "computedSleepSessionsUnion",
        "workoutsUnion", "detectedWorkoutsUnion",
        "hrSamplesUnion", "hrBucketsUnion", "rrIntervalsUnion",
        "spo2SamplesUnion", "spo2PctSamplesUnion", "skinTempSamplesUnion",
        "respSamplesUnion", "gravitySamplesUnion", "stepSamplesUnion", "sleepStateSamplesUnion",
        "stepActivityClassLatestUnion", "metricSeriesComputedUnion", "latestMetricComputedUnion",
        "habitualMidsleepSec", "editedSleeps", "dismissedSleeps", "dismissedDetected",
        "resolvedSeries", "freshness", "dataVolumeSnapshot",
        "importedSourceIds", "computedSourceIds",
    )

    private val idParamNames = listOf("deviceId", "activeDeviceId", "strapDeviceId", "activeStrapId")

    private fun repositorySource(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            "src/main/java/com/noop/data/WhoopRepository.kt",
            "app/src/main/java/com/noop/data/WhoopRepository.kt",
            "android/app/src/main/java/com/noop/data/WhoopRepository.kt",
        ).map { File(userDir, it) }.firstOrNull { it.isFile }
    }

    /** The parameter text of `fun <name>(…)`, parens balanced across lines. */
    private fun paramText(source: String, name: String): String? {
        val at = Regex("fun\\s+$name\\s*\\(").find(source)?.range?.last ?: return null
        var depth = 1
        var i = at + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0) break
            i++
        }
        return source.substring(at + 1, i)
    }

    @Test
    fun noScopeResolvedReadTakesADeviceIdParameter() {
        val file = repositorySource()
        assumeTrue("WhoopRepository.kt not reachable from ${System.getProperty("user.dir")}", file != null)
        val source = file!!.readText()

        val offenders = mutableListOf<String>()
        var found = 0
        for (name in scopeResolvedReads) {
            val params = paramText(source, name) ?: continue
            found++
            for (id in idParamNames) {
                if (Regex("\\b$id\\s*:").containsMatchIn(params)) {
                    offenders.add("$name($params)")
                }
            }
        }

        // Scanner sanity: a rename that hides every declaration must fail the audit, not pass it.
        assertTrue("expected to find most scope-resolved reads, found $found", found >= 25)
        assertTrue(
            "a scope-resolved read must derive its sources from the registry, never take an id:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
