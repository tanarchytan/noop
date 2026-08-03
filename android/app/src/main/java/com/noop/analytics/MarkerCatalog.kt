package com.noop.analytics

/*
 * Small dictionary of common, non-diagnostic marker definitions for the Health Records
 * "Lab Book" pillar.
 *
 * Ships no reference-range tables: NOOP never defines, computes, or asserts a normal range.
 * `higherIsBetter` is always null: NOOP makes no value judgement about a
 * marker's direction. The catalog is not a gate — a user can always add a custom marker.
 *
 * Pure data — no DB, no Android deps.
 */

/** The category a marker belongs to. `raw` is the value stored in the `labMarker.category`
 *  column. */
enum class LabMarkerCategory(val raw: String, val displayName: String) {
    BLOOD_PANEL("bloodPanel", "Blood panel"),
    BLOOD_PRESSURE("bloodPressure", "Blood pressure"),
    BODY_MEASUREMENT("bodyMeasurement", "Body"),
    IMAGING("imaging", "Imaging"),
    APPOINTMENT_NOTE("appointmentNote", "Notes"),
    OTHER("other", "Custom");

    companion object {
        fun fromRaw(raw: String): LabMarkerCategory =
            entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

/** A non-diagnostic marker definition: how to label and format one marker. Carries no clinical
 *  thresholds. */
data class MarkerDefinition(
    /** Stable key stored on every marker row (e.g. "ldl", "bp_systolic"). */
    val key: String,
    val displayName: String,
    val category: LabMarkerCategory,
    /** Canonical unit prefilled in the editor (e.g. "mmol/L", "mmHg"). */
    val canonicalUnit: String,
    /** How many decimals to show for this marker's values. */
    val decimals: Int,
    /** Direction hint — ALWAYS null (NOOP makes no value judgement). */
    val higherIsBetter: Boolean? = null,
)

/** The built-in, non-diagnostic marker dictionary. Custom markers a user adds are stored
 *  separately, not here. */
object MarkerCatalog {

    /** ~30 common markers across the categories. Order is the suggested picker order. */
    val builtIn: List<MarkerDefinition> = listOf(
        // Lipids (blood panel)
        MarkerDefinition("total_cholesterol", "Total cholesterol", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 2),
        MarkerDefinition("ldl", "LDL cholesterol", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 2),
        MarkerDefinition("hdl", "HDL cholesterol", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 2),
        MarkerDefinition("triglycerides", "Triglycerides", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 2),
        // Glucose
        MarkerDefinition("fasting_glucose", "Fasting glucose", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 1),
        MarkerDefinition("hba1c", "HbA1c", LabMarkerCategory.BLOOD_PANEL, "mmol/mol", 0),
        // Iron studies
        MarkerDefinition("ferritin", "Ferritin", LabMarkerCategory.BLOOD_PANEL, "µg/L", 0),
        MarkerDefinition("iron", "Serum iron", LabMarkerCategory.BLOOD_PANEL, "µmol/L", 1),
        MarkerDefinition("transferrin_saturation", "Transferrin saturation", LabMarkerCategory.BLOOD_PANEL, "%", 0),
        MarkerDefinition("haemoglobin", "Haemoglobin", LabMarkerCategory.BLOOD_PANEL, "g/L", 0),
        // Vitamins
        MarkerDefinition("vitamin_d", "Vitamin D", LabMarkerCategory.BLOOD_PANEL, "nmol/L", 0),
        MarkerDefinition("vitamin_b12", "Vitamin B12", LabMarkerCategory.BLOOD_PANEL, "ng/L", 0),
        MarkerDefinition("folate", "Folate", LabMarkerCategory.BLOOD_PANEL, "µg/L", 1),
        // Thyroid
        MarkerDefinition("tsh", "TSH", LabMarkerCategory.BLOOD_PANEL, "mIU/L", 2),
        MarkerDefinition("free_t4", "Free T4", LabMarkerCategory.BLOOD_PANEL, "pmol/L", 1),
        // Inflammation
        MarkerDefinition("crp", "C-reactive protein (CRP)", LabMarkerCategory.BLOOD_PANEL, "mg/L", 1),
        // Kidney
        MarkerDefinition("egfr", "eGFR", LabMarkerCategory.BLOOD_PANEL, "mL/min/1.73m²", 0),
        MarkerDefinition("creatinine", "Creatinine", LabMarkerCategory.BLOOD_PANEL, "µmol/L", 0),
        // Liver
        MarkerDefinition("alt", "ALT", LabMarkerCategory.BLOOD_PANEL, "U/L", 0),
        MarkerDefinition("ast", "AST", LabMarkerCategory.BLOOD_PANEL, "U/L", 0),
        MarkerDefinition("ggt", "GGT", LabMarkerCategory.BLOOD_PANEL, "U/L", 0),
        // Electrolytes
        MarkerDefinition("sodium", "Sodium", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 0),
        MarkerDefinition("potassium", "Potassium", LabMarkerCategory.BLOOD_PANEL, "mmol/L", 1),
        // Blood pressure (the paired marker — see LabBookProjection.BP_SYSTOLIC_KEY / BP_DIASTOLIC_KEY)
        MarkerDefinition("bp_systolic", "Blood pressure (systolic)", LabMarkerCategory.BLOOD_PRESSURE, "mmHg", 0),
        MarkerDefinition("bp_diastolic", "Blood pressure (diastolic)", LabMarkerCategory.BLOOD_PRESSURE, "mmHg", 0),
        MarkerDefinition("resting_pulse", "Resting pulse", LabMarkerCategory.BLOOD_PRESSURE, "bpm", 0),
        // Body measurements
        MarkerDefinition("weight", "Weight", LabMarkerCategory.BODY_MEASUREMENT, "kg", 1),
        MarkerDefinition("body_fat", "Body fat", LabMarkerCategory.BODY_MEASUREMENT, "%", 1),
        MarkerDefinition("waist", "Waist circumference", LabMarkerCategory.BODY_MEASUREMENT, "cm", 1),
        MarkerDefinition("height", "Height", LabMarkerCategory.BODY_MEASUREMENT, "cm", 1),
    )

    private val byKey: Map<String, MarkerDefinition> = builtIn.associateBy { it.key }

    /** The built-in definition for [key], or null if it's a custom marker. */
    fun definition(key: String): MarkerDefinition? = byKey[key]
}
