package com.noop.ble

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in switch for the EXPERIMENTAL WHOOP 5.0/MG ("puffin") protocol probes.
 *
 * Live HR on a 5/MG strap already works over the standard profile after CLIENT_HELLO. These probes go
 * further — sending puffin-framed commands (e.g. asking the strap to start its realtime stream) to
 * learn what a real 5/MG strap responds to. They are guesses, so they are off by default and only ever
 * written to the puffin command characteristic (fd4b0002). A 5/MG owner can flip this on under
 * Settings → Experimental to help map the protocol; everyone else is unaffected. It never touches
 * WHOOP 4.0.
 *
 * Backed by [SharedPreferences] under the key [KEY].
 */
class PuffinExperiment(private val prefs: SharedPreferences) {

    /** True if the user opted in to the WHOOP 5/MG protocol probes (default false). */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) = prefs.edit().putBoolean(KEY, v).apply()

    /** True if the user opted in to recording raw 5/MG backfill frames to a shareable JSONL file
     *  (default false). Separate from [isEnabled]: probes send commands at the strap, capture only
     *  records what arrives — different risk profiles, different switches. */
    var isCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE, false)
        set(v) = prefs.edit().putBoolean(KEY_CAPTURE, v).apply()

    /** True if the user opted in to the WHOOP 5/MG "R22" deep-data unlock — the one probe that writes a
     *  persistent feature flag to the strap (`enable_r22_*` SET_CONFIG sequence). Distinct from [isEnabled]
     *  since it changes strap state (reversible, default false); driven only from `enableWhoop5DeepData()`. */
    var isDeepDataEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEEP_DATA, false)
        set(v) = prefs.edit().putBoolean(KEY_DEEP_DATA, v).apply()

    /** HR-from-PPG sub-lag interpolation always runs: refines the v26 optical-PPG HR estimator's integer
     *  autocorrelation lag with a parabolic interpolation of the ACF peak, removing ~+-8 bpm lag-quantization
     *  near a high HR. Only fills seconds the strap never reported an HR for; never overrides a stored HR. */
    val ppgHrSubLagInterp: Boolean get() = true

    companion object {
        /** Persisted preferences file. */
        private const val PREFS = "noop_experiments"

        /** Preferences key for [isEnabled]. */
        const val KEY = "noopPuffinExperiments"

        /** 5/MG raw backfill capture (research aid for the puffin biometric decode). */
        const val KEY_CAPTURE = "noopWhoop5Capture"

        /** 5/MG R22 deep-data unlock opt-in. */
        const val KEY_DEEP_DATA = "noopWhoop5DeepData"

        fun from(context: Context): PuffinExperiment =
            PuffinExperiment(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
