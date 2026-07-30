package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings -> Debug: one switch that turns the whole debug capture on, and the two exports that carry it
 * off the phone. [DebugBundle] assembles the full export; the review dialog is the last gate before
 * anything is shared.
 */
@Composable
fun TestCentreScreen(vm: AppViewModel) {
    val context = LocalContext.current
    // A UI scope for the (suspend) log read, bundle assembly and share.
    val scope = rememberCoroutineScope()

    // A staged bundle awaiting the review gate. Non-null shows the dialog; confirming shares it.
    var staged by remember { mutableStateOf<DebugBundle.Staged?>(null) }
    var staging by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Debug",
        subtitle = "One switch, two exports. Everything stays on this phone until you share it.",
    ) {
        DebugCard(
            vm = vm,
            staging = staging,
            onFullExport = {
                staging = true
                scope.launch {
                    val logText = vm.ble.exportLogText()
                    staged = withContext(Dispatchers.IO) {
                        DebugBundle.stage(context, vm.repo, logText, vm.activeStrapId)
                    }
                    staging = false
                }
            },
            onStrapExport = { scope.launch { LogExport.shareStrapLog(context, vm.ble.exportLogText()) } },
        )
    }

    staged?.let { bundle ->
        ReportReviewDialog(
            previewText = bundle.gate.previewText,
            onCancel = { staged = null },
            onShare = {
                bundle.gate.confirm()
                scope.launch { withContext(Dispatchers.IO) { DebugBundle.share(context, bundle) } }
                staged = null
            },
        )
    }
}

@Composable
private fun DebugCard(
    vm: AppViewModel,
    staging: Boolean,
    onFullExport: () -> Unit,
    onStrapExport: () -> Unit,
) {
    // One decision, reconciled on read: an older build could leave logcat mirroring and the capture
    // disagreeing, and a switch that shows on while half of it is off is a lie.
    var debugEnabled by remember { mutableStateOf(vm.reconcileDebugEnabled()) }
    NoopSettingsSection(
        icon = Icons.Filled.Info,
        title = "Debug capture",
        blurb = "For working on NOOP itself: record what the strap sends, then export it.",
        overline = "Debug",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                Text(
                    "Enable debug",
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = debugEnabled,
                    onCheckedChange = { debugEnabled = it; vm.setDebugEnabled(it) },
                    colors = settingsSwitchColors(),
                )
            }
            Text(
                "Mirrors the strap log to the system log (logcat) for development over adb, and records a " +
                    "research capture on this phone: the raw frames of each history sync, the decoded " +
                    "record behind each frame, the strap's own event frames, and the high-rate optical and " +
                    "motion buffers once 5/MG deep data is unlocked. On a 5.0 or MG the decoded records " +
                    "alone are about 25 MB for a night of wear and about 75 MB for a full day of history; " +
                    "the four files together hold up to about 280 MB on this phone, then the oldest is " +
                    "dropped. They carry raw biometrics (heart rate, beat-to-beat intervals, skin " +
                    "temperature, motion, blood oxygen) and the strap's own diagnostic text. Nothing " +
                    "leaves this phone until you export it. Off by default.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            NoopButton(
                text = if (staging) "Preparing export…" else "Full debug export",
                leadingIcon = Icons.Filled.IosShare,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                enabled = !staging,
                onClick = onFullExport,
            )
            Text(
                "One zip: the strap log with your app and device details, the capture files above, and the " +
                    "last 14 days of decoded samples as a CSV. You see exactly what it holds before it is shared.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
            NoopButton(
                text = "Strap debug export",
                leadingIcon = Icons.Filled.Upload,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onStrapExport,
            )
            Text(
                "The strap log on its own, as a text file - enough for a connection problem.",
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun ReportReviewDialog(
    previewText: String,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.surfaceOverlay,
        title = { Text("Review before sharing", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column {
                Text(
                    "This is exactly what your export will contain. Nothing leaves this phone until you tap Share.",
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
