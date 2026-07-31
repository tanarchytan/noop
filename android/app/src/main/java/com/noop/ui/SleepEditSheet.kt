package com.noop.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.analytics.SleepEditGuard
import com.noop.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Which chip the pickers are currently driving; null when no picker is open. */
private enum class SleepEditField { BedDate, BedTime, WakeDate, WakeTime }

/**
 * EDIT TIME IN BED — the night's two bounds, each as a date chip and a time chip, then SAVE and
 * DELETE SLEEP. Both bounds are editable together, so [onSave] carries whether the WAKE chip was one
 * of the ones the user moved: that is what freezes the wake against the post-sync heal, and a bed-only
 * correction must leave the wake re-detectable.
 *
 * A corrected window that no longer overlaps the night's recorded coverage has nothing to stage from,
 * so it parks behind an explicit confirm rather than silently creating an all-awake phantom night.
 */
@Composable
internal fun SleepEditSheetContent(
    session: SleepSession,
    isNew: Boolean,
    onClose: () -> Unit,
    onSave: (Long, Long, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var bedTs by remember(session.startTs) { mutableLongStateOf(session.effectiveStartTs) }
    var wakeTs by remember(session.startTs) { mutableLongStateOf(session.effectiveEndTs) }
    var wakeMoved by remember(session.startTs) { mutableStateOf(false) }
    var editing by remember(session.startTs) { mutableStateOf<SleepEditField?>(null) }
    var showDeleteConfirm by remember(session.startTs) { mutableStateOf(false) }
    var pendingDisjoint by remember(session.startTs) { mutableStateOf<Pair<Long, Long>?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space24, vertical = Metrics.space8),
        verticalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textSecondary)
            }
            Text(
                if (isNew) "ADD A NAP" else "EDIT TIME IN BED",
                style = NoopType.overline,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(48.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TIME", style = NoopType.overline, color = Palette.textTertiary)
            Spacer(Modifier.width(Metrics.space12))
            Box(modifier = Modifier.weight(1f).height(Metrics.divider).background(Palette.hairline))
        }

        SleepEditRow(
            label = "Bed Time",
            ts = bedTs,
            onPickDate = { editing = SleepEditField.BedDate },
            onPickTime = { editing = SleepEditField.BedTime },
        )
        SleepEditRow(
            label = "Wake Time",
            ts = wakeTs,
            onPickDate = { editing = SleepEditField.WakeDate },
            onPickTime = { editing = SleepEditField.WakeTime },
        )

        Spacer(Modifier.height(Metrics.space16))
        SleepEditAction(
            label = "SAVE",
            filled = true,
            onClick = {
                val coverageStart = minOf(session.startTs, session.effectiveStartTs)
                if (!isNew && SleepEditGuard.isDisjoint(bedTs, wakeTs, coverageStart, session.effectiveEndTs)) {
                    pendingDisjoint = bedTs to wakeTs
                } else {
                    onSave(bedTs, wakeTs, wakeMoved)
                    onClose()
                }
            },
        )
        if (!isNew) {
            SleepEditAction(label = "DELETE SLEEP", filled = false, onClick = { showDeleteConfirm = true })
        }
        Spacer(Modifier.height(Metrics.space16))
    }

    val field = editing
    if (field != null) {
        val current = if (field == SleepEditField.BedDate || field == SleepEditField.BedTime) bedTs else wakeTs
        val isDate = field == SleepEditField.BedDate || field == SleepEditField.WakeDate
        SleepEditPicker(
            currentTs = current,
            pickDate = isDate,
            onDismiss = { editing = null },
            onPicked = { picked ->
                when (field) {
                    SleepEditField.BedDate, SleepEditField.BedTime -> bedTs = picked
                    SleepEditField.WakeDate, SleepEditField.WakeTime -> { wakeTs = picked; wakeMoved = true }
                }
                editing = null
            },
        )
    }

    val disjoint = pendingDisjoint
    if (disjoint != null) {
        AlertDialog(
            onDismissRequest = { pendingDisjoint = null },
            containerColor = Palette.surfaceRaised,
            titleContentColor = Palette.textPrimary,
            textContentColor = Palette.textSecondary,
            title = { Text("Move this sleep?", style = NoopType.headline) },
            text = {
                Text(
                    "This moves the night to a time with no recorded data. Stages can't be derived there, " +
                        "so it may show as empty until data covers it.",
                    style = NoopType.subhead,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(disjoint.first, disjoint.second, wakeMoved)
                    pendingDisjoint = null
                    onClose()
                }) { Text("Move anyway", style = NoopType.subhead, color = Palette.statusWarning) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisjoint = null }) {
                    Text("Cancel", style = NoopType.subhead, color = Palette.textSecondary)
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Palette.surfaceRaised,
            titleContentColor = Palette.textPrimary,
            textContentColor = Palette.textSecondary,
            title = { Text("Delete this sleep session?", style = NoopType.headline) },
            text = {
                // A detected night is tombstoned so it won't re-detect; a userEdited row writes no
                // tombstone, so its copy drops that promise.
                Text(
                    if (session.userEdited) {
                        "Removes this sleep and recomputes the day without it. You can undo for a few " +
                            "seconds after."
                    } else {
                        "Removes this recorded sleep and recomputes the day without it. NOOP won't " +
                            "re-detect sleep in this window. You can undo for a few seconds after."
                    },
                    style = NoopType.subhead,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                    onClose()
                }) { Text("Delete", style = NoopType.headline, color = Palette.statusCritical) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", style = NoopType.subhead, color = Palette.textTertiary)
                }
            },
        )
    }
}

