package com.noop.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.noop.data.DataBackup
import com.noop.data.DeviceStatus
import com.noop.data.ImportSummary
import com.noop.data.Metric
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.ingest.AppleHealthImporter
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.HealthConnectWriter
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.WhoopCsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Sources — on-device import and export hub for health history. Sources shown:
 *
 * - WHOOP History — cached "my-whoop" data with counts, plus import from a WHOOP
 * .zip/.csv export via [com.noop.ingest.WhoopCsvImporter].
 * - Apple Health — cached "apple-health" data with counts, plus import from an
 * Apple Health export.zip/export.xml via [com.noop.ingest.AppleHealthImporter].
 * - Health Connect — native Android import (steps/HR/HRV/sleep/SpO₂/weight/workouts)
 * via [com.noop.ingest.HealthConnectImporter], gated on runtime permission,
 * plus optional computed-metric writeback.
 * - Backup — Export / Import the whole on-device database through [DataBackup],
 * wired to ActivityResult document launchers.
 */
@Composable
fun DataSourcesScreen(vm: AppViewModel, onOpenAppleHealth: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hcAutoSync by vm.hcAutoSync.collectAsStateWithLifecycle()
    val hcSyncHours by vm.hcSyncHours.collectAsStateWithLifecycle()
    val hcLastSync by vm.hcLastSync.collectAsStateWithLifecycle()
    val hcWriteback by vm.hcWriteback.collectAsStateWithLifecycle()

 // Cached-store counts, loaded once from the repo (newest data is fine to recount).
    var whoopDays by remember { mutableStateOf<Int?>(null) }
    var whoopWorkouts by remember { mutableStateOf<Int?>(null) }
    var whoopHasHr by remember { mutableStateOf(false) }
    var appleDays by remember { mutableStateOf<Int?>(null) }
    var appleWorkouts by remember { mutableStateOf<Int?>(null) }
 // Health Connect has its OWN source ("health-connect"), counted separately from an Apple Health
 // export so each card reflects its own data rather than both showing under Apple Health.
    var hcDays by remember { mutableStateOf<Int?>(null) }
    var hcWorkouts by remember { mutableStateOf<Int?>(null) }

 // Count-badge refresh, shared by the initial load below and every importer's post-run refresh.
 // PERF: scalar SQL COUNTs (and one LIMIT-1 existence probe), NOT materialized row lists — the old
 // shape loaded every row of every source's history just to call `.size` on it, ~14 full-range
 // reads per screen visit. Workout counts are now exact (the row read was capped at DEFAULT_LIMIT).
    suspend fun refreshCounts() {
        val nowS = System.currentTimeMillis() / 1000
        whoopDays = vm.repo.daysCount("my-whoop")
        whoopWorkouts = vm.repo.workoutsCount("my-whoop", 0L, nowS)
        whoopHasHr = vm.repo.latestHrSampleTs("my-whoop") != null
        appleDays = vm.repo.appleDailyCount("apple-health", "0000-01-01", "9999-12-31")
        appleWorkouts = vm.repo.workoutsCount("apple-health", 0L, nowS)
        hcDays = vm.repo.appleDailyCount("health-connect", "0000-01-01", "9999-12-31")
        hcWorkouts = vm.repo.workoutsCount("health-connect", 0L, nowS)
    }

    LaunchedEffect(Unit) { refreshCounts() }

 // Busy flag shared by every importer's Export/Import buttons.
    var busy by remember { mutableStateOf(false) }
 // ah-delete : drives the "Remove Apple Health imported data" confirm dialog.
    var confirmDeleteApple by remember { mutableStateOf(false) }

 // Run an importer off the main thread, refresh the counts, then toast the result.
    fun runImport(block: suspend () -> ImportSummary) {
        busy = true
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { ImportSummary.failure("Import", it.message ?: "failed") }
            }
 // Mirror the import into the SAME exported strap log the WHOOP path uses ( parity),
 // so a tester's file import is captured in a shared debug bundle. On success: brand label +
 // per-table COUNTS only (e.g. "dailyMetric=120, sleepSession=88"). On a zero-row/failed import:
 // the brand label + the human reason from the summary. Never a file name, a path, or any health
 // value. Prefixed "Import: " so it's distinguishable from WHOOP / generic-HR lines. The Swift
 // twin logs the same in DataSourcesView's import handlers.
            if (summary.totalRows > 0) {
                val countsText = summary.counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
                vm.ble.externalLog("Import ${summary.source}: $countsText")
            } else {
                vm.ble.externalLog("Import ${summary.source} failed: ${summary.message}")
            }
 // Import & Data Ingest test mode (Test Centre): emit the parser / per-stage / day-delta trace,
 // tagged IMPORT, iff the mode is on. Gated zero-cost when off (one SharedPreferences bool read).
 // The numbers are the SAME per-table counts the summary carries (Room upserts are fire-and-forget,
 // so the persisted count equals the mapped count at this seam); emission changes nothing saved. No
 // file name, path, or health value is in any line. Twin of the macOS DataSourcesView handlers.
            emitImportTrace(context, vm, summary)
            refreshCounts()
            busy = false
            Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show()
        }
    }

 // SAF pickers — the importers auto-detect zip vs csv/xml from the file's content.
    val whoopImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { WhoopCsvImporter.importZip(context, uri, vm.repo) } }

    val appleImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { AppleHealthImporter.importExport(context, uri, vm.repo) } }

 // Health Connect permission request → import once granted.
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
            runImport { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }
        } else {
            Toast.makeText(context, "Health Connect access not granted.", Toast.LENGTH_LONG).show()
        }
    }

    val healthConnectAvailable = remember {
        HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

 // Import directly if permissions already granted, otherwise request them first.
    fun startHealthConnect() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
                runImport { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }
            } else {
                hcPermissionLauncher.launch(HealthConnectImporter.PERMISSIONS)
            }
        }
    }

 // Writeback (computed metrics → Health Connect): WRITE permissions, requested only when the
 // user opts in. Denial flips the toggle back off so the UI never claims it's writing.
    val hcWritePermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            vm.writebackHealthConnectNow()
        } else {
            vm.setHcWriteback(false)
            Toast.makeText(context, "Health Connect write access not granted.", Toast.LENGTH_LONG).show()
        }
    }

 // Write immediately if the write permissions are already granted, otherwise request them first.
    fun startWriteback() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
 // Gate on vitals AND exercise perms so a user who enabled writeback before exercise
 // writeback shipped (vitals-only grant) still gets re-prompted for WRITE_EXERCISE/
 // WRITE_DISTANCE — otherwise their workouts silently never reach Health Connect.
            if (granted.containsAll(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)) {
                vm.writebackHealthConnectNow()
            } else {
 // Request vitals + exercise-session write perms together so GPS workouts can write
 // back too (the launcher-result handler stays keyed on the vital PERMISSIONS, so
 // exercise writeback is opt-in + non-fatal if the user declines it). v1.71 /.
                hcWritePermissionLauncher.launch(HealthConnectWriter.PERMISSIONS + HealthConnectWriter.EXERCISE_PERMISSIONS)
            }
        }
    }

 // PERF : lazy scaffold — each SourceCard is an unconditional top-level child, so each becomes one
 // `item { }` in the same order. There are no standalone Spacers (the eager column relied on
 // `spacedBy(20.dp)`, which the LazyColumn reproduces), so spacing is byte-identical. Only the on-screen
 // cards now compose + get accessibility-walked on scroll — this list of 11 source cards is long. The
 // confirm dialogs below the scaffold are untouched.
    LazyScreenScaffold(
        title = "Data Sources",
        subtitle = "Everything stays on this phone. Bring your history in once, then it's yours.",
    ) {
 // --- WHOOP data (cached history) ---
        item {
        SourceCard(
            title = "WHOOP History",
            icon = Icons.Filled.MonitorHeart,
            subtitle = "Recovery, strain, sleep and workouts, stored locally. Import a full " +
                "WHOOP data export (.zip) from app.whoop.com → Data Management and it " +
                "backfills your whole history in about a minute. Working now on Android.",
        ) {
            StatePill(
                title = if (whoopHasHr) "Streaming locally" else "No samples yet",
                tone = if (whoopHasHr) StrandTone.Positive else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = whoopDays?.let { "$it days" } ?: "—",
                secondary = whoopWorkouts?.let { "$it workouts stored" } ?: "Counting…",
            )
            BackupButton(
                label = "Import WHOOP export (.zip)",
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { whoopImportLauncher.launch(arrayOf("*/*")) }
        }
        }

 // --- Apple Health ---
        item {
        SourceCard(
            title = "Apple Health",
            icon = Icons.Filled.FavoriteBorder,
            tint = Palette.metricCyan,
            subtitle = "Import HR, HRV, sleep, SpO₂ and steps from an Apple Health export. On " +
                "an iPhone: Health app → tap your photo → Export All Health Data, then " +
                "import the .zip here. Working now on Android.",
        ) {
            val hasApple = (appleDays ?: 0) > 0 || (appleWorkouts ?: 0) > 0
            StatePill(
                title = if (hasApple) "Imported" else "Nothing imported",
                tone = if (hasApple) StrandTone.Accent else StrandTone.Neutral,
                showsDot = true,
            )
            CountLine(
                primary = appleDays?.let { "$it days" } ?: "—",
                secondary = appleWorkouts?.let { "$it workouts" } ?: "Counting…",
            )
            BackupButton(
                label = "Import Apple Health export…",
                icon = Icons.Filled.FileUpload,
                enabled = !busy,
                tint = Palette.metricCyan,
                modifier = Modifier.fillMaxWidth(),
            ) { appleImportLauncher.launch(arrayOf("*/*")) }
 // Apple Health lost its top-level nav entry; its full view (charts + import detail) is reached
 // HERE now — this button drills into AppleHealthScreen. Always shown so the view is openable
 // even before the first import.
            BackupButton(
                label = "View Apple Health data",
                icon = Icons.Filled.MonitorHeart,
                enabled = true,
                tint = Palette.metricCyan,
                modifier = Modifier.fillMaxWidth(),
            ) { onOpenAppleHealth() }
 // ah-delete : a destructive "Remove imported data" action wired to
 // DeviceRegistry.deleteDeviceData("apple-health") (via vm.deletePairedDeviceData), mirroring
 // the Swift card. Shown only once there's something to remove; a confirm dialog gates it.
            if (hasApple) {
                BackupButton(
                    label = "Remove imported data",
                    icon = Icons.Filled.DeleteOutline,
                    enabled = !busy,
                    tint = Palette.statusCritical,
                    modifier = Modifier.fillMaxWidth(),
                ) { confirmDeleteApple = true }
            }
        }
        }

 // --- Health Connect (native Android health data) ---
        item {
        SourceCard(
            title = "Health Connect",
            icon = Icons.Filled.MonitorHeart,
            subtitle = "Pull steps, heart rate, HRV, sleep, SpO₂, weight and workouts straight from " +
                "Android's Health Connect. No file needed. On-device; it never overwrites richer " +
                "WHOOP data, and writes nothing unless you opt in to sharing back below.",
        ) {
            val hasHc = (hcDays ?: 0) > 0 || (hcWorkouts ?: 0) > 0
            if (hasHc) {
                StatePill(title = "Imported", tone = StrandTone.Accent, showsDot = true)
                CountLine(
                    primary = hcDays?.let { "$it days" } ?: "—",
                    secondary = hcWorkouts?.let { "$it workouts" } ?: "Counting…",
                )
            }
            if (healthConnectAvailable) {
                BackupButton(
                    label = "Import from Health Connect",
                    icon = Icons.Filled.FileUpload,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { startHealthConnect() }

 // Auto-sync: pull new Health Connect data when you open NOOP, if it's been longer than
 // the chosen interval — no manual taps. On-open only (no background worker): it avoids a
 // sensitive background-health permission and is reliable, and opening the app is enough.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-sync periodically", style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            "Re-pull new Health Connect data (e.g. Samsung Health → Health Connect) each " +
                                "time you open NOOP, if it's been longer than the interval below. " +
                                "Read-only; never overwrites strap data.",
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcAutoSync,
                        onCheckedChange = { on ->
                            vm.setHcAutoSync(on)
 // Ensure permissions (and an immediate first sync) when turning it on.
                            if (on) startHealthConnect()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Auto-sync Health Connect periodically"
                        },
                    )
                }
                if (hcAutoSync) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
                    ) {
                        Text("Every", style = NoopType.footnote, color = Palette.textSecondary)
                        SegmentedPillControl(
                            items = listOf(6, 12, 24),
                            selection = hcSyncHours,
                            label = { "${it}h" },
                            onSelect = { vm.setHcSyncHours(it) },
                        )
                    }
                    Text(
                        "Last sync: " + if (hcLastSync == 0L) "not yet"
                        else DateUtils.getRelativeTimeSpanString(hcLastSync).toString(),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }

 // Writeback: the inverse direction. Opt-in, default OFF, computed metrics only.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share back to Health Connect", style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            "Write the metrics NOOP computes from your strap (resting HR, HRV, SpO₂, " +
                                "respiratory rate, heart rate, steps, active energy and sleep) into " +
                                "Health Connect so other apps can use them. Only NOOP's own values are " +
                                "shared. Imported data is never echoed back.",
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    Switch(
                        checked = hcWriteback,
                        onCheckedChange = { on ->
                            vm.setHcWriteback(on)
 // Ensure write permissions (and an immediate first write) when turning on.
                            if (on) startWriteback()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.surfaceBase,
                            checkedTrackColor = Palette.accent,
                            uncheckedThumbColor = Palette.textSecondary,
                            uncheckedTrackColor = Palette.surfaceInset,
                            uncheckedBorderColor = Palette.hairline,
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Share computed metrics back to Health Connect"
                        },
                    )
                }
            } else {
                RoadmapNote("Health Connect isn't set up on this device. Install it from Google Play, then return here to import.")
            }
        }
        }

    }

 // ah-delete : strongly-worded confirm before purging the "apple-health" source. On confirm,

 // ah-delete : strongly-worded confirm before purging the "apple-health" source. On confirm,
 // deletes every Apple-Health-sourced row (deviceId-keyed tables) in one transaction via the registry,
 // re-counts so the card flips back to "Nothing imported", and toasts the result.
    if (confirmDeleteApple) {
        NoopConfirmDialog(
            title = "Remove Apple Health imported data?",
            text = "This permanently deletes everything imported from Apple Health: heart rate, HRV, sleep, steps, workouts and more. Your live strap data is untouched. This can't be undone.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                confirmDeleteApple = false
                busy = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { vm.deletePairedDeviceData("apple-health") }
                    }
                    vm.ble.externalLog("Import apple-health: imported data removed")
                    refreshCounts()
                    vm.loadWorkouts()
                    busy = false
                    Toast.makeText(context, "Removed Apple Health imported data.", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { confirmDeleteApple = false },
        )
    }
}

