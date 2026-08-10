package com.noop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.analytics.RustScores
import uniffi.whoop_ffi.RecoveryState
import kotlin.math.abs

// MARK: - Palette — the "Titanium & Gold" re-skin
//
// A premium dark theme built on a deep NAVY canvas (NOT pure black) with per-domain
// accent "colour worlds": Charge/recovery = GOLD, Effort/strain = amber, Rest/sleep =
// blue, HRV = teal, high stress = burnt orange. Gold is the dominant brand anchor —
// no greens anywhere.
//
// PUBLIC API IS FROZEN: every token NAME below is depended on by screens across the
// app, so the names never change — only the VALUES were re-themed to Titanium & Gold.
// New tokens (gold/titanium ramps + their gradients) are ADDED at the end of the
// object; nothing existing was removed or renamed.

object Palette {

    // The active scheme's tokens — snapshot state, so a flip re-resolves every read below (in
    // composables AND Canvas DrawScopes) with no call-site changes. Set by NoopTheme.
    internal var active by mutableStateOf(DarkTokens)
    /** True when the light scheme is active (surface code uses this for the per-scheme idiom). */
    val isLight: Boolean get() = active === LightTokens

    // Surfaces.
    val surfaceBase get() = active.surfaceBase
    val surfaceRaised get() = active.surfaceRaised
    val surfaceOverlay get() = active.surfaceOverlay
    val surfaceInset get() = active.surfaceInset
    val hairline get() = active.hairline
    val hairlineStrong get() = active.hairlineStrong

    // Text.
    val textPrimary get() = active.textPrimary
    val textSecondary get() = active.textSecondary
    val textTertiary get() = active.textTertiary

    // Glow.
    val glowAmbient get() = active.glowAmbient

    // Accent — GOLD brand anchor.
    val accent get() = active.accent
    val accentHover get() = active.accentHover
    val accentMuted get() = active.accentMuted
    val focusRing get() = active.focusRing
    /** The label/icon that sits ON a saturated fill (action blue, critical red). */
    val onFill get() = active.onFill
    const val disabledOpacity = 0.45f

    // Recovery / Charge gradient, low end to high. Where each anchor sits is whoop-rs's.
    val recovery000 get() = active.recovery000
    val recovery030 get() = active.recovery030
    val recovery055 get() = active.recovery055
    val recovery078 get() = active.recovery078
    val recovery100 get() = active.recovery100

    /** The recovery ramp: whoop-rs's anchor positions carrying this scheme's colours, in order. */
    val recoveryStops: List<Pair<Float, Color>>
        get() = RustScores.rampStops.recovery.map { it.toFloat() }
            .zip(listOf(recovery000, recovery030, recovery055, recovery078, recovery100))

    // Strain / Effort ramp, low end to high. Where each anchor sits is whoop-rs's.
    val strain000 get() = active.strain000
    val strain033 get() = active.strain033
    val strain066 get() = active.strain066
    val strain100 get() = active.strain100

    /** The effort ramp: whoop-rs's anchor positions carrying this scheme's colours, in order. */
    val strainStops: List<Pair<Float, Color>>
        get() = RustScores.rampStops.strain.map { it.toFloat() }
            .zip(listOf(strain000, strain033, strain066, strain100))

    // Sleep stages.
    val sleepAwake get() = active.sleepAwake
    val sleepLight get() = active.sleepLight
    val sleepDeep get() = active.sleepDeep
    val sleepREM get() = active.sleepREM

    // HR zones.
    val zone1 get() = active.zone1
    val zone2 get() = active.zone2
    val zone3 get() = active.zone3
    val zone4 get() = active.zone4
    val zone5 get() = active.zone5

    /** HR zones indexed 1..5; index 0 mirrors zone1 for convenience. */
    val hrZones: List<Color> get() = listOf(zone1, zone1, zone2, zone3, zone4, zone5)

    // Status.
    val statusPositive get() = active.statusPositive
    val statusWarning get() = active.statusWarning
    val statusCritical get() = active.statusCritical

