package com.noop.ui

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.ui.res.stringResource
import com.noop.R
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.Baselines
import com.noop.analytics.Zones
import com.noop.ble.PuffinExperiment
import com.noop.ble.WhoopModel
import kotlin.math.roundToInt

// MARK: - Settings (ported from Strand/Screens/SettingsView.swift)
//
// Profile (the numbers that power HR zones / calories / recovery baselines), a
// Backup & restore section wiring DataBackup export/import through the Storage
// Access Framework, and an About section with version + attribution + a Support
// link. Re-skinned to the locked NOOP component system: every surface is a
// NoopCard, every status uses StatePill, the two-column form feel is preserved.
//
// macOS parity notes:
//  - macOS persisted the profile in a ProfileStore (ObservableObject on disk). The
//    Android equivalent is SharedPreferences; this screen owns the only profile
//    store in the app, so HealthScreen's age-agnostic HR-max default can later read
//    from it. Values persist immediately on every change.
//  - macOS used native +/- Steppers; Compose has no Stepper, so each numeric field
//    is a tabular value flanked by round −/+ buttons (same intent, same ranges).
//  - The strap "Re-scan / Disconnect" controls map to the ViewModel's connect() /
//    disconnect() pass-throughs.
//  - Backup export/import run through SAF (CreateDocument / OpenDocument); the macOS
//    alert is mirrored by a Toast. DataBackup.exportTo already checkpoints the WAL,
//    so no separate repo checkpoint call is needed.

// MARK: - Profile store (SharedPreferences-backed; the macOS ProfileStore equivalent)

/**
 * The user's body profile — age / sex / weight / height plus an optional manual
 * HR-max override. Persisted to SharedPreferences so the values survive restarts
 * and other screens (HealthScreen, Coach zones) can read the same source of truth.
 *
 * Mirrors the macOS `ProfileStore` fields and ranges exactly. `hrMaxOverride == 0`
 * means "auto" — fall back to the Tanaka estimate from [age].
 */
class ProfileStore(private val prefs: SharedPreferences) {

    /**
     * Current age in whole years (#146), DERIVED from [dateOfBirthMillis] so it advances on its own
     * instead of going stale until the user bumps a number. Read-only; change age via [setAge] (the
     * +/- stepper) or [dateOfBirthMillis] directly. Every existing reader (Fitness Age / Vitality /
     * Tanaka) keeps reading `profile.age` unchanged.
     */
    val age: Int
        get() = yearsFromDob(dateOfBirthMillis).coerceIn(AGE_MIN, AGE_MAX)

    /**
     * Date of birth as epoch millis — the canonical source of truth for [age] (#146). The getter
     * lazily migrates a pre-#146 stored age (or a restored legacy `age`, see [applyBackup]) into an
     * anchored DOB the first time it's read, then persists it so the derivation is stable. The setter
     * mirrors the derived Int age under the legacy [KEY_AGE] so the `.noopbak` backup whitelist keeps
     * exporting an age with no change to the cross-platform contract.
     */
    var dateOfBirthMillis: Long
        get() {
            if (prefs.contains(KEY_DOB)) return prefs.getLong(KEY_DOB, 0L)
            val legacyAge = (if (prefs.contains(KEY_AGE)) prefs.getInt(KEY_AGE, 30) else 30)
                .coerceIn(AGE_MIN, AGE_MAX)
            val dob = dobForAge(legacyAge)
            prefs.edit().putLong(KEY_DOB, dob).putInt(KEY_AGE, legacyAge).apply()
            return dob
        }
        set(v) = prefs.edit()
            .putLong(KEY_DOB, v)
            .putInt(KEY_AGE, yearsFromDob(v).coerceIn(AGE_MIN, AGE_MAX))
            .apply()

    /** Set age by anchoring a date of birth `years` before today (the +/- stepper and backup restore
     *  both go through here, so age always flows from a DOB). Clamped to [AGE_MIN]..[AGE_MAX]. */
    fun setAge(years: Int) { dateOfBirthMillis = dobForAge(years.coerceIn(AGE_MIN, AGE_MAX)) }

    /** "male" | "female" | "nonbinary" — matches the macOS tag values. */
    var sex: String
        get() = prefs.getString(KEY_SEX, "male") ?: "male"
        set(v) = prefs.edit().putString(KEY_SEX, v).apply()

    var weightKg: Double
        get() = prefs.getFloat(KEY_WEIGHT, 75f).toDouble().coerceIn(WEIGHT_MIN, WEIGHT_MAX)
        set(v) = prefs.edit().putFloat(KEY_WEIGHT, v.coerceIn(WEIGHT_MIN, WEIGHT_MAX).toFloat()).apply()

    var heightCm: Double
        get() = prefs.getFloat(KEY_HEIGHT, 178f).toDouble().coerceIn(HEIGHT_MIN, HEIGHT_MAX)
        set(v) = prefs.edit().putFloat(KEY_HEIGHT, v.coerceIn(HEIGHT_MIN, HEIGHT_MAX).toFloat()).apply()

    /**
     * Waist circumference in cm; 0 = unset (the Fitness Age VO₂max estimate is hidden until a waist
     * is entered). Optional — it only unlocks the VO₂max read-out and never moves the headline Fitness
     * Age (the engine's body term cancels). No coercion floor (0 has to remain a sentinel for "unset");
     * the upper bound is clamped so a fat-fingered entry can't run away.
     */
    var waistCm: Double
        get() = prefs.getFloat(KEY_WAIST, 0f).toDouble().coerceIn(0.0, WAIST_MAX)
        set(v) = prefs.edit().putFloat(KEY_WAIST, v.coerceIn(0.0, WAIST_MAX).toFloat()).apply()