// MARK: - Source card (mirrors the macOS private `card(...)` builder)

@Composable
private fun SourceCard(
    title: String,
    icon: ImageVector,
    subtitle: String,
    tint: Color = Palette.accent,
    content: @Composable () -> Unit,
) {
 // A frosted, domain-tinted card: a tinted source glyph chip + title, the explainer line, then
 // the source's status pill + connect/import action(s). Replaces the old flat surface.
    NoopCard(padding = 18.dp, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

// MARK: - "N days · N workouts stored" footnote line (mirrors the macOS counts line)

@Composable
private fun CountLine(primary: String, secondary: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(primary, style = NoopType.captionNumber, color = Palette.textSecondary)
        Text("  ·  ", style = NoopType.footnote, color = Palette.textTertiary)
        Text(secondary, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun RoadmapNote(text: String) {
    Text(text, style = NoopType.footnote, color = Palette.textTertiary)
}

// MARK: - Backup action button (matches the accent fill used by CoachPrimaryButton)

@Composable
private fun BackupButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Palette.accent,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val ink = if (enabled) tint else tint.copy(alpha = Palette.disabledOpacity)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(Metrics.divider, ink.copy(alpha = 0.4f), shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Metrics.space14)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(Metrics.iconSmall))
        Spacer(Modifier.width(Metrics.space8))
        Text(label, style = NoopType.headline, color = ink)
    }
}

/**
 * Emit the Import & Data Ingest test-mode trace for a finished import, tagged TestDomain.IMPORT, iff the
 * mode is on. Shared by the Data Sources + Onboarding import flows (both call runImport). Gated zero-cost
 * when off: one SharedPreferences bool read before any line is built. The lines are byte-aligned with the
 * macOS ImportTrace shapes (parser / per-stage / reject / day-delta), built from the ImportSummary the
 * importer already returned. Never a file name, a path, or any health value.
 *
 * HONESTY (the whole point of this mode, tied to the // "didn't save" cluster): unlike the
 * Swift store, which returns the summed SQLite changes from each upsert, Room's @Upsert reports no
 * store-write count at this layer. So Android does NOT claim "(all written)" / "(all days persisted)" - it
 * emits rowsIn / daysMapped with rowsOut / daysPersisted marked UNVERIFIED. A line never asserts a save it
 * cannot confirm. REJECTED counts (e.g. skippedSpans - scrubbed/damaged spans, the OPPOSITE of written)
 * are routed through the reject line, never a stage line, matching AppleHealthImport.swift.
 */
internal fun emitImportTrace(
    context: android.content.Context,
    vm: AppViewModel,
    summary: com.noop.data.ImportSummary,
) {
    if (!com.noop.testcentre.TestCentre.from(context).active(com.noop.testcentre.TestDomain.IMPORT)) return
    if (summary.totalRows <= 0) return   // a failed/empty import already logged its reason above
    val kind = com.noop.analytics.ImportTrace.kindWire(summary.source)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.parserVersionLine(kind, importerVersion = 1),
        com.noop.testcentre.TestDomain.IMPORT,
    )
 // Reject keys are NOT writes: they are rows/spans the import dropped (the opposite of "written"), so
 // they must never become a stage line. skippedSpans is the only one an Android importer emits today.
    val skippedSpans = summary.counts["skippedSpans"] ?: 0
    for ((rawKey, count) in summary.counts) {
        if (rawKey == "skippedSpans") continue   // routed through the reject line below, not as a stage
        val category = com.noop.analytics.ImportTrace.categoryWire(summary.source, rawKey)
 // rowsOut is UNVERIFIED on Android (Room reports no store-write count); never claim "(all written)".
        vm.ble.externalLog(
            com.noop.analytics.ImportTrace.stageLineUnverified(category, rowsIn = count),
            com.noop.testcentre.TestDomain.IMPORT,
        )
    }
 // The reject line mirrors AppleHealthImport.swift: the app map drops nothing further here, so
 // droppedRows = 0; skippedSpans carries the tolerant-import scrubbed-span count (0 on non-Apple).
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.rejectLine(droppedRows = 0, skippedSpans = skippedSpans),
        com.noop.testcentre.TestDomain.IMPORT,
    )
 // Day delta: pick the source's day-keyed table (Apple -> appleDaily, WHOOP/others -> dailyMetric) so a
 // real Apple import reports the right day count, and label the stage with the Swift category vocabulary.
    val dayKey = if (summary.counts.containsKey("appleDaily")) "appleDaily" else "dailyMetric"
    val days = summary.counts[dayKey] ?: summary.counts["days"] ?: 0
    val dayCategory = com.noop.analytics.ImportTrace.categoryWire(summary.source, dayKey)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.dayDeltaLineUnverified(dayCategory, daysMapped = days),
        com.noop.testcentre.TestDomain.IMPORT,
    )
}