    // Per-metric accents.
    val metricCyan get() = active.metricCyan
    val metricPurple get() = active.metricPurple
    val metricAmber get() = active.metricAmber
    val metricRose get() = active.metricRose

    // Domain "colour worlds".
    val chargeColor get() = active.chargeColor
    val chargeDeep get() = active.chargeDeep
    val chargeBright get() = active.chargeBright
    val chargeGlow get() = active.chargeGlow

    val effortColor get() = active.effortColor
    val effortDeep get() = active.effortDeep
    val effortBright get() = active.effortBright
    val effortGlow get() = active.effortGlow

    val restColor get() = active.restColor
    val restDeep get() = active.restDeep
    val restBright get() = active.restBright
    val restGlow get() = active.restGlow

    val stressColor get() = active.stressColor
    val stressDeep get() = active.stressDeep
    val stressBright get() = active.stressBright
    val stressGlow get() = active.stressGlow

    /** Deep → bright accent pairs (gauge stroke + diagonal card wash) per domain. */
    val chargeGradientStops: List<Pair<Float, Color>> get() = listOf(0.0f to chargeDeep, 1.0f to chargeBright)
    val effortGradientStops: List<Pair<Float, Color>> get() = listOf(0.0f to effortDeep, 1.0f to effortBright)
    val restGradientStops: List<Pair<Float, Color>> get() = listOf(0.0f to restDeep, 1.0f to restBright)
    // Stress ramp: calm → warn → high. Its ends sit on opposite sides of the wheel, so the anchors are
    // subdivided along the short hue arc ([hueRamp]) and the gradient never blends across the grey axis.
    val stressGradientStops: List<Pair<Float, Color>>
        get() = hueRamp(listOf(0.0f to stressDeep, 0.5f to stressColor, 1.0f to stressBright))

    // Scenic background.
    val scenicCenter get() = active.scenicCenter
    val scenicEdge get() = active.scenicEdge
    val scenicStar get() = active.scenicStar

    /** Frosted-card tint endpoints (the accent wash sits over them). */
    val cardFillTop get() = active.cardFillTop
    val cardFillBottom get() = active.cardFillBottom

    // Gold & Titanium ramps.
    val gold get() = active.gold
    val goldLight get() = active.goldLight
    val goldDeep get() = active.goldDeep
    val goldDeepText get() = active.goldDeepText
    val signalYellow get() = active.signalYellow

    /** Gold gradient stops (light → gold → deep) — buttons, ring fills, FAB (135–155°). */
    val goldGradient: List<Pair<Float, Color>> get() = listOf(0.0f to goldLight, 0.5f to gold, 1.0f to goldDeep)

    val titaniumTop get() = active.titaniumTop
    val titaniumMid get() = active.titaniumMid
    val titaniumLow get() = active.titaniumLow
    val titaniumDeep get() = active.titaniumDeep

    /** Gauge-tip / sparkline-head core — white on dark, deep ink on light. */
    val tipCore get() = active.tipCore

    // MARK: - Sampling helpers — a normalized position along a colour ramp to a colour

