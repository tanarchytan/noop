package com.noop.ui.whoop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.noop.R
import com.noop.ui.CloseButton
import com.noop.ui.Metrics
import com.noop.ui.NoopButton
import com.noop.ui.NoopButtonKind
import com.noop.ui.NoopCard
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette

// MARK: - Customise sheet parts
//
// The pieces WhoopCustomizeSheet arranges: its top bar, one item row, the "add" section rule and the
// trailing icon action. Presentation only.

/** The sheet's top bar: a close X, a centred UPPERCASE [title] and a Reset action when [onReset] is set. */
@Composable
internal fun CustomizeSheetTopBar(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().padding(Metrics.space8)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CloseButton(onClick = onClose)
            if (onReset != null) {
                NoopButton(
                    text = stringResource(R.string.whoopskin_reset),
                    kind = NoopButtonKind.Tertiary,
                    onClick = onReset,
                )
            } else {
                Spacer(Modifier.size(Metrics.iconButton))
            }
        }
        Text(
            title.uppercase(),
            style = NoopType.overline,
            color = Palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = Metrics.iconButton + Metrics.space16),
        )
    }
}

/** One customise row: an optional [icon], the UPPERCASE [label], then the caller's [trailing] actions. */
@Composable
internal fun CustomizeItemRow(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    NoopCard(modifier = modifier, padding = Metrics.space8) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Metrics.space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Palette.textSecondary,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
            }
            Overline(label, modifier = Modifier.weight(1f), color = Palette.textPrimary)
            trailing()
        }
    }
}

/** The "add" divider: a grey UPPERCASE [title] with a hairline running out to the right edge. */
@Composable
internal fun CustomizeAddSectionRule(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(title, color = Palette.textTertiary)
        Spacer(Modifier.width(Metrics.space12))
        Box(Modifier.weight(1f).height(Metrics.divider).background(Palette.hairline))
    }
}

/** One trailing icon action on a [CustomizeItemRow]; [label] is what a screen reader speaks. */
@Composable
internal fun CustomizeRowAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Palette.textSecondary,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(Metrics.iconButton),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else Palette.textTertiary,
            modifier = Modifier.size(Metrics.iconSmall),
        )
    }
}
