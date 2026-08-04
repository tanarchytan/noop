package com.noop.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * ECG capture and rhythm-screen storage, added as the v101 -> v102 additive migration.
 *
 * Every table here stores RAW COUNTS plus the parameters needed to interpret them, never millivolts:
 * counts-per-mV is unknown, so a stored mV would be wrong by an unknown factor, while raw counts plus
 * provenance calibrate by re-derivation once the constant is known.
 *
 * Every number is computed in whoop-rs; Kotlin decides when to ask and what to keep. Wellness, never
 * medical. Nothing writes these tables yet — capture is not implemented.
 */

/**
 * One ECG capture as the strap sent it. PK = [id].
 *
 * [samples] is the raw count stream verbatim, one blob rather than a row per sample. [sampleRateHz] is
 * the rate used and [sampleRateSource] is where it came from, so a measured rate and an assumed one
 * stay distinguishable.
 */
@Entity(tableName = "ecgSession", indices = [Index(value = ["deviceId", "startUnix"])])
data class EcgSessionRow(
    @PrimaryKey
    val id: String,
    val deviceId: String,
    /** Capture start, wall-clock unix seconds. */
    val startUnix: Long,
    val durationMs: Long,
    val sampleCount: Int,
    /** Raw counts, i32 little-endian. Never millivolts. */
    val samples: ByteArray,
    /** The rate every time-domain figure derived from this capture is conditional on. */
    val sampleRateHz: Double,
    /** How [sampleRateHz] was arrived at: `measured-mains` / `measured-ppg` / `firmware-code` /
     *  `assumed`. */
    val sampleRateSource: String,
    /** Counts per millivolt. Null means UNCALIBRATED; a reader must not substitute a default. */
    val countsPerMv: Double? = null,
    /** `firmware` / `user-supplied`, or null while [countsPerMv] is null. */
    val countsPerMvSource: String? = null,
    /** The reading rule the decode sweep converged on — width, signedness, bit order, start bit,
     *  stride — serialised as whoop-rs returned it. */
    val layoutJson: String? = null,
    /** The winning candidate's ranking scalar. */
    val sweepQuality: Double? = null,
    /** Its margin over the best genuinely different answer, stored beside [sweepQuality] and never
     *  folded into it. */
    val sweepMargin: Double? = null,
    /** Per-span electrode-contact flags. A bad span stays marked, never interpolated. */
    val leadOffJson: String? = null,
    /** Left / right, as the strap reported it. */
    val wrist: String? = null,
    val firmwareVersion: String? = null,
    /** The hardware-revision string this capture came off, kept here as well as on the device row so a
     *  capture stays interpretable after its device is removed. */
    val hardwareRev: String? = null,
    /** The variant whoop-rs classified [hardwareRev] as; the prefix table stays in Rust. */
    val strapVariant: String? = null,
) {
    // ByteArray needs structural equals/hashCode (the generated identity ones break round-trip asserts).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgSessionRow) return false
        return id == other.id && deviceId == other.deviceId && startUnix == other.startUnix &&
            durationMs == other.durationMs && sampleCount == other.sampleCount &&
            samples.contentEquals(other.samples) && sampleRateHz == other.sampleRateHz &&
            sampleRateSource == other.sampleRateSource && countsPerMv == other.countsPerMv &&
            countsPerMvSource == other.countsPerMvSource && layoutJson == other.layoutJson &&
            sweepQuality == other.sweepQuality && sweepMargin == other.sweepMargin &&
            leadOffJson == other.leadOffJson && wrist == other.wrist &&
            firmwareVersion == other.firmwareVersion && hardwareRev == other.hardwareRev &&
            strapVariant == other.strapVariant
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + startUnix.hashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + sampleRateHz.hashCode()
        return result
    }
}

/**
 * One rhythm reading off the R-R path, which needs beats and not an electrode, so every strap reaches
 * it. [scope] names which level the row is — whoop-rs reports three and they are not interchangeable.
 *
 * Each index is its own column, so a moved threshold re-judges stored rows. [algoVersion] is part of
 * the key, so a re-run lands beside the original instead of replacing it.
 */
