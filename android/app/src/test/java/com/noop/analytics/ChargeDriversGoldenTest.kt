package com.noop.analytics

import com.noop.R
import com.noop.ui.TemperatureUnit
import com.noop.ui.chargeDriverRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * FROZEN golden vectors for the Charge "What shaped it" driver rows: every expected value below is a
 * LITERAL, never a second call into the implementation, so this test is the oracle and the code is not.
 *
 * Covers the boundaries a port silently gets wrong: each direction cut point on both sides and
 * exactly AT it, the skin-temp typical band at exactly +/-0.3 C, both saturated ends of the logistic,
 * a null for every optional driver, and an unusable HRV baseline.
 *
 * The seven MULTI-driver vectors were re-derived when whoop-rs made a driver's swing an exact Shapley
 * share instead of a leave-one-out marginal; the replacements were computed independently rather than
 * read back out of the code. Every vector whose other drivers sit AT baseline is unchanged, because
 * the two agree exactly there - only a night with several drivers off baseline at once can tell them
 * apart, which is why the wrong one survived this long.
 */
class ChargeDriversGoldenTest {

    // ── the copy ─────────────────────────────────────────────────────────────
    //
    // The rows now carry resource ids, so the vectors below read their English out of
    // strings_core.xml: the ids pin WHICH key each row picks, and the file pins what that key says.

    /** Every `<string name="x">y</string>` in the app's strings_core.xml, apostrophes unescaped. */
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

    /** Resource id to resource name, for every key a driver row can pick. */
    private val names: Map<Int, String> = mapOf(
        R.string.charge_driver_hrv to "charge_driver_hrv",
        R.string.charge_driver_resting_hr to "charge_driver_resting_hr",
        R.string.charge_driver_sleep to "charge_driver_sleep",
        R.string.charge_driver_respiratory to "charge_driver_respiratory",
        R.string.charge_driver_skin_temp to "charge_driver_skin_temp",
        R.string.charge_driver_recovery_index to "charge_driver_recovery_index",
        R.string.charge_driver_activity_balance to "charge_driver_activity_balance",
        R.string.charge_driver_effort_yesterday to "charge_driver_effort_yesterday",
        R.string.charge_driver_baseline to "charge_driver_baseline",
        R.string.charge_driver_vs_baseline to "charge_driver_vs_baseline",
        R.string.charge_driver_overnight to "charge_driver_overnight",
        R.string.charge_verdict_at_baseline to "charge_verdict_at_baseline",
        R.string.charge_verdict_above_supporting to "charge_verdict_above_supporting",
        R.string.charge_verdict_below_limiting to "charge_verdict_below_limiting",
        R.string.charge_verdict_below_supporting to "charge_verdict_below_supporting",
        R.string.charge_verdict_above_limiting to "charge_verdict_above_limiting",
        R.string.charge_verdict_sleep_supporting to "charge_verdict_sleep_supporting",
        R.string.charge_verdict_sleep_neutral to "charge_verdict_sleep_neutral",
        R.string.charge_verdict_sleep_limiting to "charge_verdict_sleep_limiting",
        R.string.charge_verdict_index_supporting to "charge_verdict_index_supporting",
        R.string.charge_verdict_index_neutral to "charge_verdict_index_neutral",
        R.string.charge_verdict_index_limiting to "charge_verdict_index_limiting",
        R.string.charge_verdict_activity_supporting to "charge_verdict_activity_supporting",
        R.string.charge_verdict_activity_neutral to "charge_verdict_activity_neutral",
        R.string.charge_verdict_activity_limiting to "charge_verdict_activity_limiting",
        R.string.charge_verdict_skin_temp_warm to "charge_verdict_skin_temp_warm",
        R.string.charge_verdict_skin_temp_cool to "charge_verdict_skin_temp_cool",
        R.string.charge_verdict_skin_temp_near to "charge_verdict_skin_temp_near",
    )

    private fun english(id: Int): String {
        val name = names[id] ?: error("driver row picked an unpinned resource id $id")
        return core[name] ?: error("strings_core.xml has no <string name=\"$name\">")
    }

