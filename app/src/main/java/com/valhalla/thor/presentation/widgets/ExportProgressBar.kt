// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.main.ExportProgressState

/**
 * The live view of a multi-app export, plus the only way to stop one.
 *
 * A bar and not a dialog, deliberately. The confirm dialog tells the user this "can take a long
 * time" and to keep Thor open — so the one thing the UI must not do is take the app hostage for the
 * duration. This sits above the navigation bar: always visible, never blocking, and the app stays
 * fully usable underneath it.
 *
 * There is no dismiss. The run ending *is* the dismissal, and the outcome arrives separately as a
 * message; a close button here would have to mean either "cancel" (which the stop button already
 * says honestly) or "hide a running export", which is how a user loses track of a job holding
 * gigabytes of cache.
 */
@Composable
fun ExportProgressBar(
    state: ExportProgressState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.status.asString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // App-controlled text, so one line and an ellipsis: a label long enough to
                    // wrap would push the bar over the content it is supposed to sit beside.
                    state.currentLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        painter = painterResource(R.drawable.round_close),
                        contentDescription = stringResource(R.string.export_bulk_stop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Determinate from the first frame: the runner publishes "0 of N" before the run even
            // starts, so total is always known and a spinner would say less than a full-width
            // empty bar does.
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
