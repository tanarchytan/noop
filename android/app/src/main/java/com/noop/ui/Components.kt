package com.noop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Locked component system
//
// Every screen composes ONLY these. Fixed dimensions + one spacing scale guarantee
// the uniform, instrument-grade look from the reference.

// MARK: - Frosted card surface
//
// The card surface: a deep-navy fill, rounded corners, a diagonal accent wash and a flat
// 1px hairline border — no shadow. frostedCardSurface is the one place the look lives so
// NoopCard / ad-hoc surfaces all share it. Pass a domain tint (or null for neutral).

/**
 * Paint the frosted-card surface (navy fill + diagonal accent wash + hairline border)
 * behind content. [tint] colours the wash + border bias; null uses neutral gold wash.
 */
fun Modifier.frostedCardSurface(
    tint: Color? = null,
    cornerRadius: Dp = Metrics.cardRadius,
    washStrength: Float = 1f,
): Modifier = composed {
    this
        // Dark theme: flat. Light theme: white card raised off canvas with a soft drop shadow.
        .then(
            if (Palette.isLight)
                Modifier.shadow(elevation = 6.dp, shape = RoundedCornerShape(cornerRadius), clip = false)
            else Modifier
        )
        .drawBehind {
            val radiusPx = cornerRadius.toPx()
            val corner = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
            val fill = Palette.surfaceRaised
            val border = Palette.hairline

            if (tint == null) {
                // NEUTRAL card: flat raised surface with no accent wash or bias.
                drawRoundRect(color = fill, cornerRadius = corner)
                drawRoundRect(color = border, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
            } else {
                // TINTED card: raised surface with a diagonal domain-hue wash over the fill.
                drawRoundRect(color = fill, cornerRadius = corner)
                // Faint diagonal accent hue wash over the flat fill.
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to tint.copy(alpha = 0.05f * washStrength),
                            0.5f to tint.copy(alpha = 0.015f * washStrength),
                            1.0f to Color.Transparent,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    cornerRadius = corner,
                )
                // 3) Plain 1px hairline (no accent bias) — matches the neutral card.
                drawRoundRect(color = border, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
            }
        }
}

// MARK: - NoopCard — the one card surface

/** The one card surface: frosted, rounded, with optional domain tint. */
@Composable
fun NoopCard(
    modifier: Modifier = Modifier,
    padding: Dp = Metrics.cardPadding,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .frostedCardSurface(tint = tint, cornerRadius = Metrics.cardRadius)
            .padding(padding),
    ) {
        content()
    }
}

// MARK: - DataPendingNote — "what shows now vs what needs import" banner
//
// NoopCard with AutoGraph glyph, title and body. Used in empty/partial data screens.

