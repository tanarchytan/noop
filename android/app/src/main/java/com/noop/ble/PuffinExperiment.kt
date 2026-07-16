package com.noop.ble

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in switch for the EXPERIMENTAL WHOOP 5.0/MG ("puffin") protocol probes.
 *
 * Direct port of the macOS `PuffinExperiment` (Strand/BLE/PuffinExperiment.swift). Live HR on a
 * 5/MG strap already works over the standard profile after CLIENT_HELLO. These probes go further —
 * sending puffin-framed commands (e.g. asking the strap to start its realtime stream) to learn what
 * a real 5/MG strap responds to. They are guesses, so they are OFF by default and only ever written
 * to the puffin command characteristic (fd4b0002). A 5/MG owner can flip this on under Settings →
 * Experimental to help map the protocol; everyone else is unaffected. It never touches WHOOP 4.0.
 *
 * The macOS app stored this in `UserDefaults` under the key `noopPuffinExperiments`; the Android
 * equivalent is [SharedPreferences]. The same key name is reused for parity.
 */
class PuffinExperiment(private val prefs: SharedPreferences) {

    /** True if the user opted in to the WHOOP 5/MG protocol probes (default false). */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) = prefs.edit().putBoolean(KEY, v).apply()

    /** True if the user opted in to recording raw 5/MG backfill frames to a shareable JSONL file
     *  (default false). SEPARATE from [isEnabled]: probes SEND commands at the strap; capture only
     *  RECORDS what arrives — different risk profiles, so different switches. (#78 fork) */
    var isCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE, false)
        set(v) = prefs.edit().putBoolean(KEY_CAPTURE, v).apply()

    /** True if the user opted in to the RUST SHADOW decode (default false). When on, each offload / live /
     *  command-response frame is decoded a SECOND time through the whoop-rs FFI and diffed field-by-field
     *  against the authoritative Kotlin decode (results in [com.noop.protocol.RustShadowParity]); the shadow
     *  never changes a stored value. Off = zero extra work and the native codec is never loaded. */
    var isRustShadowEnabled: Boolean
        get() = prefs.getBoolean(KEY_RUST_SHADOW, false)
        set(v) = prefs.edit().putBoolean(KEY_RUST_SHADOW, v).apply()

    /** True if the user opted in to the RUST PRIMARY decode (default false). When on, whoop-rs is the
     *  AUTHORITATIVE writer for the stored history / live / response / event / ppg rows; the Kotlin decode
     *  still runs but only feeds the comparator ([com.noop.protocol.RustShadowParity], which tags each
     *  per-field delta EXPECTED-vs-UNEXPECTED). Any Rust error / native-load failure falls back to the
     *  Kotlin decode for that frame so no data is lost. Off = today's Kotlin behavior, native codec unloaded. */
    var isRustPrimaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_RUST_PRIMARY, false)
        set(v) = prefs.edit().putBoolean(KEY_RUST_PRIMARY, v).apply()

    /** True if the user opted in to the WHOOP 5/MG "R22" deep-data unlock — the one probe that WRITES
     *  a persistent feature flag to the strap (the `enable_r22_*` SET_CONFIG sequence). Kept distinct
     *  from [isEnabled] because it changes strap state; reversible, default false. Mirrors the macOS
     *  `PuffinExperiment.deepDataKey`. Driven only from `WhoopBleClient.enableWhoop5DeepData()`. (#174) */
    var isDeepDataEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEEP_DATA, false)
        set(v) = prefs.edit().putBoolean(KEY_DEEP_DATA, v).apply()

    /** True if the user opted in to "Broadcast heart rate": NOOP writes the device-config flag
     *  whoop_live_hr_in_adv_ind_pkt="1" so the strap advertises the standard Heart Rate Service
     *  (0x180D) + its live HR, pairable by a Garmin/Zwift/gym HR client. Reversible. Default false.
     *  Mirrors the macOS `PuffinExperiment.broadcastHrKey`. (#181) */
    var broadcastHr: Boolean
        get() = prefs.getBoolean(KEY_BROADCAST_HR, false)
        set(v) = prefs.edit().putBoolean(KEY_BROADCAST_HR, v).apply()

    /** Sleep staging always runs [com.noop.analytics.SleepStagerV2] (the transparent cardiorespiratory
     *  recipe) over already-detected sleep windows. V2 was promoted to the sole staging engine after a
     *  44-subject cross-subject benchmark (AAUWSS + Walch sleep-accel, leave-one-subject-out) showed it
     *  strictly dominates V1 (kappa 0.35 vs 0.03, deep recall 55% vs 1%). noop-tan hardwires it on and
     *  drops the old experimental opt-out — a pure analysis switch (detection + scoring unchanged),
     *  model-agnostic (WHOOP 4 and 5/MG). */
    val experimentalSleepV2: Boolean get() = true

    /** HR-from-PPG sub-lag interpolation always runs: the v26 optical-PPG gap-fill HR estimator
     *  ([com.noop.protocol.PpgHr]) refines its integer autocorrelation lag with a parabolic (Variant A)
     *  interpolation of the ACF peak, removing the ~+-8 bpm lag-quantization near a high HR. It only ever
     *  fills seconds the strap never reported an HR for (NEVER overrides a WHOOP-stored HR). The pure
     *  [com.noop.protocol.PpgHr] package can't read prefs, so this is threaded from the app-layer call site
     *  (Backfiller / archive replay / capture import). noop-tan hardwires it on (no experimental toggle). */
    val ppgHrSubLagInterp: Boolean get() = true

    /** Motion-aware wake refinement (#364 "Proposal 2") always runs: a post-pass
     *  ([com.noop.analytics.WakeMotionRefinement]) over the already-staged hypnogram that reclassifies a
     *  scored WAKE segment to `light` when its per-minute step-tick cadence shows no locomotion AND its
     *  per-minute gravity posture stays stable (isolated "turn-over" burst minutes are kept as wake). It
     *  self-gates on the OBSERVED gravity + step density (never on strap family): a WHOOP 4.0 night (sparse
     *  gravity, no step stream) fails the gate untouched; a 5.0/MG night is the beneficiary. Pure analysis
     *  switch — only ever SHRINKS an already-scored wake segment, never invents wake; detection + V2 staging
     *  untouched. noop-tan hardwires it on (no experimental toggle); it complements the kept #348 tune by
     *  paring back its HR-led over-awake on dense 5/MG nights. */
    val motionAwareWake: Boolean get() = true

    companion object {
        /** Persisted preferences file. */
        private const val PREFS = "noop_experiments"

        /** Shared key name with the macOS build (`PuffinExperiment.defaultsKey`). */
        const val KEY = "noopPuffinExperiments"

        /** 5/MG raw backfill capture (research aid for the puffin biometric decode). */
        const val KEY_CAPTURE = "noopWhoop5Capture"

        /** 5/MG R22 deep-data unlock opt-in (mirrors macOS `PuffinExperiment.deepDataKey`). */
        const val KEY_DEEP_DATA = "noopWhoop5DeepData"

        /** Rust shadow-decode parity diff opt-in (Android-only; whoop-rs FFI cross-check). */
        const val KEY_RUST_SHADOW = "noopRustShadow"

        /** Rust PRIMARY-decode opt-in (Android-only; whoop-rs FFI is the authoritative writer). */
        const val KEY_RUST_PRIMARY = "noopRustPrimary"

        /** "Broadcast heart rate" opt-in (mirrors macOS `PuffinExperiment.broadcastHrKey`). */
        const val KEY_BROADCAST_HR = "noopBroadcastHr"

        fun from(context: Context): PuffinExperiment =
            PuffinExperiment(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
