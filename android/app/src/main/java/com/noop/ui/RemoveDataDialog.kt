package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.noop.ui.icons.CheckBox
import com.noop.ui.icons.CheckBoxOutlineBlank
import com.noop.ui.icons.RadioButtonChecked
import com.noop.ui.icons.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.noop.data.DataCategory

/**
 * Remove a source's stored data: everything, or only the categories chosen.
 *
 * Destructive and irreversible, so it states what it is about to destroy and which source it belongs
 * to before the confirm. Choosing every category removes exactly what "everything" removes -
 * `DataRemovalCoverageTest` is what keeps that true.
 */
@Composable
fun RemoveDataDialog(
    sourceLabel: String,
    onConfirm: (Set<DataCategory>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selective by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<DataCategory>()) }
    val canConfirm = !selective || chosen.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = { Text("Remove $sourceLabel data", style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                Text(
                    "This permanently deletes stored data for $sourceLabel on this phone. Other sources are " +
                        "untouched. This cannot be undone.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                ChoiceRow("Everything", !selective) { selective = false }
                ChoiceRow("Choose what to remove", selective) { selective = true }
                if (selective) {
                    Spacer(Modifier.size(2.dp))
                    DataCategory.ordered.forEach { category ->
                        CategoryRow(
                            category = category,
                            checked = category in chosen,
                            onToggle = {
                                chosen = if (category in chosen) chosen - category else chosen + category
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (selective) chosen else null) }, enabled = canConfirm) {
                Text(
                    "Remove",
                    style = NoopType.headline,
                    color = if (canConfirm) Palette.statusCritical else Palette.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = NoopType.headline, color = Palette.textSecondary)
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Palette.accent else Palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = NoopType.body, color = Palette.textPrimary)
    }
}

@Composable
private fun CategoryRow(category: DataCategory, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Palette.statusCritical.copy(alpha = 0.08f) else Palette.surfaceInset)
            .clickable { onToggle() }
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) Palette.statusCritical else Palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(category.label, style = NoopType.subhead, color = Palette.textPrimary)
            Text(category.detail, style = NoopType.caption, color = Palette.textTertiary)
        }
    }
}
