package com.noop.ui

import android.app.DatePickerDialog
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Calendar

/**
 * "Last Night's Sleep" with the EDIT affordance, over the chevrons that walk every recorded night. The
 * centre block opens a date picker to jump to a night directly; the arrows step one DAY at a time, so a
 * split-sleep day is one stop.
 */
@Composable
internal fun SleepNightHeader(
    offset: Int,
    lastIndex: Int,
    dateLabel: String?,
    canEdit: Boolean,
    onNavigate: (Int) -> Unit,
    onEdit: () -> Unit,
    onAddNap: () -> Unit,
    onPickNightDate: ((LocalDate) -> Unit)?,
    anchorTs: Long?,
) {
    val canGoOlder = offset < lastIndex
    val canGoNewer = offset > 0
    var showDatePicker by remember { mutableStateOf(false) }
    val blockShape = RoundedCornerShape(Metrics.cornerSm)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (canGoOlder) onNavigate(offset + 1) }, enabled = canGoOlder) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous night",
                    tint = if (canGoOlder) Palette.accent else Palette.textTertiary,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(blockShape)
                    .background(Palette.surfaceInset)
                    .border(Metrics.divider, Palette.hairline, blockShape)
                    .clickable(enabled = onPickNightDate != null, onClickLabel = "Pick night date") {
                        showDatePicker = true
                    }
                    .padding(Metrics.selectorPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    nightOffsetLabel(offset),
                    style = NoopType.caption,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dateLabel != null) {
                    Text(
                        dateLabel,
                        style = NoopType.captionNumber,
                        color = Palette.accentHover,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { if (canGoNewer) onNavigate(offset - 1) }, enabled = canGoNewer) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next night",
                    tint = if (canGoNewer) Palette.accent else Palette.textTertiary,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (offset == 0) "Last Night's Sleep" else "That Night's Sleep",
                style = NoopType.title2,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canEdit) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Metrics.cornerSm))
                        .clickable(onClickLabel = "Edit time in bed", onClick = onEdit)
                        .padding(horizontal = Metrics.space10, vertical = Metrics.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("EDIT", style = NoopType.overline, color = Palette.textPrimary)
                    Spacer(Modifier.width(Metrics.space6))
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = Palette.textPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onAddNap) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add a nap",
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // When the older-night arrow is disabled a greyed chevron reads as broken, so say why instead.
        if (!canGoOlder) {
            Text(
                "No earlier night stored yet. Earlier nights sync in the morning.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Date jump — capped at today so a future night can't be selected.
    if (showDatePicker && onPickNightDate != null) {
        val context = LocalContext.current
        val cal = Calendar.getInstance().apply {
            if (anchorTs != null) timeInMillis = anchorTs * 1000L
        }
        DisposableEffect(anchorTs) {
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    onPickNightDate(LocalDate.of(year, month + 1, day))
                    showDatePicker = false
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.maxDate = System.currentTimeMillis()
                setOnDismissListener { showDatePicker = false }
            }
            dialog.show()
            onDispose { runCatching { dialog.dismiss() } }
        }
    }
}

/** "Last night" / "1 night ago" / "N nights ago" for the browse position. */
internal fun nightOffsetLabel(offset: Int): String = when (offset) {
    0 -> "Last night"
    1 -> "1 night ago"
    else -> "$offset nights ago"
}

/**
 * The night's clock window as its own labelled row. The header caption truncates between the chevrons
 * on a phone, hiding the two times people look for first. The window is the WHOLE night's, not the
 * edit-anchor fragment's.
 */
@Composable
internal fun SleepWindowRow(onsetTs: Long, wakeTs: Long) {
    val asleep = clockTimeLabel(onsetTs)
    val woke = clockTimeLabel(wakeTs)
    NoopCard(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Fell asleep at $asleep, woke at $woke"
        },
        padding = Metrics.space14,
        tint = Palette.restColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SleepTime(icon = Icons.Filled.Bedtime, label = "Asleep", value = asleep)
            Spacer(Modifier.width(Metrics.space12))
            Box(modifier = Modifier.height(30.dp).width(Metrics.divider).background(Palette.hairline))
            Spacer(Modifier.width(Metrics.space12))
            SleepTime(icon = Icons.Filled.WbSunny, label = "Woke", value = woke)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** One labelled clock time with its glyph. */
@Composable
private fun SleepTime(icon: ImageVector, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null, // the row carries the combined description
            tint = Palette.restColor,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Overline(label, color = Palette.textTertiary)
            Text(value, style = NoopType.number(22f), color = Palette.textPrimary, maxLines = 1)
        }
    }
}
