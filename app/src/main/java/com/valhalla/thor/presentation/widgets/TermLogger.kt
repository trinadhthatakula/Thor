// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.LocalTechnicalFontFamily
import com.valhalla.thor.util.UiText

@Composable
fun TermLoggerDialog(
    modifier: Modifier = Modifier,
    title: UiText,
    logs: List<UiText>,
    isOperationComplete: Boolean,
    isOperationSuccessful: Boolean = false,
    onSupport: (() -> Unit)? = null,
    /** A stop has been asked for; the app in flight is still finishing. */
    isStopping: Boolean = false,
    /** Null when the running operation has no coherent halfway point to stop at. */
    onStop: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = {
            // Only allow dismiss if operation is done
            if (isOperationComplete) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(modifier = modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.Bottom) {
            TermLoggerContent(
                title = if (isOperationComplete) {
                    UiText.StringResource(R.string.done)
                } else {
                    title
                },
                logs = logs,
                status = when {
                    !isOperationComplete -> TermLoggerStatus.ACTIVE
                    isOperationSuccessful -> TermLoggerStatus.SUCCESS
                    else -> TermLoggerStatus.NEUTRAL
                },
                onClose = onDismiss.takeIf { isOperationComplete },
            ) {
                if (isOperationComplete) {
                    if (isOperationSuccessful && onSupport != null) {
                        OutlinedButton(onClick = onSupport, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.support_thor))
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                } else if (onStop != null) {
                    // Disabled once pressed rather than hidden: the run keeps logging for as long
                    // as the current app takes, and a button that vanished would read as "the tap
                    // did nothing".
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !isStopping,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (isStopping) R.string.log_stopping else R.string.log_stop,
                            ),
                        )
                    }
                }
            }
        }
    }
}

enum class TermLoggerStatus { ACTIVE, SUCCESS, NEUTRAL }

/** Terminal surface shared by legacy dialogs and durable queue task detail. */
@Composable
fun TermLoggerContent(
    title: UiText,
    logs: List<UiText>,
    status: TermLoggerStatus,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    wrapLines: Boolean = false,
    footerContent: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(topEnd = 20.dp, topStart = 20.dp),
            )
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (status) {
                TermLoggerStatus.ACTIVE -> RearrangingBoxesAnimation(
                    modifier = Modifier.size(50.dp),
                )

                TermLoggerStatus.SUCCESS -> Icon(
                    painterResource(R.drawable.check_circle),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                TermLoggerStatus.NEUTRAL -> Spacer(Modifier.size(40.dp))
            }

            Text(
                text = title.asString(),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.round_close),
                        stringResource(R.string.cd_close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        val lazyListState = rememberLazyListState()
        var followOutput by remember { mutableStateOf(true) }
        var followingScroll by remember { mutableStateOf(false) }
        var lastSeenOutput by remember { mutableStateOf(logs.size to logs.lastOrNull()) }
        val currentOutput = logs.size to logs.lastOrNull()
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(lazyListState.isScrollInProgress && !followingScroll,
                    lazyListState.lastScrolledBackward, lazyListState.canScrollForward)
            }.collect { (userScrolling, backward, moreBelow) ->
                if (!moreBelow) followOutput = true
                else if (userScrolling && backward) followOutput = false
            }
        }
        LaunchedEffect(currentOutput, followOutput) {
            if (logs.isEmpty()) followOutput = true
            else if (followOutput) {
                lastSeenOutput = currentOutput
                followingScroll = true
                try { lazyListState.scrollToItem(logs.lastIndex) }
                finally { followingScroll = false }
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            itemsIndexed(logs) { index, logText ->
                Text(
                    text = "> ${logText.asString()}",
                    softWrap = wrapLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(termLoggerLineTag(index)),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = LocalTechnicalFontFamily.current,
                    ),
                    maxLines = if (wrapLines) Int.MAX_VALUE else 1,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        if (!followOutput) {
            TextButton(onClick = { followOutput = true }) {
                Text(stringResource(
                    if (currentOutput != lastSeenOutput) R.string.log_jump_latest_new_output
                    else R.string.log_jump_latest,
                ))
            }
        }
        footerContent()
    }
}

internal fun termLoggerLineTag(index: Int): String = "term-logger-line-$index"
