package com.noop.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONArray

// MARK: - What the Today screen shows, and in what order
//
// Today has two customisable sections and they answer the same question twice: the Key Metrics grid
// ([KeyMetric] / [KeyMetricPrefs]) and the "Your cards" dashboard ([DashboardCard] /
// [DashboardCardPrefs]). Adding a value to Today means deciding which of the two registries it joins,
// so they sit side by side rather than in two files that each described the other. The two stored
// lists stay INDEPENDENT (separate SharedPreferences keys, separate defaults, separate editors) —
// sharing a file changes nothing about that.
//
// Both are DISPLAY-ONLY. No metric is computed or stored differently here; these decide which of the
// already-loaded values render and in what sequence. Both raw-id sets are byte-identical to their
// iOS twins so a backup/restore reads the same layout on either OS.

// MARK: - Editable Key-Metrics layout
//
// The Today screen's "Key Metrics" grid was a fixed list of ten tiles in one order. This lets the user
// choose WHICH tiles show and in WHAT order, with the default being the original order so nothing changes
// for anyone who never opens the editor. Persistence is display-only — no metric is computed or stored
// differently; this just decides which of the already-computed tiles render and in what sequence.
//
// Stored as a single comma-joined string of metric keys in SharedPreferences ("today.keyMetrics"), the
// same mechanism every other Android preference uses. Mirrors the macOS KeyMetricPrefs.swift +
// @AppStorage("today.keyMetrics"). Unknown keys are dropped on read so a removed tile can't crash, and
// any known key missing from the saved list is treated as disabled (the editor re-lists it).

/**
 * One of the Today screen's Key-Metric tiles. The [raw] is the stable persisted identifier — keep it
 * byte-identical to the macOS `KeyMetric` enum so a backup/restore reads the same layout on either OS.
 */
enum class KeyMetric(val raw: String, val title: String) {
    CHARGE("charge", "Charge"),
    EFFORT("effort", "Effort"),
    REST("rest", "Rest"),
    HRV("hrv", "HRV"),
    RESTING_HR("restingHr", "Resting HR"),
    BLOOD_OXYGEN("bloodOxygen", "SpO₂"),
    RESPIRATORY("respiratory", "Respiratory"),
    STEPS("steps", "Steps"),
    WEIGHT("weight", "Weight"),
    CALORIES("calories", "Calories");

    companion object {
        fun fromRaw(raw: String?): KeyMetric? = entries.firstOrNull { it.raw == raw }

        /** The original, hard-coded grid order — the default when the layout isn't customised. */
        val defaultOrder: List<KeyMetric> = listOf(
            CHARGE, EFFORT, REST, HRV, RESTING_HR,
            BLOOD_OXYGEN, RESPIRATORY, STEPS, WEIGHT, CALORIES,
        )
    }
}

/**
 * Display-only persistence for the Key-Metrics layout. Holds an ORDERED list of the enabled tiles; a tile
 * not in the list is hidden. SharedPreferences isn't reactive, so the Today screen reads this once into
 * remembered state (like the other prefs) and re-reads on the recomposition the editor's write triggers.
 * Mirrors the macOS KeyMetricPrefs (@AppStorage "today.keyMetrics").
 */
object KeyMetricPrefs {
    private const val KEY_LAYOUT = "today.keyMetrics"

    /** The enabled tiles in display order. An empty/unset string yields the full default order. */
    fun enabled(context: Context): List<KeyMetric> =
        decodeEnabled(NoopPrefs.of(context).getString(KEY_LAYOUT, null))

    /** Persist the enabled tiles in order. Disabled tiles are simply omitted from the stored string. */
    fun setEnabled(context: Context, metrics: List<KeyMetric>) {
        NoopPrefs.of(context).edit().putString(KEY_LAYOUT, encode(metrics)).apply()
    }

    /** Encode an ordered list of enabled tiles into the stored comma-joined string. */
    fun encode(metrics: List<KeyMetric>): String = metrics.joinToString(",") { it.raw }

    /**
     * Decode the stored string into an ordered list of enabled tiles. An empty/unset string yields the
     * full default order (so a fresh install shows every tile). Unknown tokens are ignored, duplicates
     * collapsed; this returns ONLY the enabled tiles in their saved order.
     */
    fun decodeEnabled(raw: String?): List<KeyMetric> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return KeyMetric.defaultOrder
        val seen = LinkedHashSet<KeyMetric>()
        trimmed.split(",").forEach { token ->
            KeyMetric.fromRaw(token.trim())?.let { seen.add(it) }
        }
        return if (seen.isEmpty()) KeyMetric.defaultOrder else seen.toList()
    }
}

// MARK: - "Your cards" customisable dashboard (WHOOP "My Dashboard") — Kotlin twin of DashboardCards.swift
//
// The Today screen's "Your cards" section is a user-customisable dashboard faithful to WHOOP's "My
// Dashboard": the user chooses WHICH metric cards show and in WHAT order from a registry of the values
// Today already loads. Persistence is DISPLAY-ONLY — no metric is computed or stored differently; this just
// decides which already-loaded values render as WHOOP metric rows and in what sequence.
//
// Stored as a JSON-encoded array of card ids in SharedPreferences ("today.dashboardCards") — the SAME
// JSON-array form the iOS @AppStorage uses, so a backup/restore reads the same dashboard on either OS.
// Unknown ids are dropped on read; a known id missing from the saved list is offered (disabled) in the
// editor so a future card can't be lost. Mirrors the existing [KeyMetricPrefs] mechanism but as its own
// list so the two sections stay independent (Key Metrics grid vs. the Your-cards dashboard).