    /** Linear-interpolate two colors in sRGB space. */
    private fun lerp(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * tt,
            green = a.green + (b.green - a.green) * tt,
            blue = a.blue + (b.blue - a.blue) * tt,
            alpha = a.alpha + (b.alpha - a.alpha) * tt,
        )
    }

    /** Sample a set of (location, color) stops at a normalized position 0..1. */
    fun sample(stops: List<Pair<Float, Color>>, position: Float): Color {
        if (stops.isEmpty()) return Color.Transparent
        if (stops.size == 1) return stops.first().second
        val t = position.coerceIn(0f, 1f)
        var lower = stops.first()
        var upper = stops.last()
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (t >= a.first && t <= b.first) {
                lower = a; upper = b; break
            }
        }
        val span = upper.first - lower.first
        val localT = if (span > 0f) (t - lower.first) / span else 0f
        return lerp(lower.second, upper.second, localT)
    }

    /** Segments each leg of a [hueRamp] is cut into. Four keeps the sRGB blend between two neighbours
     *  short enough that it stays on the hue arc. */
    private const val HUE_SEGMENTS = 4

    /**
     * Subdivide a stop list along the SHORT hue arc between each pair of anchors. A gradient brush
     * blends its stops in sRGB, where two colours from opposite sides of the wheel meet as grey
     * halfway; the extra stops keep the path saturated without naming a colour the palette does not.
     */
    private fun hueRamp(anchors: List<Pair<Float, Color>>): List<Pair<Float, Color>> {
        if (anchors.size < 2) return anchors
        val out = ArrayList<Pair<Float, Color>>(anchors.size * HUE_SEGMENTS + 1)
        for (i in 0 until anchors.size - 1) {
            val (fromAt, fromColor) = anchors[i]
            val (toAt, toColor) = anchors[i + 1]
            for (step in 0 until HUE_SEGMENTS) {
                val f = step.toFloat() / HUE_SEGMENTS
                out += (fromAt + (toAt - fromAt) * f) to hueBlend(fromColor, toColor, f)
            }
        }
        out += anchors.last()
        return out
    }

    /** Blend two colours in HSV, turning the hue the short way round, so the path between them keeps
     *  its saturation instead of crossing the grey axis. */
    private fun hueBlend(a: Color, b: Color, t: Float): Color {
        val (ha, sa, va) = toHsv(a)
        val (hb, sb, vb) = toHsv(b)
        var turn = hb - ha
        if (turn > 180f) turn -= 360f
        if (turn < -180f) turn += 360f
        return fromHsv(
            hue = ha + turn * t,
            saturation = sa + (sb - sa) * t,
            value = va + (vb - va) * t,
            alpha = a.alpha + (b.alpha - a.alpha) * t,
        )
    }

    /** (hue 0..360, saturation 0..1, value 0..1) for a colour. */
    private fun toHsv(c: Color): Triple<Float, Float, Float> {
        val high = maxOf(c.red, c.green, c.blue)
        val low = minOf(c.red, c.green, c.blue)
        val range = high - low
        val hue = when {
            range == 0f -> 0f
            high == c.red -> 60f * (((c.green - c.blue) / range) % 6f)
            high == c.green -> 60f * (((c.blue - c.red) / range) + 2f)
            else -> 60f * (((c.red - c.green) / range) + 4f)
        }
        return Triple(if (hue < 0f) hue + 360f else hue, if (high == 0f) 0f else range / high, high)
    }

    /** The colour for an (hue, saturation, value) triple; [hue] may run outside 0..360. */
    private fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val chroma = value * saturation
        val second = chroma * (1f - abs((h / 60f) % 2f - 1f))
        val base = value - chroma
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        return Color(
            red = (r + base).coerceIn(0f, 1f),
            green = (g + base).coerceIn(0f, 1f),
            blue = (b + base).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }

    /** How far a tint's own label is pulled toward the ink on the light scheme. */
    private const val WASH_LABEL_INK = 0.45f

    /**
     * A tint's label colour where it sits on a low-alpha wash of itself. On the dark scheme the tint
     * reads far above its wash; on the pale light one the two land close and the label falls under the
     * 4.5:1 floor, so it darkens toward the ink while the chip keeps its colour in the fill.
     */
    fun washLabel(tint: Color): Color = if (isLight) lerp(tint, textPrimary, WASH_LABEL_INK) else tint

    /** Sample the recovery gradient at a recovery score 0..100. */
    fun recoveryColor(score: Double): Color =
        sample(recoveryStops, RustScores.rampPositionScore(score).toFloat())

    /** Sample the strain gradient at an Effort value on the 0..100 scale. */
    fun strainColor(strain: Double): Color =
        sample(strainStops, RustScores.rampPositionScore(strain).toFloat())

    /**
     * Effort tint sampled by a 0..1 fraction (e.g. value/scaleMax), spreading the full ember→amber
     * ramp. Prefer this for gauge tips / value-tinted accents so a high Effort reads as bright amber
     * rather than ember. strainColor() stays for callers holding a 0..100 value.
     */
    fun effortTint(fraction: Double): Color =
        sample(strainStops, RustScores.rampPositionFraction(fraction).toFloat())

    /** The state word for a recovery score. whoop-rs picks the band; only the word is chosen here. */
    fun recoveryState(score: Double): String = when (RustScores.state(score)) {
        RecoveryState.DEPLETED -> "DEPLETED"
        RecoveryState.LOW -> "LOW"
        RecoveryState.MODERATE -> "MODERATE"
        RecoveryState.PRIMED -> "PRIMED"
        RecoveryState.PEAK -> "PEAK"
    }

    /** HR-zone color for a 1..5 zone index (clamped). */
    fun hrZoneColor(zone: Int): Color = hrZones[zone.coerceIn(1, 5)]
}

