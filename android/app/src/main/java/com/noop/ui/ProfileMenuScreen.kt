package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

// MARK: - Profile menu (the top-left avatar destination)
//
// The body profile (age / sex / weight / height / waist / max HR / step calibration) and the Units
// display preferences, split out of Settings into their own screen reached from the Today header's
// leading avatar. The controls are the same ones Settings used to host — same ProfileStore, same
// SharedPreferences keys, same wheel/stepper wiring — just relocated. Re-skinned to the locked NOOP
// component system (NoopCard section + two-column FormRows), matching the surrounding screens.

@Composable
fun ProfileMenuScreen(vm: AppViewModel) {
    val context = LocalContext.current

    // The profile store is stable for the lifetime of this screen; a version counter forces
    // recomposition after each mutating write (SharedPreferences isn't reactive).
    val profile = remember { ProfileStore.from(context) }
    var rev by remember { mutableStateOf(0) }
    fun mutate(block: () -> Unit) { block(); rev++ }

    // Imperial/Metric display preference. Display-only — stored data stays SI. Drives both the body
    // profile entry fields (imperial vs metric) and the Units card, so it's screen-level state.
    var unitSystem by remember { mutableStateOf(UnitPrefs.system(context)) }
    var temperatureRaw by remember {
        mutableStateOf(NoopPrefs.of(context).getString(NoopPrefs.KEY_TEMPERATURE_UNIT, "") ?: "")
    }
    var effortScale by remember { mutableStateOf(UnitPrefs.effortScale(context)) }

    // Steps-estimate calibration screen (WHOOP 4.0), reached from the Profile card's "Steps estimate"
    // tap-through. Full-screen Dialog; a manual-coefficient write bumps `rev` so the summary refreshes.
    var showStepsCalibration by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Profile",
        subtitle = "Your body profile and how NOOP shows units. All on this phone.",
    ) {
        // Read the revision counter so every profile write recomposes this subtree.
        @Suppress("UNUSED_VARIABLE") val tick = rev

        // Header: the large avatar (the photo set in Settings, or the NOOP loop mark) + name/age.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileAvatar(size = 72.dp, contentDescription = "Profile photo")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Profile", style = NoopType.title2, color = Palette.textPrimary)
                Text("Age ${profile.age}", style = NoopType.subhead, color = Palette.textSecondary)
            }
        }

        // --- Profile (body numbers) ---
        ProfileSection(
            icon = Icons.Outlined.Person,
            title = "Profile",
            blurb = "These power your heart-rate zones, calorie estimates and recovery baselines. Keep them accurate.",
        ) {
            Column {
                FormRow(label = "Age") {
                    StepperField(
                        value = profile.age.toString(),
                        accessibility = "Age, ${profile.age} years",
                        // #146: age is derived from a stored date of birth, so it advances on its own. The
                        // stepper re-anchors the DOB via setAge (which clamps to 13..100 — age feeds the
                        // Fitness Age + Vitality engines that gate on age > 0, so it must never go 0/negative).
                        onMinus = { mutate { profile.setAge(profile.age - 1) } },
                        onPlus = { mutate { profile.setAge(profile.age + 1) } },
                    )
                }
                RowDivider()
                FormRow(label = "Sex") {
                    SegmentedPillControl(
                        items = SEX_OPTIONS,
                        selection = SEX_OPTIONS.firstOrNull { it.tag == profile.sex } ?: SEX_OPTIONS[0],
                        label = { it.label },
                        onSelect = { mutate { profile.sex = it.tag } },
                    )
                }
                RowDivider()
                FormRow(label = "Weight") {
                    // Imperial mode steps in whole pounds and stores the kg equivalent; metric steps in
                    // 0.5 kg. The profile is always SI — only the entry unit changes.
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        val lb = UnitFormatter.kgToPounds(profile.weightKg)
                        StepperField(
                            value = "%.0f".format(lb),
                            unit = "lb",
                            accessibility = "Weight, ${lb.roundToInt()} pounds",
                            onMinus = { mutate { profile.weightKg = (lb - 1) / UnitFormatter.POUNDS_PER_KILOGRAM } },
                            onPlus = { mutate { profile.weightKg = (lb + 1) / UnitFormatter.POUNDS_PER_KILOGRAM } },
                        )
                    } else {
                        StepperField(
                            value = "%.1f".format(profile.weightKg),
                            unit = "kg",
                            accessibility = "Weight in kilograms",
                            onMinus = { mutate { profile.weightKg -= 0.5 } },
                            onPlus = { mutate { profile.weightKg += 0.5 } },
                        )
                    }
                }
                RowDivider()
                FormRow(label = "Height") {
                    // Imperial mode steps in whole inches and stores the cm equivalent; metric steps in cm.
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        val (ft, inch) = UnitFormatter.cmToFeetInches(profile.heightCm)
                        val totalInches = UnitFormatter.cmToInches(profile.heightCm).roundToInt()
                        StepperField(
                            value = "$ft′ $inch″",
                            accessibility = "Height, $ft feet $inch inches",
                            onMinus = { mutate { profile.heightCm = (totalInches - 1) * UnitFormatter.CENTIMETERS_PER_INCH } },
                            onPlus = { mutate { profile.heightCm = (totalInches + 1) * UnitFormatter.CENTIMETERS_PER_INCH } },
                        )
                    } else {
                        StepperField(
                            value = "%.0f".format(profile.heightCm),
                            unit = "cm",
                            accessibility = "Height in centimetres",
                            onMinus = { mutate { profile.heightCm -= 1 } },
                            onPlus = { mutate { profile.heightCm += 1 } },
                        )
                    }
                }
                RowDivider()
                // Waist (optional): the one extra body measure that unlocks the Fitness Age VO₂max
                // estimate. Unset (0) by design — the headline Fitness Age never needs it — so it shows
                // "Add" until entered, then steps like Height (inches in imperial, cm in metric).
                // First tap from unset seeds a typical adult waist rather than 1 cm.
                FormRow(label = "Waist (optional)") {
                    Column(horizontalAlignment = Alignment.End) {
                        val hasWaist = profile.waistCm > 0.0
                        if (unitSystem == UnitSystem.IMPERIAL) {
                            val totalInches = UnitFormatter.cmToInches(profile.waistCm).roundToInt()
                            StepperField(
                                value = if (hasWaist) "%d″".format(totalInches) else "Add",
                                accessibility = if (hasWaist) {
                                    "Waist, $totalInches inches"
                                } else {
                                    "Waist, not set. Optional: adds your VO₂max estimate"
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = true) } },
                            )
                        } else {
                            StepperField(
                                value = if (hasWaist) "%.0f".format(profile.waistCm) else "Add",
                                unit = if (hasWaist) "cm" else null,
                                accessibility = if (hasWaist) {
                                    "Waist in centimetres"
                                } else {
                                    "Waist, not set. Optional: adds your VO₂max estimate"
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = true) } },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (hasWaist) "Adds your VO₂max estimate" else "Optional · adds your VO₂max estimate",
                            style = NoopType.footnote,
                            color = if (hasWaist) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                FormRow(label = "Max heart rate") {
                    Column(horizontalAlignment = Alignment.End) {
                        StepperField(
                            value = if (profile.hrMaxOverride > 0) profile.hrMaxOverride.toString() else "Auto",
                            unit = "bpm",
                            accessibility = if (profile.hrMaxOverride == 0) {
                                "Max heart rate override, automatic"
                            } else {
                                "Max heart rate override, ${profile.hrMaxOverride} bpm"
                            },
                            valueColor = if (profile.hrMaxOverride > 0) Palette.textPrimary else Palette.textTertiary,
                            onMinus = { mutate { profile.hrMaxOverride -= 1 } },
                            onPlus = { mutate { profile.hrMaxOverride += 1 } },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (profile.hrMaxOverride > 0) {
                                "Manual override"
                            } else {
                                "Auto · ${profile.hrMaxAuto} bpm (Tanaka)"
                            },
                            style = NoopType.footnote,
                            color = if (profile.hrMaxOverride > 0) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                // Step calibration (#139/#132): daily steps = @57 counter ticks ÷ this divisor.
                // 1.0 = raw pass-through until the true 5/MG tick rate is known. The divisor goes
                // up to 30 because a 5/MG motion counter can overcount by ~24×; the stepper uses a
                // variable increment (fine near 1.0, coarse up top) so high values stay reachable.
                FormRow(label = "Step calibration") {
                    StepperField(
                        value = "%.1f".format(profile.stepTicksPerStep),
                        accessibility = "Step calibration, %.1f counter ticks per step"
                            .format(profile.stepTicksPerStep),
                        onMinus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = false) } },
                        onPlus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = true) } },
                    )
                }
                Text(
                    "Counter ticks per step. Leave at 1.0 unless your steps run high. On a WHOOP 5/MG they can run very high (10× or more), so this goes up to 30. Walk a known 1,000 steps and divide NOOP's count by the real count to get your value.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                RowDivider()
                // Tap-through to the WHOOP 4.0 steps-ESTIMATE calibration (a SEPARATE thing from the 5/MG
                // @57 counter divisor above): a 4.0 sends no step count, so NOOP estimates steps from
                // motion and calibrates that to the phone. Opens the explainer + fit + comparison + manual
                // override screen. Mirrors the macOS Profile "Steps estimate" row.
                val stepsSummary = when {
                    profile.stepsManualCoefficient > 0 -> "Manual"
                    profile.stepsCalibrationCoefficient > 0 ->
                        "Auto · ${StepsCalibrationFormat.confidenceLabel(profile.stepsCalibrationConfidence)} confidence"
                    else -> "Not calibrated"
                }
                val stepsRowInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .liquidPress(stepsRowInteraction)
                        .clickable(
                            interactionSource = stepsRowInteraction,
                            indication = null,
                        ) { showStepsCalibration = true }
                        .semantics {
                            contentDescription =
                                "Steps estimate calibration. $stepsSummary. Opens the calibration screen."
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Steps estimate", style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        stepsSummary,
                        style = NoopType.footnote,
                        color = if (profile.stepsManualCoefficient > 0) Palette.accent else Palette.textTertiary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    "For a WHOOP 4.0, which sends no step count: NOOP estimates steps from motion, calibrated to your phone. Tap to see how close it is and adjust it.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }

        // --- Units ---
        // Imperial/Metric display toggle + a separate temperature override. Display-only — nothing
        // stored changes; NOOP keeps everything in SI and converts at the point of display.
        ProfileSection(
            icon = Icons.Filled.Straighten,
            title = "Units",
            blurb = "Choose how distances, weights, heights, temperatures and Effort are shown. Your data is always stored the same way. This only changes the display.",
        ) {
            Column {
                FormRow(label = "Measurement system") {
                    SegmentedPillControl(
                        items = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL),
                        selection = unitSystem,
                        label = { if (it == UnitSystem.METRIC) "Metric" else "Imperial" },
                        onSelect = {
                            unitSystem = it
                            NoopPrefs.setUnitSystem(context, it)
                        },
                    )
                }
                RowDivider()
                FormRow(label = "Temperature") {
                    // Three-way: "Match" follows the system above; °C / °F pin it explicitly. Stored as an
                    // empty string ("match") or the TemperatureUnit raw value.
                    SegmentedPillControl(
                        items = listOf("", TemperatureUnit.CELSIUS.raw, TemperatureUnit.FAHRENHEIT.raw),
                        selection = temperatureRaw,
                        label = {
                            when (it) {
                                TemperatureUnit.CELSIUS.raw -> "°C"
                                TemperatureUnit.FAHRENHEIT.raw -> "°F"
                                else -> "Match"
                            }
                        },
                        onSelect = {
                            temperatureRaw = it
                            NoopPrefs.setTemperatureUnit(context, TemperatureUnit.fromRaw(it))
                        },
                    )
                }
                RowDivider()
                // Effort scale (#268) — NOOP's native 0–100 Effort or WHOOP's 0–21 Day Strain axis.
                // Display-only; the stored value never changes, so a flip just re-labels every read-out.
                FormRow(label = "Effort scale") {
                    SegmentedPillControl(
                        items = listOf(EffortScale.HUNDRED, EffortScale.WHOOP),
                        selection = effortScale,
                        label = { if (it == EffortScale.HUNDRED) "0-100" else "0-21" },
                        onSelect = {
                            effortScale = it
                            UnitPrefs.setEffortScale(context, it)
                        },
                    )
                }
            }
        }

        // Steps-estimate calibration, opened from the Profile card's "Steps estimate" row. Same
        // full-screen Dialog idiom as Settings used; a manual-coefficient write bumps `rev` so the
        // Profile summary row reflects the new state on dismiss.
        if (showStepsCalibration) {
            Dialog(
                onDismissRequest = { showStepsCalibration = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    StepsCalibrationScreen(
                        vm = vm,
                        profile = profile,
                        onProfileChanged = { rev++ },
                        onClose = { showStepsCalibration = false },
                    )
                }
            }
        }
    }
}