/** Banner for empty/partial data state: AutoGraph icon + title + body. */
@Composable
fun DataPendingNote(title: String, body: String, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier, padding = 18.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.AutoGraph,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(body, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

// MARK: - SyncingHistoryNote — pulsing "history sync in progress" line
//
// Shown during strap historical offload so a half-loaded screen reads as in-progress.

/** Pulsing status row shown during strap history offload. */
@Composable
fun SyncingHistoryNote(chunks: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        StatePill("Syncing strap history…", tone = StrandTone.Accent, pulsing = true)
        if (chunks > 0) {
            Text(
                "$chunks chunks pulled",
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - Overline label (ALL-CAPS, semibold, +0.8 tracking, secondary)

/** ALL-CAPS semibold label in secondary color. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Palette.textSecondary) {
    Text(
        text = text.uppercase(),
        style = NoopType.overline,
        color = color,
        modifier = modifier,
    )
}

// MARK: - Section header

/** Screen section header with optional overline and trailing text. */
@Composable
fun SectionHeader(
    title: String,
    overline: String? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (overline != null) Overline(overline)
            Text(title, style = NoopType.title2, color = Palette.textPrimary)
        }
        if (trailing != null) {
            Text(trailing, style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

// MARK: - StrandTone

enum class StrandTone {
    Neutral, Accent, Positive, Warning, Critical;

    /** Resolved on every read, never captured: an enum entry is built once, so a stored colour would
     *  hold whichever scheme was active at class load and survive a theme flip. */
    val color: Color
        @Composable get() = when (this) {
            Neutral -> Palette.textSecondary
            Accent -> Palette.accent
            Positive -> Palette.statusPositive
            Warning -> Palette.statusWarning
            Critical -> Palette.statusCritical
        }
}

// MARK: - ConnectionDot — status dot with optional breathing pulse halo

/** Status dot with optional breathing pulse halo. */
@Composable
fun ConnectionDot(
    tone: StrandTone = StrandTone.Positive,
    pulsing: Boolean = false,
    size: Dp = 9.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // PERF: only spin up the breathing transition when the dot actually pulses.
        // A still dot composes none of the child below, so it does zero per-frame work.
        if (pulsing) {
            PulsingDotHalo(tone = tone, size = size)
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tone.color),
        )
    }
}

/** The breathing halo behind a LIVE [ConnectionDot]. Isolated so the infinite transition only exists while
 *  the dot is actually pulsing. */
@Composable
private fun PulsingDotHalo(tone: StrandTone, size: Dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val scale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.breathPeriodMs, easing = Motion.easeInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.breathPeriodMs, easing = Motion.easeInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotHalo",
    )
    val halo = tone.color
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                drawCircleScaled(halo, scale, haloAlpha)
            },
    )
}

private fun DrawScope.drawCircleScaled(
    color: Color,
    scale: Float,
    alpha: Float,
) {
    drawCircle(color = color, radius = (size.minDimension / 2f) * scale, alpha = alpha)
}

// MARK: - StatePill — rounded pill with optional leading dot + tinted label
//
// The status chip behind SOLID / BUILDING / CALIBRATING / LIVE. The tone owns the hue.
// Fill .12 / border .32 / text full-strength.

/**
 * Rounded status pill with optional leading dot. Used for SOLID/BUILDING/LIVE states.
 * [icon] replaces the dot when set; [fillsWidth] stretches it into a full-width banner row.
 */
@Composable
fun StatePill(
    title: String,
    tone: StrandTone = StrandTone.Neutral,
    showsDot: Boolean = true,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fillsWidth: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .then(if (fillsWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(tone.color.copy(alpha = 0.12f))
            .border(1.dp, tone.color.copy(alpha = 0.32f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.color, modifier = Modifier.size(Metrics.iconSmall))
        } else if (showsDot) {
            ConnectionDot(tone = tone, pulsing = pulsing, size = 7.dp)
        }
        Text(title, style = NoopType.overline.copy(letterSpacing = 0.4.sp), color = tone.color)
    }
}

// MARK: - SourceBadge

/** Compact source-origin pill (e.g. ON-DEVICE). */
@Composable
fun SourceBadge(text: String, tint: Color = Palette.accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            // Preserve the canonical compact height at the default font scale, but grow instead of clipping
            // when Android's font scaling makes the single-line label taller.
            .heightIn(min = Metrics.sourceBadgeHeight)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.30f), shape)
            .padding(horizontal = Metrics.space8),
        // Centre the label in the pill both ways — the height floor above can make the pill taller than the
        // single text line, which otherwise rides the top edge.
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = NoopType.overlineSmall,
            color = Palette.washLabel(tint),
            maxLines = 1,                          // "ON-DEVICE" stays on one line
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - TrendChip — tinted delta pill with a direction arrow.
//
// A compact trend pill: an up/down/flat arrow + the delta text, tinted to [color].
// Inferred direction comes from a leading +/− in the text (else flat).

/** Tinted delta pill with direction arrow. */
@Composable
fun TrendChip(text: String, color: Color = Palette.textTertiary, modifier: Modifier = Modifier) {
    val t = text.trim()
    val symbol = when {
        t.startsWith("+") || t.startsWith("▲") || t.lowercase().startsWith("up") -> "▲"
        t.startsWith("-") || t.startsWith("−") || t.startsWith("▼") || t.lowercase().startsWith("down") -> "▼"
        // No sign → plain magnitude, not a trend: show NO direction glyph.
        else -> null
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // The pill's fill is a wash of the same colour, which on the light scheme lands close enough
        // to it that the label falls under AA; [Palette.washLabel] darkens the label, never the fill.
        val label = Palette.washLabel(color)
        if (symbol != null) Text(symbol, style = NoopType.glyph, color = label)
        // Ellipsize rather than overflow if a caller constrains the chip's width.
        Text(text, style = NoopType.captionNumber, color = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - StatTile — uniform fixed-height metric tile

// MARK: - AutoSizeValue — shrinks to fit instead of truncating
//
// Steps the font down (to a 0.6x floor) until the text fits one line, then holds.
// Resets on the text LENGTH, not the text: a ring counting up swaps its digits every frame, and
// restarting the search on each swap left it at full size for the whole animation.
@Composable
internal fun AutoSizeValue(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
) {
    var scale by remember(text.length, style) { mutableStateOf(1f) }
    Text(
        text = text,
        color = color,
        style = style,
        fontSize = style.fontSize * scale,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { result ->
            // hasVisualOverflow, not didOverflowWidth: a one-line non-wrapping paragraph is laid out
            // AT its constraint, so didOverflowWidth stays false and the step-down never fired.
            if (result.hasVisualOverflow && scale > minScale) {
                scale = maxOf(minScale, scale - 0.08f)
            }
        },
    )
}

/** Fixed-height metric tile: label, auto-sizing value, optional caption and delta. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color = Palette.textPrimary,
    delta: String? = null,
    deltaColor: Color = Palette.textTertiary,
    tint: Color? = null,
    // When true, the trailing delta chip yields width to the value instead of taking its full
    // intrinsic size. Used by the workout tiles when space is tight.
    compactDelta: Boolean = false,
) {
    // Each tile borrows its accent as a faint card wash, so a metric reads as part of its
    // colour world while staying legible on the deep blue-black. Falls back to the accent.
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp, tint = tint ?: accent) {
        Column {
            Overline(label)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Value shrinks to fit (down to 0.6x) rather than truncating.
                // The chip keeps its intrinsic size at the end.
                AutoSizeValue(
                    value,
                    style = NoopType.number(26f),
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                if (delta != null) {
                    Spacer(Modifier.width(8.dp))
                    // Compact mode: chip fills available width without growing past its content.
                    TrendChip(
                        text = delta,
                        color = deltaColor,
                        modifier = if (compactDelta) Modifier.weight(1f, fill = false) else Modifier,
                    )
                }
            }
            if (caption != null) {
                Text(
                    caption, style = NoopType.footnote, color = Palette.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// MARK: - InsightCard

/** Domain insight card: category overline, status, detail body. */
@Composable
fun InsightCard(
    category: String,
    status: String,
    detail: String,
    modifier: Modifier = Modifier,
    statusColor: Color = Palette.accent,
    tint: Color? = null,
) {
    NoopCard(modifier = modifier, padding = 18.dp, tint = tint ?: statusColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Overline(category)
            Text(status, style = NoopType.title1, color = statusColor)
            Text(detail, style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

// MARK: - SegmentedPillControl — the ONE segmented control

/** The track's one-row height. */
private val SEGMENTED_TRACK_HEIGHT = 36.dp

/** The inset between the track edge and a segment. */
private val SEGMENTED_TRACK_INSET = 4.dp

/**
 * Corner radius fixed at half the one-row height: a pill while the segments fit one row, and a rounded
 * rect once they wrap. A percentage radius would keep curving through every row, so a two-row track read
 * as one lens with the wrapped segment inside the bend.
 */
private val SEGMENTED_CORNER = SEGMENTED_TRACK_HEIGHT / 2

@Composable
fun <T> SegmentedPillControl(
    items: List<T>,
    selection: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Disabled segment stays visible (dimmed, not clickable) so it can teach that an option exists
    // before it is usable.
    enabled: (T) -> Boolean = { true },
) {
    val outerShape = RoundedCornerShape(SEGMENTED_CORNER)
    // The track holds one row of segments and grows to a second when the labels do not fit, so a set of
    // long words wraps instead of running past the track and being cut square on one edge.
    FlowRow(
        modifier = modifier
            .heightIn(min = SEGMENTED_TRACK_HEIGHT)
            .clip(outerShape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, outerShape)
            .padding(SEGMENTED_TRACK_INSET),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        items.forEach { item ->
            val selected = item == selection
            val itemEnabled = enabled(item)
            // Selected segment is SELECTION CHROME → follows the accent: a gold gradient + gold-deep ink
            // on dark; a flat blue accent + white ink on light (so light selection matches the blue
            // chrome, not gold). Unselected stays clear with tertiary text; disabled dims further.
            val pillShape = RoundedCornerShape(50)
            val pillBg = if (selected) {
                if (Palette.isLight) Modifier.background(Palette.accent, pillShape)
                else Modifier.background(Brush.linearGradient(*Palette.goldGradient.toTypedArray()), pillShape)
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    // A fixed segment height, so the pill's inset is equal top/bottom/left/right on the
                    // one row and stays equal on a second.
                    .height(SEGMENTED_TRACK_HEIGHT - SEGMENTED_TRACK_INSET * 2)
                    .clip(pillShape)
                    .then(pillBg)
                    .then(if (itemEnabled) Modifier.clickableNoRipple { onSelect(item) } else Modifier)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(item),
                    style = NoopType.captionNumber,
                    color = when {
                        selected && Palette.isLight -> androidx.compose.ui.graphics.Color.White
                        selected -> Palette.goldDeepText
                        !itemEnabled -> Palette.textTertiary.copy(alpha = 0.45f)
                        else -> Palette.textTertiary
                    },
                )
            }
        }
    }
}

// MARK: - NoopSlider — the one slider shape

/** The thumb's diameter, sized to sit on the track rather than beside it. */
private val SLIDER_THUMB_DIAMETER = 20.dp

/**
 * The app's slider. Material's own draws a narrow upright bar set off the track and a stop dot at the
 * far end, which read as a text caret and a stray pixel; this puts a round thumb on the line and drops
 * the dot. Same value contract as [Slider].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoopSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = Palette.accent,
        activeTrackColor = Palette.accent,
        inactiveTrackColor = Palette.surfaceInset,
        activeTickColor = Palette.surfaceInset,
        inactiveTickColor = Palette.hairlineStrong,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(SLIDER_THUMB_DIAMETER)
                    .clip(CircleShape)
                    .background(Palette.accent),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = {},
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

// MARK: - BevelGauge — the ring gauge primitive
//
// One open gauge: a full-span track, the progress arc on the same circle, a centred number.
// Domain-agnostic — RecoveryRing / StrainGauge delegate here.

/** The gauge's geometry, fixed. One start and one span, so an arc's LENGTH means the same thing on
 *  every screen that draws one; the 120° gap is centred at the bottom. */
private const val GAUGE_START_DEG = 150f
private const val GAUGE_SPAN_DEG = 240f

/** A ring track's tint: the text colour at low alpha. A ring is drawn on cards of several fills, and
 *  an inset surface token sits DARKER than the card on the light scheme. */
internal const val RING_TRACK_ALPHA = 0.10f

/** Open gauge primitive: track ring, gradient progress arc, centred number. Domain-agnostic. */
@Composable
fun BevelGauge(
    fraction: Double,
    stops: List<Pair<Float, Color>>,
    tipColor: Color,
    numberText: String,
    modifier: Modifier = Modifier,
    captionText: String? = null,
    stateText: String? = null,
    supporting: String? = null,
    diameter: Dp = 200.dp,
    lineWidth: Dp = 16.dp,
    showsLabel: Boolean = true,
    coreDot: Color? = null,      // RecoveryRing brand glyph: a solid core dot at the centre
    wordmark: String? = null,    // RecoveryRing brand glyph: micro ALL-CAPS mark above the number
) {
    val frac = fraction.toFloat().coerceIn(0f, 1f)

    val animatedFraction by animateFloatAsState(
        targetValue = frac,
        animationSpec = tween(Motion.durationSlow, easing = Motion.drawIn),
        label = "ringFill",
    )
    // A sweep gradient always runs 3 o'clock round to 3 o'clock, so its wrap is a hard seam. The stops
    // are laid over the arc's own span and the canvas turned so that seam sits in the middle of the gap,
    // where nothing is drawn; the clamp either side of the span then colours the two round caps.
    val halfGap = (360f - GAUGE_SPAN_DEG) / 2f
    val sweep = Brush.sweepGradient(
        *stops.map { (at, color) -> (halfGap + at * GAUGE_SPAN_DEG) / 360f to color }.toTypedArray(),
    )
    val trackColor = Palette.textPrimary.copy(alpha = RING_TRACK_ALPHA)

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                // Track and fill on ONE circle at ONE stroke. Reads animatedFraction, so it re-issues per frame.
                .drawBehind {
                    val stroke = lineWidth.toPx()
                    val radius = (min(size.width, size.height) - stroke) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val sweepStroke = Stroke(width = stroke, cap = StrokeCap.Round)

                    drawArc(
                        color = trackColor,
                        startAngle = GAUGE_START_DEG,
                        sweepAngle = GAUGE_SPAN_DEG,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = sweepStroke,
                    )

                    // The canvas turns by the gradient's own offset, so the fill still begins at
                    // GAUGE_START_DEG and the first stop lands exactly on its start cap.
                    if (animatedFraction > 0.001f) {
                        rotate(degrees = GAUGE_START_DEG - halfGap, pivot = center) {
                            drawArc(
                                brush = sweep,
                                startAngle = halfGap,
                                sweepAngle = GAUGE_SPAN_DEG * animatedFraction,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = sweepStroke,
                            )
                        }
                    }

                    // Brand glyph core: a dot at the centre, suppressed when a number is shown
                    // (it would muddy the digits).
                    if (coreDot != null && !showsLabel) {
                        drawCircle(color = coreDot, radius = stroke * 0.40f, center = center)
                    }
                },
        )

        if (showsLabel) {
            // Big bold number ≈ diameter * 0.30.
            val numberSp = diameter.value * 0.30f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Micro ALL-CAPS NOOP wordmark above the number (RecoveryRing brand glyph).
                if (wordmark != null) {
                    Text(
                        text = wordmark.uppercase(),
                        style = NoopType.overline.copy(
                            fontSize = (numberSp * 0.16f).sp,
                            letterSpacing = (numberSp * 0.055f).sp,  // ≈ .34em wordmark tracking
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Palette.gold,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
                Text(
                    text = numberText,
                    style = NoopType.display(numberSp).copy(fontWeight = FontWeight.Bold),
                    color = Palette.textPrimary,
                )
                if (captionText != null) {
                    // Caption scales with the gauge.
                    Text(
                        text = captionText,
                        style = NoopType.footnote.copy(
                            fontSize = (diameter.value * 0.085f).sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = Palette.textTertiary,
                    )
                }
                if (stateText != null) {
                    // State word scales with the gauge so it never overflows the small three-up rings:
                    // stateSize = min(11, diameter*0.085), with the overline tracking scaled to match.
                    val stateSize = minOf(11f, diameter.value * 0.085f)
                    Text(
                        text = stateText,
                        style = NoopType.overline.copy(
                            fontSize = stateSize.sp,
                            letterSpacing = (NoopType.overlineTracking * stateSize / 11f).sp,
                        ),
                        color = tipColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// MARK: - RecoveryRing — Charge / Rest hero component
//
// Delegates to BevelGauge with brand glyph (core dot + NOOP wordmark).

// MARK: - GlowRing — crisp score ring
//
// Solid arc over full-circle track with centred number. Arc springs from 12 o'clock.

/** A ring's stroke as a fraction of its diameter — the one width every screen draws a ring at. */
internal const val RING_STROKE_FRACTION = 0.10f

/** The centre number's size as a fraction of the ring diameter. */
private const val GLOW_RING_NUMBER_FRACTION = 0.36f

/** The unit mark's size, as a fraction of the number beside it. */
private const val GLOW_RING_UNIT_FRACTION = 0.42f

/** The caption's size as a fraction of the ring diameter. */
private const val GLOW_RING_CAPTION_FRACTION = 0.10f

/** Centre-number text style at `diameter * 0.36`. Bold numeral. */
fun glowRingCenterTextStyle(diameter: Dp, color: Color = Palette.textPrimary): TextStyle =
    TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (diameter.value * GLOW_RING_NUMBER_FRACTION).sp,
        color = color,
    )

/** The arc's settle: firm, no bounce. */
private val RING_FILL_SPEC = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/** The centre number's count-up, paced to land with the arc. */
private val RING_VALUE_SPEC = tween<Float>(durationMillis = 850, easing = FastOutSlowInEasing)

/**
 * Crisp score ring: solid arc over full-circle track with centred number. [fillKey] names this ring in
 * [LocalRingFillMemory], the hoisted frame that keeps a scroll from replaying the fill.
 */
@Composable
fun GlowRing(
    fraction: Float,
    value: Double,
    color: Color,
    diameter: Dp,
    lineWidth: Dp,
    fillKey: String,
    modifier: Modifier = Modifier,
    showsLabel: Boolean = true,
    format: (Double) -> String = { it.roundToInt().toString() },
    unit: String? = null,
    caption: String? = null,
) {
    val target = fraction.coerceIn(0f, 1f)
    val targetValue = value.toFloat()
    // The fill starts where this ring last stood, never at zero, so a ring the scroll destroyed and
    // rebuilt comes back filled and only a genuinely new number animates — from the old one.
    val memory = LocalRingFillMemory.current
    val fracAnim = remember(fillKey) { Animatable(memory.startFor(fillKey).fraction) }
    val valueAnim = remember(fillKey) { Animatable(memory.startFor(fillKey).value) }
    LaunchedEffect(fillKey, target) {
        fracAnim.animateTo(target, RING_FILL_SPEC)
        memory.record(fillKey, fracAnim.value, valueAnim.value)
    }
    LaunchedEffect(fillKey, targetValue) {
        valueAnim.animateTo(targetValue, RING_VALUE_SPEC)
        memory.record(fillKey, fracAnim.value, valueAnim.value)
    }
    // Leaving mid-fill banks the frame it reached, so coming back resumes instead of restarting.
    DisposableEffect(fillKey) {
        onDispose { memory.record(fillKey, fracAnim.value, valueAnim.value) }
    }
    val animFraction = fracAnim.value
    val animValue = valueAnim.value
    val trackColor = Palette.textPrimary.copy(alpha = RING_TRACK_ALPHA)
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // PERF: hoist the static full-circle track into drawWithCache so it rasterises once
                // and replays; only the glow + crisp arc re-draw per frame.
                .drawWithCache {
                    val stroke = lineWidth.toPx()
                    val inset = stroke / 2f
                    val d = minOf(size.width, size.height)
                    val arcSize = Size(d - stroke, d - stroke)
                    val tl = Offset((size.width - d) / 2f + inset, (size.height - d) / 2f + inset)
                    val trackStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                    onDrawBehind {
                        // Full-circle track so the arc reads as a fraction of a circle (like WHOOP).
                        drawArc(
                            color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            topLeft = tl, size = arcSize, style = trackStroke,
                        )
                    }
                }
                .drawBehind {
                    val stroke = lineWidth.toPx()
                    val inset = stroke / 2f
                    // Always draw a CIRCLE: size the arc off the smaller box dimension and centre it, so a
                    // non-square box never renders an ellipse.
                    val d = minOf(size.width, size.height)
                    val arcSize = Size(d - stroke, d - stroke)
                    val tl = Offset((size.width - d) / 2f + inset, (size.height - d) / 2f + inset)
                    val sweep = animFraction.coerceIn(0f, 1f) * 360f
                    // Only draw the arc (+ its glow) when there's actual progress. A near-zero round-capped
                    // arc renders as a visible dot at 12 o'clock on Android's Canvas.
                    if (animFraction > 0.001f) {
                        // One crisp, solid arc — from 12 o'clock clockwise. No wider halo under it: at 1.5x
                        // the stroke it read as an outline hanging outside the track, and at a full ring its
                        // two round caps met in a notch.

                        drawArc(
                            color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                            topLeft = tl, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                },
        )
        if (showsLabel) {
            // Number, a smaller trailing unit mark, and a caption beneath. Both extra slots scale off the
            // diameter like the number does, so one ring component reads at every size it is drawn at.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Held to the inner circle and shrunk to fit inside it: at the shared 0.36 number size a
                // string longer than a two-digit score is wider than the track and crosses the stroke.
                Row(
                    modifier = Modifier.width(diameter - lineWidth * 2),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AutoSizeValue(
                        text = format(animValue.toDouble()),
                        style = glowRingCenterTextStyle(diameter),
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unit != null) {
                        Text(
                            text = unit,
                            style = glowRingCenterTextStyle(diameter).copy(
                                fontSize = (
                                    diameter.value * GLOW_RING_NUMBER_FRACTION * GLOW_RING_UNIT_FRACTION
                                    ).sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        style = NoopType.footnote.copy(
                            fontSize = (diameter.value * GLOW_RING_CAPTION_FRACTION).sp,
                        ),
                        color = Palette.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Charge/Rest hero gauge. Delegates to BevelGauge with brand glyph. */
@Composable
fun RecoveryRing(
    score: Double,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    diameter: Dp = 240.dp,
    lineWidth: Dp = 16.dp,
    showsLabel: Boolean = true,
    valueFormat: ((Double) -> String)? = null,
) {
    // ONE encoding per value: the arc wears the single colour the ramp maps this score to — the same
    // colour Home's ring, the Charge tile and the weekly bars give it. Sweeping the whole ramp under
    // the fill put a large red segment under every score labelled MODERATE.
    val tint = Palette.recoveryColor(score)
    BevelGauge(
        fraction = score / 100.0,
        stops = listOf(0f to tint, 1f to tint),
        tipColor = tint,
        // ROUND, never truncate: the home hero ring rounds the same value, so truncating here showed
        // one Charge as 62 on Home and 61 on this screen for a stored 61.8.
        numberText = valueFormat?.invoke(score) ?: score.roundToInt().toString(),
        stateText = Palette.recoveryState(score),
        supporting = supporting,
        diameter = diameter,
        lineWidth = lineWidth,
        showsLabel = showsLabel,
        // Brand glyph: gold core dot + NOOP wordmark. The geometry is the gauge's, never its own.
        coreDot = Palette.gold,
        wordmark = "NOOP",
        modifier = modifier,
    )
}

// MARK: - StrainGauge — Effort hero gauge
//
// BevelGauge over the amber strain ramp. Caller owns scale conversion.

/** Effort hero gauge over the amber strain ramp. */
@Composable
fun StrainGauge(
    strain: Double,
    modifier: Modifier = Modifier,
    outOf: Double = 21.0,
    valueText: String? = null,
    diameter: Dp = 240.dp,
    lineWidth: Dp = 16.dp,
    showsLabel: Boolean = true,
) {
    val clamped = strain.coerceIn(0.0, outOf)
    val fraction = if (outOf > 0) clamped / outOf else 0.0
    // Sampled by the fill FRACTION so it spans the full ember→amber ramp identically on the 0–100 and
    // 0–21 display scales, then worn by the whole arc — its sibling Recovery gauge encodes one value
    // the same way.
    val tint = Palette.effortTint(fraction)
    BevelGauge(
        fraction = fraction,
        stops = listOf(0f to tint, 1f to tint),
        tipColor = tint,
        numberText = valueText
            ?: if (clamped % 1.0 == 0.0) clamped.toInt().toString() else String.format(java.util.Locale.US, "%.1f", clamped),
        captionText = "of ${outOf.toInt()}",
        diameter = diameter,
        lineWidth = lineWidth,
        showsLabel = showsLabel,
        modifier = modifier,
    )
}

// MARK: - ScenicHeroBackground — premium hero backdrop
//
// Canvas-drawn radial gradient with deterministic starfield and optional domain tint.

/** Radial gradient hero backdrop with deterministic starfield and optional domain tint. */
@Composable
fun ScenicHeroBackground(
    modifier: Modifier = Modifier,
    domain: DomainTheme? = null,
    starCount: Int = 40,
    fadesToBase: Boolean = true,
) {
    Box(modifier = modifier.semantics { contentDescription = "" }) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Radial deep blue-black: lit center → near-black edge.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Palette.scenicCenter, Palette.scenicEdge),
                    center = Offset(w * 0.5f, h * 0.36f),
                    radius = maxOf(w, h) * 0.95f,
                ),
            )

            // A soft domain-tinted bloom near the top, if a world is named.
            if (domain != null) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(domain.glow.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.30f),
                        radius = maxOf(w, h) * 0.6f,
                    ),
                )
            }

            // Deterministic starfield — fixed positions/sizes so it can't flicker. Only on dark theme;
            // on light theme the radial + domain bloom carry the hero alone.
            if (!Palette.isLight) {
                val wi = maxOf(1, w.toInt())
                val topBand = maxOf(1, (h * 0.55f).toInt())
                for (i in 0 until starCount) {
                    val x = ((i * 73 + 31) % wi).toFloat()
                    val y = (18 + ((i * 41) % topBand)).toFloat()
                    val r = if (i % 9 == 0) 1.3f else 0.7f
                    val alpha = if (i % 5 == 0) 0.34f else 0.18f
                    drawCircle(
                        color = Palette.scenicStar.copy(alpha = alpha),
                        radius = r,
                        center = Offset(x, y),
                    )
                }
            }

            // Bottom fade so a hero number / card reads cleanly over the field.
            if (fadesToBase) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Palette.scenicEdge.copy(alpha = 0.72f),
                            Palette.scenicEdge,
                        ),
                        startY = h * 0.5f,
                        endY = h,
                    ),
                )
            }
        }
    }
}

// MARK: - ScreenScaffold
//
// Scrollable screen container: title + optional subtitle header, content column with screen padding.

/** Scrollable screen container: header + content column with screen padding. */
@Composable
fun ScreenScaffold(
    title: String?,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    // Top inset above the content. Defaults to 28dp; a screen with its own compact header can
    // pass a smaller value to tighten the gap above its first element.
    topPadding: Dp = 28.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Optional full-bleed view drawn behind the scroll content at the top of the screen.
    // The scene is a screen-level backdrop the cards float over.
    topBackground: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Column outer modifier differs by path: opaque canvas vs transparent for scene-backed.
    val columnModifier: Modifier =
        if (topBackground == null) {
            modifier.fillMaxWidth().background(Palette.surfaceBase)
        } else {
            Modifier.fillMaxWidth()
        }
    val column: @Composable () -> Unit = {
        Column(
            modifier = columnModifier
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = topPadding, bottom = 28.dp),
            // One shared inter-card spacing token, so all scaffolds keep the same gap between top-level cards.
            verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing),
        ) {
            // Compact top bar: optional leading action, screen title/subtitle, optional trailing action.
            // When both title and subtitle are null the header block is omitted entirely.
            if (title != null || subtitle != null) {
                Row(verticalAlignment = Alignment.Top) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
                    ) {
                        if (title != null) {
                            Text(title, style = NoopType.title1, color = Palette.textPrimary)
                        }
                        if (subtitle != null) {
                            Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
                        }
                    }
                    if (trailing != null) trailing()
                }
            }
            content()
        }
    }

    if (topBackground == null) {
        // No scene path: the column is the root, with the caller's modifier and opaque canvas background.
        column()
    } else {
        // Scene-backed path: canvas + top-anchored backdrop bled behind status bar, transparent scroll content.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(modifier = modifier.fillMaxSize().background(Palette.surfaceBase)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .offset(y = -statusBarTop)
                    // PERF: promote static scene backdrop to compositing layer (rasterise once).
                    .graphicsLayer { },
            ) {
                topBackground()
            }
            column()
        }
    }
}

// MARK: - LazyScreenScaffold
//
// LazyColumn twin of ScreenScaffold for long lists. Content slot is [LazyListScope].

/** LazyColumn twin of ScreenScaffold for long lists. */
@Composable
fun LazyScreenScaffold(
    title: String?,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    // Top inset above content. Defaults to 28dp.
    topPadding: Dp = 28.dp,
    // Inter-row vertical spacing between top-level items. Defaults to screenRowSpacing; liquid Today
    // passes a tighter value for a more compact rhythm.
    rowSpacing: Dp = Metrics.screenRowSpacing,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Optional full-bleed scene behind scroll content. Null draws nothing.
    topBackground: (@Composable () -> Unit)? = null,
    // When true, the [topBackground] fills the whole scaffold viewport instead of the top band.
    fullBleedBackground: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    // Header row: optional leading/trailing actions + title/subtitle. Omitted when all are null.
    val header: (@Composable () -> Unit)? =
        if (title != null || subtitle != null || leading != null || trailing != null) {
            {
                Row(verticalAlignment = Alignment.Top) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
                    ) {
                        if (title != null) {
                            Text(title, style = NoopType.title1, color = Palette.textPrimary)
                        }
                        if (subtitle != null) {
                            Text(subtitle, style = NoopType.subhead, color = Palette.textSecondary)
                        }
                    }
                    if (trailing != null) trailing()
                }
            }
        } else {
            null
        }

    // Lazy list background + padding differ by path (transparent for scene-backed).
    val listModifier: Modifier =
        if (topBackground == null) {
            modifier.fillMaxWidth().background(Palette.surfaceBase)
        } else {
            Modifier.fillMaxWidth()
        }
    val list: @Composable () -> Unit = {
        LazyColumn(
            modifier = listModifier,
            contentPadding = PaddingValues(start = 28.dp, top = topPadding, end = 28.dp, bottom = 28.dp),
            // Shared inter-card spacing token by default; caller may pass a tighter rowSpacing.
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            if (header != null) {
                item { header() }
            }
            content()
        }
    }

    if (topBackground == null) {
        list()
    } else {
        // Scene-backed path: wrapping Box paints canvas + scene backdrop, transparent LazyColumn
        // floats over both. Same treatment as ScreenScaffold.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(modifier = modifier.fillMaxSize().background(Palette.surfaceBase)) {
            Box(
                modifier = (
                    if (fullBleedBackground) {
                        // Sky-behind-cards: backdrop fills the whole viewport, rows scroll over it.
                        Modifier.fillMaxSize()
                    } else {
                        // Default: a top-anchored band bled up behind the status bar.
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).offset(y = -statusBarTop)
                    }
                    ).graphicsLayer { },
            ) {
                topBackground()
            }
            list()
        }
    }
}

