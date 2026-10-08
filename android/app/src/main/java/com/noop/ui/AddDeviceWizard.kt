package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import com.noop.ui.icons.CheckBox
import com.noop.ui.icons.CheckBoxOutlineBlank
import com.noop.ui.icons.Circle
import androidx.compose.material.icons.filled.Close
import com.noop.ui.icons.FileDownload
import com.noop.ui.icons.PhonelinkErase
import com.noop.ui.icons.Science
import androidx.compose.material.icons.filled.Warning
import com.noop.ui.icons.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ble.ExperimentalBrand
import com.noop.ble.OuraLiveSource
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.oura.OuraRingGen
import kotlinx.coroutines.launch

// MARK: - Add a device - guided, branching wizard (MW-4)
//
// Different bands pair COMPLETELY differently, so this wizard asks the device TYPE first, then gives
// type-specific prep guidance and runs the RIGHT scan/connect for that type:
//
//   • WHOOP → the present-scan ([AppViewModel.presentWhoopScanAll]) over BOTH families at once; the
//     strap says which it is. Lists nearby straps from [AppViewModel.discoveredWhoops] (a present-only
//     mode that never auto-connects).
//   • Oura ring (EXPERIMENTAL) → its OWN factory-reset-and-adopt sub-flow over an isolated
//     [OuraLiveSource]. Lists from its `discovered` flow.
//
// Registration goes through [AppViewModel.registerDevice] → DeviceRegistry; the SourceCoordinator reacts
// to the active-device change and connects (pinning the WHOOP / starting the Oura source). The wizard
// never touches the BLE client directly - only the AppViewModel pass-throughs. WHOOP-FIRST: WHOOP is the
// primary band; the type list shows it first and a footer reiterates it. Renders cleanly with nothing
// nearby (the type picker, every prep step, and the searching/empty pick state all need no hardware).
// Faithful Kotlin twin of Strand/Screens/AddDeviceWizard.swift. US English throughout.

/** What the user is adding. Drives the prep copy AND which scan/register path runs. */
private enum class DeviceType {
    Whoop,
    // EXPERIMENTAL tier - best-effort, clean-room, can't be hardware-verified here. Fails to an honest
    // message and never fabricates data.
    Oura;

    val isWhoop: Boolean get() = this == Whoop
    /** Family FALLBACK for a strap whose advertisement did not name one. Nothing on the wire tells a 5.0
     *  from an MG and the user cannot tell NOOP either, so the pick reads the advertised family and this
     *  is only the floor. */
    val whoopModel: WhoopModel?
        get() = if (this == Whoop) WhoopModel.WHOOP5_MG else null

    /** True for the EXPERIMENTAL tier (shown under a clearly-labelled "Experimental" heading). */
    val isExperimental: Boolean get() = this == Oura

    val title: String
        get() = when (this) {
            Whoop -> "WHOOP"
            Oura -> "Oura ring"
        }
}

private enum class WizardStep { Type, Prep, Pick, Confirm }

/**
 * The Oura factory-reset-and-adopt sub-flow (section 2 of the onboarding UX spec). The Oura type does NOT
 * use the generic Prep/Pick/Confirm shape - it owns this step machine, entered from the type list:
 *   - [Gate]      What you get / what you lose + the irreversible red consent gate (or Advanced key field).
 *   - [Prep]      Factory-reset the ring in the Oura app first (single-owner warning).
 *   - [Pick]      Live scan + pick a ring; "still paired to Oura" rings list with a warning.
 *   - [Confirm]   Detected generation + per-gen capability checklist + the destructive "Take over" action.
 *   - [Adopting]  Honest key-install progress sub-states (no fake percent).
 *   - [Failed]    An honest dead-end when adoption fails, never a fabricated success.
 */


