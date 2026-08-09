package com.noop.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.CalibrationMilestones
import com.noop.analytics.RustScores

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
    // profile entry fields (imperial vs metric) and the Units card. Read straight from [UnitPrefs],
    // which is snapshot state, so this screen and every other one flip together.
    val unitSystem = UnitPrefs.system(context)
    val temperatureRaw = UnitPrefs.temperatureOverrideRaw(context)
    val effortScale = UnitPrefs.effortScale(context)

    // Steps-estimate calibration screen (WHOOP 4.0), reached from the Profile card's "Steps estimate"
    // tap-through. Full-screen Dialog; a manual-coefficient write bumps `rev` so the summary refreshes.
    var showStepsCalibration by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Wheel-picker option lists for weight / height. The stored profile stays SI; the labels re-format per
    // the live unit system and the picker maps the chosen index back to SI on select.
    val weightSteps = remember { generateSequence(30.0) { it + 0.5 }.takeWhile { it <= 250.0001 }.toList() }
    val heightSteps = remember { (120..230).toList() }
    val weightOptions = remember(unitSystem) { weightSteps.map { UnitFormatter.massFromKilograms(it, unitSystem) } }
    val heightOptions = remember(unitSystem) { heightSteps.map { UnitFormatter.heightFromCentimeters(it.toDouble(), unitSystem) } }

    // Modern Photo Picker for the optional profile photo (no READ_EXTERNAL_STORAGE permission needed).
    // Returns a single image Uri (or null if cancelled); we decode + downscale + persist off the main
    // thread via ProfileAvatarStore, which updates the live avatar everywhere. Stored only on this phone.
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ProfileAvatarStore.setAvatarFromUri(context, uri)
            }
            if (!ok) {
                Toast.makeText(context, R.string.profile_avatar_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    val profileTitle = stringResource(R.string.profile_title)
    val photoLabel = stringResource(R.string.profile_photo_a11y)
    val weightLabel = stringResource(R.string.profile_weight)
    val heightLabel = stringResource(R.string.profile_height)

    ScreenScaffold(
        title = profileTitle,
        subtitle = stringResource(R.string.profile_subtitle),
    ) {
        // Read the revision counter so every profile write recomposes this subtree.
        @Suppress("UNUSED_VARIABLE") val tick = rev

        // Header: the large avatar (the photo set below in the Profile menu, or the NOOP loop mark) + name/age.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            ProfileAvatar(size = 72.dp, contentDescription = photoLabel)
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(profileTitle, style = NoopType.title2, color = Palette.textPrimary)
                Text(
                    stringResource(R.string.profile_age, profile.age),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        }

        // --- Profile photo (optional, on-device) ---
        // Moved here from Settings — the avatar the top-left menu and the Today header show. A large avatar
        // + Choose/Change, and once set a Remove. Local-only: the picked image is downscaled and kept on
        // this phone, never uploaded. Reads ProfileAvatarStore.hasAvatar (snapshot state) so the controls
        // update the instant a photo is set or cleared.
        ProfileSection(
            icon = Icons.Outlined.AccountCircle,
            title = stringResource(R.string.profile_photo_title),
            blurb = stringResource(R.string.profile_photo_blurb),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                ProfileAvatar(size = 64.dp, contentDescription = photoLabel)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        NoopButton(
                            text = stringResource(
                                if (ProfileAvatarStore.hasAvatar) R.string.profile_change_photo
                                else R.string.profile_choose_photo,
                            ),
                            kind = NoopButtonKind.Secondary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                avatarPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        if (ProfileAvatarStore.hasAvatar) {
                            NoopButton(
                                text = stringResource(R.string.profile_remove_photo),
                                kind = NoopButtonKind.Tertiary,
                                modifier = Modifier.weight(1f),
                                onClick = { ProfileAvatarStore.clearAvatar(context) },
                            )
                        }
                    }
                }
            }
        }

        // --- Profile (body numbers) ---
        ProfileSection(
            icon = Icons.Outlined.Person,
            title = profileTitle,
            blurb = stringResource(R.string.profile_body_blurb),
        ) {
            Column {
                FormRow(label = stringResource(R.string.profile_birthday)) {
                    BirthdayPickerField(
                        dobMillis = profile.dateOfBirthMillis,
                        accessibility = stringResource(R.string.profile_birthday_a11y, profile.age),
                        onPick = { mutate { profile.dateOfBirthMillis = it } },
                    )
                }
                RowDivider()
                FormRow(label = stringResource(R.string.profile_sex)) {
                    SegmentedPillControl(
                        items = SEX_OPTIONS,
                        selection = SEX_OPTIONS.firstOrNull { it.tag == profile.sex } ?: SEX_OPTIONS[0],
                        label = { context.getString(it.label) },
                        onSelect = { mutate { profile.sex = it.tag } },
                    )
                }
                RowDivider()
                FormRow(label = weightLabel) {
                    WheelPickerField(
                        // Full re-labelled string (e.g. "74.5 kg" / "164.2 lb"); unit folded into value.
                        value = UnitFormatter.massFromKilograms(profile.weightKg, unitSystem),
                        accessibility = weightLabel,
                        options = weightOptions,
                        selectedIndex = weightSteps.indices.minByOrNull { kotlin.math.abs(weightSteps[it] - profile.weightKg) } ?: 0,
                        dialogTitle = weightLabel,
                        onSelected = { mutate { profile.weightKg = weightSteps[it] } },
                    )
                }
                RowDivider()
                FormRow(label = heightLabel) {
                    WheelPickerField(
                        value = UnitFormatter.heightFromCentimeters(profile.heightCm, unitSystem),
                        accessibility = heightLabel,
                        options = heightOptions,
                        selectedIndex = heightSteps.indices.minByOrNull { kotlin.math.abs(heightSteps[it] - profile.heightCm) } ?: 0,
                        dialogTitle = heightLabel,
                        onSelected = { mutate { profile.heightCm = heightSteps[it].toDouble() } },
                    )
                }
                RowDivider()
                // Waist (optional): the one extra body measure that unlocks the Fitness Age VO₂max
                // estimate. Unset (0) by design — the headline Fitness Age never needs it — so it shows
                // "Add" until entered, then steps like Height (inches in imperial, cm in metric).
                // First tap from unset seeds a typical adult waist rather than 1 cm.
                FormRow(label = stringResource(R.string.profile_waist)) {
                    Column(horizontalAlignment = Alignment.End) {
                        val hasWaist = profile.waistCm > 0.0
                        val addWord = stringResource(R.string.profile_add)
                        val waistUnsetLabel = stringResource(R.string.profile_waist_a11y_unset)
                        if (unitSystem == UnitSystem.IMPERIAL) {
                            val totalInches = UnitFormatter.cmToInches(profile.waistCm).roundToInt()
                            StepperField(
                                value = if (hasWaist) "%d″".format(totalInches) else addWord,
                                accessibility = if (hasWaist) {
                                    stringResource(R.string.profile_waist_a11y_inches, totalInches)
                                } else {
                                    waistUnsetLabel
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistInchesStep(profile.waistCm, up = true) } },
                            )
                        } else {
                            StepperField(
                                value = if (hasWaist) "%.0f".format(profile.waistCm) else addWord,
                                unit = if (hasWaist) "cm" else null,
                                accessibility = if (hasWaist) {
                                    stringResource(R.string.profile_waist_a11y_cm)
                                } else {
                                    waistUnsetLabel
                                },
                                valueColor = if (hasWaist) Palette.textPrimary else Palette.textTertiary,
                                onMinus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = false) } },
                                onPlus = { mutate { profile.waistCm = waistCmStep(profile.waistCm, up = true) } },
                            )
                        }
                        Spacer(Modifier.height(Metrics.space6))
                        Text(
                            text = stringResource(
                                if (hasWaist) R.string.profile_waist_adds_vo2
                                else R.string.profile_waist_optional_vo2,
                            ),
                            style = NoopType.footnote,
                            color = if (hasWaist) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                FormRow(label = stringResource(R.string.profile_max_hr)) {
                    Column(horizontalAlignment = Alignment.End) {
                        StepperField(
                            value = if (profile.hrMaxOverride > 0) {
                                profile.hrMaxOverride.toString()
                            } else {
                                stringResource(R.string.profile_auto)
                            },
                            unit = "bpm",
                            accessibility = if (profile.hrMaxOverride == 0) {
                                stringResource(R.string.profile_max_hr_a11y_auto)
                            } else {
                                stringResource(R.string.profile_max_hr_a11y, profile.hrMaxOverride)
                            },
                            valueColor = if (profile.hrMaxOverride > 0) Palette.textPrimary else Palette.textTertiary,
                            onMinus = { mutate { profile.hrMaxOverride -= 1 } },
                            onPlus = { mutate { profile.hrMaxOverride += 1 } },
                        )
                        Spacer(Modifier.height(Metrics.space6))
                        Text(
                            text = if (profile.hrMaxOverride > 0) {
                                stringResource(R.string.profile_max_hr_manual)
                            } else {
                                stringResource(R.string.profile_max_hr_auto, profile.hrMaxAuto)
                            },
                            style = NoopType.footnote,
                            color = if (profile.hrMaxOverride > 0) Palette.accent else Palette.textTertiary,
                        )
                    }
                }
                RowDivider()
                // Step calibration: daily steps = @57 counter ticks ÷ this divisor.
                // 1.0 = raw pass-through until the true 5/MG tick rate is known. The divisor goes
                // up to 30 because a 5/MG motion counter can overcount by ~24×; the stepper uses a
                // variable increment (fine near 1.0, coarse up top) so high values stay reachable.
                FormRow(label = stringResource(R.string.profile_step_calibration)) {
                    val ticks = "%.1f".format(profile.stepTicksPerStep)
                    StepperField(
                        value = ticks,
                        accessibility = stringResource(R.string.profile_step_calibration_a11y, ticks),
                        onMinus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = false) } },
                        onPlus = { mutate { profile.stepTicksPerStep = ProfileStore.steppedStepScale(profile.stepTicksPerStep, up = true) } },
                    )
                }
                Text(
                    stringResource(R.string.profile_step_calibration_note),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                RowDivider()
                // Tap-through to the WHOOP 4.0 steps-ESTIMATE calibration (a SEPARATE thing from the 5/MG
                // @57 counter divisor above): a 4.0 sends no step count, so NOOP estimates steps from
                // motion and calibrates that to the phone. Opens the explainer + fit + comparison + manual
                // override screen. Mirrors the macOS Profile "Steps estimate" row.
                val stepsSummary = when {
                    profile.stepsManualCoefficient > 0 -> stringResource(R.string.profile_steps_manual)
                    profile.stepsCalibrationCoefficient > 0 -> stringResource(
                        R.string.profile_steps_auto,
                        stringResource(
                            StepsCalibrationFormat.confidenceLabelRes(profile.stepsCalibrationConfidence),
                        ),
                    )
                    else -> stringResource(R.string.profile_steps_not_calibrated)
                }
                val stepsRowLabel = stringResource(R.string.profile_steps_row_a11y, stepsSummary)
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
                        .semantics { contentDescription = stepsRowLabel }
                        .padding(vertical = Metrics.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
                ) {
                    Text(
                        stringResource(R.string.profile_steps_estimate),
                        style = NoopType.body,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stepsSummary,
                        style = NoopType.footnote,
                        color = if (profile.stepsManualCoefficient > 0) Palette.accent else Palette.textTertiary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
                Text(
                    stringResource(R.string.profile_steps_note),
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
            title = stringResource(R.string.profile_units_title),
            blurb = stringResource(R.string.profile_units_blurb),
        ) {
            Column {
                val metricLabel = stringResource(R.string.profile_metric)
                val imperialLabel = stringResource(R.string.profile_imperial)
                val matchLabel = stringResource(R.string.profile_temperature_match)
                FormRow(label = stringResource(R.string.profile_measurement_system)) {
                    SegmentedPillControl(
                        items = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL),
                        selection = unitSystem,
                        label = { if (it == UnitSystem.METRIC) metricLabel else imperialLabel },
                        onSelect = { NoopPrefs.setUnitSystem(context, it) },
                    )
                }
                RowDivider()
                FormRow(label = stringResource(R.string.profile_temperature)) {
                    // Three-way: "Match" follows the system above; °C / °F pin it explicitly. Stored as an
                    // empty string ("match") or the TemperatureUnit raw value.
                    SegmentedPillControl(
                        items = listOf("", TemperatureUnit.CELSIUS.raw, TemperatureUnit.FAHRENHEIT.raw),
                        selection = temperatureRaw,
                        label = {
                            when (it) {
                                TemperatureUnit.CELSIUS.raw -> "°C"
                                TemperatureUnit.FAHRENHEIT.raw -> "°F"
                                else -> matchLabel
                            }
                        },
                        onSelect = { NoopPrefs.setTemperatureUnit(context, TemperatureUnit.fromRaw(it)) },
                    )
                }
                RowDivider()
                // Effort scale — NOOP's native 0–100 Effort or WHOOP's 0–21 Day Strain axis.
                // Display-only; the stored value never changes, so a flip just re-labels every read-out.
                FormRow(label = stringResource(R.string.profile_effort_scale)) {
                    SegmentedPillControl(
                        items = listOf(EffortScale.HUNDRED, EffortScale.WHOOP),
                        selection = effortScale,
                        label = { if (it == EffortScale.HUNDRED) "0-100" else "0-21" },
                        onSelect = { UnitPrefs.setEffortScale(context, it) },
                    )
                }
            }
        }

        ProfileSection(
            icon = Icons.Filled.Language,
            title = stringResource(R.string.profile_language_title),
            blurb = stringResource(R.string.profile_language_blurb),
        ) {
            Column {
                val currentTag = NoopLocale.current(context).tag
                NoopLocale.SUPPORTED.forEachIndexed { index, option ->
                    if (index > 0) RowDivider()
                    LanguageRow(
                        label = option.label,
                        selected = option.tag == currentTag,
                        onSelect = {
                            if (option.tag != currentTag) {
                                NoopLocale.set(context, option.tag)
                                (context as? Activity)?.recreate()
                            }
                        },
                    )
                }
            }
        }

        // --- Calibration milestones ---
        val allDays by vm.recentDays.collectAsStateWithLifecycle()
        val bankedNights = remember(allDays) {
            RustScores.bankedNights(allDays.map { it.avgHrv })
        }
        if (CalibrationMilestones.isCalibrating(bankedNights)) {
            CalibrationMilestonesCard(progress = CalibrationMilestones.progress(bankedNights))
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
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Overline(stringResource(R.string.nav_settings))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                ) {
                    Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(Metrics.iconSmall))
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

/** One language choice. Tapping it persists the tag and recreates the activity so resources reload. */
@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onSelect)
            .padding(vertical = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        Text(
            label,
            style = NoopType.body,
            color = if (selected) Palette.textPrimary else Palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(Metrics.iconSmall),
            )
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
            .padding(vertical = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
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


// MARK: - Sex options (mirrors SettingsScreen's private copy)

private data class SexOption(val tag: String, @StringRes val label: Int)

private val SEX_OPTIONS = listOf(
    SexOption("male", R.string.profile_sex_male),
    SexOption("female", R.string.profile_sex_female),
    SexOption("nonbinary", R.string.profile_sex_nonbinary),
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
