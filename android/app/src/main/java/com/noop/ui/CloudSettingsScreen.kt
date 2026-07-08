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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.cloud.CloudClient
import com.noop.cloud.CloudPrefs
import com.noop.sync.CloudSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Cloud. Link this app to the user's self-hosted **noop-cloud** with a short rotating code,
 * TV-app style: enter the cloud's URL, tap Link, then approve the shown code on the cloud's /pair page
 * with the admin PIN. The app polls until linked and stores the token (encrypted). Self-contained —
 * no viewModel coupling, like FirmwareDownloadScreen.
 */
@Composable
fun CloudSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(CloudPrefs.baseUrl(context)) }
    var linked by remember { mutableStateOf(CloudPrefs.isLinked(context)) }
    var userCode by remember { mutableStateOf<String?>(null) }
    var verifyUri by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cloudVersion by remember { mutableStateOf<String?>(null) }
    var syncOn by remember { mutableStateOf(CloudPrefs.syncEnabled(context)) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    // On open (when linked), confirm the cloud is reachable and show its version — a live health +
    // compatibility check. A failure here means the cloud is down or unreachable from this network.
    LaunchedEffect(linked) {
        if (linked && url.isNotBlank()) {
            runCatching { CloudClient(context).serverVersion(url) }
                .onSuccess { v ->
                    cloudVersion = "${v.service} ${v.version}" +
                        if (!CloudClient.isCompatible(v)) " — ⚠ ${CloudClient.incompatibilityReason(v)}" else ""
                }
                .onFailure { cloudVersion = "⚠ cloud unreachable" }
        }
    }

    // Auto data sync: when linked and sync is on, push metrics once on open (idempotent server upsert).
    LaunchedEffect(linked, syncOn) {
        if (linked && syncOn) {
            syncing = true
            syncMsg = when (val r = CloudSync.syncNow(context)) {
                is CloudSync.Result.Synced -> "Synced ${r.days} day(s) ✓"
                is CloudSync.Result.NotLinked -> null
                is CloudSync.Result.Error -> "Sync error: ${r.message}"
            }
            syncing = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cloud", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Link to your own self-hosted noop-cloud for the firmware update check, backup and coach. " +
                "noop itself stays local — this is optional.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Text(
                buildString {
                    append(if (linked) "Status: linked ✓  (${url})" else "Status: not linked")
                    cloudVersion?.let { append("\n$it") }
                },
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = url, onValueChange = { url = it; CloudPrefs.saveUrl(context, it) },
            label = { Text("Cloud URL (e.g. http://192.168.1.20:8089)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!linked) {
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true; status = null; userCode = null
                    scope.launch {
                        try {
                            val client = CloudClient(context)
                            // Verify the cloud is reachable AND speaks a compatible wire contract before
                            // pairing — a clear message beats an opaque failure mid-link.
                            val ver = client.serverVersion(url)
                            if (!CloudClient.isCompatible(ver)) {
                                status = CloudClient.incompatibilityReason(ver); busy = false
                                return@launch
                            }
                            cloudVersion = "${ver.service} ${ver.version}"
                            val start = client.startPairing(url)
                            userCode = start.userCode
                            verifyUri = start.verificationUri
                            status = "Approve this code on your cloud, then it links automatically."
                            // Poll until approved or the code expires.
                            val deadline = System.currentTimeMillis() + start.expiresInSeconds * 1000L
                            while (System.currentTimeMillis() < deadline) {
                                delay(start.intervalSeconds * 1000L)
                                when (val r = client.pollToken(url, start.deviceCode)) {
                                    is CloudClient.PollResult.Linked -> {
                                        CloudPrefs.saveTokens(
                                            context, url, r.tokens.access, r.tokens.refresh, r.tokens.expiresIn,
                                        )
                                        // Store the mutual-TLS client cert if the cloud issued one.
                                        val cert = r.tokens.clientCert; val key = r.tokens.clientKey
                                        if (cert != null && key != null) CloudPrefs.saveClientCert(context, cert, key)
                                        linked = true; userCode = null
                                        status = "Linked ✓"
                                        return@launch
                                    }
                                    is CloudClient.PollResult.Expired -> {
                                        status = "Code expired — tap Link again."; userCode = null
                                        return@launch
                                    }
                                    CloudClient.PollResult.Pending -> Unit  // keep polling
                                }
                            }
                            status = "Timed out — tap Link again."; userCode = null
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"; userCode = null
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Waiting for approval…" else "Link device") }
        } else {
            OutlinedButton(
                onClick = { CloudPrefs.unlink(context); linked = false; status = "Unlinked." },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlink") }

            // Auto data sync (opt-in): push the app's computed daily metrics to the linked cloud.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-sync my data", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Push daily recovery / strain / vitals to your cloud when the app opens.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = syncOn,
                            onCheckedChange = { syncOn = it; CloudPrefs.setSyncEnabled(context, it) },
                        )
                    }
                    Button(
                        enabled = !syncing,
                        onClick = {
                            syncing = true; syncMsg = null
                            scope.launch {
                                syncMsg = when (val r = CloudSync.syncNow(context)) {
                                    is CloudSync.Result.Synced -> "Synced ${r.days} day(s) ✓"
                                    is CloudSync.Result.NotLinked -> "Not linked."
                                    is CloudSync.Result.Error -> "Sync error: ${r.message}"
                                }
                                syncing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (syncing) "Syncing…" else "Sync now") }
                    syncMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // The rotating code + where to approve it.
        userCode?.let { code ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enter this code on your cloud:", style = MaterialTheme.typography.labelLarge)
                    Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                    verifyUri?.let { Text("at $it (+ your admin PIN)", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        status?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