@Composable
fun AddDeviceWizard(
    viewModel: AppViewModel,
    onClose: () -> Unit,
    /** Routes to the non-destructive file-import lane (Data Sources). The Oura gate's "Keep the Oura app
     *  instead (import a file)" link and every honest Oura failure offer this, so the destructive takeover
     *  is never the only door. Defaults to a plain close so existing call sites keep compiling. */
    onUseFileImport: () -> Unit = onClose,
) {
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(WizardStep.Type) }
    var type by remember { mutableStateOf<DeviceType?>(null) }

    // --- Oura factory-reset-and-adopt sub-flow: the Oura type drives its own step machine, and this is
    // inert for every other device type. ---
    /** Inline Confirm state: the key install is running. Not a step — Confirm draws the spinner. */
    var ouraAdopting by remember { mutableStateOf(false) }
    /** Inline Confirm state: the adopt failed and Confirm draws the honest reason. */
    var ouraFailed by remember { mutableStateOf(false) }
    /** The honest, irreversible "this disconnects the ring from Oura" box must be ticked to continue. */
    var ouraConsent by remember { mutableStateOf(false) }
    /** The Advanced (B-Alt) path: the user supplies their own 16-byte key and keeps the Oura app. */
    var ouraAdvanced by remember { mutableStateOf(false) }
    /** The pasted 32-hex-character ring key (Advanced path only). */
    var ouraKeyDraft by remember { mutableStateOf("") }
    /** The ring picked from the live scan, with its detected generation. */
    var pickedOura by remember { mutableStateOf<OuraLiveSource.DiscoveredRing?>(null) }
    /** The generation confirmed for the picked ring (best-effort detect, defaulted to gen3). */
    var ouraGen by remember { mutableStateOf(OuraRingGen.GEN3) }
    /** Final destructive-confirm alert before the key install. */
    var ouraConfirmAdopt by remember { mutableStateOf(false) }

    // The chosen WHOOP strap (the Oura path carries its pick in [pickedOura] above).
    var pickedWhoop by remember { mutableStateOf<WhoopBleClient.DiscoveredWhoop?>(null) }

    var nameDraft by remember { mutableStateOf("") }
    var askMakeActive by remember { mutableStateOf(false) }

    // Discovery-only EXPERIMENTAL Oura scanner: a throwaway, isolated [OuraLiveSource] (its OWN scanner +
    // GATT, never the WHOOP client; no-op persist/live, null key). The wizard reads only its `discovered` /
    // `scanning` flows; the SourceCoordinator owns the real connect once the adopted ring becomes active.
    val ouraScanner = remember { viewModel.makeOuraScanner() }

    fun startScan(t: DeviceType) {
        // Only WHOOP types reach the generic Prep/Pick flow; Oura runs its own step machine (ouraScanner).
        if (t.isWhoop) viewModel.presentWhoopScanAll()
    }

    fun stopAllScans() {
        viewModel.stopWhoopScan()
        ouraScanner.stop()
    }

    // Belt-and-braces: stop whichever scan is live whenever the wizard leaves composition.
    DisposableEffect(Unit) { onDispose { stopAllScans() } }

    fun goBack() {
        val isOura = type == DeviceType.Oura
        when (step) {
            WizardStep.Type -> Unit
            WizardStep.Prep -> {
                // Advanced is a disclosure on this step, so leaving Prep clears it with everything else.
                if (isOura) { ouraConsent = false; ouraAdvanced = false; ouraKeyDraft = "" }
                type = null
                step = WizardStep.Type
            }
            WizardStep.Pick -> { stopAllScans(); pickedOura = null; step = WizardStep.Prep }
            WizardStep.Confirm -> {
                // Re-enter the pick step and restart its scan so the user can choose a different device.
                if (isOura) { ouraScanner.scan(); pickedOura = null; ouraAdopting = false; ouraFailed = false }
                else { type?.let { startScan(it) }; pickedWhoop = null }
                step = WizardStep.Pick
            }
        }
    }

    val confirmAdvertisedName = run {
        pickedWhoop?.let { return@run it.name?.takeIf { n -> n.isNotBlank() } ?: (type?.title ?: "Device") }
        type?.title ?: "Device"
    }
    val confirmName = nameDraft.trim().ifEmpty { confirmAdvertisedName }
    // The generic Confirm step is only ever reached by a WHOOP type now (Oura confirms in its own flow).
    val confirmBrand = if (type?.isWhoop == true) "WHOOP" else "Device"
    val confirmRssi = pickedWhoop?.rssi ?: -70

    fun finishAdd(makeActive: Boolean) {
        stopAllScans()
        val now = System.currentTimeMillis() / 1000
        val pw = pickedWhoop
        val device: PairedDeviceRow? = if (pw != null && type?.whoopModel != null) {
            // WHOOP: full capability set; id namespaced by address; model "4.0" / "5.0 / MG".
            // The 5-series label keeps the slash because nothing on the wire tells a 5.0 from an MG —
            // one WhoopModel covers both — and "5.0 MG" read as a claim that the strap is an MG.
            // The family is what the strap ADVERTISED, not what was picked from the type menu; the menu
            // is only the fallback for a strap whose advertisement did not say. Recording it is what
            // makes the SourceCoordinator's reconnect target the right service, the same way onboarding
            // records the family at pick.
            val wm = pw.family ?: type!!.whoopModel!!
            viewModel.noteDetectedModel(wm)
            val modelLabel = if (wm == WhoopModel.WHOOP4) "4.0" else "5.0 / MG"
            PairedDeviceRow(
                id = "whoop-${pw.address}",
                brand = "WHOOP",
                model = modelLabel,
                nickname = confirmName,
                peripheralId = pw.address,
                sourceKind = SourceKind.liveBLE.name,
                capabilities = "hr,hrv,spo2,skinTemp,sleep,strainLoad",
                status = DeviceStatus.paired.name,
                addedAt = now,
                lastSeenAt = now,
            )
        } else {
            null
        }
        if (device == null) { onClose(); return }
        scope.launch { viewModel.registerDevice(device, makeActive = makeActive) }
        onClose()
    }

    /**
     * Register an adopted (or Advanced-key) Oura ring. Builds the oura [PairedDeviceRow] - id
     * "oura-<address>", model = the picked generation's display name (the row recovers the gen via
     * OuraRingGen.from(model)), sourceKind "oura" (routes the SourceCoordinator to [OuraLiveSource]),
     * gen-filtered capabilities - then registers it active so the live source starts. The Advanced path
     * also stores the user-supplied 16-byte key in the encrypted key store under the SAME id so the live
     * source's authKey closure can read it. Mirrors the macOS finishAdd Oura branch.
     */
    /**
     * Register an adopted (or Advanced-key) Oura ring active. [closeAfter] controls whether the wizard
     * dismisses immediately:
     *   - Advanced-key path: true. The ring authenticates with the user's supplied key (no install), so
     *     there is no Adopting progress to watch; register and close.
     *   - Standard destructive adopt: false. We arm the one-shot adopt-intent, register active (the live
     *     source then runs the dangerous key install), and STAY on the Adopting step so the observed
     *     adoptPhase / needs-pairing can drive it to success (close) or a REACHABLE honest Failed. Without
     *     this the user was trapped on a fake "Installing NOOP's key" spinner that never resolved.
     */
    fun finishAddOura(closeAfter: Boolean) {
        stopAllScans()
        val ring = pickedOura ?: run { onClose(); return }
        val now = System.currentTimeMillis() / 1000
        // Brand string, id prefix and the "oura" routing are catalog facts, never spelled here.
        val oura = ExperimentalBrand.OURA
        val deviceId = "${oura.idPrefix}-${ring.address}"
        // Advanced (B-Alt): persist the pasted key BEFORE registering so the active source authenticates
        // with it WITHOUT a factory reset (the Oura app keeps working). This path NEVER arms adopt-intent,
        // so the live source never sends the dangerous install opcode.
        // Standard adopt: arm the one-shot adopt-intent BEFORE registering active, so the SourceCoordinator
        // consumes it when it builds the live source and the dangerous post-factory-reset key install is
        // reachable for exactly this one session. The user already passed the
        // irreversible-consent gate AND the second destructive "Take over" confirm to get here.
        if (ouraAdvanced) {
            val key = parseHexKey(ouraKeyDraft)
            if (key != null) viewModel.saveOuraInstallKey(deviceId, key)
        } else {
            viewModel.armOuraAdopt(deviceId)
        }
        val device = PairedDeviceRow(
            id = deviceId,
            brand = oura.displayBrand,
            model = ouraGen.displayName,
            nickname = nameDraft.trim().takeIf { it.isNotEmpty() && it != "Oura ring" },
            peripheralId = ring.address,
            sourceKind = oura.sourceKind.name,
            // Gen-filtered: the OuraMetric rawValues are byte-identical to the app-side Metric rawValues
            // (hr/hrv/spo2/skinTemp/sleep), so the joined string round-trips through the registry unchanged.
            capabilities = ouraGen.capabilities.joinToString(",") { it.raw },
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        // The takeover always makes the ring active (it is the user's new live source); no make-active
        // prompt - the destructive gate already committed to "this is now your device".
        scope.launch { viewModel.registerDevice(device, makeActive = true) }
        if (closeAfter) onClose()
    }

    // The live adopt-failure reason (the source's needs-pairing message). Collected here so the honest
    // Failed step can surface it instead of static copy, mirroring the Swift wizard's `model.ouraNeedsPairing`.
    // The Adopting->Failed observer (the LaunchedEffect below) reads the SAME value.
    val adoptNeedsPairing by viewModel.ouraNeedsPairing.collectAsStateWithLifecycle()

    // A ring and a strap walk the SAME four steps; this only picks which content each step draws.
    val isOura = type == DeviceType.Oura

    AlertDialog(
        onDismissRequest = { stopAllScans(); onClose() },
        containerColor = Palette.surfaceOverlay,
        title = {
            val hTitle = if (isOura) ouraHeaderTitle(step, ouraAdopting, ouraFailed) else headerTitle(step, type)
            val hSub = if (isOura) ouraHeaderSubtitle(step, ouraAdopting, ouraFailed) else headerSubtitle(step)
            // Back is offered on every step except the very first (the type list), and is withheld while
            // the key install is in flight so the user cannot interrupt it.
            val showBack = step != WizardStep.Type && !(isOura && ouraAdopting)
            Row(verticalAlignment = Alignment.Top) {
                if (showBack) {
                    IconButton(onClick = { goBack() }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = Palette.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(hTitle, style = NoopType.title2, color = Palette.textPrimary)
                    hSub?.let {
                        Text(it, style = NoopType.caption, color = Palette.textTertiary)
                    }
                }
                IconButton(onClick = { stopAllScans(); onClose() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textTertiary, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            // Make the wizard body scrollable so no step is ever cut off under large font scaling or on
            // large/short displays (the device-type list was taller than the dialog and the lower rows,
            // e.g. Oura, were unreachable). The AlertDialog text slot does not scroll its content on its own,
            // so we own the scroll here. Every step renders unchanged inside this scroll container.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // ONE step machine for every brand: Type -> Prep -> Pick -> Confirm. A ring differs only in
            // what each step draws, never in how many there are.
            when (step) {
                WizardStep.Type -> TypeStep(onPick = { t ->
                    type = t
                    nameDraft = ""
                    if (t == DeviceType.Oura) { ouraConsent = false; ouraAdvanced = false; ouraKeyDraft = "" }
                    step = WizardStep.Prep
                })

                WizardStep.Prep -> if (isOura) {
                    OuraPrepStep(
                        consent = ouraConsent,
                        onConsent = { ouraConsent = it },
                        advanced = ouraAdvanced,
                        onAdvanced = { ouraAdvanced = it; if (!it) ouraKeyDraft = "" },
                        keyDraft = ouraKeyDraft,
                        onKeyDraft = { ouraKeyDraft = it },
                        onScan = { ouraScanner.scan(); step = WizardStep.Pick },
                        onUseFileImport = { stopAllScans(); onUseFileImport() },
                    )
                } else {
                    type?.let { t -> PrepStep(t, onScan = { startScan(t); step = WizardStep.Pick }) }
                }

                WizardStep.Pick -> if (isOura) {
                    OuraPickStep(
                        scanner = ouraScanner,
                        onPick = { ring ->
                            pickedOura = ring
                            // Confirm the generation from the picked ring's best-effort detection, defaulting
                            // to gen3 (the verified-corpus generation) when the name carries no marker.
                            ouraGen = ring.detectedGen ?: OuraRingGen.GEN3
                            nameDraft = "Oura ring"
                            ouraScanner.stopScan()
                            step = WizardStep.Confirm
                        },
                        onRescan = { ouraScanner.scan() },
                    )
                } else {
                    type?.let { t ->
                        WhoopPickStep(
                            viewModel = viewModel,
                            onSelect = { strap ->
                                pickedWhoop = strap
                                nameDraft = strap.name?.takeIf { it.isNotBlank() } ?: t.title
                                viewModel.stopWhoopScan()
                                step = WizardStep.Confirm
                            },
                            onRescan = { viewModel.presentWhoopScanAll() },
                        )
                    }
                }

                WizardStep.Confirm -> if (isOura) {
                    OuraConfirmStep(
                        advanced = ouraAdvanced,
                        gen = ouraGen,
                        name = nameDraft,
                        onName = { nameDraft = it },
                        // Adopting and failure are STATES of this step, not destinations.
                        adopting = ouraAdopting,
                        failed = ouraFailed,
                        failureReason = adoptNeedsPairing,
                        onAdopt = {
                            // The standard adopt installs NOOP's key on the ring, so it gates behind the
                            // final "Take over this ring?" alert. The Advanced key path authenticates with
                            // the user's own key and never resets the ring, so it connects straight through.
                            if (ouraAdvanced) finishAddOura(closeAfter = true)
                            else ouraConfirmAdopt = true
                        },
                        onTryAgain = {
                            ouraAdopting = false
                            ouraFailed = false
                            ouraScanner.scan()
                            pickedOura = null
                            step = WizardStep.Pick
                        },
                        onUseFileImport = { stopAllScans(); onUseFileImport() },
                    )
                } else {
                    ConfirmStep(
                        advertisedName = confirmAdvertisedName,
                        brand = confirmBrand,
                        rssi = confirmRssi,
                        name = nameDraft,
                        onName = { nameDraft = it },
                        onAdd = { askMakeActive = true },
                    )
                }
            }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )

    // After adding, offer to make the new device active.
    if (askMakeActive) {
        NoopConfirmDialog(
            title = "Make this your active device?",
            text = "Make $confirmName your active device now? It will provide your live data. You can change this any time.",
            confirmLabel = "Make active",
            onConfirm = { askMakeActive = false; finishAdd(makeActive = true) },
            onDismiss = { askMakeActive = false; finishAdd(makeActive = false) },
            cancelLabel = "Not now",
        )
    }

    // Final destructive confirm before the Oura key install (Step D's system alert). Tapping "Take over"
    // moves to the honest Adopting progress, then registers the ring. Mirrors the macOS adopt confirm.
    if (ouraConfirmAdopt) {
        NoopConfirmDialog(
            title = "Take over this ring?",
            text = "NOOP will install its own key on the ring and become its owner. The Oura app will no longer control this ring. This is intended and it cannot be undone from NOOP.",
            confirmLabel = "Take over",
            destructive = true,
            onConfirm = {
                ouraConfirmAdopt = false
                ouraAdopting = true
                finishAddOura(closeAfter = false)
            },
            onDismiss = { ouraConfirmAdopt = false },
        )
    }

    // Drive the Adopting step to success (the active source reached streaming -> close) or to a REACHABLE
    // honest Failed step (the active source reported its adopt failed or announced needs-pairing). Only acts
    // while on the Adopting step, so a later steady-state needs-pairing on the device card never reopens this.
    // Mirrors the Swift wizard's onChange(of: model.ouraAdoptPhase) / ouraNeedsPairing observers; a
    // LaunchedEffect keeps the state write a side effect of the observed change, not a composition write.
    val adoptPhase by viewModel.ouraAdoptPhase.collectAsStateWithLifecycle()
    LaunchedEffect(type, ouraAdopting, adoptPhase, adoptNeedsPairing) {
        if (type != DeviceType.Oura || !ouraAdopting) return@LaunchedEffect
        when {
            adoptPhase == com.noop.ble.OuraLiveSource.AdoptPhase.Streaming -> { stopAllScans(); onClose() }
            adoptPhase == com.noop.ble.OuraLiveSource.AdoptPhase.Failed -> { ouraAdopting = false; ouraFailed = true }
            // A needs-pairing message during Adopting is an honest failure too (covers the no-ack / ack!=OK
            // paths that surface via needsPairing rather than a phase flip alone).
            adoptNeedsPairing != null -> { ouraAdopting = false; ouraFailed = true }
        }
    }
}

private fun headerTitle(step: WizardStep, type: DeviceType?): String = when (step) {
    WizardStep.Type -> "Add a device"
    WizardStep.Prep -> type?.title ?: "Add a device"
    WizardStep.Pick -> "Pick your device"
    WizardStep.Confirm -> "Name & confirm"
}

private fun headerSubtitle(step: WizardStep): String? = when (step) {
    WizardStep.Type -> "What are you adding?"
    WizardStep.Prep -> "Get it ready, then scan."
    WizardStep.Pick -> "Tap the one that's yours."
    WizardStep.Confirm -> null
}

// MARK: - Oura header titles (the shared four steps, worded for a ring)

private fun ouraHeaderTitle(step: WizardStep, adopting: Boolean, failed: Boolean): String = when (step) {
    WizardStep.Type -> "Add a device"
    WizardStep.Prep -> "Oura ring"
    WizardStep.Pick -> "Pick the ring"
    WizardStep.Confirm -> when {
        adopting -> "Taking over your ring"
        failed -> "Could not take over"
        else -> "Your ring"
    }
}

private fun ouraHeaderSubtitle(step: WizardStep, adopting: Boolean, failed: Boolean): String? = when (step) {
    WizardStep.Type -> null
    WizardStep.Prep -> "Take it over locally. Beta."
    WizardStep.Pick -> "Tap the one that's yours."
    WizardStep.Confirm -> if (adopting || failed) null else null
}

// MARK: - Step 1 - type picker

@Composable
private fun TypeStep(onPick: (DeviceType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        TypeRow(Icons.Filled.Watch, DeviceType.Whoop.title, "4.0, 5.0 or MG. NOOP works out which it is") {
            onPick(DeviceType.Whoop)
        }

        // EXPERIMENTAL tier - clearly labelled, opt-in, best-effort. Honest about what it can actually
        // read; never fabricates data.
        Overline("Experimental", modifier = Modifier.padding(top = 8.dp))
        ExperimentalTierNote()
        TypeRow(Icons.Filled.Circle, DeviceType.Oura.title, "Take over your ring locally. Beta. This replaces the Oura app.") {
            onPick(DeviceType.Oura)
        }
    }
}

/** A shared "this tier is experimental" note shown on the type-list heading and every experimental prep
 *  step. Honest, US-neutral, no em-dashes. */
@Composable
private fun ExperimentalTierNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.statusWarning.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Science, contentDescription = null, tint = Palette.statusWarning, modifier = Modifier.size(18.dp))
        Text(
            "Experimental, best-effort support. We're still testing these, so they might not connect on " +
                "every device. They never make up data, and they'll tell you honestly when live isn't possible.",
            style = NoopType.footnote,
            color = Palette.statusWarning,
        )
    }
}

@Composable
private fun TypeRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .frostedCardSurface(cornerRadius = 14.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title. $subtitle" }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = NoopType.headline, color = Palette.textPrimary)
            Text(subtitle, style = NoopType.caption, color = Palette.textTertiary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The one-phone pairing warning shown before pairing a WHOOP strap. A WHOOP band bonds to a single
 * device/app at a time, so connecting it to NOOP means it won't stream to the official WHOOP app at the
 * same time (and vice versa). Honest + reversible: re-pairing in the other app hands the strap back. No
 * em-dashes. Mirrors the iOS one-phone warning card so all platforms say the same thing.
 */
@Composable
private fun OnePhoneWarningCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.statusWarning.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.PhonelinkErase,
            contentDescription = null,
            tint = Palette.statusWarning,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Text(
                "One phone at a time",
                style = NoopType.headline,
                color = Palette.statusWarning,
            )
            Text(
                "A WHOOP strap bonds to a single device. While it's connected to NOOP it won't stream " +
                    "to the official WHOOP app, and the other way round. It's reversible: pair it in the " +
                    "other app whenever you want it back.",
                style = NoopType.footnote,
                color = Palette.statusWarning,
            )
        }
    }
}

// MARK: - Step 2 - type-specific prep + guidance

@Composable
private fun PrepStep(type: DeviceType, onScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space14), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                // PrepStep is only ever reached by a WHOOP type (Oura runs its own flow); the band glyph
                // applies. Oura is kept for completeness of the branch.
                if (type == DeviceType.Oura) Icons.Filled.FileDownload else Icons.Filled.Watch,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(28.dp),
            )
            Text(type.title, style = NoopType.title2, color = Palette.textPrimary)
        }

        if (type.isWhoop) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.statusWarning.copy(alpha = 0.10f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, tint = Palette.statusWarning, modifier = Modifier.size(18.dp))
                Text(
                    "WHOOP 4.0 is fully supported. 5.0 / MG support is newer and still experimental.",
                    style = NoopType.footnote,
                    color = Palette.statusWarning,
                )
            }
        } else if (type.isExperimental) {
            ExperimentalTierNote()
        }

        // A WHOOP strap bonds to ONE phone/app at a time. Make the trade-off explicit BEFORE pairing so it
        // isn't a surprise, with the honest reassurance that it is reversible. Mirrors the iOS one-phone
        // pairing warning card. Shown for both WHOOP models (the constraint is the strap's, not the app's).
        if (type.isWhoop) {
            OnePhoneWarningCard()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .frostedCardSurface(cornerRadius = 14.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            prepInstructions(type).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10), verticalAlignment = Alignment.Top) {
                    Text("•", style = NoopType.body, color = Palette.accent)
                    Text(line, style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }

        TextButton(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.accent)
                .semantics { contentDescription = "Scan for ${type.title}" },
        ) {
            Text("Scan", style = NoopType.headline, color = Palette.goldDeepText)
        }
    }
}

