package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.ble.PuffinExperiment
import com.noop.ble.WhoopModel
import com.noop.ingest.RawSensorExport
import com.noop.testcentre.CaptureAccumulator
import com.noop.testcentre.CaptureKind
import com.noop.testcentre.DisplayPerformanceMonitor
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import com.noop.testcentre.TestCentre
import com.noop.testcentre.TestCentreLayout
import com.noop.testcentre.TestDomain
import com.noop.testcentre.TestMode
import com.noop.testcentre.TestModeRegistry
import com.noop.testcentre.TestReportFlow
import com.noop.testcentre.TestReportLink
import kotlinx.coroutines.launch

/**
 * Settings -> Test Centre (spec section 7), the Android twin of TestCentreView. Two sections: domain
 * test modes (rendered from the registry projection) and diagnostic tools. A NEW file because
 * SettingsScreen.kt (132 KB) cannot grow. Section 1 renders from TestCentreLayout.visibleModes; the
 * Diagnostic tools card now consolidates every diagnostic control in one place: the bug-report button,
 * strap-log / recalibrate / debug-logging, the raw-sensor CSV export, and (on a 5/MG) the raw-frame
 * capture + share, all on the same bindings the Settings cards used before the move. No em-dash.
 */
@Composable
fun TestCentreScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val testCentre = remember { TestCentre.from(context) }
    // CAPTURE-D: a UI scope to emit the data-volume line off the toggle-on path (a store read, so it can't
    // run inline in the non-suspend onToggle).
    val scope = rememberCoroutineScope()

    // The strap model the Settings #22 gate reads, mirrored here so the 5/MG block shows for a 5/MG only.
    val live by vm.live.collectAsStateWithLifecycle()
    val selectedModelName = remember {
        NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
    }
    // Match the Settings `showFiveMGControls` gate exactly: pref OR a live-detected 5/MG this session, so a
    // 5/MG connected before its pref is written still sees the experimental block. (SettingsScreen.kt:346.)
    val is5MG = selectedModelName == WhoopModel.WHOOP5_MG.name || live.whoop5Detected

    // A report awaiting the mandatory review-before-share gate (spec section 12). Non-null shows the
    // review dialog; confirming runs TestReportFlow.run.
    var pendingReport by remember { mutableStateOf<PendingReport?>(null) }

    // The Display frame monitor follows the screen: if the Display mode was already on when the screen
    // appears, (re)start it; always tear it down when the screen leaves so no Choreographer callback
    // survives a navigation away. The mode flag stays on (the user's test is still active); the monitor
    // resumes next time this screen is shown. This keeps the perpetual-callback contract: a callback
    // exists only while the Test Centre is on screen with the Display mode on.
    DisposableEffect(Unit) {
        if (testCentre.active(TestDomain.DISPLAY)) {
            // CAPTURE-D (#797): wire the data-volume provider so the monitor can emit ONE `dataVolume` line
            // read STRAIGHT from the store (not the reactive caches), against the registry's active strap id.
            DisplayPerformanceMonitor.dataVolumeProvider = { vm.repo.dataVolumeSnapshot(vm.activeStrapId) }
            DisplayPerformanceMonitor.start(context) { line ->
                vm.ble.externalLog(line, TestDomain.DISPLAY)
            }
        }
        onDispose { DisplayPerformanceMonitor.stop() }
    }
    // CAPTURE-D: emit the data-volume line once when the Display mode is active on entry. Kept off the
    // (non-suspend) DisposableEffect: the read hits the store, so it runs from a coroutine.
    LaunchedEffect(Unit) {
        if (testCentre.active(TestDomain.DISPLAY)) DisplayPerformanceMonitor.emitDataVolume()
    }

    ScreenScaffold(
        title = "Test Centre",
        subtitle = "Turn on a test for the thing that's wrong, wear the strap, then tap Report. Everything stays on this phone.",
    ) {
        // --- Section 1: Domain test modes ---
        SettingsSectionTC(
            icon = Icons.Filled.BugReport,
            title = "Test modes",
            blurb = "Each test logs extra detail for one part of the app while you wear the strap, then bundles it for a bug report.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TestCentreLayout.visibleModes(is5MG).forEach { mode ->
                    TestModeRow(
                        mode = mode,
                        active = testCentre.active(mode.domain),
                        startedAtSeconds = testCentre.startedAt(mode.domain),
                        // #965: the shareable strap log the report exports, so the row's "K of N" is the
                        // HONEST per-mode captured-day count (CaptureAccumulator), not an elapsed-clock proxy.
                        // Recomputes with `live` (collected above) so the count updates as new days land.
                        logText = vm.ble.exportLogText(),
                        onToggle = { on ->
                            if (on) testCentre.activate(mode.domain) else testCentre.deactivate(mode.domain)
                            // Display & Performance owns a live frame monitor. It must run ONLY while the
                            // mode is on: start it on toggle-on (wiring its sink to the redacting DISPLAY
                            // log), tear it down on toggle-off so no Choreographer callback survives.
                            // Zero-cost when off.
                            if (mode.domain == TestDomain.DISPLAY) {
                                if (on) {
                                    // CAPTURE-D (#797): wire the data-volume provider (store-read, active id),
                                    // start the monitor, then emit the upfront dataVolume line off a scope.
                                    DisplayPerformanceMonitor.dataVolumeProvider =
                                        { vm.repo.dataVolumeSnapshot(vm.activeStrapId) }
                                    DisplayPerformanceMonitor.start(context) { line ->
                                        vm.ble.externalLog(line, TestDomain.DISPLAY)
                                    }
                                    scope.launch { DisplayPerformanceMonitor.emitDataVolume() }
                                } else {
                                    DisplayPerformanceMonitor.stop()
                                }
                            }
                        },
                        onReport = {
                            // Launched (#1002): buildPending is now suspend (storage probe reads the store).
                            scope.launch { pendingReport = buildPending(context, mode, vm.ble.exportLogText(), vm) }
                        },
                    )
                }
            }
        }

        // --- Section 2: Diagnostic tools (now hosts the relocated bug-report + raw exports too) ---
        DiagnosticToolsCard(
            vm,
            is5MG,
            onReport = {
                // Launched (#1002): buildPending is now suspend (storage probe reads the store).
                scope.launch { pendingReport = buildPending(context, MASTER_REPORT_MODE, vm.ble.exportLogText(), vm) }
            },
        )
    }

    pendingReport?.let { p ->
        ReportReviewDialog(
            previewText = p.gate.previewText,
            modeInactive = p.modeInactive,
            onCancel = { pendingReport = null },
            onShare = {
                p.gate.confirm()
                TestReportFlow.run(
                    context = context,
                    profile = p.profile,
                    title = p.title,
                    version = BuildConfig.VERSION_NAME,
                    platform = "Android",
                    osVersion = android.os.Build.VERSION.RELEASE ?: "?",
                    gate = p.gate,
                    entries = p.entries,
                )
                pendingReport = null
            },
        )
    }
}

