package com.noop.analytics

// FitnessAgeEngine.kt — on-device Fitness Age support: PA-index reconstruction, BMI, and the readiness
// gate. The VO₂max estimate and the self-consistent Fitness Age math now live in whoop-rs (physio-algo,
// via RustScores.vo2maxEstimate / RustScores.fitnessAgeCompute); this object keeps only the inputs the
// FFI still needs from Kotlin plus the readiness checklist the UI shows.
//
// INDEPENDENT implementation of published, peer-reviewed methods (NOT medical advice; a fitness
// comparison, never a "biological age"). Physical-activity index: HUNT1 PA-Q (Kurtze 2008),
// frequency×intensity×duration ∈ [0, 15]; reconstructed from NOOP's measured weekly signals.
object FitnessAgeEngine {

    /** Body-mass index from metric height/weight (used by callers; not required for Fitness Age). */
    fun bmi(weightKg: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        if (m <= 0) return 0.0
        return weightKg / (m * m)
    }

    /** Reconstruct the HUNT PA-index (0–15 = frequency×intensity×duration) from measured weekly
     *  aggregates. Bucket edges mirror the HUNT1 PA-Q response options (Kurtze 2008). */
    fun physicalActivityIndex(activeDaysPerWeek: Int, avgActiveMinutesPerDay: Double,
                              highIntensityFraction: Double): Double {
        val frequency = when {
            activeDaysPerWeek < 1 -> 0.0
            activeDaysPerWeek == 1 -> 0.5
            activeDaysPerWeek == 2 -> 1.0
            activeDaysPerWeek <= 4 -> 2.5
            else -> 5.0
        }
        val intensity = when {
            highIntensityFraction < 0.15 -> 1.0
            highIntensityFraction < 0.5 -> 2.0
            else -> 3.0
        }
        val duration = when {
            avgActiveMinutesPerDay < 15 -> 0.10
            avgActiveMinutesPerDay < 30 -> 0.38
            avgActiveMinutesPerDay < 60 -> 0.75
            else -> 1.0
        }
        if (frequency == 0.0) return 0.0
        return frequency * intensity * duration
    }

    /** PA-index (0–15) from NOOP's measured weekly load — the UNIVERSAL path the orchestrator uses
     *  (strain is computed from HR on any device; zone minutes only exist for CSV-importers). `strain`
     *  already integrates intensity × duration, so map mean active-day strain to the HUNT
     *  intensity×duration product (0–3) and multiply by the frequency factor (no double-counting).
     *  Reference peer (≈4 active days, mean strain ≈60) → PA-index ≈ 5. */
    fun physicalActivityIndexFromStrain(activeDaysPerWeek: Int, meanActiveStrain: Double): Double {
        val frequency = when {
            activeDaysPerWeek < 1 -> 0.0
            activeDaysPerWeek == 1 -> 0.5
            activeDaysPerWeek == 2 -> 1.0
            activeDaysPerWeek <= 4 -> 2.5
            else -> 5.0
        }
        if (frequency == 0.0) return 0.0
        val intensityDuration = (meanActiveStrain / 30.0).coerceIn(0.0, 3.0)
        return frequency * intensityDuration
    }

    // ── Readiness checklist (transparency: which inputs we have, grouped by what each unlocks) ──
    const val minCoverageDays = 4
    const val goodCoverageDays = 6

    /** Nights of resting-HR still needed before the headline can compute AT ALL — the countdown the
     *  not-ready card shows ("N more nights of wear…"). 0 once [minCoverageDays] is met. Assumes continued
     *  nightly wear. Shared with the iOS engine so both platforms show the same number. */
    fun nightsUntilReady(rhrDays: Int): Int = maxOf(0, minCoverageDays - rhrDays)

    private fun coverageStatus(days: Int, floor: Int): FitnessReadinessStatus = when {
        days >= goodCoverageDays -> FitnessReadinessStatus.SATISFIED
        days >= floor || days > 0 -> FitnessReadinessStatus.PARTIAL
        else -> FitnessReadinessStatus.MISSING
    }

    /** Build the readiness checklist + overall confidence. Weight/height/waist sit under the VO₂max
     *  role — they don't move the headline age (body term cancels). */
    fun assessReadiness(hasAge: Boolean, hasSex: Boolean, rhrDays: Int, activityDays: Int,
                        hasHeightWeight: Boolean, hasWaist: Boolean): FitnessAgeReadiness {
        val items = listOf(
            FitnessReadinessItem("age", "Your age",
                if (hasAge) FitnessReadinessStatus.SATISFIED else FitnessReadinessStatus.MISSING,
                required = true, role = FitnessReadinessRole.DRIVES_AGE,
                detail = if (hasAge) "Set" else "Add it in Settings"),
            FitnessReadinessItem("sex", "Biological sex",
                if (hasSex) FitnessReadinessStatus.SATISFIED else FitnessReadinessStatus.MISSING,
                required = true, role = FitnessReadinessRole.DRIVES_AGE,
                detail = if (hasSex) "Set" else "Add it in Settings"),
            FitnessReadinessItem("rhr", "Resting heart rate",
                coverageStatus(rhrDays, minCoverageDays), required = true,
                role = FitnessReadinessRole.DRIVES_AGE, detail = "$rhrDays of last 7 nights"),
            FitnessReadinessItem("activity", "Recent activity",
                coverageStatus(activityDays, minCoverageDays), required = false,
                role = FitnessReadinessRole.DRIVES_AGE, detail = "$activityDays of last 7 days"),
            FitnessReadinessItem("bodyMetrics", "Height & weight",
                if (hasHeightWeight) FitnessReadinessStatus.SATISFIED else FitnessReadinessStatus.MISSING,
                required = false, role = FitnessReadinessRole.UNLOCKS_VO2MAX,
                detail = if (hasHeightWeight) "Unlocks your VO₂max" else "Add to also see VO₂max"),
            FitnessReadinessItem("waist", "Waist (optional)",
                if (hasWaist) FitnessReadinessStatus.SATISFIED else FitnessReadinessStatus.MISSING,
                required = false, role = FitnessReadinessRole.UNLOCKS_VO2MAX,
                detail = if (hasWaist) "Sharpens VO₂max" else "Optional - sharpens VO₂max"),
        )
        val confidence = when {
            !hasAge || !hasSex || rhrDays < minCoverageDays -> FitnessAgeConfidence.NOT_READY
            rhrDays >= goodCoverageDays && activityDays >= goodCoverageDays -> FitnessAgeConfidence.READY
            else -> FitnessAgeConfidence.ESTIMATE
        }
        return FitnessAgeReadiness(items, confidence)
    }
}

enum class FitnessReadinessStatus { SATISFIED, PARTIAL, MISSING }
enum class FitnessReadinessRole { DRIVES_AGE, UNLOCKS_VO2MAX }
enum class FitnessAgeConfidence { READY, ESTIMATE, NOT_READY }

data class FitnessReadinessItem(
    val key: String,
    val label: String,
    val status: FitnessReadinessStatus,
    val required: Boolean,
    val role: FitnessReadinessRole,
    val detail: String,
)

data class FitnessAgeReadiness(
    val items: List<FitnessReadinessItem>,
    val confidence: FitnessAgeConfidence,
) {
    val canCompute: Boolean get() = confidence != FitnessAgeConfidence.NOT_READY
}