// MARK: - Stepper field — tabular value + round −/+ buttons
//
// The canonical profile editor used by both Settings and onboarding.

/** Tabular value with round -/+ buttons for profile editing. */
@Composable
fun StepperField(
    value: String,
    accessibility: String,
    unit: String? = null,
    valueColor: Color = Palette.textPrimary,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        modifier = Modifier.semantics { contentDescription = accessibility },
    ) {
        Text(
            value,
            style = NoopType.bodyNumber,
            color = valueColor,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        if (unit != null) {
            Text(unit, style = NoopType.caption, color = Palette.textTertiary)
        }
        StepperButton(symbol = "−", onClick = onMinus, label = "Decrease $accessibility")
        StepperButton(symbol = "+", onClick = onPlus, label = "Increase $accessibility")
    }
}

/** Round −/+ button used inside [StepperField]. */
@Composable
fun StepperButton(symbol: String, onClick: () -> Unit, label: String) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = NoopType.body.copy(fontWeight = FontWeight.SemiBold), color = Palette.textPrimary)
    }
}

// MARK: - clickable without ripple, for pill segments

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick,
    )

// MARK: - Shared reusable components (deduplicated from Settings/Automations/TestCentre)

/** A settings section card — the one shared wrapper for every settings-group card across the app. */
@Composable
fun NoopSettingsSection(
    icon: ImageVector,
    title: String,
    blurb: String,
    overline: String = "",
    active: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            if (overline.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                ) {
                    Overline(overline)
                    if (active) Overline("ON", color = Palette.accent)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) Palette.accent else Palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = NoopType.title2, color = Palette.textPrimary)
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

