// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Everything the UI needs to render one retained durable sweep request. */
data class SweepProgressUiState(
    val phase: PrivilegeSweepPhase,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val rootLaneDegraded: Boolean,
    val message: UiText? = null,
) {
    val isActive: Boolean
        get() = phase == PrivilegeSweepPhase.QUEUED || phase == PrivilegeSweepPhase.RUNNING
}

fun PrivilegeSweepStatus.toSweepProgressUiState(): SweepProgressUiState =
    SweepProgressUiState(
        phase = phase,
        total = total,
        succeeded = succeeded,
        failed = failed,
        busy = busy,
        unresolved = unresolved,
        rootLaneDegraded = rootLaneDegraded,
        message = if (phase == PrivilegeSweepPhase.OBSERVER_FAILURE) {
            UiText.StringResource(R.string.sweep_observer_failure_desc)
        } else {
            null
        },
    )

fun queuedSweepProgress(total: Int): SweepProgressUiState = SweepProgressUiState(
    phase = PrivilegeSweepPhase.QUEUED,
    total = total,
    succeeded = 0,
    failed = 0,
    busy = 0,
    unresolved = total,
    rootLaneDegraded = false,
)

fun failedSweepProgress(total: Int, message: UiText): SweepProgressUiState = SweepProgressUiState(
    phase = PrivilegeSweepPhase.FAILED,
    total = total,
    succeeded = 0,
    failed = 0,
    busy = 0,
    unresolved = total,
    rootLaneDegraded = false,
    message = message,
)

fun SweepProgressUiState?.asObserverFailure(): SweepProgressUiState = SweepProgressUiState(
    phase = PrivilegeSweepPhase.OBSERVER_FAILURE,
    total = this?.total ?: 0,
    succeeded = this?.succeeded ?: 0,
    failed = this?.failed ?: 0,
    busy = this?.busy ?: 0,
    unresolved = this?.unresolved ?: 0,
    rootLaneDegraded = this?.rootLaneDegraded ?: false,
    message = UiText.StringResource(R.string.sweep_observer_failure_desc),
)

/**
 * Count-only presentation of a durable privilege sweep.
 *
 * Active work blocks accidental Back/outside dismissal and offers cancellation of the whole queue.
 * Every non-success terminal outcome remains visible until acknowledged; a full success may dismiss
 * itself after [autoDismissMillis].
 */
@Composable
fun FreezeLoggerDialog(
    state: SweepProgressUiState,
    onDismiss: () -> Unit,
    onCancelQueue: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = 2000L,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(state.phase) {
        if (state.phase == PrivilegeSweepPhase.SUCCEEDED) {
            delay(autoDismissMillis.milliseconds)
            currentOnDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (!state.isActive) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !state.isActive,
            dismissOnClickOutside = !state.isActive,
        ),
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SweepStateIcon(state.phase)

                Text(
                    text = sweepTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                sweepBody(state)?.let { body ->
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                if (state.rootLaneDegraded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.warning),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.sweep_root_lane_degraded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                when {
                    state.isActive -> Button(
                        onClick = onCancelQueue,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cancel_sweep_queue))
                    }

                    state.phase != PrivilegeSweepPhase.SUCCEEDED -> Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun SweepStateIcon(phase: PrivilegeSweepPhase) {
    when (phase) {
        PrivilegeSweepPhase.QUEUED,
        PrivilegeSweepPhase.RUNNING -> AnimateLottieRaw(
            resId = R.raw.rearrange,
            shouldLoop = true,
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
        )

        PrivilegeSweepPhase.SUCCEEDED -> Icon(
            painter = painterResource(R.drawable.check_circle),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        PrivilegeSweepPhase.PARTIAL -> Icon(
            painter = painterResource(R.drawable.warning),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        PrivilegeSweepPhase.CANCELLED -> Icon(
            painter = painterResource(R.drawable.round_close),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrivilegeSweepPhase.FAILED,
        PrivilegeSweepPhase.OBSERVER_FAILURE -> Icon(
            painter = painterResource(R.drawable.danger),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun sweepTitle(state: SweepProgressUiState): String = when (state.phase) {
    PrivilegeSweepPhase.QUEUED -> stringResource(R.string.sweep_queued)
    PrivilegeSweepPhase.RUNNING -> stringResource(
        R.string.sweep_running,
        state.succeeded + state.failed + state.busy,
        state.total,
    )

    PrivilegeSweepPhase.SUCCEEDED -> stringResource(
        R.string.sweep_succeeded,
        state.succeeded,
        state.total,
    )

    PrivilegeSweepPhase.PARTIAL -> stringResource(R.string.sweep_partial)
    PrivilegeSweepPhase.CANCELLED -> stringResource(R.string.sweep_cancelled)
    PrivilegeSweepPhase.FAILED -> stringResource(
        if (state.message == null) R.string.sweep_failed else R.string.sweep_launch_failed_title
    )

    PrivilegeSweepPhase.OBSERVER_FAILURE -> stringResource(R.string.sweep_observer_failure)
}

@Composable
private fun sweepBody(state: SweepProgressUiState): String? = state.message?.asString()
    ?: when (state.phase) {
        PrivilegeSweepPhase.PARTIAL,
        PrivilegeSweepPhase.CANCELLED,
        PrivilegeSweepPhase.FAILED -> stringResource(
            R.string.sweep_progress_summary,
            state.succeeded,
            state.failed,
            state.busy,
            state.unresolved,
        )

        PrivilegeSweepPhase.OBSERVER_FAILURE ->
            stringResource(R.string.sweep_observer_failure_desc)

        PrivilegeSweepPhase.QUEUED,
        PrivilegeSweepPhase.RUNNING,
        PrivilegeSweepPhase.SUCCEEDED -> null
    }