/** A report staged for the mandatory review gate: the profile, its title, the already-redacted entries
 *  and the gate built over them. The Kotlin gate keeps its entries private, so we hold them here too to
 *  hand TestReportFlow.run the same list it reviews. [modeInactive] (#1002): the selected profile's test
 *  mode is not on at report time, so the bundle carries no capture for the very thing being reported -
 *  the review dialog warns off it (the #812 capture_check only grades ACTIVE modes, so it can't). */
private class PendingReport(
    val profile: TestDomain,
    val title: String,
    val entries: List<Pair<String, ByteArray>>,
    val gate: ReportReviewGate,
    val modeInactive: Boolean = false,
)

/** The "whole app" report profile for the section-3 manual Report button. MASTER is not a registry mode
 *  (it has no wear-and-capture flow), so the deep-link self-applies the test:all label via this. */
private val MASTER_REPORT_MODE = TestMode(
    domain = TestDomain.MASTER, title = "Bug report", blurb = "", icon = "ic_bug",
    priority = com.noop.testcentre.TestPriority.HIGH, captures = emptyList(),
    questionnaire = emptyList(), liveReadout = emptyList(),
    capture = com.noop.testcentre.CaptureKind.Toggle, includesScreenshot = false, requires5MG = false,
)

/** Assemble the redacted, capped bundle for a profile and wrap it in the review gate. Suspend (#1002):
 *  the storage probe reads the store, so the callers launch it on the UI scope; the dialog presents off
 *  the same `pendingReport` state a beat after the tap. */