    /** Manual max-heart-rate override in bpm; 0 = automatic (Tanaka). */
    var hrMaxOverride: Int
        get() = prefs.getInt(KEY_HRMAX, 0).coerceIn(0, 230)
        set(v) = prefs.edit().putInt(KEY_HRMAX, v.coerceIn(0, 230)).apply()

    /**
     * Step-calibration divisor (#139/#132): counter ticks per real step for the @57 motion
     * counter. 1.0 = raw pass-through (default — no behavior change). Clamped 0.5–30.0
     * (WHOOP 5/MG motion-counter overcount can reach ~24×, so the ceiling has to be high).
     */
    var stepTicksPerStep: Double
        get() = prefs.getFloat(KEY_STEP_SCALE, 1f).toDouble().coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX)
        set(v) = prefs.edit()
            .putFloat(KEY_STEP_SCALE, v.coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX).toFloat())
            .apply()

    // ── Steps ESTIMATE calibration (WHOOP 4.0; StepsEstimateEngine) ─────────────────────────────
    // Mirror of the macOS ProfileStore fields: the engine writes the auto-fit each analytics pass and
    // the Settings/Steps screen reads them. [stepsManualCoefficient] is the ONLY user-settable field
    // (0 = auto-fit / null to the engine; > 0 = manual override fed into calibrate()); the other three
    // are fitted outputs surfaced read-only.
    /** Fitted (or manually-set) steps-per-unit-of-motion coefficient last persisted by the engine. */
    var stepsCalibrationCoefficient: Double
        get() = prefs.getFloat(KEY_STEPS_COEFF, 0f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_STEPS_COEFF, v.toFloat()).apply()

    /** How many calibration days fed the last auto-fit (0 when purely manual / not yet fit). */
    var stepsCalibrationSampleDays: Int
        get() = prefs.getInt(KEY_STEPS_SAMPLE_DAYS, 0)
        set(v) = prefs.edit().putInt(KEY_STEPS_SAMPLE_DAYS, v).apply()

    /** 0–1 trust in the last fit (1.0 for a manual coefficient). */
    var stepsCalibrationConfidence: Double
        get() = prefs.getFloat(KEY_STEPS_CONFIDENCE, 0f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_STEPS_CONFIDENCE, v.toFloat()).apply()

    /** True when the persisted coefficient came from the user's manual override, not an auto-fit. */
    var stepsCalibrationManual: Boolean
        get() = prefs.getBoolean(KEY_STEPS_MANUAL_FLAG, false)
        set(v) = prefs.edit().putBoolean(KEY_STEPS_MANUAL_FLAG, v).apply()

    /** User-set manual coefficient. 0 = auto-fit (null to the engine); > 0 = manual override. */
    var stepsManualCoefficient: Double
        get() = prefs.getFloat(KEY_STEPS_MANUAL_COEFF, 0f).toDouble().coerceAtLeast(0.0)
        set(v) = prefs.edit().putFloat(KEY_STEPS_MANUAL_COEFF, v.coerceAtLeast(0.0).toFloat()).apply()

    /** The manual override to feed into `StepsEstimateEngine.calibrate(points, manualOverride)`:
     *  null when 0 (auto-fit), the positive value otherwise. */
    val stepsManualOverride: Double? get() = stepsManualCoefficient.takeIf { it > 0 }

    /** The auto (Tanaka) HR-max for the current age. */
    val hrMaxAuto: Int get() = Zones.hrMaxTanaka(age)

    /** Effective HR-max: the manual override if set, else the Tanaka estimate. */
    val hrMax: Int get() = if (hrMaxOverride > 0) hrMaxOverride else hrMaxAuto

    // ── Backup settings snapshot/apply (#1000) ──────────────────────────────────────────────────
    // The profile half of a `.noopbak`'s `settings.json`. Canonical key strings mirror
    // `BackupSettingsCodec.WHITELIST` (and the Apple `BackupSettings.whitelist`) exactly — note
    // canonical `profile.hrMax` maps onto this store's `hr_max_override` pref. Lives on ProfileStore
    // because only it knows its private pref keys; `contains` checks keep never-set fields OUT of the
    // snapshot so restoring on another device doesn't stamp defaults over that device's real values.

    /** The user-SET profile fields, keyed canonically, for the backup exporter. */
    fun backupSnapshot(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        // #146: age is now derived from a DOB; export the current derived Int under the legacy
        // `profile.age` key (the whitelist carries an Int, not a Date). A never-touched profile
        // (neither key set) still stays out of the snapshot.
        if (prefs.contains(KEY_DOB) || prefs.contains(KEY_AGE)) out["profile.age"] = age
        if (prefs.contains(KEY_SEX)) out["profile.sex"] = sex
        if (prefs.contains(KEY_WEIGHT)) out["profile.weightKg"] = weightKg
        if (prefs.contains(KEY_HEIGHT)) out["profile.heightCm"] = heightCm
        if (prefs.contains(KEY_WAIST)) out["profile.waistCm"] = waistCm
        if (prefs.contains(KEY_HRMAX)) out["profile.hrMax"] = hrMaxOverride
        return out
    }

    /**
     * Apply a restored backup's profile fields (canonical keys, already whitelist-filtered by
     * `BackupSettingsCodec.decode`). Missing keys leave the current values alone; every write goes
     * through the property setters, so the usual range clamps apply.
     */
    fun applyBackup(values: Map<String, Any>) {
        // #146: a restore carries only an Int age. Route it through setAge so the restored age
        // re-anchors this device's DOB (clearing any stale local DOB) and then advances on its own —
        // the deterministic twin of the Apple side clearing `profile.dateOfBirth` on apply.
        (values["profile.age"] as? Number)?.let { setAge(it.toInt()) }
        (values["profile.sex"] as? String)?.let { sex = it }
        (values["profile.weightKg"] as? Number)?.let { weightKg = it.toDouble() }
        (values["profile.heightCm"] as? Number)?.let { heightCm = it.toDouble() }
        (values["profile.waistCm"] as? Number)?.let { waistCm = it.toDouble() }
        (values["profile.hrMax"] as? Number)?.let { hrMaxOverride = it.toInt() }
    }

    companion object {
        private const val PREFS = "noop_profile"
        /** Date of birth as epoch millis — the #146 source of truth for [age]. */
        private const val KEY_DOB = "date_of_birth"
        /** Pre-#146 age key, now kept mirrored from the DOB so the `.noopbak` whitelist (Int age)
         *  keeps round-tripping unchanged. */
        private const val KEY_AGE = "age"
        private const val KEY_SEX = "sex"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_WAIST = "waist_cm"
        private const val KEY_HRMAX = "hr_max_override"
        private const val KEY_STEP_SCALE = "step_ticks_per_step"
        private const val KEY_STEPS_COEFF = "steps_calibration_coefficient"
        private const val KEY_STEPS_SAMPLE_DAYS = "steps_calibration_sample_days"
        private const val KEY_STEPS_CONFIDENCE = "steps_calibration_confidence"
        private const val KEY_STEPS_MANUAL_FLAG = "steps_calibration_manual"
        private const val KEY_STEPS_MANUAL_COEFF = "steps_manual_coefficient"

        private const val AGE_MIN = 13
        private const val AGE_MAX = 100
        private const val WEIGHT_MIN = 30.0
        private const val WEIGHT_MAX = 250.0
        private const val HEIGHT_MIN = 120.0
        private const val HEIGHT_MAX = 230.0
        private const val WAIST_MAX = 200.0
        private const val STEP_SCALE_MIN = 0.5
        private const val STEP_SCALE_MAX = 30.0

        /**
         * Variable step for the calibration stepper so high values stay reachable: fine near the
         * 1.0 default (where most people land), coarse up at the 20s+ a 5/MG needs. A flat 0.1 step
         * from 0.5 to 30 would be ~295 taps — unusable. Mirrors macOS `ProfileStore.stepScaleIncrement`.
         *  - `< 2.0` → 0.1   (precision around the default)
         *  - `2.0–5.0` → 0.5
         *  - `>= 5.0` → 1.0   (ballpark the ~24× overcount in ~19 taps)
         */
        fun stepScaleIncrement(value: Double): Double = when {
            value < 2.0 -> 0.1
            value < 5.0 -> 0.5
            else -> 1.0
        }

        // ── #146 age <-> date-of-birth ──────────────────────────────────────────────────────────
        /** Whole years between the DOB and today (floor — a birthday not yet reached doesn't count).
         *  Uses the device's default zone so the rollover matches the user's local calendar. Mirrors
         *  the Apple `ProfileStore.years(from:to:)`. */
        fun yearsFromDob(dobMillis: Long): Int {
            val zone = java.time.ZoneId.systemDefault()
            val dob = java.time.Instant.ofEpochMilli(dobMillis).atZone(zone).toLocalDate()
            return java.time.temporal.ChronoUnit.YEARS.between(dob, java.time.LocalDate.now(zone)).toInt()
        }

        /** A date of birth `age` whole years before today (anchored to today's month/day, so the
         *  derived age is exactly `age`). Mirrors the Apple `ProfileStore.dateOfBirth(forAge:)`. */
        fun dobForAge(age: Int): Long {
            val zone = java.time.ZoneId.systemDefault()
            return java.time.LocalDate.now(zone).minusYears(age.toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /**
         * One increment/decrement of the calibration divisor, snapped to the increment grid and
         * clamped to [STEP_SCALE_MIN]..[STEP_SCALE_MAX]. Decrement uses the increment for the
         * *target* band so the up/down sequence is symmetric at band boundaries (e.g. 5.0 −1 → 4.0,
         * 4.0 +0.5 → 4.5). Mirrors macOS `ProfileStore.steppedStepScale`.
         */
        fun steppedStepScale(value: Double, up: Boolean): Double {
            val delta = if (up) stepScaleIncrement(value) else stepScaleIncrement(value - 0.0001)
            val next = Math.round((value + if (up) delta else -delta) / delta) * delta
            return next.coerceIn(STEP_SCALE_MIN, STEP_SCALE_MAX)
        }

        fun from(context: Context): ProfileStore =
            ProfileStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

// MARK: - Screen

@Composable
fun SettingsScreen(
    vm: AppViewModel,
) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()

    // The profile store is stable for the lifetime of this screen; only its sex is read here now (the
    // body-profile editing UI + its recomposition counter moved to ProfileMenuScreen).
    val profile = remember { ProfileStore.from(context) }

    // "Recalibrate Charge baseline" confirm dialog (Charge advanced). Writes now-seconds to BOTH the
    // noop.hrvBaselineEpoch and noop.recoveryBaselineEpoch prefs so foldHistory re-seeds every baseline
    // that feeds Charge from tonight onward; the standing analyze loop picks it up on its next pass.
    // Fixes a baseline poisoned by a bad first week (worn sick, or early nights that anchored too high).
    var showRecalibrateConfirm by remember { mutableStateOf(false) }

    // Whether the "Advanced" disclosure (experimental 5/MG probes and the Trends report) is expanded;
    // diagnostics and raw-sensor export now live in the Test Centre "Diagnostic tools" card. Default
    // FALSE so a first-run user lands on the everyday sections instead of the full wall of cards (S3);
    // nothing is removed, every section stays one tap away by expanding.
    // Persisted to the same key the iOS @AppStorage uses ("noop.settingsAdvancedOpen"); SharedPreferences
    // isn't reactive, so the Switch-style toggle drives a local state that writes straight through.
    var advancedOpen by remember {
        mutableStateOf(SettingsDisclosurePrefs.read(NoopPrefs.of(context)))
    }

    // EXPERIMENTAL WHOOP 5/MG protocol probes (off by default). Mirrors the macOS @AppStorage toggle;
    // SharedPreferences isn't reactive, so the Switch drives a local mutableState that the store reads.
    val puffinExperiment = remember { PuffinExperiment.from(context) }
    var puffinExperiments by remember { mutableStateOf(puffinExperiment.isEnabled) }
    var deepData by remember { mutableStateOf(puffinExperiment.isDeepDataEnabled) }

    // Whether to surface the WHOOP 5/MG-only probes (puffin / R22 / frame-capture). Gated
    // so a confident 4.0 owner never sees 5/MG controls that can't touch their strap (#22). The model
    // preference DEFAULTS to WHOOP4, so we deliberately do NOT hide on the raw default alone — the same
    // "noop.selectedWhoopModel" key is rewritten to the family that actually advertised when a strap
    // connects (WhoopBleClient.persistSelectedModel, PR#195), so a real 5/MG owner who never opened the
    // model picker still flips this true once their strap is discovered. We also show it whenever a 5/MG
    // is live-detected this session. Hide only when the user is confidently on a 4.0 (pref says WHOOP4
    // AND nothing 5/MG is connected). Mirrors the macOS SettingsView `showFiveMGControls` gate.
    val selectedModelName = remember {
        context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
            .getString("noop.selectedWhoopModel", null)
    }
    val showFiveMGControls = selectedModelName == WhoopModel.WHOOP5_MG.name || live.whoop5Detected

    // --- v5 Health & wellness toggle group. All SharedPreferences-backed (not reactive), so each Switch
    // drives a local mirror that writes straight through to the same keys the v5 engine readers use.
    // Illness watch routes through the ViewModel so the banner recomputes live; the rest are pref writes
    // the engines pick up on the next analytics pass / offload. All opt-in / safe-default per spec.
    var illnessWatch by remember { mutableStateOf(NoopPrefs.illnessWatch(context)) }
    var cycleTracking by remember { mutableStateOf(NoopPrefs.cycleTracking(context)) }
    var hydrationTracking by remember { mutableStateOf(NoopPrefs.hydrationTracking(context)) }
    var stressCheckIn by remember { mutableStateOf(BiofeedbackPrefs.checkInEnabled(context)) }
    var stressAutoNudge by remember { mutableStateOf(BiofeedbackPrefs.autoNudge(context)) }
    var coachSignals by remember { mutableStateOf(NoopPrefs.coachSignals(context)) }
    var autoDetectWorkouts by remember { mutableStateOf(NoopPrefs.autoDetectWorkouts(context)) }
    // Keep the screen on during a manual workout recording (#703), default OFF. The live-workout
    // screen reads this same "workoutKeepScreenOn" key. String shared verbatim with the iOS/Mac twin
    // (AppStorage "workoutKeepScreenOn"). Read/written inline against the shared prefs store.
    var workoutKeepScreenOn by remember {
        mutableStateOf(NoopPrefs.of(context).getBoolean("workoutKeepScreenOn", false))
    }
    // Live Sessions (beta) — gates the Today "Start session" entry. Unlike its section-mates this is a
    // BETA feature flag, default ON (`live_sessions_beta`, see LiveSessionPrefs); off hides the entry.
    var liveSessionsBeta by remember { mutableStateOf(LiveSessionPrefs.enabled(context)) }

    // App icon (v3 "Titanium & Gold") — machined-titanium (.IconDefault) or blued-titanium (.IconNavy).
    // SharedPreferences isn't reactive, so the segmented control drives this local mirror; flipping it
    // enables exactly one launcher alias via PackageManager (see setAppIcon below).
    var appIconNavy by remember { mutableStateOf(NoopPrefs.appIconNavy(context)) }

    // Theme (System / Light / Dark) — drives NoopTheme; AppearancePrefs mirrors it in snapshot state.
    var themeMode by remember { mutableStateOf(AppearancePrefs.mode) }
    // Chart colours (Titanium / Classic) — re-colours gauges + charts; ChartStylePrefs mirrors it live.
    var chartStyle by remember { mutableStateOf(ChartStylePrefs.style) }
    // Trend charts (Line / Bar) — flips the Trends tab between the gradient line and value-ramp bars.
    // Display-only; SharedPreferences isn't reactive, so mirror into local state and persist on select.
    var trendChartStyle by remember { mutableStateOf(UnitPrefs.trendChartStyle(context)) }
    // Day-cycle background (#698) — the time-of-day scene behind Today. Default ON. SharedPreferences
    // isn't reactive, so the Switch mirrors into local state; TodayScreen reads the same pref on entry.
    var showDayCycleBackground by remember { mutableStateOf(NoopPrefs.showDayCycleBackground(context)) }
    // Card-surface opacity (0f = clear, 1f = solid), for the "Card transparency" slider. Live-previews via
    // CardAppearance; saved on release.
    var cardOpacity by remember { mutableStateOf(NoopPrefs.cardOpacityPercent(context) / 100f) }

    // #477 Power saving: battery-adaptive strap-sync cadence + optional HRV-capture pause. Local mirrors.
    var powerSaving by remember { mutableStateOf(NoopPrefs.powerSaving(context)) }
    var powerSavingBatteryPct by remember { mutableStateOf(NoopPrefs.powerSavingBatteryPct(context)) }

    ScreenScaffold(
        title = "Settings",
        subtitle = "Your numbers, your strap, and how NOOP works. All on this phone.",
        // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the static time-of-day sky settles
        // into the theme canvas behind the top of the list, exactly like the liquid Today. This is a long,
        // scroll-heavy list with NO hero gauge, so the liquid finish here is just the sky + liquidPress on
        // the tappable rows. Gated on the same day-cycle background pref Today reads, so turning that off
        // returns Settings to the plain dark canvas too.
        topBackground = if (showDayCycleBackground) { { LiquidScreenSky() } } else null,
    ) {
        // --- Appearance (Theme) ---
        NoopSettingsSection(
            icon = Icons.Filled.Brightness6,
            title = "Appearance",
            blurb = "Choose Light, Dark, or follow your system. Dark is the signature near-black; Light keeps the same clean look on a bright canvas.",
            overline = "Settings",
        ) {
            FormRow(label = "Theme") {
                SegmentedPillControl(
                    items = listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK),
                    selection = themeMode,
                    label = { it.label },
                    onSelect = { mode ->
                        themeMode = mode
                        AppearancePrefs.set(context, mode)
                    },
                )
            }
            RowDivider()   // #79 parity: the hairline every other section has between FormRows (Android rows
                           // were already 16dp-spaced, unlike iOS where they touched — this matches both)
            FormRow(label = "Chart colours") {
                // Titanium = brand gold/amber/blue ramps; Classic = throwback red→green readiness scale
                // (cool→hot zones, green→red stress). Re-colours every gauge/chart, in both schemes.
                SegmentedPillControl(
                    items = listOf(ChartStyle.TITANIUM, ChartStyle.CLASSIC),
                    selection = chartStyle,
                    label = { it.label },
                    onSelect = { style ->
                        chartStyle = style
                        ChartStylePrefs.set(context, style)
                    },
                )
            }
            RowDivider()
            // Trend chart style (line vs bar). Display-only: flips the Trends tab's charts between the
            // gradient line and value-ramp bars. The plotted data is identical either way.
            FormRow(label = "Trend charts") {
                SegmentedPillControl(
                    items = listOf(TrendChartStyle.LINE, TrendChartStyle.BAR),
                    selection = trendChartStyle,
                    label = { if (it == TrendChartStyle.BAR) "Bars" else "Line" },
                    onSelect = { style ->
                        trendChartStyle = style
                        UnitPrefs.setTrendChartStyle(context, style)
                    },
                )
            }

            // Day-cycle background (#698): the time-of-day scene behind Today. On by default. Off swaps it
            // for a plain dark canvas for people who find the moving scene distracting. Takes effect next
            // time Today is opened (the pref is read once on entry, like the other Today-screen toggles).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Day-cycle background",
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                    Text(
                        "Shows a soft sunrise, day, dusk and night scene behind the Today screen. Turn it off for a plain dark canvas. Your cards stay exactly as readable.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = showDayCycleBackground,
                    onCheckedChange = {
                        showDayCycleBackground = it
                        NoopPrefs.setShowDayCycleBackground(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }

            // Card transparency: scale every frosted card's glass toward the background. Live-preview (the
            // cards on THIS screen update as you drag) via CardAppearance; saved on release. Default solid.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Card transparency",
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${((1f - cardOpacity) * 100).toInt()}%",
                        style = NoopType.number(15f),
                        color = Palette.accent,
                    )
                }
                Text(
                    "How see-through the cards (Heart Rate, Key Metrics, Recovery Vitals, …) are. Left = solid, right = clear.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                Slider(
                    // The slider shows TRANSPARENCY (0 = solid, 1 = fully clear); we store the OPACITY.
                    value = 1f - cardOpacity,
                    onValueChange = { t ->
                        cardOpacity = 1f - t
                        CardAppearance.opacity = cardOpacity   // live preview on every card on-screen
                    },
                    onValueChangeFinished = {
                        NoopPrefs.setCardOpacityPercent(context, (cardOpacity * 100).toInt())
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.accent,
                        activeTrackColor = Palette.accent,
                        inactiveTrackColor = Palette.surfaceInset,
                    ),
                )
            }

            RowDivider()
            // App icon (v3 "Titanium & Gold"): two staged launcher icons — machined titanium (default)
            // and blued/dark-blue titanium. The swap enables exactly one <activity-alias>
            // (.IconDefault / .IconNavy) at runtime; the launcher may take a beat (or briefly
            // disappear/redraw) while it re-reads the icon.
            FormRow(label = "App icon") {
                SegmentedPillControl(
                    items = listOf(false, true),
                    selection = appIconNavy,
                    label = { if (it) "Blue Titanium" else "Titanium" },
                    onSelect = { navy ->
                        appIconNavy = navy
                        setAppIcon(context, navy)
                    },
                )
            }
        }

        // --- Health & wellness (v5 opt-in toggles) ---
        NoopSettingsSection(
            icon = Icons.Filled.Science,
            title = "Health & wellness",
            blurb = "Optional, on-device wellness signals. Each is off by default, computed only on this phone from data you already have, and never a medical diagnosis.",
            overline = "Settings",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NoopToggleRow(
                    title = "Illness heads-up",
                    detail = "Watches your resting heart rate, HRV and skin temperature for the pattern that often shows up before you feel unwell, and surfaces a gentle heads-up. An observation about your own numbers, not a diagnosis.",
                    checked = illnessWatch,
                    onCheckedChange = {
                        illnessWatch = it
                        vm.setIllnessWatchEnabled(it)
                    },
                )
                RowDivider()
                // #801 — not offered on a male profile (it would just sit at "Learning your pattern"). Hidden
                // when off for a male profile so it can't be enabled here; still shown when already on so it
                // can be turned off — mirroring HealthScreen's cycle opt-in gate (cycleOptInApplies). The
                // sister surfaces (Health opt-in, the card's off-control) were sex-gated in v7.3.2; this
                // Settings toggle was the one surface that was missed, so a male profile could enable it here.
                if (cycleTracking || cycleOptInApplies(profile.sex)) {
                    NoopToggleRow(
                        title = "Cycle awareness",
                        detail = "Reads a coarse menstrual-cycle phase from your nightly skin-temperature shift, on this device only. Awareness only: not contraception, not a fertility predictor, not a medical service.",
                        checked = cycleTracking,
                        onCheckedChange = {
                            cycleTracking = it
                            vm.setCycleTrackingEnabled(it)
                        },
                    )
                    RowDivider()
                }
                NoopToggleRow(
                    title = "Hydration tracking",
                    detail = "Adds a simple fluid log with a daily goal that adjusts to your effort. Tap to add a sip, cup or bottle and watch a progress ring fill. On this phone only. Nothing is synced.",
                    checked = hydrationTracking,
                    onCheckedChange = {
                        hydrationTracking = it
                        NoopPrefs.setHydrationTracking(context, it)
                    },
                )
                RowDivider()
                NoopToggleRow(
                    title = "Auto-detect workouts",
                    detail = "After a sync, NOOP looks over your recent heart rate for a sustained, raised stretch that looks like exercise and offers to save it. It only ever suggests. Nothing is saved until you tap Save, and you can dismiss any suggestion. Deliberately conservative, so the odd workout may be missed. On this phone only.",
                    checked = autoDetectWorkouts,
                    onCheckedChange = {
                        autoDetectWorkouts = it
                        NoopPrefs.setAutoDetectWorkouts(context, it)
                    },
                )
                RowDivider()
                NoopToggleRow(
                    title = "Keep screen on during a workout",
                    detail = "Holds the screen awake while you're recording a workout, so your live heart rate stays visible without the phone dimming. Only applies during a recording. The screen sleeps normally the rest of the time. Leaving it on does use a bit more battery, and means your unlocked screen stays visible for the whole workout, so flip it off if that's a concern.",
                    checked = workoutKeepScreenOn,
                    onCheckedChange = {
                        workoutKeepScreenOn = it
                        NoopPrefs.of(context).edit().putBoolean("workoutKeepScreenOn", it).apply()
                    },
                )
                RowDivider()
                // BETA + default ON (the one exception to this section's off-by-default rule): the flag
                // gates the Today entry so anyone can wave the beta away here with one flip.
                NoopToggleRow(
                    title = "Live Sessions (beta)",
                    detail = "Silence-first strap coaching during workouts.",
                    checked = liveSessionsBeta,
                    onCheckedChange = {
                        liveSessionsBeta = it
                        LiveSessionPrefs.setEnabled(context, it)
                    },
                )
                RowDivider()
                NoopToggleRow(
                    title = "Stress check-ins (haptic)",
                    detail = "Lets NOOP notice a fresh HRV dip while you're still and offer a minute to breathe. \"Stress\" here is an autonomic proxy from your own baseline, never a diagnosis. The strap gives one light confirming buzz; no push notification.",
                    checked = stressCheckIn,
                    onCheckedChange = {
                        stressCheckIn = it
                        BiofeedbackPrefs.setCheckInEnabled(context, it)
                        // Turning the master off also disarms the auto-nudge sub-toggle so it can't fire.
                        if (!it) { stressAutoNudge = false; BiofeedbackPrefs.setAutoNudge(context, false) }
                    },
                )
                if (stressCheckIn) {
                    NoopToggleRow(
                        title = "Offer a breath automatically",
                        detail = "When a dip is detected, surface the check-in card on its own (rate-limited, quiet-hours aware). Off keeps it manual.",
                        checked = stressAutoNudge,
                        onCheckedChange = {
                            stressAutoNudge = it
                            BiofeedbackPrefs.setAutoNudge(context, it)
                        },
                    )
                }
                RowDivider()
                NoopToggleRow(
                    title = "Share on-device signals with the Coach",
                    detail = "When the opt-in Coach is set up with your own key, also include a short summary of your strongest on-device patterns and Lab Book markers in its context. Summary only; no raw data leaves your phone. Requires the Coach's own data consent first.",
                    checked = coachSignals,
                    onCheckedChange = {
                        coachSignals = it
                        NoopPrefs.setCoachSignals(context, it)
                    },
                )
            }
        }

        // --- Charge (Recovery) advanced ---
        // A manual reset for the personal Charge baseline. If a bad first week poisons it — worn while
        // sick, or the first few nights read high (a common cold-start artefact) — the baseline anchors
        // off and holds your Charge wrong for a couple of weeks while the rolling average catches up.
        // Recalibrate re-learns it from tonight onward. Writes now-seconds to BOTH noop.hrvBaselineEpoch
        // and noop.recoveryBaselineEpoch (so HRV plus resting HR / respiration / skin temp re-anchor);
        // foldHistory drops every night before that epoch and re-seeds. Mirrors the iOS/Mac button.
        NoopSettingsSection(
            icon = Icons.Filled.Favorite,
            title = "Charge",
            blurb = "Charge is NOOP's daily readiness score, learned from your own HRV, resting heart rate and more over time. Your history stays.",
            overline = "Settings",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Recalibrate Charge baseline", style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        "Restarts the roughly 4-night build-up for Charge and your HRV baseline from tonight. Use it if a bad first week set your baseline off. Your history stays.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                NoopButton(
                    text = "Recalibrate Charge baseline",
                    leadingIcon = Icons.Filled.Autorenew,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    modifier = Modifier.semantics { contentDescription = "Recalibrate Charge baseline" },
                    onClick = { showRecalibrateConfirm = true },
                )
            }
        }

        // #477 Power saving. Two BENIGN battery levers only: the offload-cadence stretch (%-gated) and
        // the HRV-capture pause (Battery-Saver-gated). The riskier connection-priority idle throttle is
        // deliberately not surfaced here — it stays dormant pending on-strap validation (#478).
        NoopSettingsSection(
            icon = Icons.Filled.BatteryStd,
            title = stringResource(R.string.power_saving),
            blurb = stringResource(R.string.power_saving_blurb),
            overline = "Settings",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.power_saving_mode), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.power_saving_mode_desc),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = powerSaving,
                    onCheckedChange = {
                        powerSaving = it
                        vm.setPowerSaving(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }
            if (powerSaving) {
                RowDivider()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.power_saving_kick_in), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(stringResource(R.string.power_saving_pct, powerSavingBatteryPct), style = NoopType.subhead, color = Palette.accent)
                    }
                    Slider(
                        value = powerSavingBatteryPct.toFloat(),
                        // 10–30% snapping to 5% steps (10/15/20/25/30). steps = the 3 stops BETWEEN ends.
                        onValueChange = { powerSavingBatteryPct = it.roundToInt() },
                        onValueChangeFinished = { vm.setPowerSavingBatteryPct(powerSavingBatteryPct) },
                        valueRange = 10f..30f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = Palette.accent,
                            activeTrackColor = Palette.accent,
                            inactiveTrackColor = Palette.surfaceInset,
                        ),
                    )
                }
            }
        }

        // Lower-frequency sections collapse behind a single default-closed disclosure (S3) so the
        // screen opens at the everyday handful instead of the full wall of cards. Nothing is removed;
        // the experimental probes and Trends report stay one tap away. Mirrors the iOS SettingsView
        // "Advanced" disclosure and the Test Centre Advanced group.
        SettingsDisclosure(
            title = "Advanced",
            subtitle = "Experimental 5/MG probes and the shareable Trends report. Tucked away to keep the everyday screen tidy.",
            expanded = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen; SettingsDisclosurePrefs.write(NoopPrefs.of(context), advancedOpen) },
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing)) {
        // --- Experimental · WHOOP 5 / MG --- (hidden when the user is confidently on a 4.0, #22)
        if (showFiveMGControls) {
        NoopSettingsSection(
            icon = Icons.Filled.Science,
            title = "Experimental · WHOOP 5 / MG",
            blurb = "Live heart rate already works on a WHOOP 5/MG strap. These probes go further and try to coax more out of it. They are guesses, off by default, and only ever touch a 5/MG strap. WHOOP 4.0 is never affected.",
            overline = "Settings",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Try WHOOP 5/MG protocol probes",
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = puffinExperiments,
                        onCheckedChange = {
                            puffinExperiments = it
                            puffinExperiment.isEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Try WHOOP 5/MG protocol probes"
                        },
                    )
                }
                Text(
                    "On a 5/MG connection NOOP will send a puffin realtime-stream request after the handshake, and log what comes back. If you have a 5/MG strap, turning this on and sharing your strap log helps map the protocol. No effect on WHOOP 4.0.",
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )

                // --- R22 deep-data unlock — the one probe that writes to the strap. (#174) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Unlock WHOOP 5/MG deep data (R22)",
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = deepData,
                        onCheckedChange = {
                            deepData = it
                            puffinExperiment.isDeepDataEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Unlock WHOOP 5/MG deep data"
                        },
                    )
                }
                Text(
                    "WHOOP 5/MG straps hand a fresh app only live heart rate. The official app switches on the deeper streams (high-rate HR + motion + history) by writing a set of feature flags, a sequence two independent projects have documented. With this on, the button below sends that exact sequence to your strap. Unlike everything else here it does write to the strap, but it's reversible (it only changes which data the strap emits) and is the same thing the official app does. Experimental: it may do nothing on your firmware.",
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
                if (deepData) {
                    NoopButton(
                        text = "Send enable sequence to strap",
                        leadingIcon = Icons.Filled.Bolt,
                        kind = NoopButtonKind.Primary,
                        enabled = live.encryptedBond && live.worn,
                        onClick = { vm.ble.enableWhoop5DeepData() },
                    )
                    Text(
                        if (!live.encryptedBond) "Needs the full encrypted bond: close the official WHOOP app and pair the strap to NOOP first (a live-HR-only link can't carry the unlock)."
                        else if (!live.worn) "Put the strap on first. The deep stream is on-wrist only."
                        else "Wear the strap, tap once, then let it sync and share your strap log.",
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                    )
                    // Live R22 telemetry (#174): proof of what the strap is doing right now.
                    if (live.r22FlagsAccepted > 0) {
                        Text(
                            if (live.r22FlagsAccepted >= 15) "✓ Strap accepted all 15 R22 flags"
                            else "Strap accepted ${live.r22FlagsAccepted}/15 R22 flags…",
                            style = NoopType.caption,
                            color = if (live.r22FlagsAccepted >= 15) Palette.statusPositive else Palette.textSecondary,
                        )
                    }
                    if (live.deepPacketsThisSession > 0) {
                        Text(
                            "${live.deepPacketsThisSession} type-0x2F historical-offload frame(s) seen outside our sync. These are history (e.g. another app pulling the strap's backlog), not a live R22 stream (#494).",
                            style = NoopType.caption,
                            color = Palette.textSecondary,
                        )
                    } else if (live.r22FlagsAccepted >= 15) {
                        Text(
                            "Flags accepted, but the enable sequence doesn't start a separate live stream. The deep records arrive as part of the normal history sync (#494).",
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                }
                // The 5/MG raw-frame capture and its share paths now live in the Test Centre
                // "Diagnostic tools" card (#22 consolidation), alongside every other diagnostic control.
            }
        }
        } // end if (showFiveMGControls)

        // --- Trends report (#436) — shareable offline PDF over a date range. Self-contained
        // card (its own NoopCard + range picker + CTA), so it drops in without a SettingsSection wrapper.
        TrendsReportExportSection(vm)
        } // end Advanced disclosure content Column
        } // end SettingsDisclosure("Advanced")

        if (showRecalibrateConfirm) {
            NoopConfirmDialog(
                title = "Recalibrate your Charge baseline?",
                text = "This restarts the roughly 4-night build-up for Charge and your HRV baseline. Your history stays. Use it if a bad first week, like wearing it while sick, set your baseline off.",
                confirmLabel = "Recalibrate",
                onConfirm = {
                    val nowSeconds = System.currentTimeMillis() / 1000L
                    val editor = NoopPrefs.of(context).edit()
                    Baselines.recalibrateRecoveryBaselines(editor, nowSeconds)
                    editor.apply()
                    showRecalibrateConfirm = false
                    vm.syncNow()
                    Toast.makeText(
                        context,
                        "Charge baseline reset. NOOP will re-learn it from tonight. Your history stays, and it takes a few nights to settle.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onDismiss = { showRecalibrateConfirm = false },
            )
        }

        // Backup & restore + automatic backups moved to Data → Backup & Sync (single home, one system).

    }
}