/** A labelled toggle row — title + optional detail + Switch. The single source for every settings
 *  toggle across the app. */
@Composable
fun NoopToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary)
            if (detail != null) {
                Text(detail, style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

/** The one confirm/cancel dialog used across the app. */
@Composable
fun NoopConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = { Text(title, style = NoopType.title2, color = Palette.textPrimary) },
        text = { Text(text, style = NoopType.subhead, color = Palette.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = NoopType.body,
                    color = if (destructive) Palette.statusCritical else Palette.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

/** The one close [IconButton] used across header rows in the app. */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier.size(36.dp)) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textSecondary)
    }
}

/** Hairline divider row used inside settings/automation cards. */
@Composable
fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 4.dp)
            .background(Palette.hairline),
    )
}

// MARK: - MetricRow — icon, label, right-aligned value, direction chip, comparison
//
// The densest reusable line: outline icon + [Overline] label left, bold value with a [TrendChip]
// direction glyph right, and the comparison figure beneath it.

/**
 * One metric line. [trend] must carry its own sign ("+1,240" / "−12%") — [TrendChip] derives the
 * ▲/▼ from that sign — and [trendColor] carries better/worse, which the caller decides. A null
 * [value] input has no honest rendering here, so pass the caller's own placeholder string.
 */