private suspend fun buildPending(
    context: android.content.Context,
    mode: TestMode,
    logText: String,
    vm: AppViewModel,
): PendingReport {
    // #1002 REAL storage probe, replacing the Phase-1 zeros in meta.json:
    //  - db_bytes: the Room store's on-disk footprint (noop_whoop.db + its -wal/-shm sidecars);
    //  - rows: per-table row counts via the store (WhoopRepository.storageRowCounts);
    //  - raw_capture_bytes: the 5/MG frame-recorder JSONL on disk (both rotation generations).
    // Everything read, never guessed; when nothing was readable the probe stays null and meta keeps the
    // honest zeroed block. Mirrors the Swift TestCentreReport.storageProbe.
    val dbPath = context.getDatabasePath(com.noop.data.WhoopDatabase.DB_NAME)
    var dbBytes = 0L
    for (suffix in listOf("", "-wal", "-shm")) {
        val f = java.io.File(dbPath.path + suffix)
        if (f.exists()) dbBytes += f.length()
    }
    val rows = vm.repo.storageRowCounts()
    var rawBytes = 0L
    for (name in listOf(
        com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE,
        com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE + ".1",
    )) {
        val f = java.io.File(context.filesDir, name)
        if (f.exists()) rawBytes += f.length()
    }
    val storage = if (dbBytes > 0L || rows.isNotEmpty() || rawBytes > 0L) {
        com.noop.testcentre.TestBundleMeta.Storage(
            dbBytes = dbBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rows = rows,
            rawCaptureBytes = rawBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    } else {
        null
    }
    // #1002: the connected model - the scan/connect path persists the DETECTED family to this pref, so
    // it reflects the strap that actually linked; the display name matches the Swift wire value.
    val strapModel = NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
        ?.let { name -> runCatching { WhoopModel.valueOf(name).displayName }.getOrNull() }
    val entries = TestBundleAssembler.assemble(context, mode.domain, logText, storage, strapModel)
    val modeInactive = mode.domain != TestDomain.MASTER && !TestCentre.from(context).active(mode.domain)
    return PendingReport(mode.domain, mode.title, entries, ReportReviewGate(entries), modeInactive)
}

@Composable
private fun TestModeRow(
    mode: TestMode,
    active: Boolean,
    startedAtSeconds: Long?,
    logText: String,
    onToggle: (Boolean) -> Unit,
    onReport: () -> Unit,
) {
    var on by remember { mutableStateOf(active) }
    val elapsed = startedAtSeconds?.let { (System.currentTimeMillis() / 1000.0) - it }
    // #965: HONEST per-mode captured-day count for a guided row (distinct days THIS mode produced its own
    // trace on), read from the same log the report exports, so each active mode accumulates its OWN count
    // instead of every guided row sharing one elapsed number. null for a toggle mode (no "K of N") / when off.
    val capturedUnits: Int? =
        if (on && mode.capture is CaptureKind.Guided) {
            CaptureAccumulator.capturedDays(
                domain = mode.domain,
                reportText = logText,
                tzOffsetSeconds =
                    (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toLong(),
            )
        } else {
            null
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mode.title, style = NoopType.body, color = Palette.textPrimary)
                Text(
                    TestCentreLayout.statusText(mode, on, elapsed, capturedUnits),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { on = it; onToggle(it) },
                colors = settingsSwitchColors(),
            )
        }
        Text(mode.blurb, style = NoopType.footnote, color = Palette.textTertiary)
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReport) {
                Text("Report", color = Palette.accent, style = NoopType.body)
            }
        }
    }
}

