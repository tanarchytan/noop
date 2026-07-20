package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.automirrored.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.DatePickerDialog
import android.view.HapticFeedbackConstants
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Day navigation (#817) - chevron arrows + horizontal swipe, iOS parity
//
// `selectedDayOffset` is days-back-from-today (0 = today, 1 = yesterday, …). The header chevrons and a
// horizontal swipe across the dashboard both move it: older increments the offset (no upper bound - you
// can browse arbitrarily far back), newer decrements it but is CLAMPED at 0 so a future day can never be
// selected. These pure helpers hold that clamp so it's covered by a JVM test and shared by both the
// arrow taps and the swipe handler, matching the iOS DayNavBar's `canGoNewer` / `selectedOffset ± 1`.

/**
 * #860 item 1: the launch day-landing policy, as ONE pure decision so the rule can't drift between the
 * screen and its test and stays byte-identical to the iOS `TodayView.launchDayOffset` twin. A FRESH-PROCESS
 * launch ALWAYS lands on today (offset 0), even when today has no data yet and the only banked data is N days
 * back - that exact case is what stranded a calibrating user on an old day after an app update (the reporter's
 * case on v7.6.0). A non-fresh (in-session) call returns [savedOffset] UNCHANGED, so tabbing away to an old
 * day and coming back within the same process preserves the user-navigated day (#739/#614). The old
 * "land on the most recent data day" inputs are gone because that behaviour is retired. Mirror in Swift.
 */
internal fun launchDayOffset(
    isFreshLaunch: Boolean,
    savedOffset: Int,
): Int {
    if (!isFreshLaunch) return savedOffset
    return 0
}

/** The offset for one step toward an OLDER day (previous). Unbounded above - history runs as far back as
 *  the data does. */
internal fun dayNavOlder(selectedOffset: Int): Int = selectedOffset + 1

/** The offset for one step toward a NEWER day (next), CLAMPED at 0 so a future day is never selectable. */
internal fun dayNavNewer(selectedOffset: Int): Int = (selectedOffset - 1).coerceAtLeast(0)

/** True when there IS a newer day to step to (i.e. we're not already on today). Gates the ▶ chevron's
 *  enabled state, mirroring the iOS `canGoNewer`. */
internal fun dayNavCanGoNewer(selectedOffset: Int): Boolean = selectedOffset > 0

/** The minimum horizontal drag (px) that counts as a day-change swipe, so a small wobble during a
 *  vertical scroll doesn't flip the day. ~64dp at mdpi; the handler passes density-scaled px. */
internal const val DAY_NAV_SWIPE_THRESHOLD_DP: Float = 64f

/**
 * Resolve a completed horizontal swipe to the next [selectedDayOffset]. A drag whose total horizontal
 * travel doesn't clear [thresholdPx] returns the offset UNCHANGED (treated as a non-swipe). A rightward
 * swipe (positive [dragX], the natural "go back" / reveal-the-past gesture) steps to the OLDER day; a
 * leftward swipe steps to the NEWER day, clamped at today. Pure so the gesture mapping is unit-tested.
 */
internal fun dayNavSwipeTarget(selectedOffset: Int, dragX: Float, thresholdPx: Float): Int = when {
    kotlin.math.abs(dragX) < thresholdPx -> selectedOffset
    dragX > 0f -> dayNavOlder(selectedOffset)
    else -> dayNavNewer(selectedOffset)
}

// MARK: - Liquid Today header (iOS LiquidTodayView.scene parity)
//
// A STRUCTURAL rebuild to mirror the iOS liquid Today header element-for-element (NOT the old numeric-date +
// recording-light + bell header). LEFT: a tappable title block — the big rounded-bold day title over a human
// date line ("Friday, 3 July"), tap opens the day picker. RIGHT: exactly the iOS four controls, in order —
// a filled HEART (→ Support), the PROFILE AVATAR (→ Settings), a "+" ADD button (→ quick actions), and the
// strap BATTERY RING (→ Devices). Each ~34dp, spacing ~8dp. There is no recording light and no bell here;
// iOS's Today header has neither, and the Updates inbox is relocated into the "+" quick-actions sheet.

@Composable
internal fun LiquidTodayHeader(
    dayTitle: String,
    humanDate: String,
    selectedDay: LocalDate,
    batteryPct: Double?,
    // #245: sync state for the compact header chip (twin of iOS SyncStatusChip).
    backfilling: Boolean = false,
    syncChunksThisSession: Int = 0,
    lastSyncAt: Long? = null,
    historySyncExperimental: Boolean = false,
    onPickDay: (Int) -> Unit,
    onQuickActions: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        val context = LocalContext.current
        DisposableEffect(selectedDay) {
            val cal = Calendar.getInstance().apply {
                set(selectedDay.year, selectedDay.monthValue - 1, selectedDay.dayOfMonth)
            }
            // Anchor the offset to the LOGICAL day (matches selectedDayOffset's anchor) so a picked date
            // resolves to the same row the header is labelling, never drifting against LocalDate.now().
            val anchor = logicalDayNow()
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = LocalDate.of(year, month + 1, day)
                    val offset = ChronoUnit.DAYS.between(picked, anchor).toInt().coerceAtLeast(0)
                    onPickDay(offset)
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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // LEADING: the profile avatar (the photo set in the Profile menu, or the NOOP loop mark) → Profile menu.
        // Moved to the leading position so the avatar reads as "you" at the very start of the header.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenProfile,
                )
                .semantics { contentDescription = "Profile" },
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(size = 34.dp)
        }
        // LEFT: the tappable title block — big rounded-bold day title over the human date line. Taps open the
        // day picker; a horizontal swipe across the dashboard still changes the day. weight(1f) so the title
        // claims the leading room and never pushes the trailing control cluster. Mirrors iOS's title Button.
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Metrics.cornerSm))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Change day",
                    onClick = { showPicker = true },
                )
                .semantics { contentDescription = "$dayTitle, $humanDate. Tap to pick a day, swipe to change day." },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                dayTitle,
                // ~28sp Bold rounded, matching iOS `StrandFont.rounded(28)`. NoopType.number is the house
                // tabular sans; Bold at 28 is the display day title.
                style = NoopType.number(28f, weight = FontWeight.Bold),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                humanDate,
                style = NoopType.caption,
                color = Palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // RIGHT: the controls, in order — [sync chip] · + · battery ring. Each ~34dp, 8dp apart.
        // (The profile avatar moved to the leading/left position above.)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // #245: compact sync-status chip, shown for EVERY user — syncing / last-synced / experimental,
            // so the absence of active syncing reads as caught-up (the full SyncingHistoryNote is gated on
            // recovery == null). Twin of iOS SyncStatusChip.
            SyncStatusChip(
                backfilling = backfilling, chunks = syncChunksThisSession,
                lastSyncAt = lastSyncAt, historySyncExperimental = historySyncExperimental,
            )
            // (b) Quick-add (+), the accented primary. Mirrors iOS's LiquidAddButton (a glyph on a translucent
            // disc → the quick-actions menu). Sized 34dp to match the rest of the liquid cluster.
            QuickActionDisc(onClick = onQuickActions)
            // (c) Strap battery ring showing the % (iOS LiquidBatteryButton). Tap → Devices.
            LiquidBatteryRing(batteryPct = batteryPct, onClick = onOpenDevices)
        }
    }
}