@Composable
fun MetricRow(
    icon: ImageVector?,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trend: String? = null,
    trendColor: Color = Palette.textTertiary,
    comparison: String? = null,
    iconTint: Color = Palette.textSecondary,
    valueColor: Color = Palette.textPrimary,
    onClick: (() -> Unit)? = null,
) {
    val spoken = listOfNotNull(label, value, trend, comparison).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Metrics.iconSmall))
        }
        Overline(label, modifier = Modifier.weight(1f), color = Palette.textPrimary)
        // Both halves are weighted, so a long value takes at most half the row and the label keeps
        // enough width to wrap on a word rather than mid-word. `fill = false` lets a short value shrink.
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Text(
                    value,
                    style = NoopType.number(20f, FontWeight.Bold),
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trend != null) TrendChip(text = trend, color = trendColor)
            }
            if (comparison != null) {
                Text(
                    comparison,
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - NoopCardHeader — UPPERCASE title with a trailing chevron when tappable

/** A card's title row: [Overline] title, an optional [trailing] slot, and a chevron when [onClick] is set. */
@Composable
fun NoopCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    openLabel: String = "Open $title",
    color: Color = Palette.textPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Overline(title, modifier = Modifier.weight(1f), color = color)
        if (trailing != null) trailing()
        if (onClick != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

// MARK: - DayPagerBar — ‹ label › one-step-at-a-time pager
//
// The prev/next stepper around a centred day label. Chevrons dim at the ends of the range.

/** Prev/next day pager: chevrons around [label], with an optional [overline] caption beneath it. */
@Composable
fun DayPagerBar(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true,
    overline: String? = null,
    previousLabel: String = "Previous day",
    nextLabel: String = "Next day",
    onLabelClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoPrevious, modifier = Modifier.size(Metrics.iconButton)) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = previousLabel,
                tint = if (canGoPrevious) Palette.accent else Palette.textTertiary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Metrics.cornerSm))
                .then(
                    if (onLabelClick != null) {
                        Modifier.clickable(onClickLabel = "Pick a day", onClick = onLabelClick)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = Metrics.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
        ) {
            Text(
                label,
                style = NoopType.headline,
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (overline != null) Overline(overline, color = Palette.textSecondary)
        }
        IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(Metrics.iconButton)) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = nextLabel,
                tint = if (canGoNext) Palette.accent else Palette.textTertiary,
            )
        }
    }
}

