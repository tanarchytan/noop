package com.noop.ui.whoop

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.noop.ui.DayPagerBar
import com.noop.ui.Metrics
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.GlowRing
import com.noop.ui.Palette
import com.noop.ui.ProfileAvatar
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.batteryPillTone
import com.noop.ui.logicalDayNow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import kotlin.math.roundToInt

// MARK: - Home header
//
// The sticky bar (avatar · day pager · strap chip), the three hero rings, and the compact ring row the
// hero collapses into on scroll. No figure is computed here: every value arrives already scored.

/** Ring stroke as a fraction of the ring diameter — the WHOOP arc weight. */
private const val RING_STROKE_FRACTION = 0.10f

/** The compact row's ring stroke; a small ring needs a proportionally heavier arc to read. */
private const val COMPACT_RING_STROKE_FRACTION = 0.16f

/** Slots the hero row divides its width into: three rings plus the gaps between them. */
private const val HERO_RING_SLOTS = 3.1f

/**
 * The sticky top bar: the profile avatar, the `‹ TODAY ›` day pager (its caption carries the date), and
 * the strap chip. [batteryPct] only shows while [connected], so a stale reading never presents as live.
 */
@Composable
internal fun WhoopHomeTopBar(
    title: String,
    subtitle: String,
    date: LocalDate,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDay: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDevices: () -> Unit,
    connected: Boolean,
    batteryPct: Double?,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        val context = LocalContext.current
        DisposableEffect(date) {
            val cal = Calendar.getInstance().apply {
                set(date.year, date.monthValue - 1, date.dayOfMonth)
            }
            // Anchor on the LOGICAL day so a picked date resolves to the row the bar is labelling.
            val anchor = logicalDayNow()
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = LocalDate.of(year, month + 1, day)
                    onPickDay(ChronoUnit.DAYS.between(picked, anchor).toInt().coerceAtLeast(0))
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.surfaceBase)
            .padding(horizontal = Metrics.screenPadding, vertical = Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Box(
            modifier = Modifier
                .size(Metrics.iconButton)
                .clip(CircleShape)
                .clickable(onClickLabel = "Profile", onClick = onOpenProfile)
                .semantics { contentDescription = "Profile" },
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(size = Metrics.iconButton)
        }
        DayPagerBar(
            label = title,
            overline = subtitle,
            onPrevious = onPrevious,
            onNext = onNext,
            canGoNext = canGoNext,
            onLabelClick = { showPicker = true },
            modifier = Modifier.weight(1f),
        )
        WhoopStrapChip(connected = connected, batteryPct = batteryPct, onClick = onOpenDevices)
    }
}

/** The strap state chip: the battery percentage while the link is up, else an honest "No strap". */
@Composable
private fun WhoopStrapChip(connected: Boolean, batteryPct: Double?, onClick: () -> Unit) {
    val pct = if (connected) batteryPct?.roundToInt() else null
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Metrics.cornerPill))
            .clickable(onClickLabel = "Strap", onClick = onClick),
    ) {
        if (pct != null) {
            StatePill(title = "$pct%", tone = batteryPillTone(pct), icon = Icons.Filled.Watch)
        } else {
            StatePill(title = "No strap", tone = StrandTone.Neutral, icon = Icons.Filled.Watch)
        }
    }
}

/**
 * One hero ring's inputs. [value] null draws an empty track and the caller's honest empty words.
 * [unit] is the mark beside the number ("%"); [caption] the line under it (the Effort denominator).
 */
internal data class WhoopHeroRing(
    val label: String,
    val value: Double?,
    val fraction: Double?,
    val tint: Color,
    val format: (Double) -> String,
    val emptyTitle: String,
    val emptyDetail: String? = null,
    val unit: String? = null,
    val caption: String? = null,
    val onClick: () -> Unit,
)

/**
 * The fill-memory key a hero ring shares with its pinned twin, so a ring that has already filled stays
 * filled through the collapse and through every scroll that recycles it.
 */
private fun homeRingFillKey(ring: WhoopHeroRing): String = "home.${ring.label}"

/**
 * The centred "N O O P" mark over the hero. Drawn letter by letter because one tracked string adds a
 * trailing gap after the last glyph and pushes the word off centre. Decorative, so TalkBack skips it.
 */
@Composable
private fun NoopWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Metrics.space14, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        "NOOP".forEach { ch ->
            Text(ch.toString(), style = NoopType.overline, color = Palette.textTertiary)
        }
    }
}

/** The hero block: the wordmark over three rings, filling the width as one balanced row. */
@Composable
internal fun WhoopHeroRingRow(rings: List<WhoopHeroRing>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val diameter: Dp = ((maxWidth - Metrics.space14 * 2) / HERO_RING_SLOTS)
            .coerceIn(Metrics.tileHeight * 0.78f, Metrics.tileHeight)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            NoopWordmark()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space14, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                rings.forEach { ring -> WhoopHeroRingColumn(ring = ring, diameter = diameter) }
            }
        }
    }
}

@Composable
private fun WhoopHeroRingColumn(ring: WhoopHeroRing, diameter: Dp) {
    val spoken = buildString {
        append(ring.label).append(", ")
        append(ring.value?.let(ring.format) ?: ring.emptyTitle)
        if (ring.value != null && ring.unit != null) append(ring.unit)
        if (ring.value != null && ring.caption != null) append(" ").append(ring.caption)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .clickable(onClickLabel = ring.label, onClick = ring.onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Box(contentAlignment = Alignment.Center) {
            GlowRing(
                fraction = (ring.fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                value = ring.value ?: 0.0,
                color = ring.tint,
                diameter = diameter,
                lineWidth = diameter * RING_STROKE_FRACTION,
                fillKey = homeRingFillKey(ring),
                showsLabel = ring.value != null,
                format = ring.format,
                unit = ring.unit,
                caption = ring.caption,
            )
            if (ring.value == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        ring.emptyTitle,
                        style = NoopType.headline,
                        color = Palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ring.emptyDetail != null) {
                        Text(
                            ring.emptyDetail,
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space2),
        ) {
            Overline(ring.label)
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

/**
 * The pinned row the hero collapses into once the page scrolls past it: a small ring and its word,
 * three across, over a hairline. The signature Home interaction.
 */
@Composable
internal fun WhoopHeroRingRowCompact(rings: List<WhoopHeroRing>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(Palette.surfaceBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.screenPadding, vertical = Metrics.space8),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rings.forEach { ring ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Metrics.cornerSm))
                        .clickable(onClickLabel = ring.label, onClick = ring.onClick)
                        .semantics(mergeDescendants = true) { contentDescription = ring.label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                ) {
                    GlowRing(
                        fraction = (ring.fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                        value = ring.value ?: 0.0,
                        color = ring.tint,
                        diameter = Metrics.iconButton,
                        lineWidth = Metrics.iconButton * COMPACT_RING_STROKE_FRACTION,
                        fillKey = homeRingFillKey(ring),
                        showsLabel = false,
                    )
                    Overline(ring.label, color = Palette.textPrimary)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Metrics.divider)
                .background(Palette.hairline),
        )
    }
}
