package com.noop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noop.data.DataBackup
import com.noop.data.WhoopRepository
import com.noop.ingest.WhoopCsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & Sync (Phase 1 - folder). Apple mirror of `BackupSyncView`: pick a folder, turn on the
 * opt-in daily auto-backup, back up now, or restore. Snapshots are the existing `.noopbak` whole-DB
 * format ([DataBackup]). Point the folder at a Google Drive / Dropbox sync app for off-device backup
 * with no in-app cloud account.
 *
 * Must-fixes baked in here:
 *  1. Restore lists the snapshots in the CHOSEN folder (newest-first) and lets the user pick one,
 *     rather than re-prompting with an unrelated document picker. A tightened file fallback exists
 *     only for folders we can't enumerate / legacy files.
 *  2. An explicit in-app confirm dialog fires before any destructive restore call.
 *  3. The file-fallback picker is tightened off the all-files wildcard to the backup MIME types, and
 *     the live [DataBackup.importFrom] now also rejects a foreign-but-valid SQLite (Mac/GRDB or other-app DB).
 */
@Composable
fun BackupSyncScreen(repo: WhoopRepository, activeStrapId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android-only fork: backups write to a STABLE public folder ([BackupSync.backupDir]) via All-Files
    // Access, not a SAF tree whose per-folder grant is revoked on reinstall. [hasAccess] mirrors the live
    // permission and is re-read on ON_RESUME so returning from the system grant screen updates the UI.
    var hasAccess by remember { mutableStateOf(BackupSync.hasFilesAccess(context)) }
    var auto by remember { mutableStateOf(BackupSyncPrefs.autoEnabled(context)) }
    var lastMs by remember { mutableStateOf(BackupSyncPrefs.lastBackupMs(context)) }
    var busy by remember { mutableStateOf(false) }
    // How many dated snapshots to keep; pruning deletes the oldest beyond this (BackupSync.snapshotsToPrune).
    var keep by remember { mutableStateOf(BackupSyncPrefs.keepCount(context)) }
    var keepMenu by remember { mutableStateOf(false) }
    // Time-of-day the daily backup runs (minutes since midnight); default 01:00, user-adjustable.
    var backupMinute by remember { mutableStateOf(BackupSyncPrefs.backupMinute(context)) }

    // Restore-from-folder sheet state: the listed snapshots, and the one pending confirmation.
    var snapshots by remember { mutableStateOf<List<BackupSync.SnapshotFile>>(emptyList()) }
    var showSnapshotPicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<String, Uri>?>(null) }

    // Legacy WRITE grant (API <= 29); API 30+ uses the All-Files-Access settings screen instead.
    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasAccess = BackupSync.hasFilesAccess(context)
        if (hasAccess) runCatching { BackupSync.reschedule(context) }
    }
    fun requestFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Open THIS app's "All files access" toggle; fall back to the generic list if the OEM lacks the
            // app-specific screen. The grant is applied when the user returns (ON_RESUME re-reads it below).
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    // Re-read the live permission whenever the screen resumes (e.g. back from the All-Files-Access screen).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = BackupSync.hasFilesAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Runs the actual destructive restore for a chosen backup Uri, off the main thread.
    fun runRestore(uri: Uri) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { DataBackup.importFrom(context, uri) }
            busy = false
            when (r) {
                is DataBackup.ImportResult.NeedsRestart -> {
                    // #57: the restore CLOSED and swapped the database file. The long-lived WhoopRepository +
                    // BLE client still hold a DAO on the OLD (now-closed) connection, so any strap sync would
                    // fail with "connection pool has been closed" — and, worse, empty/metadata history ENDs
                    // would still ack and trim the strap PAST records we can't store, discarding real history.
                    // Relaunching the process re-opens Room against the restored file. Do it automatically
                    // rather than trust the user to read a toast (which is exactly how #57 happened).
                    Toast.makeText(context, "Backup restored — restarting NOOP…", Toast.LENGTH_LONG).show()
                    // NonCancellable: this coroutine runs in the screen's scope, which is cancelled the
                    // instant the user navigates away. The restart is a data-safety guarantee (the DB is
                    // already swapped), so it must complete even if the composition leaves — otherwise the
                    // user could keep syncing into the closed DB, the very bug we're fixing.
                    withContext(NonCancellable) {
                        delay(800)   // let the toast render before the process dies
                        val ctx = context.applicationContext
                        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            ?.let { ctx.startActivity(it) }
                        Runtime.getRuntime().exit(0)
                    }
                }
                is DataBackup.ImportResult.Failed ->
                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Must-fix #1 + #3: the FILE fallback is tightened to the backup MIME types (was `*/*`). Used only
    // when the chosen folder holds no snapshots, or to restore a one-off file from elsewhere. The chosen
    // file still passes through importFrom's full validation (magic + Room/GRDB-origin) and the same
    // confirm dialog before it overwrites anything.
    val pickRestoreFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingRestore = "the selected file" to uri
    }

    // Export a portable copy (moved here from Settings): a one-off .noopbak to any location, and the
    // WHOOP-format CSV zip. The folder auto-backup above stays the primary path; these are for sharing /
    // moving to another phone without configuring a folder.
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) { busy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DataBackup.exportTo(context, uri) } }
            busy = false
            result.fold(
                onSuccess = {
                    Toast.makeText(
                        context,
                        "Backup exported. Copy it to another phone and use Restore there.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { e -> Toast.makeText(context, "Backup problem: ${e.message}", Toast.LENGTH_LONG).show() },
            )
        }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) { busy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            // #458: thread the registry's ACTIVE strap id — the exporter's old "my-whoop" default
            // exported an empty zip on live-BLE installs (the engine banks under "<strapId>-noop").
            val result = withContext(Dispatchers.IO) { runCatching { WhoopCsvExporter.exportZip(context, uri, repo, activeStrapId) } }
            busy = false
            result.fold(
                onSuccess = { msg ->
                    Toast.makeText(
                        context,
                        "$msg Re-import it via Data sources -> WHOOP import, on Android or Mac.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { e -> Toast.makeText(context, "CSV export problem: ${e.message}", Toast.LENGTH_LONG).show() },
            )
        }
    }

    LazyScreenScaffold(
        title = "Backup & Sync",
        subtitle = "Save a full backup to a folder you choose - point it at Google Drive / Dropbox for off-device sync.",
    ) {
        // 1 · Destination folder
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Backup location", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "Backups are saved to ${BackupSync.backupDir().path}",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    Text(
                        "Point a sync app (FolderSync / Autosync) or a desktop Drive / Dropbox client at that " +
                            "folder for off-device backup — NOOP only writes the local file.",
                        style = NoopType.caption, color = Palette.accent,
                    )
                    if (hasAccess) {
                        Text("✓ File access granted", style = NoopType.footnote, color = Palette.accent)
                    } else {
                        Text(
                            "NOOP needs file access to write backups to that folder. Grant it once — it survives " +
                                "reinstalls (unlike the old folder-picker, whose access was lost on every update).",
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                        NoopButton(
                            text = "Allow file access",
                            leadingIcon = Icons.Filled.FolderOpen,
                            kind = NoopButtonKind.Secondary,
                            enabled = !busy,
                            onClick = { requestFilesAccess() },
                        )
                    }
                }
            }
        }

        // 2 · Auto-backup + back up now
        item {
            NoopCard(padding = 20.dp, tint = if (auto && hasAccess) Palette.accent else null) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Daily auto-backup", style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                "Writes a fresh dated backup to your folder once a day at the time below, keeping " +
                                    "the latest $keep. Off by default - flip it on if you want it.",
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = auto,
                            enabled = hasAccess && !busy,
                            onCheckedChange = {
                                auto = it
                                BackupSyncPrefs.setAutoEnabled(context, it)
                                runCatching { BackupSync.reschedule(context) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.surfaceBase,
                                checkedTrackColor = Palette.accent,
                                uncheckedThumbColor = Palette.textSecondary,
                                uncheckedTrackColor = Palette.surfaceInset,
                                uncheckedBorderColor = Palette.hairline,
                            ),
                        )
                    }
                    // Retention: how many dated snapshots to keep. Wired to the existing setKeepCount; the
                    // next backup (auto or "Back up now") prunes the oldest beyond this count.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Keep last snapshots", style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                "Older backups beyond this many are pruned, oldest first (≈ that many days of " +
                                    "daily backups). For recovery: if data ever corrupts, grab the newest snapshot.",
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Box {
                            TextButton(
                                enabled = hasAccess && !busy,
                                onClick = { keepMenu = true },
                            ) {
                                Text("$keep", style = NoopType.body, color = Palette.accent)
                            }
                            DropdownMenu(
                                expanded = keepMenu,
                                onDismissRequest = { keepMenu = false },
                            ) {
                                KEEP_OPTIONS.forEach { n ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "$n",
                                                style = NoopType.body,
                                                color = if (n == keep) Palette.accent else Palette.textPrimary,
                                            )
                                        },
                                        onClick = {
                                            keep = n
                                            BackupSyncPrefs.setKeepCount(context, n)
                                            keepMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Backup time-of-day. Picking a new time re-anchors the schedule immediately
                    // (BackupSync.applyTimeChange); WorkManager isn't exact so it's best-effort.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Backup time", style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                "Roughly when the daily backup runs (best-effort — the system may slide it a little).",
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        TimeChip(
                            minutes = backupMinute,
                            accessibilityLabel = "Daily backup time",
                            onPicked = { m ->
                                backupMinute = m
                                BackupSyncPrefs.setBackupMinute(context, m)
                                runCatching { BackupSync.applyTimeChange(context) }
                            },
                        )
                    }
                    Text(
                        if (lastMs > 0L) {
                            "Last backup: ${DateUtils.getRelativeTimeSpanString(lastMs)}"
                        } else {
                            "No backup yet."
                        },
                        style = NoopType.caption, color = Palette.textTertiary,
                    )
                    NoopButton(
                        text = if (busy) "Working…" else "Back up now",
                        leadingIcon = Icons.Filled.CloudUpload,
                        fullWidth = true,
                        enabled = hasAccess && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { BackupSync.backupNow(context) }
                                lastMs = BackupSyncPrefs.lastBackupMs(context)
                                busy = false
                                Toast.makeText(
                                    context,
                                    if (ok) {
                                        "Backed up to ${BackupSync.backupDir().name}."
                                    } else {
                                        "Backup failed - grant file access and try again."
                                    },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                }
            }
        }

        // 3 · Restore (must-fix #1: from the chosen folder, newest-first)
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Restore", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "Replace this device's data with one of your backups. This overwrites current data, " +
                            "so back up first if unsure.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    NoopButton(
                        text = "Restore from a backup…",
                        leadingIcon = Icons.Filled.Restore,
                        kind = NoopButtonKind.Secondary,
                        enabled = !busy,
                        onClick = {
                            if (!hasAccess) {
                                // No file access yet: fall back to the one-off file picker (any location).
                                pickRestoreFile.launch(RESTORE_MIME_TYPES)
                            } else {
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        runCatching { BackupSync.listSnapshots() }.getOrDefault(emptyList())
                                    }
                                    if (found.isEmpty()) {
                                        // Folder has no snapshots yet - point at a file instead.
                                        pickRestoreFile.launch(RESTORE_MIME_TYPES)
                                    } else {
                                        snapshots = found
                                        showSnapshotPicker = true
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        // 4 · Export a copy (moved here from Settings) - a portable file to move to another phone or share.
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Export a copy", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "Save a one-off file to move your data to another phone or share it. The .noopbak is the " +
                            "lossless restore file; the CSV is a WHOOP-format zip that re-imports on Android or Mac.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NoopButton(
                            text = if (busy) "Working…" else "Export backup file…",
                            kind = NoopButtonKind.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                busy = true
                                exportFileLauncher.launch("noop-backup-${java.time.LocalDate.now()}.noopbak")
                            },
                        )
                        NoopButton(
                            text = "Export CSV…",
                            kind = NoopButtonKind.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                busy = true
                                csvExportLauncher.launch("noop-export-${java.time.LocalDate.now()}.zip")
                            },
                        )
                    }
                }
            }
        }
    }

    // Must-fix #1: the snapshot picker - the folder's backups, newest-first.
    if (showSnapshotPicker) {
        AlertDialog(
            onDismissRequest = { showSnapshotPicker = false },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text("Choose a backup", style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Newest first. Restoring replaces this device's data.",
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                    snapshots.forEach { snap ->
                        // Label + confirmation come from the resolved timeMs carried through from
                        // listSnapshotDocs, so a hand-named / date-only backup still shows a friendly date
                        // (its file-modification date) instead of the raw filename - parity with Swift. Only
                        // when the date is genuinely unknown (timeMs == 0) do we fall back to the name.
                        val whenLabel = if (snap.timeMs > 0L) {
                            DateUtils.getRelativeTimeSpanString(snap.timeMs).toString()
                        } else {
                            snap.name
                        }
                        Text(
                            text = whenLabel,
                            style = NoopType.body,
                            color = Palette.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSnapshotPicker = false
                                    pendingRestore = if (snap.timeMs > 0L) {
                                        "the backup from $whenLabel"
                                    } else {
                                        snap.name
                                    } to Uri.fromFile(snap.file)
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnapshotPicker = false }) {
                    Text("Cancel", style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }

    // Must-fix #2: explicit in-app confirm BEFORE any destructive restore call, on every restore path.
    pendingRestore?.let { (label, uri) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text("Replace all current data?", style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Text(
                    "Replace all current data with $label? This cannot be undone.",
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    runRestore(uri)
                }) {
                    Text("Replace", style = NoopType.body, color = Palette.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text("Cancel", style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }
}

/**
 * Must-fix #3: the restore file fallback is tightened off the all-files wildcard to the backup
 * container MIME types: the .noopbak ZIP (octet-stream / zip) and a legacy plain SQLite. Anything that
 * slips through still meets importFrom's magic-byte + Room/GRDB-origin validation before it can touch
 * the live DB.
 */
/** Retention choices for the "Keep last snapshots" menu. Each snapshot is a dated .noopbak; the daily
 *  job keeps this many and prunes the oldest. Kept modest — a few days of rollback without hoarding. */
private val KEEP_OPTIONS = listOf(1, 3, 5, 7, 10, 14)

private val RESTORE_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/zip",
    "application/x-sqlite3",
)
