package com.noop.analytics


// Vitality: 0-100 wellness score + optional Body Age in years.
//
// The app-shaped door over the whoop-rs vitality model: each wearable input maps to its published
// all-cause-mortality log-hazard, the sum is overlap-corrected and divided by the Gompertz slope to
// become a "years of ageing" offset. Body Age = chronological age + that offset; average = own age,
// healthier = younger. The maths and coefficients live in whoop-rs.
// A wellness comparison, never a clinical biological age.
object VitalityEngine {

    const val minBodyAge = 20.0
    const val maxBodyAge = 90.0
    const val minFactors = 3
    const val bandYears = 5.0

    data class Inputs(
        val chronoAge: Double,
        val restingHR: Double? = null,
        val vo2max: Double? = null,
        val expectedVO2max: Double? = null,
        val sleepHours: Double? = null,
        /** The real Sleep Regularity Index (-100..100). Preferred over [sleepConsistency]. */
        val sleepRegularityIndex: Double? = null,
        /** Duration-regularity fallback; used only when the index could not be computed. */
        val sleepConsistency: Double? = null,
        val rmssd: Double? = null,
        val rmssdNorm: Double? = null,
        val steps: Double? = null,
    )

    data class Contribution(val key: String, val label: String, val lnHazard: Double)

    data class Result(
        val vitality: Double,
        val bodyAge: Double,
        val chronoAge: Double,
        /** Body Age minus chronological: POSITIVE = older than your years, negative = younger. Same
         *  convention as the Rust `rhythm_age` and `fitness_age` advances, so the three read alike. */
        val advanceYears: Double,
        val bandYears: Double,
        val contributions: List<Contribution>,
        val factorsUsed: Int,
    )

    /** Nocturnal RMSSD ~50th-pct by age (ms). A person at the age norm contributes zero hazard. */
    fun rmssdNorm(forAge: Double): Double = RustScores.vitalityRmssdNorm(forAge)

    /** Sleep regularity (0-1) from nightly sleep durations (hours). Under three nights -> null. */
    fun sleepConsistency(nightlyHours: List<Double>): Double? =
        RustScores.vitalitySleepConsistency(nightlyHours)

    /** Each present driver's signed log-hazard, without the three-driver gate. */
    fun contributions(inputs: Inputs): List<Contribution> = RustScores.vitalityContributions(inputs)

    /** Full Vitality + Body Age. Null until at least [minFactors] drivers are present. */
    fun compute(inputs: Inputs): Result? = RustScores.vitality(inputs)
}
