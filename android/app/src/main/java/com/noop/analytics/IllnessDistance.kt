package com.noop.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Alternative multivariate illness-anomaly distance (Mahalanobis). PARALLEL PATH, not the default
 * scorer - IllnessSignalEngine's per-signal z-sum + corroboration + confounder suppression stays
 * as-is; this measures how far today's 4-signal vector sits from personal baseline, behind an
 * explicit flag, so the UI lane can A/B it without touching the live alert path.
 *
 *     D^2 = (x - mu)^T * C^-1 * (x - mu)
 *
 * The four illness signals (RHR up, RMSSD down, skin-temp up, respiration up) are CORRELATED, so
 * z-scored features (mu = 0, covariance = the personal correlation matrix C) with C^-1 discount the
 * shared variance instead of double-counting it: two correlated signals moving together count as ONE
 * coordinated move. D is in SD-equivalent units, so threshold 2.5 matches the per-signal z~=2 gate.
 * Fires only when D > distanceThreshold AND >= minDeviatingFeatures are themselves illness-ward. The
 * correlation inverse is Gauss-Jordan; a singular matrix falls back to the DIAGONAL inverse rather
 * than producing NaNs. Pure, deterministic, DB-free, APPROXIMATE, non-clinical.
 */
object IllnessDistance {

    const val distanceThreshold: Double = 2.5
    const val minDeviatingFeatures: Int = 2
    const val featureZThreshold: Double = 2.0
    const val ridge: Double = 1e-6

    /**
     * The four illness signals, in fixed order, each as an illness-ORIENTED z (positive = more illness-like:
     * RHR up, skin-temp up, respiration up pass raw z; RMSSD down passes the NEGATED z). null = absent this
     * window (dropped from the distance, never counted as deviating).
     */
    data class FeatureVector(
        val restingHR: Double? = null,
        val rmssd: Double? = null,      // already negated by the caller: positive == drop == illness-ward
        val skinTemp: Double? = null,
        val respiration: Double? = null,
    ) {
        /** Present coordinates in fixed order (restingHR, rmssd, skinTemp, respiration). */
        fun present(): List<Double> = listOfNotNull(restingHR, rmssd, skinTemp, respiration)
    }

    data class Result(
        val distance: Double,
        val deviatingFeatures: Int,
        val fires: Boolean,
        val usedDiagonalFallback: Boolean,
    )

    /**
     * Mahalanobis distance of today's z-vector from baseline. Features are already z-scored, so the
     * mean is 0 and the covariance is the personal correlation matrix. [correlation] is the NxN matrix
     * over present features in fixed order; null means identity, so D is the Euclidean norm.
     */
    fun evaluate(features: FeatureVector, correlation: List<List<Double>>? = null): Result {
        val x = features.present()
        val k = x.size
        if (k == 0) return Result(0.0, 0, fires = false, usedDiagonalFallback = false)

        var deviating = 0
        for (v in x) if (v >= featureZThreshold) deviating++

        val suppliedCorrelation =
            correlation != null && correlation.size == k && correlation.all { it.size == k }
        val corr: Array<DoubleArray> =
            if (suppliedCorrelation) {
                Array(k) { i -> DoubleArray(k) { j -> correlation!![i][j] } }
            } else {
                identity(k)
            }
        // Ridge the diagonal for conditioning, but only on a supplied correlation. The identity (null)
        // case must invert to itself exactly so D equals the Euclidean norm to full precision - a
        // Tikhonov term there would shrink it by ~ridge and break that contract.
        if (suppliedCorrelation) for (i in 0 until k) corr[i][i] += ridge

        val (inv, fellBack) = invertOrDiagonal(corr)

        var d2 = 0.0
        for (i in 0 until k) {
            var rowDot = 0.0
            for (j in 0 until k) rowDot += inv[i][j] * x[j]
            d2 += x[i] * rowDot
        }
        if (d2 < 0) d2 = 0.0
        val distance = sqrt(d2)

        val fires = distance > distanceThreshold && deviating >= minDeviatingFeatures
        return Result(distance, deviating, fires, fellBack)
    }

    // ── Linear algebra (Gauss-Jordan with diagonal fallback) ──

    internal fun identity(n: Int): Array<DoubleArray> =
        Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

    /**
     * Invert a square matrix by Gauss-Jordan with partial pivoting. Returns (inverse, fellBack=false) on
     * success; on a (near-)singular pivot returns (diagonalInverse, fellBack=true), with any non-positive
     * diagonal entry mapped to 1 (identity in that coordinate) so the result is always finite.
     */
    internal fun invertOrDiagonal(a: Array<DoubleArray>): Pair<Array<DoubleArray>, Boolean> {
        val n = a.size
        val m = Array(n) { DoubleArray(2 * n) }
        for (i in 0 until n) {
            for (j in 0 until n) m[i][j] = a[i][j]
            m[i][n + i] = 1.0
        }
        val eps = 1e-12
        for (col in 0 until n) {
            var pivotRow = col
            var pivotMag = abs(m[col][col])
            for (r in (col + 1) until n) {
                if (abs(m[r][col]) > pivotMag) {
                    pivotMag = abs(m[r][col]); pivotRow = r
                }
            }
            if (pivotMag < eps) {
                return Pair(diagonalInverse(a), true)
            }
            if (pivotRow != col) {
                val tmp = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmp
            }
            val pivot = m[col][col]
            for (j in 0 until 2 * n) m[col][j] /= pivot
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col]
                if (factor == 0.0) continue
                for (j in 0 until 2 * n) m[r][j] -= factor * m[col][j]
            }
        }
        val inv = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) inv[i][j] = m[i][n + j]
        return Pair(inv, false)
    }

    /**
     * Diagonal inverse: 1/diag on the diagonal, zeros elsewhere. A non-positive diagonal entry maps to 1
     * (identity in that coordinate) so the fallback is always finite.
     */
    internal fun diagonalInverse(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val inv = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            val d = a[i][i]
            inv[i][i] = if (d > 1e-12) 1.0 / d else 1.0
        }
        return inv
    }
}