/** Type-specific "get it ready" guidance - the point of the branching wizard. US English copy. */
private fun prepInstructions(type: DeviceType): List<String> = when (type) {
    DeviceType.Whoop -> listOf(
        "Put your WHOOP on your wrist and make sure it's awake.",
        "A strap bonds to one device at a time, so unpair it from the official WHOOP app first.",
        "NOOP will look for it nearby and work out whether it is a 4.0, a 5.0 or an MG.",
    )
    // Oura runs the factory-reset-and-adopt prep inside OuraFlow (ouraPrepInstructions), so this generic
    // branch is unreached for Oura; kept for the exhaustive when.
    DeviceType.Oura -> ouraPrepInstructions
}

/** The factory-reset prep checklist for the Oura adopt flow (Step B of the onboarding UX spec). No
 *  em-dashes; matches the iOS copy. */
private val ouraPrepInstructions: List<String> = listOf(
    "Open the official Oura app and remove this ring (Oura calls it \"factory reset\" or \"unpair and " +
        "reset\"). This wipes the ring's owner so NOOP can take it over.",
    "Keep the ring on the charger or on your finger so it stays awake.",
    "Make sure the Oura app is fully closed. A ring answers one owner at a time.",
    "When the ring is reset and waking, tap Scan below.",
)

