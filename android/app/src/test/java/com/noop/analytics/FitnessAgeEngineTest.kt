package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Kotlin support that stayed after the VO2max / Fitness-Age scoring math routed to whoop-rs
 * (RustScores): BMI, the PA-index reconstruction, and the readiness checklist. The Nes-2011 VO2max +
 * self-consistent Fitness-Age numbers are now pinned Rust-side in [RustVo2maxParityTest].
 */
class FitnessAgeEngineTest {

    @Test fun bmiHelper() =
        assertEquals(25.249, FitnessAgeEngine.bmi(80.0, 178.0), 1e-3)

    @Test fun paiSedentary() = assertEquals(0.0, FitnessAgeEngine.physicalActivityIndex(0, 0.0, 0.0), 1e-9)
    @Test fun paiHigh() = assertEquals(15.0, FitnessAgeEngine.physicalActivityIndex(7, 75.0, 0.8), 1e-9)
    @Test fun paiModerate() = assertEquals(3.75, FitnessAgeEngine.physicalActivityIndex(3, 40.0, 0.3), 1e-9)

    @Test fun paiFromStrain() {
        assertEquals(0.0, FitnessAgeEngine.physicalActivityIndexFromStrain(0, 0.0), 1e-9)
        assertEquals(15.0, FitnessAgeEngine.physicalActivityIndexFromStrain(7, 90.0), 1e-9)
        assertEquals(3.75, FitnessAgeEngine.physicalActivityIndexFromStrain(3, 45.0), 1e-9)
        assertEquals(5.0, FitnessAgeEngine.physicalActivityIndexFromStrain(4, 60.0), 1e-9)
    }

    // Readiness checklist
    @Test fun readinessAllPresentIsReady() {
        val r = FitnessAgeEngine.assessReadiness(true, true, 7, 7, true, true)
        assertEquals(FitnessAgeConfidence.READY, r.confidence)
        assertTrue(r.canCompute)
        assertTrue(r.items.all { it.status == FitnessReadinessStatus.SATISFIED })
        assertEquals(6, r.items.size)
    }

    @Test fun readinessMissingRhrIsNotReady() {
        val r = FitnessAgeEngine.assessReadiness(true, true, 0, 7, true, true)
        assertEquals(FitnessAgeConfidence.NOT_READY, r.confidence)
        assertFalse(r.canCompute)
        assertEquals(FitnessReadinessStatus.MISSING, r.items.first { it.key == "rhr" }.status)
    }

    @Test fun readinessPartialIsEstimate() {
        val r = FitnessAgeEngine.assessReadiness(true, true, 5, 3, false, false)
        assertEquals(FitnessAgeConfidence.ESTIMATE, r.confidence)
        assertTrue(r.canCompute)
        val body = r.items.first { it.key == "bodyMetrics" }
        assertEquals(FitnessReadinessStatus.MISSING, body.status)
        assertEquals(FitnessReadinessRole.UNLOCKS_VO2MAX, body.role)
        assertFalse(body.required)
    }

    @Test fun readinessMissingAgeIsNotReady() {
        assertEquals(FitnessAgeConfidence.NOT_READY,
            FitnessAgeEngine.assessReadiness(false, true, 7, 7, true, true).confidence)
    }

    @Test fun readinessNoBodyMetricsStillReady() {
        assertEquals(FitnessAgeConfidence.READY,
            FitnessAgeEngine.assessReadiness(true, true, 7, 6, false, false).confidence)
    }
}