@Composable
private fun DiagnosticToolsCard(vm: AppViewModel, is5MG: Boolean, onReport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // "Debug logging" moved here from Settings: dev-only, mirrors the strap log to logcat over adb.
    var debugLogging by remember { mutableStateOf(NoopPrefs.debugLogging(context)) }
    // Live strap state (for the 5/MG raw-capture share paths) + the 5/MG frame recorder toggle, both
    // moved here from Settings (#22 consolidation) so every diagnostic tool lives in one card.
    val live by vm.live.collectAsStateWithLifecycle()
    val puffinExperiment = remember { PuffinExperiment.from(context) }
    var puffinCapture by remember { mutableStateOf(puffinExperiment.isCaptureEnabled) }
    SettingsSectionTC(
        icon = Icons.Filled.Info,
        title = "Diagnostic tools",
        blurb = "Report a bug, share your strap log, buzz the time on your strap, and export the raw sensor CSV (any strap) or the raw 5/MG capture. Nothing leaves the phone unless you share it.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The master bug-report action (relocated from the old Export card): assembles the redacted
            // whole-app bundle and opens the review-before-share gate. The primary action, so it leads.
            NoopButton(
                text = "Report a bug with my log",
                leadingIcon = Icons.Filled.BugReport,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                onClick = onReport,
            )
            // Strap log, the same exportLogText share the Settings Diagnostics button uses.
            NoopButton(
                text = "Share strap log (for bug reports)",
                leadingIcon = Icons.Filled.Upload,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { scope.launch { LogExport.shareStrapLog(context, vm.ble.exportLogText()) } },
            )
            // Debug logging (moved here from Settings): mirror the strap log to logcat for adb
            // development. Dev-only, off by default; the in-app log and "Share strap log" above work either way.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Debug logging", style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        "Also write the strap log to the system log (logcat) for development over adb. Off by default.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = debugLogging,
                    onCheckedChange = { debugLogging = it; vm.setDebugLogging(it) },
                    colors = settingsSwitchColors(),
                )
            }
            // Raw sensor CSV export (moved from Settings Diagnostics): the decoded per-sample streams
            // NOOP already stores, last 24h, as one long-format CSV. UNGATED — a WHOOP 4.0 owner still
            // needs it to prototype on their own data (#308/#276/#322).
            NoopButton(
                text = "Export raw sensor data (CSV)",
                leadingIcon = Icons.Filled.Upload,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { scope.launch { RawSensorExport.export(context, vm.repo) } },
            )
            Text(
                "Saves the last 24h of decoded sensor samples (heart rate, R-R, motion, steps and any 5/MG deep streams you've unlocked) as one CSV you can share, for tinkering with your own data. Nothing leaves the phone unless you share it.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            // Haptic clock (#460, moved here from Settings): buzz the current time on the strap as a
            // sequence of buzzes. Works on any strap; no-ops safely when disconnected. 12/24h follows the
            // phone's own clock setting.
            NoopButton(
                text = "Buzz the time on your strap",
                leadingIcon = Icons.Filled.Vibration,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { vm.ble.buzzTimeNow(is24h = android.text.format.DateFormat.is24HourFormat(context)) },
            )
            Text(
                "Feel the current time as a sequence of buzzes (#460). Does nothing unless your strap is connected.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            // Rust shadow decode (whoop-rs FFI cross-check, default OFF): decode each offload / live /
            // command-response frame a second time through the shared Rust codec and diff field-by-field
            // against the Kotlin decode. Nothing stored changes; the counters below are the parity report.
            var rustShadow by remember { mutableStateOf(puffinExperiment.isRustShadowEnabled) }
            var parityReport by remember { mutableStateOf(com.noop.protocol.RustShadowParity.report()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Rust shadow decode (parity)",
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = rustShadow,
                    onCheckedChange = {
                        rustShadow = it
                        puffinExperiment.isRustShadowEnabled = it
                        parityReport = com.noop.protocol.RustShadowParity.report()
                    },
                    colors = settingsSwitchColors(),
                )
            }
            Text(
                "Decodes each frame a second time through the shared Rust codec (whoop-rs) and compares it " +
                    "field-by-field to the app's own decode, to prove they agree before any switch-over. It " +
                    "never changes a stored value. Off by default.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            if (rustShadow || com.noop.protocol.RustShadowParity.hasData()) {
                Text(
                    parityReport,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NoopButton(
                        text = "Refresh parity",
                        kind = NoopButtonKind.Secondary,
                        onClick = { parityReport = com.noop.protocol.RustShadowParity.report() },
                    )
                    NoopButton(
                        text = "Reset counters",
                        kind = NoopButtonKind.Secondary,
                        onClick = {
                            com.noop.protocol.RustShadowParity.reset()
                            parityReport = com.noop.protocol.RustShadowParity.report()
                        },
                    )
                }
            }
            if (is5MG) {
                // 5/MG raw-frame capture (moved from the Settings Experimental card): record every frame
                // of each history sync to a phone-local file, then share it (or the matched raw+log pair)
                // for the puffin decode effort. Gated to a 5/MG strap; the 4.0 protocol is fully decoded.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Record 5/MG raw capture (research)",
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = puffinCapture,
                        onCheckedChange = {
                            puffinCapture = it
                            puffinExperiment.isCaptureEnabled = it
                        },
                        colors = settingsSwitchColors(),
                    )
                }
                Text(
                    "Records the raw frames of each 5/MG history sync to a file on this phone, so you can share them and help NOOP learn to decode 5/MG sleep, recovery and strain. The file contains raw biometric frames (heart rate, R-R, skin temperature, motion) and the strap's own diagnostic text. Nothing leaves the phone unless you share it. Off by default.",
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
                NoopButton(
                    text = "Share 5/MG capture (for the decode effort)",
                    leadingIcon = Icons.Filled.Upload,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { LogExport.shareWhoop5Capture(context, live.whoop5Detected) },
                )
                // One-tap "matched pair" export (#510): the raw capture file AND the strap log together,
                // timestamped the same minute, so a protocol-mapping issue arrives with the frames AND the
                // context that produced them.
                NoopButton(
                    text = "Export raw + log (matched pair)",
                    leadingIcon = Icons.Filled.IosShare,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { scope.launch { LogExport.shareRawAndLog(context, vm.ble.exportLogText(), live.whoop5Detected) } },
                )
            }
        }
    }
}