// MARK: - DomainTheme (NEW — Titanium & Gold per-domain colour worlds)
//
// Maps a daily-score domain (Charge / Effort / Rest / Stress) to its accent
// "colour world": a primary colour, a deep→bright gradient for gauge strokes and
// card washes, and a glow colour for blooms / end-cap halos. Every surface
// (layered gauge, frosted card tint, scenic hero) reads its colours from here so a
// screen only has to name its domain.

enum class DomainTheme {
    Charge,
    Effort,
    Rest,
    Stress;

    /** The dominant accent colour for the world. */
    val color: Color
        get() = when (this) {
            Charge -> Palette.chargeColor
            Effort -> Palette.effortColor
            Rest -> Palette.restColor
            Stress -> Palette.stressColor
        }

    /** The deep (low) end of the world's accent ramp. */
    val deep: Color
        get() = when (this) {
            Charge -> Palette.chargeDeep
            Effort -> Palette.effortDeep
            Rest -> Palette.restDeep
            Stress -> Palette.stressDeep
        }

    /** The bright (high) end of the world's accent ramp. */
    val bright: Color
        get() = when (this) {
            Charge -> Palette.chargeBright
            Effort -> Palette.effortBright
            Rest -> Palette.restBright
            Stress -> Palette.stressBright
        }

    /** The world's glow colour for blooms and gauge end-caps. */
    val glow: Color
        get() = when (this) {
            Charge -> Palette.chargeGlow
            Effort -> Palette.effortGlow
            Rest -> Palette.restGlow
            Stress -> Palette.stressGlow
        }

    /** A short upper-case label for the world (CHARGE / STRAIN / REST / STRESS). */
    val label: String get() = if (this == Effort) "Strain" else name
}

// MARK: - Motion
//
// Physiological motion — breathe / pulse / flow, no cartoon bounce.

object Motion {
    // Durations (ms)
    const val durationStandard = 300   // card appear, fades
    const val durationSlow = 900       // ring arc, waveform ignite
    const val breathPeriodMs = 3200    // one breath cycle for ambient pulsing

    // Easings
    val easeOut: Easing = LinearOutSlowInEasing
    val easeInOut: Easing = FastOutSlowInEasing
    val drawIn: Easing = LinearOutSlowInEasing
    val interactive: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}

// MARK: - Shared UI tokens — the alpha values charts, selectors and hairlines draw with

object StrandAlpha {
    const val subtleLine = 0.60f
    const val selectedFill = 0.12f
    const val selectedBorder = 0.55f
    const val chartFillStrong = 0.28f
    const val chartFillSoft = 0.04f
    const val chartMarker = 0.35f
    const val chartShadow = 0.28f
    const val chartLabel = 0.95f
    const val unselectedBar = 0.88f
    const val warningFill = 0.12f

    /** The plate behind a chart's value label, so a number stays readable over another series' line. */
    const val labelPlate = 0.82f
}

// MARK: - Metrics — the shared spacing / size tokens every screen lays out with

