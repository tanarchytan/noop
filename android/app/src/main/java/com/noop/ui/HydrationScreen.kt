package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.HydrationGoal
import com.noop.analytics.HydrationStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Hydration detail (MVP, opt-in, local-only)
//
// Standard surfaces throughout: the scaffold paints Palette.surfaceBase, the headline fill is a GlowRing
// (the same ring Home draws) inside a NoopCard, and the daily goal reads as a LinearProgressIndicator over
// Palette.surfaceInset. All data bindings, the pure HydrationGoal engine, and the local-only HydrationStore
// reads/writes are UNCHANGED — this is a presentation change only.

/** The goal ring's diameter, and its stroke as a fraction of that diameter (the Home hero ratio). */
private val HYDRATION_RING_DIAMETER = 184.dp
private const val HYDRATION_RING_STROKE_FRACTION = 0.10f

/** Upper bound (ml) for a single custom hydration log - a sane cap so a stray digit can't bank an
 *  absurd 50-litre day. A 3-litre container covers any realistic bottle/jug. Mirrors the iOS clamp. */
private const val MAX_CUSTOM_ML: Int = 3000

/** Parse a custom-amount field to a clamped whole-ml value in 1..[MAX_CUSTOM_ML], or null when the text
 *  isn't a usable positive number. Pure + side-effect-free so the dialog's confirm gate is unit-testable. */
internal fun parseCustomHydrationMl(text: String): Int? {
    val n = text.trim().toIntOrNull() ?: return null
    if (n <= 0) return null
    return n.coerceAtMost(MAX_CUSTOM_ML)
}

/**
 * The Hydration detail screen. The goal's effort bump uses today's Effort/strain (0..100) read from the
 * view-model's `today` row (null leaves the bump at 0). The screen reads/writes via [viewModel].repo;
 * the SharedPreferences-backed profile sex is read once. A log tap appends to the local-only day total
 * and refreshes the vessel + history.
 */
