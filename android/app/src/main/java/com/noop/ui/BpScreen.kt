package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.bp.BpNative
import com.noop.bp.BpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seconds of still capture for a guided BP reading (matches how cuffless-BP apps sample). */
private const val CAPTURE_SECS = 25

/**
 * Blood pressure — an **experimental, opt-in wellness estimate** from the strap's optical (PPG) signal,
 * computed entirely on-device. Not a medical measurement. A reading is a guided "hold still" capture;
 * a one-time cuff calibration personalises it (a no-cuff population baseline is available meanwhile).
 *
 * [onSync] kicks a strap offload to pull fresh PPG; [onEnsureFeed] makes sure the optical decode is
 * feeding the buffer. Both wired in AppRoot to the BLE client.
 */
@Composable
fun BpScreen(onSync: () -> Unit = {}, onEnsureFeed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var estimate by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }

    // Make sure the on-device PPG decode is running so a capture has signal.
    LaunchedEffect(Unit) { onEnsureFeed() }

    fun msg(r: BpResult): String = when (r) {
        is BpResult.NoSignal -> "No pulse signal — make sure the strap is snug and worn, then try again."
        is BpResult.NotCalibrated -> "Set a baseline or calibrate with a cuff first."
        is BpResult.Error -> r.message
        else -> ""
    }

    /** Guided still capture: show the countdown, pull fresh PPG, then run [action] on the window. */
    fun guidedCapture(action: suspend () -> BpResult, onDone: (BpResult) -> Unit) {
        if (busy || measuring) return
        measuring = true; status = null; countdown = CAPTURE_SECS
        scope.launch {
            onSync()
            for (s in CAPTURE_SECS downTo 0) { countdown = s; delay(1000) }
            measuring = false; busy = true
            onDone(action())
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Blood pressure", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Text(
                "⚠ Experimental wellness estimate from your strap's optical (PPG) signal — NOT a medical " +
                    "measurement and never a diagnosis. Computed on your device; the signal never leaves your phone.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
            )
        }

        // --- Measure ---
        Button(
            enabled = !busy && !measuring,
            onClick = {
                guidedCapture(action = { BpNative.estimate(context) }) { r ->
                    when (r) {
                        is BpResult.Estimate -> {
                            estimate = "${r.systolic}/${r.diastolic} mmHg"
                            status = "confidence ${(r.confidence * 100).toInt()}% · " +
                                if (r.calibrated) "calibrated" else "uncalibrated base — add a cuff to personalise"
                        }
                        else -> status = msg(r)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Measure blood pressure") }

        estimate?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(it, style = MaterialTheme.typography.headlineMedium)
                    status?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // --- Baseline (no cuff) ---
        Text("Baseline", style = MaterialTheme.typography.titleMedium)
        Text(
            "No cuff yet? Set a baseline while resting — it anchors your pulse to a population-normal " +
                "(~120/80) so you get a rough estimate now and can track changes. Add a cuff later to personalise.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            enabled = !busy && !measuring,
            onClick = {
                guidedCapture(action = { BpNative.setBaseline(context) }) { r ->
                    status = if (r is BpResult.Calibrated) "Baseline set — Measure now (uncalibrated)." else msg(r)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set baseline (no cuff)") }

        // --- Calibrate with a cuff ---
        Text("Calibrate with a cuff", style = MaterialTheme.typography.titleMedium)
        Text(
            "Take a reading with a real BP cuff and enter it here while wearing the strap. This replaces the " +
                "baseline and personalises every estimate to your body.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sys, onValueChange = { sys = it.filter(Char::isDigit).take(3) },
                label = { Text("Systolic") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = dia, onValueChange = { dia = it.filter(Char::isDigit).take(3) },
                label = { Text("Diastolic") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            enabled = !busy && !measuring && sys.toIntOrNull() != null && dia.toIntOrNull() != null,
            onClick = {
                val s = sys.toInt(); val d = dia.toInt()
                guidedCapture(action = { BpNative.calibrate(context, s, d) }) { r ->
                    status = if (r is BpResult.Calibrated) "Calibrated ✓ — measurements are now personalised." else msg(r)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save calibration") }

        status?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // Guided "hold still" countdown dialog.
    if (measuring) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Hold still") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rest your arm, keep your wrist steady, and breathe normally.")
                    Text("$countdown s", style = MaterialTheme.typography.headlineLarge)
                }
            },
        )
    }
}