object Metrics {
    val space2 = 2.dp
    val space4 = 4.dp
    val space6 = 6.dp
    val space8 = 8.dp
    val space10 = 10.dp
    val space12 = 12.dp
    val space14 = 14.dp
    val space16 = 16.dp
    val space18 = 18.dp
    val space24 = 24.dp
    val sourceBadgeHeight = 18.dp
    val cardRadius = 18.dp   // Bevel continuous radius (18–22dp)
    val heroRadius = 26.dp   // the liquid hero card's frosted-glass rounding
    val cornerXs = 2.dp
    val cornerSm = 12.dp
    val cornerPill = 50.dp
    val cardPadding = 16.dp
    val gap = 12.dp           // gap between cards
    val sectionGap = 28.dp    // gap between sections
    // the ONE inter-card vertical spacing for a screen's top-level scroll rows. Both ScreenScaffold
    // and LazyScreenScaffold use this for `spacedBy(...)`, so every Today/Explore card sits on the same
    // rhythm instead of a bare `20.dp` literal repeated per scaffold (and Today no longer injects ad-hoc
    // Spacer rows that broke that rhythm). One token = uniform, consistent gaps across the screens.
    val screenRowSpacing = 20.dp
    val screenPadding = 24.dp
    val tileHeight = 108.dp   // every metric tile is this tall
    val chartHeight = 220.dp
    val divider = 1.dp
    val compactChartHeight = chartHeight - 90.dp
    val selectorTopUp = sectionGap - screenRowSpacing
    val iconButton = 36.dp
    val iconSmall = 18.dp
    val selectorPadding = 10.dp
    val selectorSpacing = 8.dp
    val motionStripHeight = 40.dp   // the movement/restlessness trace under the hypnogram
    // WHOOP-style per-stage sleep timeline rows.
    val stageRowTrackHeight = 20.dp  // hatched night track + solid stage segments
    val stageRowCorner = 10.dp       // row background rounding
    val stageRowPadH = 10.dp         // row inner horizontal padding — the movement strip and axis share it so epochs align
    val stageRowPadV = 8.dp          // row inner vertical padding
    val stageSegMinWidth = 2.dp      // width floor so a brief fragment reads as a block, not a hairline
    val stageSegCorner = 1.5.dp      // solid segment rounding
    val stageInsightHeight = 36.dp   // fixed insight slot height — selection never reflows the card
    val hrChartHeight = 132.dp       // night HR trace over the sleep window, above the stage rows
    val hrChartGutter = 26.dp        // bpm label gutter; the bound-label row shares it so both align
    val trendStripHeight = 120.dp
    val segmentBarHeight = 18.dp
    val legendSwatch = 9.dp
    val legendLineWidth = 14.dp
    val legendLineHeight = 3.dp
    val progressHeight = 10.dp
}

// MARK: - Typography
//
// A Helvetica-Neue FontFamily where one is bundled in res/font, else FontFamily.SansSerif
// as the documented substitute (no Helvetica asset is bundled, so the platform grotesque
// stands in) with the same sizes/weights. Numeric/live styles stay in the house sans and
// request TABULAR figures via fontFeatureSettings = "tnum" so live values don't reflow;
// Monospace is reserved for the `mono` raw/log style only.

object NoopType {
    // Helvetica Neue family — falls back to the platform grotesque (SansSerif) when
    // no res/font/helvetica_neue asset is bundled, per the v3 type spec.
    private val sans = FontFamily.SansSerif
    private val monoFamily = FontFamily.Monospace

