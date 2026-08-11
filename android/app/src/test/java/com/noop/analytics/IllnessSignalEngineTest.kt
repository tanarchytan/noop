package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of the Swift IllnessSignalEngineTests — identical inputs and expected outputs (parity guard). */
class IllnessSignalEngineTest {

    private val labels = mapOf(
        "restingHR" to "RHR +6",
        "skinTemp" to "skin temp +0.7 °C",
        "hrv" to "HRV −22%",
        "respiration" to "respiration up",
    )

    private fun reading(z: Double) = IllnessSignalEngine.SignalReading(z)

    @Test fun classicThreeSignalPatternRaises() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val r = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        assertEquals(IllnessSignalEngine.Level.RAISED, r.level)
        assertTrue(r.score >= IllnessSignalEngine.raiseThreshold)
        assertEquals(3, r.signalCount)
        assertEquals(listOf("RHR +6", "skin temp +0.7 °C", "HRV −22%"), r.firedSignals)
        assertTrue(r.suppressedBy.isEmpty())
        assertEquals(IllnessSignalEngine.Message.RAISED, r.message)
    }

    @Test fun alcoholTagSuppresses() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val raised = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        val suppressed = IllnessSignalEngine.evaluate(
            inputs, IllnessSignalEngine.Context(alcohol = true), labels)
        assertEquals(IllnessSignalEngine.Level.SUPPRESSED, suppressed.level)
        assertEquals(listOf(IllnessSignalEngine.Confounder.ALCOHOL), suppressed.suppressedBy)
        assertTrue(suppressed.score < raised.score)
        assertEquals(raised.score * IllnessSignalEngine.confounderDampen, suppressed.score, 1e-9)
        assertEquals(IllnessSignalEngine.Message.SUPPRESSED, suppressed.message)
    }

    @Test fun stressSaunaTravelEachDowngradeWithReason() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val stress = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(stress = true), labels)
        assertEquals(IllnessSignalEngine.Level.SUPPRESSED, stress.level)
        assertEquals(listOf(IllnessSignalEngine.Confounder.STRESS), stress.suppressedBy)

        val sauna = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(sauna = true), labels)
        assertEquals(listOf(IllnessSignalEngine.Confounder.SAUNA), sauna.suppressedBy)

        val travel = IllnessSignalEngine.evaluate(
            inputs, IllnessSignalEngine.Context(travelPhaseJump = true), labels)
        assertEquals(listOf(IllnessSignalEngine.Confounder.TRAVEL), travel.suppressedBy)
        assertEquals(IllnessSignalEngine.Message.SUPPRESSED, travel.message)
    }

    @Test fun multipleConfoundersJoinNaturally() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val r = IllnessSignalEngine.evaluate(
            inputs, IllnessSignalEngine.Context(alcohol = true, stress = true), labels)
        assertEquals(
            listOf(IllnessSignalEngine.Confounder.ALCOHOL, IllnessSignalEngine.Confounder.STRESS),
            r.suppressedBy,
        )
    }

    @Test fun alreadyUnwellSwitchesTheMessage() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val r = IllnessSignalEngine.evaluate(
            inputs, IllnessSignalEngine.Context(alreadyUnwell = true), labels)
        assertEquals(IllnessSignalEngine.Level.ALREADY_UNWELL, r.level)
        assertEquals(IllnessSignalEngine.Message.UNWELL_AND_AGREES, r.message)
    }

    @Test fun singleSignalDoesNotRaise() {
        val inputs = IllnessSignalEngine.Inputs(restingHR = reading(4.0))
        val r = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        assertEquals(IllnessSignalEngine.Level.QUIET, r.level)
        assertEquals(1, r.signalCount)
    }

    @Test fun untrustedBaselineStaysSilent() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val r = IllnessSignalEngine.evaluate(
            inputs, IllnessSignalEngine.Context(baselineTrusted = false), labels)
        assertEquals(IllnessSignalEngine.Level.QUIET, r.level)
        assertEquals(IllnessSignalEngine.Message.LEARNING_BASELINE, r.message)
    }

    @Test fun belowThresholdSignalsAreMildNotRaised() {
        val inputs = IllnessSignalEngine.Inputs(restingHR = reading(2.6), skinTemp = reading(2.6))
        val r = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        assertEquals(2, r.signalCount)
        assertEquals(IllnessSignalEngine.Level.MILD, r.level)
        assertTrue(r.score < IllnessSignalEngine.raiseThreshold)
        assertTrue(r.score >= IllnessSignalEngine.mildThreshold)
    }

    @Test fun absentSignalsDoNotCount() {
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2),
            skinTemp = IllnessSignalEngine.SignalReading(9.0, present = false),
            hrv = reading(3.5))
        val r = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        assertEquals(2, r.signalCount)
        assertFalse(r.firedKeys.contains(IllnessSignalEngine.SignalKey.SKIN_TEMP))
    }

    @Test fun eachContextResolvesToItsOwnMessage() {
        // The wording lives in the UI, so what is pinned here is WHICH read fires: the engine may
        // never reach for a message that names a condition, because no such message exists.
        val inputs = IllnessSignalEngine.Inputs(
            restingHR = reading(3.2), skinTemp = reading(3.0), hrv = reading(3.5))
        val expected = listOf(
            IllnessSignalEngine.Context() to IllnessSignalEngine.Message.RAISED,
            IllnessSignalEngine.Context(alcohol = true) to IllnessSignalEngine.Message.SUPPRESSED,
            IllnessSignalEngine.Context(alreadyUnwell = true) to IllnessSignalEngine.Message.UNWELL_AND_AGREES,
        )
        for ((ctx, message) in expected) {
            assertEquals(message, IllnessSignalEngine.evaluate(inputs, ctx, labels).message)
        }
    }

    @Test fun scorePerSignalCapping() {
        val inputs = IllnessSignalEngine.Inputs(restingHR = reading(100.0), skinTemp = reading(2.5))
        val r = IllnessSignalEngine.evaluate(inputs, IllnessSignalEngine.Context(), labels)
        val expectedSkin = IllnessSignalEngine.kZToScore * (2.5 - IllnessSignalEngine.signalZThreshold)
        assertEquals(IllnessSignalEngine.perSignalCap + expectedSkin, r.score, 1e-9)
    }
}