@Composable
fun HydrationScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val today by viewModel.today.collectAsStateWithLifecycle()
    val strain = today?.strain

    val sex = remember { ProfileStore.from(context).sex }
    val goalMl = remember(sex, strain) { HydrationGoal.dailyGoalMl(sex, strain) }

    // Today's running total + the per-day history, loaded off the gesture path and refreshed after a log.
    var totalMl by remember { mutableStateOf(0.0) }
    var history by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    // A simple reload key the log taps bump so the LaunchedEffect re-reads the store.
    var reloadTick by remember { mutableStateOf(0) }
    LaunchedEffect(reloadTick) {
        totalMl = runCatching { HydrationStore.total(viewModel.repo) }.getOrDefault(0.0)
        history = runCatching { HydrationStore.history(viewModel.repo, days = 7) }.getOrDefault(emptyList())
    }

    // the LAST amount logged this session, so the detail can offer a one-tap "Undo" that removes
    // exactly that container from the day total. The single-total schema can't address an individual log,
    // so undo subtracts the last known amount (clamped at 0 by the store). Reset to null after an undo so
    // the affordance only appears when there's something to take back. Not persisted - a relaunch shows
    // the day total but no pending undo, which is honest (you can still correct via the custom dialog).
    var lastLoggedMl by remember { mutableStateOf<Int?>(null) }
    // The custom-amount dialog (the "custom container size" - log any ml the quick buttons don't cover).
    var showCustom by remember { mutableStateOf(false) }

    val log: (Int) -> Unit = { amount ->
        scope.launch {
            runCatching { HydrationStore.log(viewModel.repo, amount) }
            if (amount > 0) lastLoggedMl = amount
            reloadTick += 1
        }
    }
    // Remove [amount] ml from the day total (the undo / delete-a-log path). Clears the pending undo.
    val remove: (Int) -> Unit = { amount ->
        scope.launch {
            runCatching { HydrationStore.remove(viewModel.repo, amount) }
            lastLoggedMl = null
            reloadTick += 1
        }
    }

    val fraction = if (goalMl > 0) (totalMl / goalMl).toFloat() else 0f
    // Water reads as the cyan data token, which the light scheme turns to teal; the old fill was the
    // one royal blue on the page and sat outside both schemes.
    val accent = Palette.metricCyan

    // the custom-amount entry. Logs any whole-ml amount the Sip/Cup/Bottle quick buttons don't
    // cover (a bespoke container), clamped to a sane 1..MAX_CUSTOM_ML, then routed through the SAME
    // additive `log` path so it accumulates into the day total and refreshes the vessel + history.
    if (showCustom) {
        CustomAmountDialog(
            accent = accent,
            onDismiss = { showCustom = false },
            onConfirm = { ml ->
                showCustom = false
                log(ml)
            },
        )
    }

    // PERF: lazy scaffold — each top-level section is one `item { }`. Order + spacing unchanged
    // (LazyColumn reproduces the eager `spacedBy(20.dp)`); only on-screen cards compose + are
    // accessibility-walked. All children are unconditional, so every wrap is a bare `item { }`.
    //
    // No topBackground: the scaffold paints Palette.surfaceBase, the one canvas every screen shares. The
    // decorated backdrop it used to carry painted fixed dark-mode colours in both themes.
    LazyScreenScaffold(
        title = "Hydration",
        subtitle = "Your fluid intake today, on this phone only.",
    ) {
        // HERO — the day's intake as the standard GlowRing on a NoopCard: the arc is the goal fraction in
        // the hydration accent, the litre figure counts up in the centre and the goal reads as the caption
        // under it. The daily goal repeats as a progress bar beneath. Same fraction, accent and litre
        // values as before.
        item {
            NoopCard(padding = Metrics.screenRowSpacing) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Metrics.space14),
                ) {
                    GlowRing(
                        fraction = fraction.coerceIn(0f, 1f),
                        value = totalMl / 1000.0,
                        color = accent,
                        diameter = HYDRATION_RING_DIAMETER,
                        lineWidth = HYDRATION_RING_DIAMETER * HYDRATION_RING_STROKE_FRACTION,
                        fillKey = "hydration.today",
                        format = { String.format(Locale.US, "%.1f", it) },
                        unit = " L",
                        caption = String.format(Locale.US, "of %.1f L", goalMl / 1000.0),
                    )
                    // DAILY GOAL — the same goal fraction again as a horizontal bar, on the theme's inset
                    // track so it reads in both schemes.
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        color = accent,
                        trackColor = Palette.surfaceInset,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Metrics.progressHeight)
                            .clip(RoundedCornerShape(Metrics.progressHeight / 2))
                            .semantics {
                                contentDescription =
                                    "${kotlin.math.min(100, (fraction * 100).toInt())} percent of today's goal"
                            },
                    )
                    Text(
                        "${kotlin.math.min(100, (fraction * 100).toInt())}% of today's goal",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }

        // LOG TILES — Sip / Cup / Bottle. Each owns its own interactionSource wired to BOTH its clickable
        // and liquidPress, so the whole tile settles inward on press, routing the SAME `log(...)` amounts.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10), modifier = Modifier.fillMaxWidth()) {
                QuickLogTile(
                    label = "Sip",
                    icon = Icons.Filled.WaterDrop,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                ) { log(HydrationGoal.SIP_ML) }
                QuickLogTile(
                    label = "Cup",
                    icon = Icons.Filled.LocalDrink,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                ) { log(HydrationGoal.CUP_ML) }
                QuickLogTile(
                    label = "Bottle",
                    icon = Icons.Filled.LocalDrink,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                ) { log(HydrationGoal.BOTTLE_ML) }
            }
        }
        // "Custom" (a bespoke container size). Full-width secondary button under the quick-add tiles;
        // opens the custom-amount dialog so any ml the presets don't cover can be logged. (Kept as a
        // NoopButton — it carries its own press feedback and this is a secondary affordance, not a quick-log.)
        item {
            NoopButton(
                text = "Custom amount",
                leadingIcon = Icons.Filled.Add,
                kind = NoopButtonKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            ) { showCustom = true }
        }
        item {
            Text(
                "Sip ${HydrationGoal.SIP_ML} ml · Cup ${HydrationGoal.CUP_ML} ml · Bottle ${HydrationGoal.BOTTLE_ML} ml",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }

        // 7-DAY HISTORY — flat mini bars, today on the right. Kept CRISP: this is a multi-bar mini chart
        // (one bar per day), not a single-value progress bar, so a tube would flatten it — leave it as-is.
        item {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Overline("Last 7 days")
                    HydrationHistoryBars(history = history, goalMl = goalMl, accent = accent)
                }
            }
        }

        // TODAY'S TOTAL as a single read-out row (the MVP "logged entries" — the day total is the running
        // sum the store banks; per-tap rows aren't separately persisted, so we show the honest day figure).
        item {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Overline("Today")
                    if (totalMl <= 0.0) {
                        Text(
                            "No drinks logged yet. Tap Sip, Cup or Bottle to start.",
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.WaterDrop,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(Metrics.iconSmall),
                            )
                            Spacer(Modifier.width(Metrics.space10))
                            Text(
                                "Logged today",
                                style = NoopType.subhead,
                                color = Palette.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${totalMl.toInt()} ml",
                                style = NoopType.headline.copy(fontWeight = FontWeight.SemiBold),
                                color = Palette.textPrimary,
                            )
                        }
                        // undo / correct affordances. "Undo last" appears once something has been logged
                        // this session and removes exactly that container from the day total. "Clear today" zeroes
                        // the day's total outright (the delete-everything correction). Both route through the
                        // store's clamped remove/set, so the total never goes negative.
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10), modifier = Modifier.fillMaxWidth()) {
                            lastLoggedMl?.let { last ->
                                NoopButton(
                                    text = "Undo last ($last ml)",
                                    leadingIcon = Icons.AutoMirrored.Filled.Undo,
                                    kind = NoopButtonKind.Secondary,
                                    modifier = Modifier.weight(1f),
                                ) { remove(last) }
                            }
                            NoopButton(
                                text = "Clear today",
                                leadingIcon = Icons.Filled.Delete,
                                kind = NoopButtonKind.Secondary,
                                modifier = Modifier.weight(1f),
                            ) { remove(totalMl.toInt()) }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "A simple goal that adjusts to your effort. General wellness guidance, not medical advice.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * One quick-log tile (Sip / Cup / Bottle). The tile owns a single [MutableInteractionSource] wired to BOTH
 * its `clickable` and `Modifier.liquidPress`, so the whole tile settles inward on press, then fires [onLog].
 * Frosted card surface, accent-tinted icon tile.
 */
@Composable
private fun QuickLogTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onLog: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .liquidPress(interaction)
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .frostedCardSurface(cornerRadius = Metrics.cardRadius)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Log $label",
                onClick = onLog,
            )
            .padding(vertical = Metrics.space14),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
        }
        Text(
            label,
            style = NoopType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = Palette.textPrimary,
        )
    }
}