/** One bound: its name, then the date chip and the time chip that move it. */
@Composable
private fun SleepEditRow(label: String, ts: Long, onPickDate: () -> Unit, onPickTime: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        SleepEditChip(text = editDateLabel(ts), description = "$label date", onClick = onPickDate)
        Spacer(Modifier.width(Metrics.space8))
        SleepEditChip(text = clockTimeLabel(ts), description = "$label of day", onClick = onPickTime)
    }
}

/** A tappable value chip. */
@Composable
private fun SleepEditChip(text: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceOverlay)
            .clickable(onClickLabel = "Change $description", onClick = onClick)
            .padding(horizontal = Metrics.space16, vertical = Metrics.space12)
            .semantics { contentDescription = "$description $text" },
    ) {
        Text(text, style = NoopType.captionNumber, color = Palette.textPrimary, maxLines = 1)
    }
}

/** SAVE and DELETE SLEEP: one filled, one outlined, both full width. */
@Composable
private fun SleepEditAction(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(Palette.textPrimary)
                } else {
                    Modifier.border(1.dp, Palette.hairlineStrong, shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space16),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = NoopType.overline,
            color = if (filled) Palette.surfaceBase else Palette.textPrimary,
        )
    }
}

/**
 * The platform picker for one chip. A DATE pick keeps the bound's time of day and a TIME pick keeps
 * its calendar day, so each chip moves only what it names — which is what the two-chip layout promises
 * and what the time-only editor could not offer.
 */
@Composable
private fun SleepEditPicker(currentTs: Long, pickDate: Boolean, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val context = LocalContext.current
    val cal = Calendar.getInstance().apply { timeInMillis = currentTs * 1000L }
    DisposableEffect(currentTs, pickDate) {
        val dialog = if (pickDate) {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val out = Calendar.getInstance().apply {
                        timeInMillis = currentTs * 1000L
                        set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                    }
                    onPicked(out.timeInMillis / 1000L)
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).apply { datePicker.maxDate = System.currentTimeMillis() }
        } else {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val out = Calendar.getInstance().apply {
                        timeInMillis = currentTs * 1000L
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(out.timeInMillis / 1000L)
                },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true,
            )
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        onDispose { runCatching { dialog.dismiss() } }
    }
}

/** "Jul 29" for a chip. */
internal fun editDateLabel(ts: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(ts * 1000L))

/** What the edit sheet is open on: an existing block, or a provisional nap awaiting its first save. */
internal data class SleepEditTarget(val session: SleepSession, val isNew: Boolean)