// MARK: - Step 3 - pick from the live scan

// `internal` (not `private`) so the first-run OnboardingScreen can reuse the SAME per-band picker the
// wizard uses, instead of auto-grabbing whatever strap the phone already holds (its helpers PickList /
// DiscoveredRow stay private to this file).
@Composable
internal fun WhoopPickStep(
    viewModel: AppViewModel,
    onSelect: (WhoopBleClient.DiscoveredWhoop) -> Unit,
    onRescan: () -> Unit,
) {
    val found by viewModel.discoveredWhoops.collectAsStateWithLifecycle()
    PickList(searching = true, isEmpty = found.isEmpty(), onRescan = onRescan) {
        found.sortedByDescending { it.rssi }.forEach { strap ->
            DiscoveredRow(
                name = strap.name?.takeIf { it.isNotBlank() } ?: "WHOOP",
                // Show the family the scan detected (the merged onboarding scan lists both at once), so a
                // user with a 4.0 and a 5/MG can tell them apart. Falls back to "WHOOP" when unresolved.
                subtitle = strap.family?.displayName ?: "WHOOP",
                rssi = strap.rssi,
                onTap = { onSelect(strap) },
            )
        }
    }
}

// MARK: Step 2 - Prep: everything the user must know and do before scanning
//
// One screen, not three. It carries what the old Gate, Prep and Advanced-key steps each carried:
// the beta warning, what you get and lose, the reset instructions, the single-owner warning, the
// irreversible-consent tick, and the Advanced key field as a disclosure. Advanced is a disclosure
// and not a mode, so opening it never walks the user backwards.