/**
 * The 7-day mini bar history: one flat rounded bar per day, height = day total ÷ goal (clamped to 1), with
 * the weekday initial beneath. Today's bar is the accent blue; prior days a muted accent. Empty days render
 * a faint track. Pure Compose Canvas + a row of labels (design-reset flat style, no gridlines/axes).
 */
@Composable
private fun HydrationHistoryBars(
    history: List<Pair<String, Double>>,
    goalMl: Int,
    accent: Color,
) {
    if (history.isEmpty()) {
        Text("No history yet.", style = NoopType.footnote, color = Palette.textTertiary)
        return
    }
    // A week with nothing logged has no bar to draw, and seven full-height tracks read as seven days at
    // goal. Say the week is empty instead.
    if (history.none { it.second > 0.0 }) {
        Text(
            "No drinks logged in the last 7 days.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }
    val goal = goalMl.coerceAtLeast(1).toDouble()
    val track = Palette.textPrimary.copy(alpha = 0.10f)
    val priorBar = accent.copy(alpha = 0.45f)
    val lastIndex = history.lastIndex
    val maxMl = history.maxOf { it.second }
    // Scale the bars to the LARGER of the goal and the biggest day, so an over-goal day doesn't clip.
    val ceiling = kotlin.math.max(goal, maxMl).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val n = history.size
            val gap = 10.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            val corner = 6.dp.toPx()
            history.forEachIndexed { i, (_, ml) ->
                val x = i * (barW + gap)
                // Track (full-height faint bar).
                drawRoundRectBar(x, 0f, barW, size.height, corner, track)
                val frac = (ml / ceiling).toFloat().coerceIn(0f, 1f)
                if (frac > 0f) {
                    val h = size.height * frac
                    val color = if (i == lastIndex) accent else priorBar
                    drawRoundRectBar(x, size.height - h, barW, h, corner, color)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            history.forEach { (dayKey, _) ->
                Text(
                    weekdayInitial(dayKey),
                    style = NoopType.overline.copy(letterSpacing = 0.2.sp),
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Draw one rounded-rect bar via the Canvas draw scope (small helper so the bar loop stays readable). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectBar(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    corner: Float,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
}

/** The single-letter weekday for a yyyy-MM-dd key (M T W T F S S), or "·" when unparseable. */
private fun weekdayInitial(dayKey: String): String =
    runCatching {
        LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("EEE", Locale.US)).take(1)
    }.getOrDefault("·")

/**
 * the custom-amount dialog. A single numeric ml field (the "custom container size") with a clamped
 * confirm: the Log button is disabled until the text parses to a positive whole-ml value (1..MAX_CUSTOM_ML
 * via [parseCustomHydrationMl]), and confirming hands the parsed amount back to the caller's additive log.
 * Tokens-only on the hydration-blue field; mirrors the iOS custom-amount sheet.
 */
@Composable
private fun CustomAmountDialog(
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val parsed = parseCustomHydrationMl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WaterDrop,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Metrics.space10))
                Text("Custom amount", style = NoopType.title2, color = Palette.textPrimary)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(5) },
                    label = { Text("Millilitres (ml)", style = NoopType.footnote) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Palette.textPrimary,
                        unfocusedTextColor = Palette.textPrimary,
                        cursorColor = accent,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Palette.hairline,
                        focusedLabelColor = accent,
                        unfocusedLabelColor = Palette.textSecondary,
                        focusedContainerColor = Palette.surfaceInset,
                        unfocusedContainerColor = Palette.surfaceInset,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Enter any amount from 1 to $MAX_CUSTOM_ML ml.",
                    style = NoopType.footnote,
                    color = if (text.isNotEmpty() && parsed == null) Palette.statusWarning else Palette.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text("Log", color = if (parsed != null) accent else Palette.textTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Palette.textSecondary)
            }
        },
    )
}
