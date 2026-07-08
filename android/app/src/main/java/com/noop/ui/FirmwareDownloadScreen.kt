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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.noop.firmware.WhoopFirmwareClient
import com.noop.firmware.WhoopHardware
import kotlinx.coroutines.launch
import java.io.File

/**
 * Firmware DOWNLOAD screen (frankenwhoop fork addition).
 *
 * Signs into the user's own WHOOP account, downloads the official chip firmware ZIP for the
 * chosen band + target version, and extracts it to app storage. DOWNLOAD ONLY — it never
 * flashes the band (that stays exclusive to the official WHOOP app). Self-contained: it owns
 * its own form state + coroutine scope and does not touch noop's local-first data layer.
 */
@Composable
fun FirmwareDownloadScreen(currentFirmware: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hw by remember { mutableStateOf(WhoopHardware.MAVERICK) }
    var targetVersion by remember { mutableStateOf("") }
    var currentVersion by remember { mutableStateOf(currentFirmware ?: "") }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }

    // The connected band's firmware version may arrive after this screen opens (BLE handshake);
    // pre-fill the check field when it lands so the user doesn't have to type it.
    LaunchedEffect(currentFirmware) {
        if (!currentFirmware.isNullOrBlank() && currentVersion.isBlank()) currentVersion = currentFirmware
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Firmware download", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Downloads official firmware from your own WHOOP account. Download only — it does " +
                "NOT flash the band (use the WHOOP app to install). Personal use only.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Current firmware read from the connected band over BLE (the same value Devices shows).
        Card(Modifier.fillMaxWidth()) {
            Text(
                if (!currentFirmware.isNullOrBlank()) "Connected band firmware: $currentFirmware"
                else "No band connected — connect on Devices to read the current firmware.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("WHOOP email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("WHOOP password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Device", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WhoopHardware.entries.forEach { option ->
                FilterChip(
                    selected = hw == option,
                    onClick = { hw = option },
                    label = { Text(option.label) },
                )
            }
        }
        Text("chips: ${hw.chips.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = currentVersion, onValueChange = { currentVersion = it },
            label = { Text("Current version (from Devices, e.g. 50.40.1.0) — for update check") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        // Check for update: reads your band's current version, asks WHOOP what it wants the device
        // on, and compares — no image is downloaded.
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && currentVersion.isNotBlank(),
            onClick = {
                output = null
                busy = true
                scope.launch {
                    try {
                        val s = WhoopFirmwareClient().checkForUpdate(email, password, hw, currentVersion.trim())
                        output = buildString {
                            appendLine(if (s.updateAvailable) "UPDATE AVAILABLE" else "Up to date ✓")
                            appendLine("current: ${s.current}")
                            appendLine("WHOOP wants: ${s.desired}")
                            if (s.forceUpdate) appendLine("force_update: yes")
                        }
                    } catch (e: Exception) {
                        output = "ERROR: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Checking…" else "Check for update") }

        OutlinedTextField(
            value = targetVersion, onValueChange = { targetVersion = it },
            label = { Text("Target version (blank = latest known, e.g. AMBIQ 50.36.1.0)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                output = null
                busy = true
                scope.launch {
                    try {
                        val versions = if (targetVersion.isBlank()) emptyMap()
                        else hw.chips.associateWith { targetVersion.trim() }
                        val outDir = File(context.filesDir, "firmware/${hw.deviceName}")
                        val r = WhoopFirmwareClient().download(email, password, hw, versions, outDir)
                        output = buildString {
                            appendLine("OK — ${r.zipBytes} bytes, ${r.files.size} files")
                            r.serverConfig?.let { appendLine("server: $it") }
                            appendLine("saved to: ${r.outputDir}")
                            r.files.forEach { appendLine("  ${it.name} (${it.size} B)") }
                        }
                    } catch (e: Exception) {
                        output = "ERROR: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Downloading…" else "Download firmware") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        output?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