/** #245: compact sync-status chip for the Today top bar, shown to EVERY user. The full-width
 *  SyncingHistoryNote is gated on `recovery == null`, so an established user (and especially a WHOOP 5/MG
 *  owner, whose history offloads are rare) saw no sync feedback on Today. THREE states so the ABSENCE of
 *  active syncing reads as "caught up", not "missing indicator" (the real #245 confusion): actively
 *  offloading → ⟳ N; idle with a known last-sync → ✓ Xm; a 5/MG whose history sync is experimental
 *  (live-connected, no completed offload yet) → ✓ live. Nothing shows only on a true cold start (the
 *  building-scores note owns that). Twin of iOS SyncStatusChip. DRAFT (#245): final styling/wording TBD. */
@Composable
private fun SyncStatusChip(
    backfilling: Boolean,
    chunks: Int,
    lastSyncAt: Long?,
    historySyncExperimental: Boolean,
) {
    when {
        backfilling -> ChipCapsule(
            Icons.Filled.Autorenew, "$chunks", Palette.accent, "Syncing strap history, $chunks chunks")
        lastSyncAt != null -> ChipCapsule(
            Icons.Filled.Check, shortSyncAgo(lastSyncAt), Palette.textSecondary,
            "Strap history synced ${shortSyncAgo(lastSyncAt)} ago")
        historySyncExperimental -> ChipCapsule(
            Icons.Filled.Check, "live", Palette.textSecondary,
            "Connected; strap history sync is experimental on this strap")
        // else: cold start — render nothing; the building-scores note covers it.
    }
}

/** The shared sync-chip capsule (icon + terse label). Twin of the iOS `SyncStatusChip.chip`. */
@Composable
private fun ChipCapsule(icon: ImageVector, text: String, tint: Color, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.surfaceInset)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = NoopType.caption, color = tint)
    }
}

/** Compact relative age for the header chip ("now" / "Nm" / "Nh" / "Nd") from a unix-SECONDS timestamp —
 *  deliberately terse. Twin of the iOS `SyncStatusChip.shortAgo`. */