@Composable
private fun ReportReviewDialog(
    previewText: String,
    modeInactive: Boolean,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.surfaceOverlay,
        title = { Text("Review before sharing", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column {
                if (modeInactive) {
                    // #1002: the selected profile's test mode is off, so this bundle carries no capture
                    // for the very thing being reported. Warn plainly, with the fix, BEFORE the user
                    // ships a report a maintainer can't act on. Twin of the Swift review-sheet warning.
                    Text(
                        "Heads up: this test mode is off, so the report has no capture for it. For a " +
                            "useful report, turn the mode on, reproduce the problem while wearing the " +
                            "strap, then report again.",
                        style = NoopType.footnote, color = Palette.statusWarning,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    "This is exactly what your report will contain. Nothing leaves this phone until you tap Share.",
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    previewText.ifBlank { "(nothing to share yet)" },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text("Share", style = NoopType.body, color = Palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", style = NoopType.body, color = Palette.textSecondary) }
        },
    )
}

// MARK: - Local section + toggle wrappers (Test Centre owns its own so it never reaches into the private
// SettingsScreen.kt helpers; same NoopCard idiom).

@Composable
private fun SettingsSectionTC(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    blurb: String,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Test Centre")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.Icon(
                        icon, contentDescription = null, tint = Palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.surfaceBase,
    checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Palette.textSecondary,
    uncheckedTrackColor = Palette.surfaceInset,
    uncheckedBorderColor = Palette.hairline,
)
