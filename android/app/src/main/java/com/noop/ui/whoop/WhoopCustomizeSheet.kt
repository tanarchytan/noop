package com.noop.ui.whoop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.DashboardCard
import com.noop.ui.DashboardCardPrefs
import com.noop.ui.KeyMetric
import com.noop.ui.KeyMetricPrefs
import com.noop.ui.Metrics
import com.noop.ui.NoopButton
import com.noop.ui.NoopButtonKind
import com.noop.ui.NoopType
import com.noop.ui.Palette

// MARK: - Customise sheet
//
// Two ordered lists over a pinned Save: what is on the dashboard, then what can be added. Persistence
// is display-only — it changes WHICH already-computed values render and in WHAT order, never a value.

/**
 * The customise sheet over the registry [all]. [initial] is the saved selection, [label] and [icon]
 * name each item, and [onSave] receives the new ordered enabled list. Passing [defaults] adds a Reset
 * action. Content-only: host it in a ModalBottomSheet or a full-screen Dialog.
 */
@Composable
fun <T> WhoopCustomizeSheet(
    title: String,
    all: List<T>,
    initial: List<T>,
    label: (T) -> String,
    onClose: () -> Unit,
    onSave: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
    addSectionTitle: String = stringResource(R.string.whoopskin_add_to_my_dashboard),
    saveLabel: String = stringResource(R.string.whoopskin_save),
    defaults: List<T>? = null,
) {
    val selection = rememberCustomizeSelection(all, initial)
    val density = LocalDensity.current
    // The Save bar overlays the list, so the list takes its measured height as a bottom inset.
    var saveBarHeight by remember { mutableStateOf(0.dp) }

    val onReset: (() -> Unit)? = defaults?.let { fallback -> { selection.reset(fallback) } }

    Surface(modifier = modifier.fillMaxSize(), color = Palette.surfaceBase) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                CustomizeSheetTopBar(title = title, onClose = onClose, onReset = onReset)

                val enabled = selection.enabled
                val available = selection.available
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = Metrics.cardPadding,
                        end = Metrics.cardPadding,
                        top = Metrics.space8,
                        bottom = saveBarHeight + Metrics.space12,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Metrics.gap),
                ) {
                    itemsIndexed(enabled) { index, item ->
                        val name = label(item)
                        CustomizeItemRow(label = name, icon = icon(item)) {
                            CustomizeRowAction(
                                icon = Icons.Filled.KeyboardArrowUp,
                                label = stringResource(R.string.whoopskin_move_up, name),
                                enabled = index > 0,
                                onClick = { selection.move(index, index - 1) },
                            )
                            CustomizeRowAction(
                                icon = Icons.Filled.KeyboardArrowDown,
                                label = stringResource(R.string.whoopskin_move_down, name),
                                enabled = index < enabled.lastIndex,
                                onClick = { selection.move(index, index + 1) },
                            )
                            CustomizeRowAction(
                                icon = Icons.Filled.RemoveCircleOutline,
                                label = stringResource(R.string.whoopskin_remove_item, name),
                                tint = Palette.textTertiary,
                                onClick = { selection.remove(item) },
                            )
                        }
                    }
                    if (enabled.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.whoopskin_add_at_least_one),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                    }
                    if (available.isNotEmpty()) {
                        item { CustomizeAddSectionRule(addSectionTitle) }
                        items(available) { item ->
                            val name = label(item)
                            CustomizeItemRow(label = name, icon = icon(item)) {
                                CustomizeRowAction(
                                    icon = Icons.Filled.Add,
                                    label = stringResource(R.string.whoopskin_add_item, name),
                                    tint = Palette.accent,
                                    onClick = { selection.add(item) },
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { saveBarHeight = with(density) { it.height.toDp() } }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.surfaceBase)))
                    .navigationBarsPadding()
                    .padding(horizontal = Metrics.cardPadding, vertical = Metrics.space16),
            ) {
                NoopButton(
                    text = saveLabel,
                    kind = NoopButtonKind.Primary,
                    fullWidth = true,
                    enabled = selection.canSave,
                ) {
                    onSave(selection.enabled.toList())
                }
            }
        }
    }
}

/**
 * The dashboard customise sheet. Reads the saved selection, writes it back on save and hands the new
 * list to [onSaved] so the host can refresh its own state; [onClose] dismisses without saving.
 */
@Composable
fun WhoopDashboardCustomizeSheet(
    onClose: () -> Unit,
    onSaved: (List<DashboardCard>) -> Unit,
    modifier: Modifier = Modifier,
    initial: List<DashboardCard> = DashboardCardPrefs.enabled(LocalContext.current),
) {
    val context = LocalContext.current
    WhoopCustomizeSheet(
        title = stringResource(R.string.whoopskin_customise_dashboard),
        all = DashboardCard.canonicalOrder,
        initial = initial,
        label = { context.getString(it.titleRes) },
        icon = { it.icon },
        defaults = DashboardCard.defaultSelection,
        onClose = onClose,
        onSave = { cards ->
            DashboardCardPrefs.setEnabled(context, cards)
            onSaved(cards)
        },
        modifier = modifier,
    )
}

/** The same sheet over the Key-Metrics grid. That registry carries no icons, so those rows have none. */
@Composable
fun WhoopKeyMetricsCustomizeSheet(
    onClose: () -> Unit,
    onSaved: (List<KeyMetric>) -> Unit,
    modifier: Modifier = Modifier,
    initial: List<KeyMetric> = KeyMetricPrefs.enabled(LocalContext.current),
) {
    val context = LocalContext.current
    WhoopCustomizeSheet(
        title = stringResource(R.string.whoopskin_customise_key_metrics),
        all = KeyMetric.defaultOrder,
        initial = initial,
        label = { context.getString(it.titleRes) },
        addSectionTitle = stringResource(R.string.whoopskin_add_to_my_key_metrics),
        defaults = KeyMetric.defaultOrder,
        onClose = onClose,
        onSave = { metrics ->
            KeyMetricPrefs.setEnabled(context, metrics)
            onSaved(metrics)
        },
        modifier = modifier,
    )
}
