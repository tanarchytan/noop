package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import com.noop.testcentre.TestCentre
import com.noop.testcentre.TestDomain
import com.noop.testcentre.TestReportFlow
import kotlinx.coroutines.launch

/**
 * Settings -> Debug, the Android diagnostic-tools screen. One card consolidates every diagnostic control
 * in one place: the bug-report button, the strap-log share, the debug-logging switch, the raw-sensor CSV
 * export, and (on a 5/MG) the raw-frame capture + share, all on the same bindings the Settings cards used
 * before the move. The bug report assembles the redacted whole-app bundle and opens the review-before-share
 * gate. No em-dash.
 */
@Composable
fun TestCentreScreen(vm: AppViewModel) {
    val context = LocalContext.current
    // A UI scope for the (suspend) bug-report build off the Report tap.
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

    ScreenScaffold(
        title = "Debug",
        subtitle = "Diagnostic tools for bug reports. Everything stays on this phone unless you share it.",
    ) {
        // Diagnostic tools: the bug-report action plus the raw exports.
        DiagnosticToolsCard(
            vm,
            is5MG,
            onReport = {
                // Launched (#1002): buildPending is now suspend (storage probe reads the store).
                scope.launch {
                    pendingReport = buildPending(context, TestDomain.MASTER, "Bug report", vm.ble.exportLogText(), vm)
                }
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

/** Assemble the redacted, capped bundle for a profile and wrap it in the review gate. Suspend (#1002):
 *  the storage probe reads the store, so the callers launch it on the UI scope; the dialog presents off
 *  the same `pendingReport` state a beat after the tap. */
private suspend fun buildPending(
    context: android.content.Context,
    profile: TestDomain,
    title: String,
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
    val entries = TestBundleAssembler.assemble(context, profile, logText, storage, strapModel)
    val modeInactive = profile != TestDomain.MASTER && !TestCentre.from(context).active(profile)
    return PendingReport(profile, title, entries, ReportReviewGate(entries), modeInactive)
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
    NoopSettingsSection(
        icon = Icons.Filled.Info,
        title = "Diagnostic tools",
        blurb = "Report a bug, share your strap log, and export the raw sensor CSV (any strap) or the raw 5/MG capture. Nothing leaves the phone unless you share it.",
        overline = "Test Centre",
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



@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.surfaceBase,
    checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Palette.textSecondary,
    uncheckedTrackColor = Palette.surfaceInset,
    uncheckedBorderColor = Palette.hairline,
)