@Composable
private fun OuraPrepStep(
    consent: Boolean,
    onConsent: (Boolean) -> Unit,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    keyDraft: String,
    onKeyDraft: (String) -> Unit,
    onScan: () -> Unit,
    onUseFileImport: () -> Unit,
) {
    val parsedKey = parseHexKey(keyDraft)
    val keyError = advanced && keyDraft.isNotBlank() && parsedKey == null
    // Standard path needs the consent tick; the Advanced path needs a valid key instead, because it
    // authenticates with the user's own key and never resets the ring.
    val canScan = if (advanced) parsedKey != null else consent

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        OuraAmberPanel(
            "Beta. Read this first.",
            "Local Oura support is new and we cannot test every ring here. It may not connect on your " +
                "ring, and it can change between updates. NOOP never makes up a number. If something does " +
                "not work, it will tell you plainly.",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .frostedCardSurface(cornerRadius = 14.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.space14),
        ) {
            Overline("What you get")
            OuraBulletList(
                listOf(
                    "Your ring talks to NOOP only, fully offline, no Oura account.",
                    "Live heart rate, and HRV when the ring can measure it.",
                    "Overnight sleep staging, resting heart rate, skin-temperature trend, motion and " +
                        "battery, read straight off the ring.",
                    "NOOP's own Charge, Effort and Rest, computed on your device from published methods.",
                ),
            )
            Overline("What you lose")
            OuraBulletList(
                listOf(
                    "The Oura app and your Oura account stop working with this ring. This is the point. " +
                        "You are replacing Oura.",
                    "Oura's own Readiness and Sleep scores. NOOP does not copy them. It computes its own.",
                    "Anything that needs Oura's cloud (web dashboard, Oura's coaching, shared circles).",
                    "Likely your Oura warranty and support, because the ring is no longer paired to Oura. " +
                        "Treat this as permanent.",
                ),
            )
        }

        // The reset instructions and the single-owner warning belong to the destructive path only.
        if (!advanced) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .frostedCardSurface(cornerRadius = 14.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                Overline("Get your ring ready")
                ouraPrepInstructions.forEach { line ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(line, style = NoopType.body, color = Palette.textSecondary)
                    }
                }
            }
            OuraAmberPanel(
                "A ring talks to one owner at a time.",
                "If the Oura app is still running it will hold the ring and adoption will fail. Force-quit " +
                    "Oura, then scan.",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.statusCritical.copy(alpha = 0.10f))
                    .clickable { onConsent(!consent) }
                    .semantics {
                        contentDescription =
                            "I understand this disconnects the ring from Oura and that NOOP cannot undo it for me."
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (consent) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = Palette.statusCritical,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "I understand this disconnects the ring from Oura and that NOOP cannot undo it for me. To " +
                        "go back to Oura I would factory-reset the ring again and set it up in the Oura app.",
                    style = NoopType.footnote,
                    color = Palette.statusCritical,
                )
            }
        }

        // Advanced, disclosed in place: the user's own 16-byte key, which keeps the Oura app working.
        if (advanced) {
            OuraAmberPanel(
                "For power users.",
                "If you extracted your ring's 16-byte key from a previous Oura setup, NOOP can talk to the " +
                    "ring with that key WITHOUT resetting it, so the Oura app keeps working too. NOOP does not " +
                    "extract keys for you and cannot help you find one. If you do not know what this means, " +
                    "turn this off and use the standard setup or file import.",
            )
            Overline("Ring key (32 hex characters)")
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { onKeyDraft(it) },
                singleLine = true,
                isError = keyError,
                placeholder = {
                    Text(
                        "0123456789abcdef0123456789abcdef",
                        style = NoopType.body,
                        color = Palette.textTertiary,
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii),
                visualTransformation = VisualTransformation.None,
                colors = wizardFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Ring key, 32 hex characters" },
            )
            if (keyError) {
                Text("That is not a 32-character hex key.", style = NoopType.footnote, color = Palette.statusCritical)
            }
            Text(
                "NOOP stores this key only on this device, in the same place it stores your paired bands.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }

        TextButton(
            onClick = onScan,
            enabled = canScan,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (canScan) Palette.accent else Palette.surfaceInset)
                .semantics { contentDescription = "Scan for your ring" },
        ) {
            Text(
                "Scan for your ring",
                style = NoopType.headline,
                color = if (canScan) Palette.goldDeepText else Palette.textTertiary,
            )
        }
        TextButton(onClick = onUseFileImport, modifier = Modifier.fillMaxWidth()) {
            Text("Keep the Oura app instead (import a file)", style = NoopType.subhead, color = Palette.accent)
        }
        TextButton(onClick = { onAdvanced(!advanced) }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (advanced) "Use the standard setup instead" else "Advanced: I already have my ring's key",
                style = NoopType.footnote,
                color = Palette.accent,
            )
        }
    }
}