// MARK: - GalleryCard — photo-backed tile for a 2-up row
//
// Full-bleed image with a bottom scrim, a small icon overlay and a 2-line UPPERCASE title.
// With no [image] it falls back to the flat [frostedCardSurface] every other card uses.

/** A 2-up gallery tile: optional [image] behind an [icon], a 2-line UPPERCASE [title] and [subtitle]. */
@Composable
fun GalleryCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    image: Painter? = null,
    tint: Color = Palette.accent,
    height: Dp = Metrics.tileHeight + Metrics.sectionGap,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val spoken = listOfNotNull(title, subtitle).joinToString(", ")
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .then(if (image == null) Modifier.frostedCardSurface(tint = tint) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        if (image != null) {
            Image(
                painter = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Bottom scrim so the title stays legible over any photo.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Palette.scenicEdge.copy(alpha = 0.85f)),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(Metrics.cardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (image != null) Palette.textPrimary else tint,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
            } else {
                Spacer(Modifier.height(Metrics.space2))
            }
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(
                    title.uppercase(),
                    style = NoopType.overline,
                    color = Palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// MARK: - NoopStatRow — icon badge, label, bold value + small unit
//
// The notable-stats list row. Pair consecutive rows with [RowDivider].

/** One stat line: tinted [icon] badge, [label] left, bold [value] with a small [unit] right. */
@Composable
fun NoopStatRow(
    icon: ImageVector?,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    iconTint: Color = Palette.accent,
    valueColor: Color = Palette.textPrimary,
    onClick: (() -> Unit)? = null,
) {
    val spoken = listOfNotNull(label, value, unit).joinToString(" ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(vertical = Metrics.space8)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        if (icon != null) StatIconBadge(icon = icon, tint = iconTint)
        Text(
            label,
            style = NoopType.subhead,
            color = Palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        ) {
            Text(
                value,
                style = NoopType.number(18f, FontWeight.Bold),
                color = valueColor,
                maxLines = 1,
            )
            if (unit != null) {
                Text(unit, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        }
    }
}

/** The rounded tinted square behind a [NoopStatRow] icon. */
@Composable
private fun StatIconBadge(icon: ImageVector, tint: Color) {
    val shape = RoundedCornerShape(Metrics.cornerSm)
    Box(
        modifier = Modifier
            .size(Metrics.iconButton)
            .clip(shape)
            .background(tint.copy(alpha = 0.13f))
            .border(Metrics.divider, tint.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
    }
}