    /** A row's value or baseline: the bare figure, or the phrase its resource wraps it in. */
    private fun wrap(id: Int?, value: String): String =
        if (id == null) value else String.format(Locale.US, english(id), value)

    /**
     * 28 distinct ids, so a resource id the app never resolved to a real number (every key reading 0)
     * collapses this map and fails here rather than making every vector below agree by accident.
     */
    @Test fun everyDriverResourceIsDistinctAndPresent() {
        assertEquals(28, names.size)
        names.values.forEach { assertTrue("strings_core.xml is missing $it", core.containsKey(it)) }
    }

    /** House style, now checked where the wording lives rather than on the row that renders it. */
    @Test fun noDriverCopyCarriesAnEmDash() {
        names.values.forEach {
            assertTrue("$it must not contain an em-dash", !core.getValue(it).contains('—'))
        }
    }

    // ── the seam ─────────────────────────────────────────────────────────────

    /** The ONE call into the implementation under test: whoop-rs scores, Kotlin words it. */
    private fun rows(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: BaselineState,
        rhrBaseline: BaselineState?,
        respBaseline: BaselineState?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        recoveryIndexSlope: Double? = null,
        effortBaseline: BaselineState? = null,
        priorDayEffort: Double? = null,
    ): List<String> = chargeDriverRows(
        tempUnit = TemperatureUnit.CELSIUS,
        hrv = hrv, rhr = rhr, resp = resp,
        hrvBaseline = hrvBaseline, rhrBaseline = rhrBaseline, respBaseline = respBaseline,
        sleepPerf = sleepPerf, skinTempDev = skinTempDev, recoveryIndexSlope = recoveryIndexSlope,
        effortBaseline = effortBaseline, priorDayEffort = priorDayEffort,
    ).map {
        listOf(
            english(it.labelRes),
            "${it.deltaPoints}",
            wrap(it.valueRes, it.valueText),
            wrap(it.baselineRes, it.baselineValue),
            english(it.verdictRes),
        ).joinToString("|")
    }

    /** A usable baseline with a given mean and Gaussian sigma (spread is internal abs-dev units). */
    private fun bl(mean: Double, sigma: Double): BaselineState =
        BaselineState(mean, sigma / 1.253, 20, 0, BaselineStatus.TRUSTED)

    private val hrvBl get() = bl(50.0, 6.0)
    private val rhrBl get() = bl(55.0, 3.0)
    private val respBl get() = bl(16.0, 2.0)
    private val effortBl get() = bl(40.0, 15.0)

    /** The two-term background every boundary vector varies one signal against; both sit AT baseline. */
    private fun background(
        hrv: Double = 50.0,
        rhr: Double = 55.0,
        resp: Double? = null,
        respBaseline: BaselineState? = null,
        sleepPerf: Double? = null,
        skinTempDev: Double? = null,
        recoveryIndexSlope: Double? = null,
        effortBaseline: BaselineState? = null,
        priorDayEffort: Double? = null,
    ): List<String> = rows(
        hrv = hrv, rhr = rhr, resp = resp,
        hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBaseline,
        sleepPerf = sleepPerf, skinTempDev = skinTempDev, recoveryIndexSlope = recoveryIndexSlope,
        effortBaseline = effortBaseline, priorDayEffort = priorDayEffort,
    )

    private val hrvFlat = "Heart rate variability|0|50 ms|50 ms baseline|at baseline"
    private val rhrFlat = "Resting heart rate|0|55 bpm|55 bpm baseline|at baseline"

    // ── A. every term present ────────────────────────────────────────────────