// MARK: Step C - Pick the ring (live scan)

@Composable
private fun OuraPickStep(
    scanner: OuraLiveSource,
    onPick: (OuraLiveSource.DiscoveredRing) -> Unit,
    onRescan: () -> Unit,
) {
    val discovered by scanner.discovered.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(
                if (scanning) "Searching…" else "Idle",
                tone = if (scanning) StrandTone.Accent else StrandTone.Neutral,
                pulsing = scanning,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRescan) {
                Text("Rescan", style = NoopType.subhead, color = Palette.accent)
            }
        }
        if (discovered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .frostedCardSurface(cornerRadius = 14.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(22.dp))
                Text("Searching…", style = NoopType.body, color = Palette.textPrimary)
                Text(
                    "Not showing up? Make sure you reset the ring in the Oura app and force-quit it, then " +
                        "tap Rescan. A ring still owned by Oura will not list here.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                discovered.sortedByDescending { it.rssi }.forEach { ring ->
                    DiscoveredRow(
                        name = ring.name,
                        // Subtitle = the detected generation (best-effort); "Oura ring" when undetected.
                        subtitle = ring.detectedGen?.displayName ?: "Oura ring",
                        rssi = ring.rssi,
                        onTap = { onPick(ring) },
                    )
                }
            }
        }
    }
}