@Entity(
    tableName = "rhythmScreen",
    primaryKeys = ["deviceId", "startUnix", "scope", "algoVersion"],
    indices = [Index(value = ["deviceId", "startUnix"])],
)
data class RhythmScreenRow(
    val deviceId: String,
    val startUnix: Long,
    /** `run` (one whole screen) / `episode` (one reported stretch) / `segment` (one assessed window). */
    val scope: String,
    /** The whoop-rs version stamp that produced every number on this row. */
    val algoVersion: String,
    val durationS: Long,
    /** On a `run`: `Calibrating` / `Regular` / `IrregularEpisodes` / `Inconclusive`. On a `segment`:
     *  `Assessed` / `Inconclusive`. Null on an `episode`, which carries no verdict of its own. */
    val verdict: String? = null,
    /** The measured input condition behind a declined reading, so a refusal stays legible. */
    val refusal: String? = null,
    val windowsAssessed: Int? = null,
    val windowsIrregular: Int? = null,
    /** Consecutive windows in an `episode` row's run. */
    val episodeWindows: Int? = null,
    /** `Low` / `Moderate` / `High` on an `episode`. Stored as the name: whoop-rs defines it as an
     *  ordering, and a number here would read as a probability. */
    val confidence: String? = null,
    val cosen: Double? = null,
    /** The same index with the premature beats removed, stored beside [cosen] and never instead of it. */
    val residualCosen: Double? = null,
    val rmssdOverMean: Double? = null,
    val shannonEntropy: Double? = null,
    val turningPointRatio: Double? = null,
    val sampleEntropy: Double? = null,
    val sd1: Double? = null,
    val sd2: Double? = null,
    val cellOccupancy: Double? = null,
    val ectopicFraction: Double? = null,
    val meanRrMs: Double? = null,
    /** Beats the indices ran over, and beats the range filter dropped — the cleaning cost an index is
     *  meaningless without. */
    val beatsUsed: Int? = null,
    val beatsRejected: Int? = null,
    /** Exact `(second, value)` repeats, as a share of input beats. */
    val duplicateFraction: Double? = null,
    /** The rescaled-copy share, which an exact-repeat count cannot see. whoop-rs refuses such a series
     *  rather than scoring it. */
    val rescaledFraction: Double? = null,
    /** Beat-time over elapsed time; above 1.0 the beats cannot all be real. */
    val coverage: Double? = null,
)

/**
 * Waveform morphology over one [EcgSessionRow] — the ECG path, so MG only. Never fused with
 * [RhythmScreenRow]: one needs an electrode and one does not, and a combined number would change
 * meaning with the strap worn.
 *
 * PK (ecgSessionId, algoVersion): a re-analysis of a stored capture adds a row.
 */
@Entity(
    tableName = "rhythmMorphology",
    primaryKeys = ["ecgSessionId", "algoVersion"],
    indices = [Index(value = ["ecgSessionId"])],
)
data class RhythmMorphologyRow(
    /** The capture this reading was made over ([EcgSessionRow.id]). */
    val ecgSessionId: String,
    val algoVersion: String,
    /** The rate every time-domain figure below is conditional on; a re-analysis at a corrected rate is
     *  a different reading of the same samples. */
    val fsHz: Double,
    val beats: Int,
    /** Three-way, never a boolean: `Present` / `Absent` / `Indeterminate`. A wave under the noise floor
     *  is not a wave that is absent, and `Absent` is a positive claim about a quiet ensemble. */
    val pWaveFinding: String,
    /** Which limit an `Indeterminate` finding hit. */
    val pWaveLimit: String? = null,
    val pWaveBeatsExamined: Int? = null,
    val pWaveBeatsExcluded: Int? = null,
    val pWavePresentFraction: Double? = null,
    val pWaveConsistency: Double? = null,
    /** Peak ensemble deflection over median R amplitude — a ratio, so it needs no amplitude scale. */
    val pWaveAmplitudeRatio: Double? = null,
    val pWaveNoiseRatio: Double? = null,
    /** Share of the evidence budget the reading had, capped at 1.0. Not a probability. */
    val pWaveConfidence: Double? = null,
    /** The 4-9 Hz share of the quiet segment between beats. Carries no threshold; reported only. */
    val atrialBandRatio: Double? = null,
    val atrialBandSegments: Int? = null,
    val atrialBandMedianSegmentMs: Double? = null,
    val atrialBandConfidence: Double? = null,
    val atrialBandLimit: String? = null,
    /** Mean leave-one-out correlation of each beat against the average of the others. */
    val beatTemplateCorrelation: Double? = null,
    val beatTemplateBeats: Int? = null,
    val beatTemplateConfidence: Double? = null,
    /** The signal-quality indices, each on its own. [bExcess] is the part of the detector agreement
     *  detection density does not explain. */
    val bSqi: Double? = null,
    val bExcess: Double? = null,
    val kSqi: Double? = null,
    val pSqi: Double? = null,
    val basSqi: Double? = null,
)
