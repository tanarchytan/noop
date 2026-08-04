package com.noop.ui

import androidx.compose.runtime.Composable

// MARK: - Empty state

@Composable
internal fun SleepEmptyState() {
    DataPendingNote(
        title = "No nights here yet",
        body = "Import your WHOOP export in Data Sources to see every night, your sleep " +
            "stages and trends straight away.",
    )
}

