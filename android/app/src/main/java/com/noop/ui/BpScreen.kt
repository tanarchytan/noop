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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.noop.bp.LivePpgProbe
import com.noop.bp.PpgBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Blood pressure — an **experimental, opt-in wellness estimate** derived on-device from the strap's
 * optical PPG waveform. Not a medical measurement. Needs a one-time (and periodic) cuff calibration.
 * Everything runs locally: the PPG never leaves the phone.
 *
 * [onStartLiveProbe]/[onStopLiveProbe] drive the EXPERIMENTAL live raw-PPG probe (wired in AppRoot to
 * the BLE client) — a diagnostic to discover whether a WHOOP 5.0 exposes a realtime PPG waveform.
 */
@Composable
fun BpScreen(
    onStartLiveProbe: () -> Unit = {},
    onStopLiveProbe: () -> Unit = {},
    onUnlockDeepStream: () -> Unit = {},
    onSaveProbeReport: () -> Unit = {},
    onStartCommandServer: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var estimate by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }

    // Experimental live-probe state (diagnostic readout, polled from LivePpgProbe while active).
    var probing by remember { mutableStateOf(false) }
    var probeReadout by remember { mutableStateOf("") }
    // Auto-start the adb command server whenever this screen opens, so a reinstall needs no button tap.
    LaunchedEffect(Unit) { onStartCommandServer() }
    LaunchedEffect(probing) {
        while (probing) {
            probeReadout = "frames: ${LivePpgProbe.typeSummary()}\n" +
                "responses: ${LivePpgProbe.responseSummary()}\n" +
                "type-43: ${LivePpgProbe.lastRawType43Size} B  •  PPG fed: ${LivePpgProbe.samplesFed}  •  " +
                "buffer: ${PpgBuffer.window(30)?.size ?: 0}\n" +
                (LivePpgProbe.lastRawType43Hex.ifEmpty { "(no type-43 frames seen)" })
            delay(1000)
        }
    }

    fun msg(r: BpResult): String = when (r) {
        is BpResult.NoSignal -> "No PPG yet — wear the strap and sync, then try again."
        is BpResult.NotCalibrated -> "Not calibrated yet — enter a cuff reading below first."
        is BpResult.Error -> r.message
        else -> ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Blood pressure", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Text(
                "⚠ Experimental wellness estimate from your strap's optical (PPG) signal — NOT a medical " +
                    "measurement and never a diagnosis. It tracks changes from your last cuff calibration " +
                    "and drifts; recalibrate often. It is computed on your device; the PPG never leaves your phone.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
            )
        }

        // --- Estimate ---
        Button(
            enabled = !busy,
            onClick = {
                busy = true; status = null
                scope.launch {
                    when (val r = BpNative.estimate(context)) {
                        is BpResult.Estimate -> {
                            estimate = "${r.systolic}/${r.diastolic} mmHg"
                            status = "confidence ${(r.confidence * 100).toInt()}%"
                        }
                        else -> status = msg(r)
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Reading…" else "Estimate now") }

        estimate?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(it, style = MaterialTheme.typography.headlineMedium)
                    status?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // --- Live capture (EXPERIMENTAL diagnostic) ---
        // The 5.0 does NOT put its raw PPG in the normal offload, so we probe the realtime stream to
        // find out what the strap actually emits. Connect + wear the strap, then Start and watch the
        // frame tally: a climbing "type-43" count with a sane size = raw data is reachable live.
        Text("Live capture (experimental)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Diagnostic: asks the strap to stream realtime raw data and reports what arrives. Wear the " +
                "strap and be connected first. Battery-costly — stop when done.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                if (!probing) { onStartLiveProbe(); probing = true }
                else { onStopLiveProbe(); probing = false }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (probing) "Stop live capture" else "Start live capture") }

        // Deep-stream unlock: write the R22 feature flags (as the official app does), then wear + sync.
        // Reversible config write on your own device; the target is raw PPG appearing in the offload.
        OutlinedButton(
            onClick = { onUnlockDeepStream(); probing = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock deep sensor stream (R22), then wear + sync") }

        // Dump the probe report (tally + per-record hex samples) into the strap log so it rides the
        // existing Settings → "Share strap log" export — no cable needed.
        OutlinedButton(
            onClick = onSaveProbeReport,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save report to strap log (then Settings → Share strap log)") }

        // Start the adb command server: PC drives the strap over `adb forward localabstract:noop_bp_cmd`.
        OutlinedButton(
            onClick = { onStartCommandServer(); probing = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start adb command server") }

        if (probing || probeReadout.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    probeReadout.ifEmpty { "starting…" },
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }

        // --- Calibration ---
        Text("Calibrate", style = MaterialTheme.typography.titleMedium)
        Text(
            "Take a reading with a real BP cuff, then enter it here while wearing the strap so the estimate " +
                "is anchored to your body.",
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
            enabled = !busy && sys.toIntOrNull() != null && dia.toIntOrNull() != null,
            onClick = {
                busy = true; status = null
                scope.launch {
                    val r = BpNative.calibrate(context, sys.toInt(), dia.toInt())
                    status = if (r is BpResult.Calibrated) "Calibrated ✓ — you can Estimate now." else msg(r)
                    busy = false
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
}
