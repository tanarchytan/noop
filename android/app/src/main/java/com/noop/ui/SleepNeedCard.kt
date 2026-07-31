package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.SleepDebtLedger
import kotlin.math.abs
import kotlin.math.max

/** Height of the two comparison bars, and of the per-night balance strip under them. */
private val NEED_BAR_HEIGHT = 14.dp
private val DEBT_STRIP_HEIGHT = 56.dp
private val LEDGER_SWATCH = 12.dp

/**
 * HOURS VS. NEEDED — the night's asleep total against the personal sleep need, drawn as two bars on
 * one scale, then the need and the running balance behind it.
 *
 * The reference splits the need into Healthy Minimum, Recent Strain and Sleep Debt. whoop-rs returns
 * the need as one number and does not decompose it, so these rows state what it returns rather than a
 * split invented here.
 */
@Composable
internal fun SleepNeedCard(
    percent: Double?,
    typicalPercent: Double?,
    sleptMin: Double,
    neededMin: Double,
    ledger: SleepDebtLedger,
) {
    val scale = max(max(sleptMin, neededMin), 1.0)
    NoopCard(padding = Metrics.cardPadding, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text("HOURS VS. NEEDED", style = NoopType.overline, color = Palette.textTertiary)
                Text(
                    pctValue(percent),
                    style = NoopType.tileValueLarge,
                    color = Palette.restColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    typicalPercent?.let { "${pctValue(it)} typical" } ?: "no typical yet",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
            NeedBar(
                label = "HOURS OF SLEEP",
                value = durationText(sleptMin),
                fraction = (sleptMin / scale).coerceIn(0.0, 1.0).toFloat(),
                brush = Brush.horizontalGradient(listOf(Palette.restDeep, Palette.restBright)),
            )
            NeedBar(
                label = "SLEEP NEEDED",
                value = durationText(neededMin),
                fraction = (neededMin / scale).coerceIn(0.0, 1.0).toFloat(),
                brush = Brush.horizontalGradient(listOf(Palette.surfaceOverlay, Palette.textTertiary)),
            )
            CardHairline()
            if (ledger.nightCount == 0) {
                Text(
                    "No nights with sleep data yet. Your balance fills in as you wear the strap to bed.",
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    NeedLedgerRow("Sleep need", durationText(ledger.needMin), Palette.textTertiary)
                    NeedLedgerRow("Sleep balance", debtSigned(ledger.balanceMin), debtBalanceColor(ledger))
                    Text(debtRead(ledger), style = NoopType.footnote, color = Palette.textSecondary)
                    DebtBalanceStrip(ledger)
                }
            }
        }
    }
}

/** One comparison bar: its label and read-out over a full-width track filled to [fraction]. */
@Composable
private fun NeedBar(label: String, value: String, fraction: Float, brush: Brush) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NoopType.overline, color = Palette.textTertiary, modifier = Modifier.weight(1f))
            Text(value, style = NoopType.captionNumber, color = Palette.textPrimary, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NEED_BAR_HEIGHT)
                .clip(RoundedCornerShape(Metrics.cornerPill))
                .background(Palette.surfaceInset)
                .semantics { contentDescription = "$label $value" },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(NEED_BAR_HEIGHT)
                    .clip(RoundedCornerShape(Metrics.cornerPill))
                    .background(brush),
            )
        }
    }
}

/** One labelled figure under the bars, with the swatch its colour explains. */
@Composable
private fun NeedLedgerRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LEDGER_SWATCH)
                .clip(RoundedCornerShape(3.dp))
                .background(valueColor),
        )
        Spacer(Modifier.width(Metrics.space10))
        Text(label, style = NoopType.subhead, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.captionNumber, color = valueColor, maxLines = 1)
    }
}

/**
 * The per-night balance strip: each night a bar from the centre line, up for a surplus and down for a
 * deficit, scaled to the largest magnitude in the window.
 */
@Composable
private fun DebtBalanceStrip(ledger: SleepDebtLedger) {
    val deltas = ledger.nights.map { it.deltaMin }
    val scale = max(deltas.maxOfOrNull { abs(it) } ?: 1.0, 1.0)
    val surplusColor = Palette.accent
    val deficitColor = Palette.metricRose
    val centreColor = Palette.hairline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DEBT_STRIP_HEIGHT)
            .semantics {
                contentDescription =
                    "Per-night sleep balance: ${ledger.nightCount} nights, net ${debtSigned(ledger.balanceMin)}"
            }
            .drawBehind {
                val n = max(deltas.size, 1)
                val slot = size.width / n
                val barW = max(2f, slot * 0.6f)
                val midY = size.height / 2f
                drawLine(
                    color = centreColor,
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 1f,
                )
                deltas.forEachIndexed { i, d ->
                    val frac = (abs(d) / scale).toFloat().coerceIn(0f, 1f)
                    val h = max(2f, frac * (midY - 2f))
                    val cx = slot * i + slot / 2f
                    drawRoundRect(
                        color = if (d >= 0.0) surplusColor else deficitColor,
                        topLeft = Offset(cx - barW / 2f, if (d >= 0.0) midY - h else midY),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }
            },
    )
}
