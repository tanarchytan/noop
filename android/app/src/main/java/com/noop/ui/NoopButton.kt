package com.noop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

// MARK: - NoopButton — the unified button system
//
// One button, four kinds, no glow. Beauty comes from a crisp filled action colour, honest surface
// fills, restrained spacing and a subtle press — never neon, bloom or a halo. Labels are
// sentence-case (never ALL CAPS), single line, with the optional leading icon as one unit, and
// degrade gracefully under Reduce Motion (the press scale drops; only the dim remains).
//
// The fill is [Palette.accent], the scheme's own tone, so a primary action reads as part of the theme
// rather than as a third brand colour beside it.

/** The four button roles. Colour + emphasis differ; geometry is identical across all four. */
enum class NoopButtonKind {
    /** Filled scheme accent, white label — the one primary action on a screen. */
    Primary,
    /** Raised-surface fill, primary-text label, hairline edge — secondary actions. */
    Secondary,
    /** No fill, accent label — low-emphasis / inline actions. */
    Tertiary,
    /** Filled critical (red), white label — destructive / irreversible actions. */
    Destructive,
}

// MARK: - Shared geometry (single source of truth for button shape)

private object NoopButtonMetrics {
    /** Standard control height (48). */
    val height = 48.dp
    /** Corner radius (14) — softer than a card, not a pill. */
    val cornerRadius = 14.dp
    /** Horizontal label inset. */
    val hPadding = 18.dp
    /** Spacing between a leading icon and the label. */
    val iconSpacing = 8.dp
    /** Apple's minimum touch target. */
    val minHitTarget = 44.dp
    /** Pressed scale (subtle 0.97). Reduce Motion collapses this to 1 (dim only). */
    const val pressedScale = 0.97f
    /** Pressed dim — a slight opacity drop, applied in BOTH motion modes. */
    const val pressedOpacity = 0.82f
    /** Disabled dim — applied to a tertiary/unfilled button, which has no fill to swap. */
    const val disabledOpacity = 0.4f
}

/** Resolves a [NoopButtonKind] to its concrete fill / label / border tokens. */
private data class NoopButtonAppearance(
    val fill: Color?,     // null = no fill (tertiary)
    val label: Color,
    val border: Color?,   // null = no hairline edge
)

@Composable
private fun appearanceFor(kind: NoopButtonKind, enabled: Boolean): NoopButtonAppearance {
    // Disabled swaps the tokens rather than dimming the whole button: one alpha over a saturated fill
    // and its white label costs the label far more contrast than the fill, which is what left "Sync now"
    // and "Back up now" looking pressable with an unreadable word on them.
    if (!enabled && kind != NoopButtonKind.Tertiary) {
        return NoopButtonAppearance(
            fill = Palette.surfaceInset, label = Palette.textTertiary, border = Palette.hairline,
        )
    }
    return when (kind) {
        NoopButtonKind.Primary -> NoopButtonAppearance(
            fill = Palette.accent, label = Palette.onFill, border = null,
        )
        NoopButtonKind.Secondary -> NoopButtonAppearance(
            fill = Palette.surfaceRaised, label = Palette.textPrimary, border = Palette.hairline,
        )
        NoopButtonKind.Tertiary -> NoopButtonAppearance(
            fill = null, label = Palette.accent, border = null,
        )
        NoopButtonKind.Destructive -> NoopButtonAppearance(
            fill = Palette.statusCritical, label = Palette.onFill, border = null,
        )
    }
}

// MARK: - NoopButton (the convenience view)

/**
 * The unified button. A sentence-case title, an optional leading icon, a [NoopButtonKind], an
 * optional [fullWidth], and an action. Crisp, flat, glow-free; subtle press; 44dp hit floor;
 * Reduce-Motion aware.
 *
 * ```
 * NoopButton(text = "Save changes", leadingIcon = Icons.Filled.Check, kind = NoopButtonKind.Primary, fullWidth = true) {
 *     save()
 * }
 * ```
 *
 * @param text the sentence-case label (single line).
 * @param leadingIcon optional leading icon, 8dp before the label.
 * @param kind the button role (default Primary).
 * @param fullWidth stretch to the available width.
 * @param enabled when false the button dims and ignores taps.
 * @param onClick the tap action.
 */
@Composable
fun NoopButton(
    text: String,
    leadingIcon: ImageVector? = null,
    kind: NoopButtonKind = NoopButtonKind.Primary,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val reduced = rememberReduceMotion()
    val appearance = appearanceFor(kind, enabled)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Reduce Motion: no scale, dim only. Otherwise subtle scale + dim. Pressed dim applies in both.
    val targetScale = if (pressed && !reduced) NoopButtonMetrics.pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reduced) tween(0) else NoopMotion.value(),
        label = "NoopButton.scale",
    )
    val opacity = when {
        !enabled && kind == NoopButtonKind.Tertiary -> NoopButtonMetrics.disabledOpacity
        pressed -> NoopButtonMetrics.pressedOpacity
        else -> 1f
    }

    val shape = RoundedCornerShape(NoopButtonMetrics.cornerRadius)

    var box = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .alpha(opacity)
        .let { if (fullWidth) it.fillMaxWidth() else it }
        .height(NoopButtonMetrics.height)
        .defaultMinSize(minHeight = NoopButtonMetrics.minHitTarget)
        .clip(shape)

    if (appearance.fill != null) box = box.background(appearance.fill, shape)
    if (appearance.border != null) box = box.border(BorderStroke(1.dp, appearance.border), shape)

    box = box
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
        .padding(horizontal = NoopButtonMetrics.hPadding)

    Row(
        modifier = box,
        horizontalArrangement = Arrangement.spacedBy(
            NoopButtonMetrics.iconSpacing, Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null, // the text labels the button
                tint = appearance.label,
                modifier = Modifier.height(18.dp),
            )
        }
        Text(
            text = text,
            style = NoopType.headline.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp, // a hair of openness on the semibold face (iOS tracking 0.2)
            ),
            color = appearance.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