    @Test fun allSevenTermsFrozenInBiggestMoverOrder() {
        assertEquals(
            listOf(
                "Heart rate variability|26|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|6|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Sleep quality|1|90%||a strong night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                "Skin temperature|0|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = 0.4, recoveryIndexSlope = -3.0,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    // ── B. every directionVerdict cut point, both sides and exactly AT it ─────

    @Test fun hrvVerdictAtItsCutPoint() {
        assertEquals(
            listOf("Heart rate variability|-5|49 ms|50 ms baseline|below baseline, limiting recovery", rhrFlat),
            background(hrv = 49.0),
        )
        assertEquals(listOf(hrvFlat, rhrFlat), background(hrv = 50.0))
        assertEquals(
            listOf("Heart rate variability|5|51 ms|50 ms baseline|above baseline, supporting recovery", rhrFlat),
            background(hrv = 51.0),
        )
    }

    @Test fun restingHrVerdictAtItsCutPoint() {
        assertEquals(
            listOf("Resting heart rate|3|54 bpm|55 bpm baseline|below baseline, supporting recovery", hrvFlat),
            background(rhr = 54.0),
        )
        assertEquals(listOf(hrvFlat, rhrFlat), background(rhr = 55.0))
        assertEquals(
            listOf("Resting heart rate|-3|56 bpm|55 bpm baseline|above baseline, limiting recovery", hrvFlat),
            background(rhr = 56.0),
        )
    }

    @Test fun respiratoryVerdictAtItsCutPoint() {
        assertEquals(
            listOf(
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                hrvFlat, rhrFlat,
            ),
            background(resp = 15.0, respBaseline = respBl),
        )
        assertEquals(
            listOf(hrvFlat, rhrFlat, "Respiratory rate|0|16.0 br/min|16.0 br/min baseline|at baseline"),
            background(resp = 16.0, respBaseline = respBl),
        )
        assertEquals(
            listOf(
                "Respiratory rate|-1|17.0 br/min|16.0 br/min baseline|above baseline, limiting recovery",
                hrvFlat, rhrFlat,
            ),
            background(resp = 17.0, respBaseline = respBl),
        )
    }

    /** Sleep has no learned baseline: its cut point is the fixed 0.85 "good night" centre. */
    @Test fun sleepVerdictAtItsCutPoint() {
        assertEquals(
            listOf("Sleep quality|-3|80%||below a good night, limiting recovery", hrvFlat, rhrFlat),
            background(sleepPerf = 0.80),
        )
        assertEquals(
            listOf(hrvFlat, rhrFlat, "Sleep quality|0|85%||a typical night"),
            background(sleepPerf = 0.85),
        )
        assertEquals(
            listOf("Sleep quality|3|90%||a strong night, supporting recovery", hrvFlat, rhrFlat),
            background(sleepPerf = 0.90),
        )
    }

    /** The slope's cut point is a flat overnight 0.0 bpm/hr; negative (declining) supports recovery. */
    @Test fun recoveryIndexVerdictAtItsCutPoint() {
        assertEquals(
            listOf(
                "Recovery index|-1|+1.0 bpm/hr|overnight|resting HR rose overnight, limiting recovery",
                hrvFlat, rhrFlat,
            ),
            background(recoveryIndexSlope = 1.0),
        )
        assertEquals(
            listOf(hrvFlat, rhrFlat, "Recovery index|0|+0.0 bpm/hr|overnight|resting HR held flat overnight"),
            background(recoveryIndexSlope = 0.0),
        )
        assertEquals(
            listOf(
                "Recovery index|1|-1.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                hrvFlat, rhrFlat,
            ),
            background(recoveryIndexSlope = -1.0),
        )
    }

    /** Activity balance rounds to 0 points either side of its cut point, yet still picks a signed word. */
    @Test fun activityBalanceVerdictAtItsCutPoint() {
        assertEquals(
            listOf(
                hrvFlat, rhrFlat,
                "Activity balance|0|39 effort yesterday|40 baseline|a lighter day yesterday, supporting recovery",
            ),
            background(effortBaseline = effortBl, priorDayEffort = 39.0),
        )
        assertEquals(
            listOf(hrvFlat, rhrFlat, "Activity balance|0|40 effort yesterday|40 baseline|a typical day yesterday"),
            background(effortBaseline = effortBl, priorDayEffort = 40.0),
        )
        assertEquals(
            listOf(
                hrvFlat, rhrFlat,
                "Activity balance|0|41 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
            ),
            background(effortBaseline = effortBl, priorDayEffort = 41.0),
        )
    }

    // ── C. the skin-temp typical band, at exactly +/-0.3 C ───────────────────

    /** The band edge is INCLUSIVE: exactly +/-0.3 reads neutral, one ulp beyond does not. */
    @Test fun skinTempBandEdgeIsInclusiveAtPointThree() {
        assertEquals(
            listOf(hrvFlat, rhrFlat, "Skin temperature|0|+0.0 °C|vs baseline|near baseline"),
            background(skinTempDev = 0.0),
        )
        assertEquals(
            listOf("Skin temperature|-1|+0.3 °C|vs baseline|near baseline", hrvFlat, rhrFlat),
            background(skinTempDev = 0.3),
        )
        assertEquals(
            listOf("Skin temperature|-1|-0.3 °C|vs baseline|near baseline", hrvFlat, rhrFlat),
            background(skinTempDev = -0.3),
        )
        assertEquals(
            listOf("Skin temperature|-1|+0.3 °C|vs baseline|near baseline", hrvFlat, rhrFlat),
            background(skinTempDev = 0.29999999),
        )
    }

    /** Just outside the band the verdict flips while the DISPLAYED value still rounds to +/-0.3 C. */
    @Test fun skinTempJustOutsideTheBandFlipsTheVerdictNotTheDisplayedValue() {
        assertEquals(
            listOf("Skin temperature|-1|+0.3 °C|vs baseline|warmer than baseline, limiting recovery", hrvFlat, rhrFlat),
            background(skinTempDev = 0.30000001),
        )
        assertEquals(
            listOf("Skin temperature|-1|-0.3 °C|vs baseline|cooler than baseline, limiting recovery", hrvFlat, rhrFlat),
            background(skinTempDev = -0.30000001),
        )
    }

    /** The penalty is symmetric on |deviation|: equal magnitudes cost equal points, opposite words. */
    @Test fun skinTempPenaltyIsSymmetric() {
        assertEquals(
            listOf("Skin temperature|-4|+1.5 °C|vs baseline|warmer than baseline, limiting recovery", hrvFlat, rhrFlat),
            background(skinTempDev = 1.5),
        )
        assertEquals(
            listOf("Skin temperature|-4|-1.5 °C|vs baseline|cooler than baseline, limiting recovery", hrvFlat, rhrFlat),
            background(skinTempDev = -1.5),
        )
    }

    // ── D. both ends of the score range, and a degenerate baseline ───────────

    /** The logistic saturates at 100 and 0, so the swing tops out at +42 / -58 from the z = 0 score. */
    @Test fun scoreRangeEndsAreSaturated() {
        assertEquals(
            listOf("Heart rate variability|42|5000 ms|50 ms baseline|above baseline, supporting recovery", rhrFlat),
            background(hrv = 5000.0),
        )
        assertEquals(
            listOf("Heart rate variability|-58|-5000 ms|50 ms baseline|below baseline, limiting recovery", rhrFlat),
            background(hrv = -5000.0),
        )
    }

    /** A zero-spread baseline yields a saturating z rather than a non-finite one. */
    @Test fun zeroSpreadBaselineSaturatesRatherThanDiverging() {
        assertEquals(
            listOf("Heart rate variability|42|60 ms|50 ms baseline|above baseline, supporting recovery", rhrFlat),
            rows(
                hrv = 60.0, rhr = 55.0, resp = null,
                hrvBaseline = bl(50.0, 0.0), rhrBaseline = rhrBl, respBaseline = null, sleepPerf = null,
            ),
        )
    }

    /**
     * CURRENT behaviour, frozen but NOT endorsed: a non-finite driver value crashes the whole
     * breakdown in roundToInt. Recorded so a port has to make this a decision, not an accident.
     */
    @Test fun nonFiniteInputThrowsToday() {
        listOf<() -> List<String>>(
            { background(hrv = Double.NaN) },
            { background(skinTempDev = Double.NaN) },
        ).forEach { call ->
            val thrown = runCatching { call() }.exceptionOrNull()
            assertTrue("a non-finite driver must not silently produce rows", thrown is IllegalArgumentException)
            assertEquals("Cannot round NaN value.", thrown?.message)
        }
    }

    // ── E. a null for every optional driver ──────────────────────────────────

    @Test fun aNullRestingHrBaselineDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|32|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Sleep quality|2|90%||a strong night, supporting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                "Skin temperature|-1|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = null, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = 0.4, recoveryIndexSlope = -3.0,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    @Test fun aNullRespirationValueDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|27|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|7|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Sleep quality|2|90%||a strong night, supporting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Skin temperature|0|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = null,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = 0.4, recoveryIndexSlope = -3.0,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    @Test fun aNullSleepPerfDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|29|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|7|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                "Skin temperature|-1|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = null, skinTempDev = 0.4, recoveryIndexSlope = -3.0,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    @Test fun aNullSkinTempDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|27|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|6|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Sleep quality|2|90%||a strong night, supporting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = null, recoveryIndexSlope = -3.0,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    @Test fun aNullSlopeDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|28|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|7|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Activity balance|-3|75 effort yesterday|40 baseline|a harder day yesterday, limiting recovery",
                "Sleep quality|2|90%||a strong night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                "Skin temperature|0|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = 0.4, recoveryIndexSlope = null,
                effortBaseline = effortBl, priorDayEffort = 75.0,
            ),
        )
    }