private fun shortSyncAgo(unixSec: Long): String {
    val secs = (System.currentTimeMillis() / 1000L - unixSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "now"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}

/** The liquid header strap-battery ring: when connected + a reading exists it draws a trimmed ring in
 *  the charge/warning/critical hue plus the % inside, else a
 *  bolt-slash glyph. Tap → Devices. Mirrors the iOS liquid header battery ring. */
@Composable
private fun LiquidBatteryRing(batteryPct: Double?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label = batteryPct?.let { "Strap battery ${it.roundToInt()} percent" } ?: "Strap battery"
    Box(
        modifier = Modifier
            .size(34.dp)
            .liquidPress(interaction)
            .clip(CircleShape)
            // A raised themed disc + hairline rim so it reads on the plain themed canvas, in both light and dark.
            .background(Palette.surfaceRaised)
            .border(1.dp, Palette.hairline, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (batteryPct != null) {
            val pct = batteryPct.coerceIn(0.0, 100.0)
            val ringColor = when {
                pct < 15 -> Palette.statusCritical
                pct < 35 -> Palette.statusWarning
                else -> Palette.chargeColor
            }
            Canvas(modifier = Modifier.size(34.dp).padding(2.5.dp)) {
                val strokePx = 3.dp.toPx()
                val d = size.minDimension - strokePx
                val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                // Track.
                drawArc(
                    color = Palette.hairline,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(d, d),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                // Fill arc (min 2% so a near-flat battery still shows a cap), clockwise from 12 o'clock.
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = (360f * (pct / 100.0).coerceIn(0.02, 1.0)).toFloat(),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(d, d),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
            Text(
                "${pct.roundToInt()}",
                style = NoopType.number(9f, weight = FontWeight.Bold),
                color = Palette.textPrimary,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.BatteryUnknown,
                contentDescription = null,
                tint = Palette.textSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// MARK: - NOOP wordmark (iOS LiquidWordmark parity — centred, with a tap easter egg)
//
// The subtle "N O O P" wordmark that sits on the sky between the header and the hero. Built as a row of
// letters (not one tracked string, which adds a trailing gap after the last glyph and pushes the word
// off-centre), so it sits DEAD centre, white @ ~50% opacity. A tap plays one of several random one-shot
// animations — wiggle / shake / flip / spin / bounce / jelly squash. Mirrors iOS LiquidWordmark.

@Composable
internal fun LiquidWordmark() {
    val reduced = rememberReduceMotion()
    var rot by remember { mutableStateOf(0f) }        // z-rotation (wiggle / spin)
    var scaleX by remember { mutableStateOf(1f) }     // horizontal scale (jelly squash)
    var scaleY by remember { mutableStateOf(1f) }     // vertical scale (bounce / jelly)
    var dx by remember { mutableStateOf(0f) }         // horizontal offset (shake)
    var egg by remember { mutableIntStateOf(0) }      // which egg to play (drives the LaunchedEffect)

    val view = LocalView.current
    val animRot by animateFloatAsState(rot, tween(durationMillis = if (reduced) 0 else 520), label = "wordmark-rot")
    val animScaleX by animateFloatAsState(scaleX, tween(durationMillis = if (reduced) 0 else 380), label = "wordmark-sx")
    val animScaleY by animateFloatAsState(scaleY, tween(durationMillis = if (reduced) 0 else 380), label = "wordmark-sy")
    val animDx by animateFloatAsState(dx, tween(durationMillis = if (reduced) 0 else 420), label = "wordmark-dx")

    // On each tap, kick a value to an extreme then settle it back so the animateFloatAsState eases through
    // to rest — a natural wobble without hand-authored keyframes. Six variants, chosen at random per tap.
    LaunchedEffect(egg) {
        if (egg == 0) return@LaunchedEffect
        when ((0..5).random()) {
            0 -> { rot = -12f; kotlinx.coroutines.delay(90); rot = 0f }            // wiggle
            1 -> { dx = -12f; kotlinx.coroutines.delay(90); dx = 0f }              // shake
            2 -> { rot += 360f }                                                    // spin
            3 -> { scaleX = 1.28f; scaleY = 1.28f; kotlinx.coroutines.delay(90); scaleX = 1f; scaleY = 1f } // bounce
            4 -> { scaleX = 1.35f; scaleY = 0.7f; kotlinx.coroutines.delay(90); scaleX = 1f; scaleY = 1f }  // jelly
            else -> { rot += 360f }                                                 // flip (spin twin)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                egg += 1
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            .graphicsLayer {
                rotationZ = animRot
                this.scaleX = animScaleX
                this.scaleY = animScaleY
                translationX = animDx
            }
            .clearAndSetSemantics {}, // decorative wordmark — invisible to TalkBack
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        "NOOP".forEach { ch ->
            Text(
                ch.toString(),
                style = NoopType.number(16f, weight = FontWeight.Bold),
                color = Palette.textTertiary,
            )
        }
    }
}