// MARK: - Local helpers (private copies of the Settings idiom, matching the per-screen pattern the
// Automations / Notifications settings screens already use — each keeps its own private SettingsSection
// + FormRow + RowDivider rather than sharing one internal copy).

/**
 * A grouped settings card: a "Settings" overline + icon + title header, an explanatory blurb, then
 * content. Mirrors the private SettingsSection in SettingsScreen.
 */
@Composable
private fun ProfileSection(
    icon: ImageVector,
    title: String,
    blurb: String,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Settings")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

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

// MARK: - Sex options (mirrors SettingsScreen's private copy)

private data class SexOption(val tag: String, val label: String)

private val SEX_OPTIONS = listOf(
    SexOption("male", "Male"),
    SexOption("female", "Female"),
    SexOption("nonbinary", "Non-binary"),
)

// MARK: - Waist stepper (optional VO₂max input; mirrors SettingsScreen's private copy)

/** A typical adult waist (cm) used as the first value when stepping up from "unset" (0). ~34". */
private const val WAIST_SEED_CM = 86.0

/** Step the waist by one centimetre, seeding [WAIST_SEED_CM] when starting from unset (0). */
private fun waistCmStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    return (current + if (up) 1.0 else -1.0).coerceAtLeast(WAIST_SEED_CM - 30.0)
}

/** Step the waist by one inch (entry unit in imperial; stored as cm), seeding [WAIST_SEED_CM] from unset. */
private fun waistInchesStep(current: Double, up: Boolean): Double {
    if (current <= 0.0) return if (up) WAIST_SEED_CM else 0.0
    val inches = UnitFormatter.cmToInches(current).roundToInt()
    val nextInches = (inches + if (up) 1 else -1)
    val nextCm = nextInches * UnitFormatter.CENTIMETERS_PER_INCH
    return nextCm.coerceAtLeast(WAIST_SEED_CM - 30.0)
}
