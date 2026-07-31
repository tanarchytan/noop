package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * The chart container every screen draws a chart in: a [NoopCard] with a header (title + subtitle +
 * trailing read-out), the chart body, then a footer slot.
 */
@Composable
internal fun ChartCard(
    title: String,
    subtitle: String,
    trailing: String?,
    footer: @Composable () -> Unit,
    tint: Color? = null,
    chart: @Composable () -> Unit,
) {
    NoopCard(padding = Metrics.cardPadding, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = NoopType.headline, color = Palette.textPrimary)
                    Text(subtitle, style = NoopType.footnote, color = Palette.textSecondary)
                }
                if (trailing != null) {
                    Text(trailing, style = NoopType.chartValue, color = Palette.textPrimary)
                }
            }
            chart()
            footer()
        }
    }
}

/** A footer strip of label/value pairs, evenly distributed. Values hold one line so a narrow column
 *  can't push the row taller and clip against the card edge. */
@Composable
internal fun ChartCardFooter(items: List<Pair<String, String>>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        items.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Overline(label, color = Palette.textTertiary)
                Text(
                    value,
                    style = NoopType.captionNumber,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        }
    }
}

/** The 1dp divider between sections inside a card. */
@Composable
internal fun CardHairline() {
    Box(modifier = Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
}
