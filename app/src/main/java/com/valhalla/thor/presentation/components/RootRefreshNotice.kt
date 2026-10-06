// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.RootRefreshStatus

/** Keeps refresh uncertainty visible without replacing loaded screen content. */
@Composable
fun RootRefreshNotice(
    status: RootRefreshStatus,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val message = when (status) {
        RootRefreshStatus.IDLE -> return
        RootRefreshStatus.CHECKING -> R.string.root_refresh_checking
        RootRefreshStatus.BUSY -> R.string.root_refresh_busy
        RootRefreshStatus.TIMED_OUT -> R.string.root_refresh_timed_out
        RootRefreshStatus.FAILED -> R.string.root_refresh_failed
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null && status != RootRefreshStatus.CHECKING) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_label)) }
        }
    }
}