// MARK: - App icon swap (v3 "Titanium & Gold")

/**
 * The two launcher-icon aliases declared in AndroidManifest.xml. Exactly one is ever enabled — the
 * enabled one is the app's home-screen entry point and supplies the launcher icon.
 */
private const val ALIAS_DEFAULT = "com.noop.IconDefault" // machined titanium
private const val ALIAS_NAVY = "com.noop.IconNavy"       // blued / dark-blue titanium

/**
 * Persist the chosen launcher icon and flip the manifest aliases so exactly one is enabled:
 * [navy] true enables `.IconNavy` and disables `.IconDefault`, false does the inverse. We use
 * DONT_KILL_APP so the toggle doesn't tear down our own process. The home launcher may briefly hide
 * and redraw the icon (or take a few seconds) while it re-reads the component state — that's expected
 * and is the only user-visible side effect.
 */
private fun setAppIcon(context: Context, navy: Boolean) {
    NoopPrefs.setAppIconNavy(context, navy)
    val pm = context.packageManager
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_NAVY),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
    pm.setComponentEnabledSetting(
        ComponentName(context, ALIAS_DEFAULT),
        if (navy) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

// MARK: - Strap status helper (still used by StrapStatusDetailTest)

// `internal` (not private) so the unit test in the same package can assert the scanning branch.
internal fun strapStatusDetail(bonded: Boolean, connected: Boolean, scanning: Boolean): String = when {
    scanning -> "Searching for your WHOOP… make sure it's charged, on your wrist, and the official WHOOP app isn't connected to it."
    bonded && connected -> "Your strap is paired and sending data. Open Live for a real-time heart rate."
    connected -> "Connected. Finishing the secure pairing handshake…"
    bonded -> "Previously paired but not currently connected. Re-scan to reconnect."
    else -> "No strap connected. Put your WHOOP nearby and tap Re-scan to pair."
}

// MARK: - Advanced disclosure persistence (S3)

/**
 * The persisted open/closed state of the Settings "Advanced" disclosure. Keyed identically to the iOS
 * `@AppStorage("settingsAdvancedOpen")` (here under the `noop.` SharedPreferences namespace), and it
 * DEFAULTS to false so a first-run user lands collapsed. Pulled out so the default is a single testable
 * fact: a regression that ships it defaulting open would dump the full wall of cards on first run again.
 */
internal object SettingsDisclosurePrefs {
    const val KEY = "noop.settingsAdvancedOpen"
    const val DEFAULT_OPEN = false

    fun read(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT_OPEN)
    fun write(prefs: SharedPreferences, open: Boolean) { prefs.edit().putBoolean(KEY, open).apply() }
}

// MARK: - Advanced disclosure (S3, ports SettingsView's SettingsDisclosureGroup)

/**
 * A collapsible group that tucks the lower-frequency settings sections behind one tap. It is NOT a
 * section card itself (the cards it wraps keep their own [SettingsSection] chrome). It's a header row
 * plus a default-collapsed reveal, modelled on the Test Centre "Advanced" group. Nothing is removed:
 * collapsed simply means the wrapped sections aren't composed until the row is tapped open. A custom
 * header (not Material's ExposedDropdown / accordion) keeps it on NOOP's near-black instrument look.
 */
@Composable
private fun SettingsDisclosure(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "advancedChevron",
    )
    val headerInteraction = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidPress(headerInteraction)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = headerInteraction,
                    indication = null,
                    onClick = onToggle,
                )
                .semantics {
                    contentDescription = title
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = NoopType.title2, color = Palette.textPrimary)
                Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(22.dp).rotate(chevronRotation),
            )
        }
        if (expanded) {
            content()
        }
    }
}

// MARK: - Section card (ports SettingsView's private SettingsSection)


// MARK: - Two-column form row (ports SettingsView's private FormRow)

/** Label on the left, control on the right — the two-column form feel. */
@Composable
private fun FormRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = NoopType.body,
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        control()
    }
}

// MARK: - Shared bits

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Palette.hairline),
    )
}