    /** Display 64–80 / Bold — the recovery ring number. Tight tracking (≈ -0.04em),
     *  tabular figures so a changing value never reflows. */
    fun display(size: Float = 72f) = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = size.sp,
        letterSpacing = displayTracking(size).sp, fontFeatureSettings = "tnum",
    )

    /** The tight tracking for big display numbers (≈ -0.04em). Already applied inside
     *  display(); exposed so a caller building its own style can match it. */
    fun displayTracking(size: Float = 72f): Float = -size * 0.04f

    val title1 = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 28.sp)
    val title2 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
    val headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val subhead = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val footnote = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp)

    /** Overline 11 / Bold, +1.4 tracking, ALL-CAPS at use site. */
    val overline = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        letterSpacing = 1.4.sp,
    )

    /** Overline 10 / Bold, +0.5 tracking — the dense variant for a pill that has to hold a word like
     *  ON-DEVICE on one line. The only sanctioned step below [overline]. */
    val overlineSmall = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        letterSpacing = 0.5.sp,
    )

    /** Tab-bar item label 10 / Medium — the bottom nav's caption, below [footnote] so five fit a row.
     *  A call site may `.copy(fontWeight = …)` to mark the selected tab. */
    val tabLabel = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 10.sp)

    /** A small pictographic mark (▲ ▼) sized to ride beside caption text rather than to be read. */
    val glyph = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 8.sp,
        fontFeatureSettings = "tnum",
    )

    /** Mono 13 — raw / log views. */
    val mono = TextStyle(fontFamily = monoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp)

    /** A numeric style at an arbitrary size — the house sans with TABULAR figures
     *  ('tnum') so live values don't reflow. */
    fun number(size: Float, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
        fontFamily = sans, fontWeight = weight, fontSize = size.sp, fontFeatureSettings = "tnum",
    )

    fun mono(size: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = monoFamily, fontWeight = weight, fontSize = size.sp,
    )

    val bodyNumber = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, fontFeatureSettings = "tnum")
    val captionNumber = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, fontFeatureSettings = "tnum")
    val chartValue = number(18f)
    val chartValueLarge = number(22f)
    val tileValueLarge = number(26f)

    const val overlineTracking = 1.4f
}

// MARK: - Material3 bridge

/** Build the Material3 colour scheme from a token set. Dark/light differ only in the builder used
 *  (which sets sensible defaults for the slots we don't override); the NOOP surfaces are all driven
 *  by `Palette.*` directly, so this only feeds Material components (text fields, switches, etc.). */
private fun noopColorScheme(t: PaletteTokens, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = t.accent,
        onPrimary = if (dark) t.surfaceBase else t.goldDeepText,
        primaryContainer = t.accentMuted,
        onPrimaryContainer = if (dark) t.accentHover else t.accent,
        secondary = t.metricPurple,
        onSecondary = if (dark) t.surfaceBase else Color(0xFFFFFFFF),
        background = t.surfaceBase,
        onBackground = t.textPrimary,
        surface = t.surfaceRaised,
        onSurface = t.textPrimary,
        surfaceVariant = t.surfaceOverlay,
        onSurfaceVariant = t.textSecondary,
        outline = t.hairline,
        outlineVariant = t.hairlineStrong,
        error = t.statusCritical,
        onError = if (dark) t.surfaceBase else Color(0xFFFFFFFF),
    )
}

private val NoopMaterialTypography = Typography(
    displayLarge = NoopType.display(72f),
    titleLarge = NoopType.title1,
    titleMedium = NoopType.title2,
    titleSmall = NoopType.headline,
    bodyLarge = NoopType.body,
    bodyMedium = NoopType.subhead,
    bodySmall = NoopType.caption,
    labelLarge = NoopType.headline,
    labelMedium = NoopType.caption,
    labelSmall = NoopType.overline,
)

private val NoopShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(Metrics.cardRadius),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * NoopTheme — instrument-grade, now System / Light / Dark. The chosen mode (default System) drives
 * both `Palette.active` (so every `Palette.*` read re-resolves) and the Material scheme. The write to
 * `Palette.active` is guarded + idempotent, and happens before children compose, so there's no flash
 * and no recomposition loop (NoopTheme itself never reads `active`).
 */
@Composable
fun NoopTheme(content: @Composable () -> Unit) {
    val dark = when (AppearancePrefs.mode) {
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val tokens = if (dark) DarkTokens else LightTokens
    if (Palette.active !== tokens) Palette.active = tokens

    // Status-/nav-bar icon appearance: light icons on the dark theme, dark icons on the warm-paper
    // light theme (otherwise the icons are invisible). Edge-to-edge keeps the bars transparent.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = noopColorScheme(tokens, dark),
        typography = NoopMaterialTypography,
        shapes = NoopShapes,
        content = content,
    )
}
