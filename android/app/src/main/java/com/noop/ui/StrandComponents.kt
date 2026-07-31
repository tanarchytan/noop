package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale

/**
 * Chevron-navigation day selector for the Today screen. The left chevron steps one day
 * older, the right one day newer (disabled at today so a future day can't be selected),
 * and tapping the centre accent block opens a [DatePickerDialog] capped at today for a
 * direct jump to any past date. Mirrors the SleepScreen night navigator, replacing the
 * fixed three-day strip with unbounded back-navigation while keeping the same tokens.
 */
@Composable
internal fun DayNavBar(
    selectedOffset: Int,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val base = LocalDate.now()
    val selectedDay = base.minusDays(selectedOffset.toLong())
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        DisposableEffect(selectedDay) {
            val cal = Calendar.getInstance().apply {
                set(selectedDay.year, selectedDay.monthValue - 1, selectedDay.dayOfMonth)
            }
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = LocalDate.of(year, month + 1, day)
                    val offset = ChronoUnit.DAYS.between(picked, base).toInt().coerceAtLeast(0)
                    onSelect(offset)
                    showPicker = false
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.maxDate = System.currentTimeMillis()
                setOnDismissListener { showPicker = false }
            }
            dialog.show()
            onDispose { runCatching { dialog.dismiss() } }
        }
    }

    val canGoNewer = selectedOffset > 0
    val label = when (selectedOffset) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> selectedDay.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
    }
    val date = selectedDay.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
    val blockShape = RoundedCornerShape(Metrics.cornerSm)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelect(selectedOffset + 1) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day", tint = Palette.accent)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(blockShape)
                // Clean, material surface — no gold wash behind the date (that read as a murky
                // dark-yellow block); the gold pop lives only on the date text itself.
                .background(Palette.surfaceInset)
                .border(Metrics.divider, Palette.hairline, blockShape)
                .clickable(onClickLabel = "Pick a date") { showPicker = true }
                .padding(vertical = Metrics.selectorPadding, horizontal = Metrics.selectorPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = NoopType.caption, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                date,
                style = NoopType.captionNumber,
                // The single gold pop on the chip — the date itself, on a clean material surface.
                color = Palette.accentHover,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Metrics.space2),
            )
        }
        IconButton(onClick = { if (canGoNewer) onSelect(selectedOffset - 1) }, enabled = canGoNewer) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next day", tint = if (canGoNewer) Palette.accent else Palette.textTertiary)
        }
    }
}

@Composable
internal fun InsetChartPlaceholder(
    message: String,
    height: Dp = Metrics.compactChartHeight,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = NoopType.subhead, color = Palette.textTertiary)
    }
}