/**
 * One available card in the "Your cards" dashboard. The [raw] is the stable persisted identifier — keep it
 * BYTE-IDENTICAL to the iOS `DashboardCard` rawValue so a backup/restore reads the same dashboard on either
 * OS. [title] / [subtitle] / [unit] mirror the Swift registry verbatim; [icon] is the Material twin of the
 * SF Symbol (closest match in the bundled icon set).
 */
enum class DashboardCard(
    val raw: String,
    val title: String,
    val subtitle: String,
    val unit: String,
    val icon: ImageVector,
) {
    HRV("hrv", "HRV", "Heart-rate variability", "ms", Icons.Filled.MonitorHeart),
    RESTING_HR("restingHr", "Resting HR", "Resting heart rate", "bpm", Icons.Filled.Favorite),
    RESPIRATORY("respiratory", "Respiratory", "Breaths per minute", "rpm", Icons.Filled.Air),
    STEPS("steps", "Steps", "Today", "", Icons.AutoMirrored.Filled.DirectionsWalk),
    STRESS("stress", "Stress", "Autonomic load", "", Icons.Filled.Bolt),
    FITNESS_AGE("fitnessAge", "Fitness Age", "Updated weekly", "yrs", Icons.AutoMirrored.Filled.DirectionsRun),
    VITALITY("vitality", "Vitality", "Wellness score", "", Icons.Filled.AutoAwesome),
    BLOOD_OXYGEN("bloodOxygen", "SpO₂", "Blood oxygen saturation", "", Icons.Filled.WaterDrop),
    SKIN_TEMP("skinTemp", "Skin Temp", "Skin temperature", "", Icons.Filled.Thermostat),
    SLEEP("sleep", "Sleep", "Last night", "", Icons.Filled.Bedtime),
    CALORIES("calories", "Calories", "Active energy", "kcal", Icons.Filled.LocalFireDepartment),
    HYDRATION("hydration", "Hydration", "Today's fluid", "", Icons.Filled.LocalDrink),

    // Optional, default-OFF: a tap-through to the Coupled view (the WHOOP-style day read). Unlike
    // every other card it carries NO metric value of its own, it is a navigation row that opens the full
    // CoupledScreen. It is NOT in [defaultSelection], so a fresh install never shows it until the user adds
    // it via CUSTOMISE. Mirrors iOS DashboardCard.coupled (raw "coupled", byte-identical across OS).
    COUPLED("coupled", "Coupled view", "Recovery, strain and sleep in one glance", "", Icons.Filled.Hexagon);

    companion object {
        fun fromRaw(raw: String?): DashboardCard? = entries.firstOrNull { it.raw == raw }

        /**
         * The default set when the user hasn't customised the dashboard: the original Stress / Fitness age /
         * Vitality trio plus HRV + Resting HR (per the task's "sensible default"). Cards with no value yet
         * simply render a dash, so the default set is safe on a fresh install. Mirrors iOS defaultSelection.
         */
        val defaultSelection: List<DashboardCard> = listOf(
            STRESS, FITNESS_AGE, VITALITY, HRV, RESTING_HR,
        )

        /** Canonical order used to list the disabled remainder in the editor (matches iOS allCases order). */
        val canonicalOrder: List<DashboardCard> = entries.toList()
    }
}

/**
 * Display-only persistence for the "Your cards" dashboard selection. Holds an ORDERED list of the enabled
 * cards as a JSON-encoded array of ids; a card not in the list is hidden. Stored in SharedPreferences under
 * "today.dashboardCards", the same mechanism every other Android preference uses ([NoopPrefs]).
 * SharedPreferences isn't reactive, so the Today screen reads this once into remembered state (like the
 * other prefs) and re-reads on the recomposition the editor's write triggers. Mirrors the iOS
 * DashboardCardPrefs (@AppStorage "today.dashboardCards", JSON-array form).
 */
object DashboardCardPrefs {
    private const val KEY_SELECTION = "today.dashboardCards"

    /** The enabled cards in display order. An empty/unset value yields the default selection. */
    fun enabled(context: Context): List<DashboardCard> =
        decodeEnabled(NoopPrefs.of(context).getString(KEY_SELECTION, null))

    /** Persist the enabled cards in order. Disabled cards are simply omitted from the stored string. */
    fun setEnabled(context: Context, cards: List<DashboardCard>) {
        NoopPrefs.of(context).edit().putString(KEY_SELECTION, encode(cards)).apply()
    }

    /** Encode an ordered list of enabled cards into the stored JSON-array string (matches the iOS form). */
    fun encode(cards: List<DashboardCard>): String {
        val arr = JSONArray()
        cards.forEach { arr.put(it.raw) }
        return arr.toString()
    }

    /**
     * Decode the stored string into an ordered list of enabled cards. An empty/unset string yields the
     * default selection (so a fresh install shows the sensible default). Accepts both the JSON-array form
     * (the canonical iOS form) and a legacy comma-joined form. Unknown ids are dropped; duplicates are
     * de-duped; this returns ONLY the enabled cards in their saved order — the editor pairs it with the
     * disabled remainder. An all-unknown / empty decode falls back to the default set so the dashboard is
     * never blanked. Mirrors iOS DashboardCardPrefs.decodeEnabled.
     */
    fun decodeEnabled(raw: String?): List<DashboardCard> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DashboardCard.defaultSelection

        val ids: List<String> = parseJsonArray(trimmed)
            ?: trimmed.split(",").map { it.trim() }

        val seen = LinkedHashSet<DashboardCard>()
        ids.forEach { token -> DashboardCard.fromRaw(token)?.let { seen.add(it) } }
        return if (seen.isEmpty()) DashboardCard.defaultSelection else seen.toList()
    }

    /** Parse a JSON string array into a list of ids, or null if it isn't valid JSON (caller then falls back
     *  to the legacy comma-joined form). */
    private fun parseJsonArray(s: String): List<String>? = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()
}
