package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noop.R

// MARK: - Empty state

@Composable
internal fun SleepEmptyState() {
    DataPendingNote(
        title = stringResource(R.string.sleep_empty_title),
        body = stringResource(R.string.sleep_empty_body),
    )
}

