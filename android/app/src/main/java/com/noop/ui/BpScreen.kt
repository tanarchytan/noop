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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.bp.Bp
import com.noop.bp.BpClient
import com.noop.bp.BpStore
import kotlinx.coroutines.launch

/**
 * Blood pressure — an **experimental, opt-in wellness estimate** derived from the strap's optical PPG
 * waveform by the linked noop-cloud. Not a medical measurement. Needs a one-time (and periodic) cuff
 * calibration. Self-contained, like [FirmwareDownloadScreen].
 */
@Composable
fun BpScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var estimate by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var native by remember { mutableStateOf(BpStore.mode(context) == BpStore.Mode.NATIVE) }

    fun msg(r: BpClient.Result): String = when (r) {
        is BpClient.Result.NotLinked -> "Link a cloud first (Settings → Cloud)."
        is BpClient.Result.NoSignal -> "No PPG yet — wear the strap and sync, then try again."
        is BpClient.Result.Error -> r.message
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
                    "and drifts; recalibrate often.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
            )
        }

        // --- Compute location: on-device (default) vs linked cloud ---
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Compute on device", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (native) {
                            "Private + offline. The PPG never leaves your phone; calibration is stored locally."
                        } else {
                            "Estimated by your linked noop-cloud (Settings → Cloud). The PPG window is uploaded."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = native,
                    onCheckedChange = {
                        native = it
                        BpStore.setMode(context, if (it) BpStore.Mode.NATIVE else BpStore.Mode.CLOUD)
                        estimate = null; status = null
                    },
                )
            }
        }

        // --- Estimate ---
        Button(
            enabled = !busy,
            onClick = {
                busy = true; status = null
                scope.launch {
                    when (val r = Bp.estimate(context)) {
                        is BpClient.Result.Estimate -> {
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
                    val r = Bp.calibrate(context, sys.toInt(), dia.toInt())
                    status = if (r is BpClient.Result.Calibrated) "Calibrated ✓ — you can Estimate now." else msg(r)
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
