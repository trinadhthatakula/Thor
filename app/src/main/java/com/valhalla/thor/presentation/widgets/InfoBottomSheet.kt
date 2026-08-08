// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * Explains one action in full, opened by long-pressing its tile. Half-width bento tiles have to
 * drop their description to keep the title from clipping; this is where that description goes.
 *
 * Unlike the app's other bottom sheets this one can carry buttons. A long press fires onLongClick
 * *instead of* onClick, so holding a tile no longer performs its action — [confirmLabel] hands
 * that back, letting the user read the explanation and then go ahead without a second gesture.
 *
 * Both [confirmLabel] and [onConfirm] are optional, and omitting them makes the sheet purely
 * informational: one *Close* button and no way to act from here. That is the right shape wherever
 * the tile's own tap still works and the action is destructive — a confirm button on a Force Stop
 * or Freeze explainer would be a second trigger, reached by a gesture the user made to *ask a
 * question*. They are declared as two parameters rather than one pair because every existing caller
 * already passes them separately; passing only one of the two is a caller bug, and the sheet
 * degrades to the informational form rather than rendering a button that does nothing.
 */
@Composable
fun InfoBottomSheet(
    title: String,
    body: String,
    icon: Int,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Short, fixed-height content: a partial detent would only ever hide part of a paragraph
        // the user explicitly asked to read.
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden)
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(14.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (confirmLabel != null && onConfirm != null) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = onConfirm) {
                        Text(confirmLabel)
                    }
                } else {
                    // "Close", not "Cancel": with nothing to confirm there is nothing to cancel, and
                    // a lone Cancel on a paragraph of explanation reads as undoing something.
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