// MARK: Step D - Detect generation + confirm + the destructive adopt action

@Composable
private fun OuraConfirmStep(
    advanced: Boolean,
    gen: OuraRingGen,
    name: String,
    onName: (String) -> Unit,
    adopting: Boolean,
    failed: Boolean,
    failureReason: String?,
    onAdopt: () -> Unit,
    onTryAgain: () -> Unit,
    onUseFileImport: () -> Unit,
) {
    // The key install and its failure are states of this step, not destinations: the user stays
    // where they were and watches it resolve in place.
    if (adopting) {
        OuraAdoptingStep()
        return
    }
    if (failed) {
        OuraFailedStep(failureReason, onTryAgain, onUseFileImport)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        // The identified ring: gen name + per-gen capability checklist + a Beta pill.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .frostedCardSurface(cornerRadius = 14.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                Icon(Icons.Filled.Circle, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(24.dp))
                Text(gen.displayName, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                StatePill("Beta", tone = StrandTone.Warning, showsDot = false)
            }
            // Per-gen capability checklist: tick for supported, dash for not-available, * for an estimate.
            ouraCapabilityRows(gen).forEach { (mark, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.Top) {
                    Text(mark, style = NoopType.caption, color = Palette.textTertiary, modifier = Modifier.width(14.dp))
                    Text(label, style = NoopType.caption, color = Palette.textSecondary)
                }
            }
            Text(
                "Beta. * is an on-device estimate.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }

        Overline("Name")
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text("Oura ring", style = NoopType.body, color = Palette.textTertiary) },
            colors = wizardFieldColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Device name" },
        )

        // The adopt action. The destructive (key-install) path is red; the Advanced key path is not
        // destructive (it does not reset the ring), so it reads as a plain accent connect.
        if (advanced) {
            TextButton(
                onClick = onAdopt,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.accent)
                    .semantics { contentDescription = "Connect to this ring" },
            ) {
                Text("Connect to this ring", style = NoopType.headline, color = Palette.goldDeepText)
            }
            Text(
                "Both NOOP and the Oura app can use a ring you own by key, but only one can hold the " +
                    "Bluetooth link at a time.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        } else {
            TextButton(
                onClick = onAdopt,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.statusCritical.copy(alpha = 0.16f))
                    .semantics { contentDescription = "Take over this ring" },
            ) {
                Text("Take over this ring", style = NoopType.headline, color = Palette.statusCritical)
            }
        }
    }
}

// MARK: Step E - Adopting (key install) progress

@Composable
private fun OuraAdoptingStep() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .frostedCardSurface(cornerRadius = 14.dp)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(22.dp))
            Text("Taking over your ring", style = NoopType.headline, color = Palette.textPrimary)
        }
        Text(
            "Installing NOOP's key and confirming the ring answers only to NOOP. Keep the ring close and " +
                "do not open the Oura app.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )
    }
}

// MARK: Step E (failure) - honest dead-end, never a fabricated success