    /** Activity balance needs BOTH the value and its baseline; the baseline alone going null drops it. */
    @Test fun aNullEffortBaselineDropsItsRowAndReweightsTheRest() {
        assertEquals(
            listOf(
                "Heart rate variability|26|62 ms|50 ms baseline|above baseline, supporting recovery",
                "Resting heart rate|6|51 bpm|55 bpm baseline|below baseline, supporting recovery",
                "Recovery index|2|-3.0 bpm/hr|overnight|resting HR fell through the night, supporting recovery",
                "Sleep quality|1|90%||a strong night, supporting recovery",
                "Respiratory rate|1|15.0 br/min|16.0 br/min baseline|below baseline, supporting recovery",
                "Skin temperature|0|+0.4 °C|vs baseline|warmer than baseline, limiting recovery",
            ),
            rows(
                hrv = 62.0, rhr = 51.0, resp = 15.0,
                hrvBaseline = hrvBl, rhrBaseline = rhrBl, respBaseline = respBl,
                sleepPerf = 0.9, skinTempDev = 0.4, recoveryIndexSlope = -3.0,
                effortBaseline = null, priorDayEffort = 75.0,
            ),
        )
    }

    /** With every optional driver null, the lone HRV term carries the whole weight. */
    @Test fun hrvAloneCarriesTheWholeWeight() {
        assertEquals(
            listOf("Heart rate variability|39|62 ms|50 ms baseline|above baseline, supporting recovery"),
            rows(
                hrv = 62.0, rhr = 51.0, resp = null,
                hrvBaseline = hrvBl, rhrBaseline = null, respBaseline = null, sleepPerf = null,
            ),
        )
    }

    // ── F. an unusable HRV baseline changes the composition to nothing ───────

    @Test fun anUnusableHrvBaselineYieldsNoRowsAtAll() {
        listOf(
            BaselineState(50.0, 5.0, 2, 0, BaselineStatus.CALIBRATING),
            BaselineState(50.0, 5.0, 30, 40, BaselineStatus.STALE),
        ).forEach { unusable ->
            assertEquals(
                emptyList<String>(),
                rows(
                    hrv = 62.0, rhr = 51.0, resp = 15.0,
                    hrvBaseline = unusable, rhrBaseline = rhrBl, respBaseline = respBl,
                    sleepPerf = 0.9, skinTempDev = 0.4,
                ),
            )
        }
    }
}