@Composable
private fun OuraFailedStep(reason: String?, onTryAgain: () -> Unit, onUseFileImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .frostedCardSurface(cornerRadius = 14.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Text("We could not take over this ring.", style = NoopType.headline, color = Palette.textPrimary)
        // Surface the live adopt-failure reason when the source reported one; otherwise the static help.
        // Mirrors the Swift wizard's `model.ouraNeedsPairing ?? <static fallback>`.
        Text(
            reason ?: "The most common cause is the ring was not fully reset in the Oura app, or the Oura " +
                "app is still running. Reset the ring again, force-quit Oura, then try once more. If it keeps " +
                "failing, your ring may be a generation NOOP cannot adopt yet. You can still use file import.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )
        // Honest recovery reassurance (Swift parity): a failed adopt never bricks the ring.
        Text(
            "The ring is not bricked. To go back to where you started, factory-reset it again and set it " +
                "up in the Oura app.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            TextButton(
                onClick = onTryAgain,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.accent),
            ) {
                Text("Try again", style = NoopType.headline, color = Palette.goldDeepText)
            }
            TextButton(
                onClick = onUseFileImport,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.surfaceInset),
            ) {
                Text("Use file import", style = NoopType.headline, color = Palette.accent)
            }
        }
    }
}

// MARK: - Oura shared pieces

/** An amber heads-up panel (the experimental-note / single-owner-warning treatment): bold lead line +
 *  body, statusWarning at 0.10 fill. No em-dashes. */
@Composable
private fun OuraAmberPanel(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.statusWarning.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.statusWarning, modifier = Modifier.size(16.dp))
            Text(title, style = NoopType.subhead, color = Palette.statusWarning)
        }
        Text(body, style = NoopType.footnote, color = Palette.statusWarning)
    }
}

/** A simple bulleted list used in the gate's "what you get / lose" sections. */
@Composable
private fun OuraBulletList(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.Top) {
                Text("•", style = NoopType.body, color = Palette.accent)
                Text(line, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

/**
 * The per-generation capability checklist (section 3 of the onboarding UX spec). Each row is a (mark,
 * label) pair: a tick for decoded-and-used, * for a best-effort on-device estimate, and a dash for
 * not-available-off-the-ring. Gen3/Ring4 are the verified path; the newer (gen4-family / gen5) variant
 * carries the same set with the extra caveat that decoding is least proven. Mirrors the macOS capability
 * matrix; no Oura Readiness/Sleep score or absolute SpO2 % ever comes off the ring.
 */
private fun ouraCapabilityRows(gen: OuraRingGen): List<Pair<String, String>> {
    val live = if (gen == OuraRingGen.GEN5) "*" else "✓"   // newer rings: live HR is best-effort
    val firm = if (gen == OuraRingGen.GEN5) "*" else "✓"   // resting HR / sleep / battery
    return listOf(
        live to "Live heart rate",
        "*" to "HRV (rMSSD)",
        firm to "Resting heart rate",
        firm to "Sleep staging",
        "*" to "Skin-temperature trend",
        "*" to "Steps / motion",
        firm to "Battery",
        "-" to "SpO₂ (blood oxygen %)",
        "-" to "Oura Readiness / Sleep score",
    )
}

/**
 * Parse a 32-hex-character ring key string into 16 unsigned bytes (0..255), or null when it is not exactly
 * 32 hex chars. Whitespace is ignored so a pasted key with stray spaces still validates. Shared by the
 * Advanced gate's validation and finishAddOura's key store write. Mirrors the macOS 16-byte/32-hex check.
 */
private fun parseHexKey(input: String): IntArray? {
    val hex = input.filterNot { it.isWhitespace() }
    if (hex.length != 32) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return IntArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
}

/** Shared pick-step shell: a searching status bar + a Rescan button, then either the searching card
 *  (while [isEmpty]) or the caller's discovered [rows]. Mirrors the iOS pick step's ScanStatusBar +
 *  SearchingCard. */
@Composable
private fun PickList(
    searching: Boolean,
    isEmpty: Boolean,
    onRescan: () -> Unit,
    rows: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(
                if (searching) "Searching…" else "Idle",
                tone = if (searching) StrandTone.Accent else StrandTone.Neutral,
                pulsing = searching,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRescan) {
                Text("Rescan", style = NoopType.subhead, color = Palette.accent)
            }
        }
        if (isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .frostedCardSurface(cornerRadius = 14.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(22.dp))
                Text("Searching…", style = NoopType.body, color = Palette.textPrimary)
                Text(
                    "Make sure it's awake and not connected elsewhere.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) { rows() }
        }
    }
}

@Composable
private fun DiscoveredRow(name: String, subtitle: String, rssi: Int, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .frostedCardSurface(cornerRadius = 12.dp)
            .clickable(onClick = onTap)
            .semantics { contentDescription = "$name, signal ${SignalBars.level(rssi)} of 4" }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(rssi)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(name, style = NoopType.body, color = Palette.textPrimary)
            Text(subtitle, style = NoopType.caption, color = Palette.textTertiary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// MARK: - Step 4 - name + confirm

@Composable
private fun ConfirmStep(
    advertisedName: String,
    brand: String,
    rssi: Int,
    name: String,
    onName: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .frostedCardSurface(cornerRadius = 12.dp)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBars(rssi)
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(advertisedName, style = NoopType.headline, color = Palette.textPrimary)
                Text(brand, style = NoopType.caption, color = Palette.textTertiary)
            }
        }

        Overline("Name")
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text("Device name", style = NoopType.body, color = Palette.textTertiary) },
            colors = wizardFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Device name" },
        )

        TextButton(
            onClick = onAdd,
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (name.trim().isNotEmpty()) Palette.accent else Palette.surfaceInset),
        ) {
            Text(
                "Add",
                style = NoopType.headline,
                color = if (name.trim().isNotEmpty()) Palette.goldDeepText else Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun wizardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
)
