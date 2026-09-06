// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskLogLine
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.presentation.navigation.ThorRoute
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.presentation.widgets.TermLoggerContent
import com.valhalla.thor.presentation.widgets.TermLoggerStatus
import com.valhalla.thor.util.ServiceQueueEvent
import com.valhalla.thor.util.ServiceQueueLatencyProbe
import com.valhalla.thor.util.ServiceQueueOperation
import com.valhalla.thor.util.UiText
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TaskDetailScreen(
    route: ThorRoute.TaskDetail,
    onBackground: () -> Unit,
    onActionDispatch: (TaskDetailActionResult) -> Unit = {},
    viewModel: TaskDetailViewModel = koinViewModel { parametersOf(route) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.actionResults, onEvent = onActionDispatch)

    TaskDetailContent(
        state = state,
        onBackground = onBackground,
        onAction = viewModel::perform,
    )
}

@Composable
internal fun TaskDetailContent(
    state: TaskDetailUiState,
    onBackground: () -> Unit,
    onAction: (TaskAction) -> Unit,
    onLoggerVisible: (ServiceQueueOperation) -> Unit = { operation ->
        ServiceQueueLatencyProbe.mark(operation, ServiceQueueEvent.LOGGER_VISIBLE)
    },
) {
    val active = state.phase in ACTIVE_LOGGER_PHASES
    val latencyOperation = state.loggerLatencyOperation()
    val loggerVisibleMarked = remember(latencyOperation) { AtomicBoolean() }
    val markerModifier = if (BuildConfig.DEBUG && active && latencyOperation != null) {
        Modifier.drawWithContent {
            drawContent()
            if (loggerVisibleMarked.compareAndSet(false, true)) {
                onLoggerVisible(latencyOperation)
            }
        }
    } else {
        Modifier
    }

    Dialog(
        onDismissRequest = onBackground,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TASK_DETAIL_SCRIM_TAG)
                .pointerInput(onBackground) {
                    detectTapGestures { onBackground() }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .testTag(taskDetailRouteTag(state.taskId)),
            )
            TermLoggerContent(
                title = state.title(),
                logs = state.loggerLines(),
                status = state.loggerStatus(),
                modifier = markerModifier
                    .testTag(TASK_DETAIL_LOGGER_TAG)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
                onClose = if (TaskAction.ACKNOWLEDGE in state.actions) {
                    { onAction(TaskAction.ACKNOWLEDGE) }
                } else {
                    null
                },
            ) {
                val terminal = TaskAction.ACKNOWLEDGE in state.actions
                if (!terminal) {
                    OutlinedButton(
                        onClick = onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(TASK_DETAIL_BACKGROUND_TAG),
                    ) {
                        Text(stringResource(R.string.task_queue_background))
                    }
                }

                if (state.phase == TaskLifecyclePhase.STOPPING) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(TASK_DETAIL_STOPPING_TAG),
                    ) {
                        Text(stringResource(R.string.task_state_stopping))
                    }
                }

                TaskAction.entries.forEach { action ->
                    if (action in state.actions) {
                        Button(
                            onClick = { onAction(action) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(taskDetailActionTag(action)),
                        ) {
                            Text(stringResource(action.labelRes()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDetailUiState.title(): UiText = summary?.operationTitle()
    ?: provisionalIdentity?.operationTitle()
    ?: UiText.StringResource(phase.labelRes())

internal fun TaskDetailUiState.loggerStatus(): TermLoggerStatus = when (phase) {
    TaskLifecyclePhase.STARTING,
    TaskLifecyclePhase.QUEUED,
    TaskLifecyclePhase.RUNNING,
    TaskLifecyclePhase.STOPPING,
        -> TermLoggerStatus.ACTIVE

    TaskLifecyclePhase.SUCCEEDED -> TermLoggerStatus.SUCCESS
    else -> TermLoggerStatus.NEUTRAL
}

@Composable
private fun TaskDetailUiState.loggerLines(): List<UiText> {
    val summary = summary ?: return listOf(
        UiText.StringResource(
            when (phase) {
                TaskLifecyclePhase.STARTING -> R.string.log_initializing
                TaskLifecyclePhase.FAILED -> R.string.task_reason_failed
                else -> R.string.task_reason_observer_failure
            },
        ),
    )

    val result = mutableListOf<UiText>()
    result += UiText.StringResource(summary.phase.labelRes())
    result += UiText.StringResource(
        R.string.task_queue_progress,
        summary.progress.completed,
        summary.progress.total,
    )
    summary.progress.stageLabel?.stageText()?.let(result::add)
    for (line in lines.sortedBy(TaskLogLine::order)) {
        line.displayText()?.let(result::add)
    }
    summary.reason()?.let(result::add)
    if (summary.rootLaneDegraded) {
        result += UiText.StringResource(R.string.task_reason_root_lane_degraded)
    }
    return result
}

private fun String.stageText(): UiText? = when (this) {
    DataTaskStage.PREPARING.name -> UiText.StringResource(R.string.task_log_stage_preparing)
    DataTaskStage.STAGING_SOURCE.name -> UiText.StringResource(R.string.task_log_stage_staging_source)
    DataTaskStage.MEASURING.name -> UiText.StringResource(R.string.task_log_stage_measuring)
    DataTaskStage.CAPTURING.name -> UiText.StringResource(R.string.task_log_stage_capturing)
    DataTaskStage.WRITING.name -> UiText.StringResource(R.string.task_log_stage_writing)
    DataTaskStage.INSTALLING.name -> UiText.StringResource(R.string.task_log_stage_installing)
    DataTaskStage.RESTORING.name -> UiText.StringResource(R.string.task_log_stage_restoring)
    DataTaskStage.PUBLISHING.name -> UiText.StringResource(R.string.task_log_stage_publishing)
    DataTaskStage.FINISHING.name -> UiText.StringResource(R.string.task_log_stage_finishing)
    else -> null
}

private fun TaskLogLine.displayText(): UiText? {
    val target = arguments.firstOrNull()
    return when (messageCode) {
        "TASK_ITEM_PENDING",
        "SWEEP_TARGET_PENDING",
            -> UiText.StringResource(R.string.task_log_queued)

        "TASK_ITEM_RUNNING",
        "SWEEP_TARGET_RUNNING",
            -> target?.let { UiText.StringResource(R.string.task_log_item_running, it) }

        "TASK_ITEM_SUCCEEDED",
        "SWEEP_TARGET_SUCCEEDED",
            -> target?.let { UiText.StringResource(R.string.task_log_item_succeeded, it) }

        "TASK_ITEM_FAILED",
        "SWEEP_TARGET_FAILED",
            -> target?.let { UiText.StringResource(R.string.task_log_item_failed, it) }

        "TASK_ITEM_CANCELLED",
        "SWEEP_TARGET_CANCELLED",
            -> target?.let { UiText.StringResource(R.string.task_log_item_cancelled, it) }

        "SWEEP_TARGET_BUSY" ->
            target?.let { UiText.StringResource(R.string.task_log_item_busy, it) }

        "SWEEP_TARGET_UNKNOWN",
        "SWEEP_TARGET_LEGACY_UNKNOWN",
            -> target?.let { UiText.StringResource(R.string.task_log_item_unknown, it) }

        else -> null
    }
}

private fun QueuedTaskSummary.reason(): UiText? = when (phase) {
    TaskLifecyclePhase.QUEUED -> UiText.StringResource(R.string.task_reason_queued)
    TaskLifecyclePhase.STOPPING -> UiText.StringResource(R.string.task_reason_stopping)
    TaskLifecyclePhase.WAITING_FOR_AUTH ->
        (actionRequirement as? TaskActionRequirement.ArchiveAuthentication)?.let {
            UiText.StringResource(R.string.task_reason_waiting_for_auth, it.packageName)
        }

    TaskLifecyclePhase.WAITING_FOR_SOURCE ->
        (actionRequirement as? TaskActionRequirement.RestoreSource)?.let {
            UiText.StringResource(R.string.task_reason_waiting_for_source, it.expectedPackageName)
        }

    TaskLifecyclePhase.WAITING_FOR_PRIVILEGE ->
        UiText.StringResource(R.string.task_reason_waiting_for_privilege)

    TaskLifecyclePhase.INTERRUPTED_REVIEW -> when (val requirement = actionRequirement) {
        is TaskActionRequirement.RestoreInterruptionReview -> UiText.StringResource(
            R.string.task_reason_interrupted_restore,
            requirement.breadcrumb.appLabel,
        )

        is TaskActionRequirement.SweepRetryAuthorization -> UiText.StringResource(
            R.string.task_reason_interrupted_sweep,
            requirement.packageName,
            UiText.StringResource(requirement.operation.labelRes()),
        )

        else -> null
    }

    TaskLifecyclePhase.READY -> (actionRequirement as? TaskActionRequirement.PreparedShare)?.let {
        UiText.StringResource(R.string.task_reason_ready, it.outputIds.size)
    }

    TaskLifecyclePhase.READY_PARTIAL ->
        (actionRequirement as? TaskActionRequirement.PreparedShare)?.let {
            UiText.StringResource(
                R.string.task_reason_ready_partial,
                it.outputIds.size,
                progress.total,
            )
        }

    TaskLifecyclePhase.START_BLOCKED ->
        UiText.StringResource(R.string.task_reason_start_blocked)

    TaskLifecyclePhase.START_BLOCKED_NOTIFICATION ->
        UiText.StringResource(R.string.task_reason_start_blocked_notification)

    TaskLifecyclePhase.FAILED -> UiText.StringResource(R.string.task_reason_failed)
    TaskLifecyclePhase.EXPIRED -> UiText.StringResource(R.string.task_reason_expired)
    TaskLifecyclePhase.OBSERVER_FAILURE ->
        UiText.StringResource(R.string.task_reason_observer_failure)

    TaskLifecyclePhase.STARTING,
    TaskLifecyclePhase.RUNNING,
    TaskLifecyclePhase.SUCCEEDED,
    TaskLifecyclePhase.PARTIAL,
    TaskLifecyclePhase.CANCELLED,
        -> null
}

private fun QueuedTaskSummary.operationTitle(): UiText =
    operationTitle(queueKind, operationId)

private fun ProvisionalTaskIdentity.operationTitle(): UiText =
    operationTitle(queueKind, operationId)

private fun operationTitle(queueKind: TaskQueueKind, operationId: String): UiText =
    UiText.StringResource(
        when (operationId) {
            DataTaskKind.ARCHIVE_BACKUP.name -> R.string.task_operation_archive_backup
            DataTaskKind.ARCHIVE_RESTORE.name -> R.string.task_operation_archive_restore
            DataTaskKind.APP_EXPORT.name -> R.string.task_operation_app_export
            DataTaskKind.SHARE_PREPARE.name -> R.string.task_operation_share_prepare
            PrivilegeSweepOperation.FREEZE.name -> R.string.task_operation_freeze
            PrivilegeSweepOperation.UNFREEZE.name -> R.string.task_operation_unfreeze
            PrivilegeSweepOperation.CLEAR_CACHE.name -> R.string.task_operation_clear_cache
            PrivilegeSweepOperation.REINSTALL.name -> R.string.task_operation_reinstall
            else -> when (queueKind) {
                TaskQueueKind.DATA -> R.string.task_queue_kind_data
                TaskQueueKind.PRIVILEGE -> R.string.task_queue_kind_privilege
            }
        },
    )

@StringRes
private fun TaskLifecyclePhase.labelRes(): Int = when (this) {
    TaskLifecyclePhase.STARTING -> R.string.task_state_starting
    TaskLifecyclePhase.QUEUED -> R.string.task_state_queued
    TaskLifecyclePhase.RUNNING -> R.string.task_state_running
    TaskLifecyclePhase.STOPPING -> R.string.task_state_stopping
    TaskLifecyclePhase.WAITING_FOR_AUTH -> R.string.task_state_waiting_for_auth
    TaskLifecyclePhase.WAITING_FOR_SOURCE -> R.string.task_state_waiting_for_source
    TaskLifecyclePhase.WAITING_FOR_PRIVILEGE -> R.string.task_state_waiting_for_privilege
    TaskLifecyclePhase.INTERRUPTED_REVIEW -> R.string.task_state_interrupted_review
    TaskLifecyclePhase.READY -> R.string.task_state_ready
    TaskLifecyclePhase.READY_PARTIAL -> R.string.task_state_ready_partial
    TaskLifecyclePhase.START_BLOCKED -> R.string.task_state_start_blocked
    TaskLifecyclePhase.START_BLOCKED_NOTIFICATION ->
        R.string.task_state_start_blocked_notification

    TaskLifecyclePhase.SUCCEEDED -> R.string.task_state_succeeded
    TaskLifecyclePhase.PARTIAL -> R.string.task_state_partial
    TaskLifecyclePhase.FAILED -> R.string.task_state_failed
    TaskLifecyclePhase.CANCELLED -> R.string.task_state_cancelled
    TaskLifecyclePhase.EXPIRED -> R.string.task_state_expired
    TaskLifecyclePhase.OBSERVER_FAILURE -> R.string.task_state_observer_failure
}

@StringRes
private fun TaskAction.labelRes(): Int = when (this) {
    TaskAction.CANCEL -> R.string.task_queue_cancel
    TaskAction.AUTHENTICATE_ARCHIVE -> R.string.task_action_authenticate_archive
    TaskAction.PROVIDE_SOURCE -> R.string.task_action_provide_source
    TaskAction.REVIEW_RESTORE -> R.string.task_action_review_restore
    TaskAction.AUTHORIZE_PRIVILEGE -> R.string.task_action_authorize_privilege
    TaskAction.AUTHORIZE_SWEEP_RETRY -> R.string.task_action_authorize_retry
    TaskAction.RETRY -> R.string.task_action_retry
    TaskAction.RESUME -> R.string.task_action_resume
    TaskAction.SHARE -> R.string.task_action_share
    TaskAction.OPEN_NOTIFICATION_SETTINGS -> R.string.task_action_open_notification_settings
    TaskAction.ACKNOWLEDGE -> R.string.task_queue_close
}

@StringRes
private fun PrivilegeSweepOperation.labelRes(): Int = when (this) {
    PrivilegeSweepOperation.FREEZE -> R.string.task_operation_freeze
    PrivilegeSweepOperation.UNFREEZE -> R.string.task_operation_unfreeze
    PrivilegeSweepOperation.CLEAR_CACHE -> R.string.task_operation_clear_cache
    PrivilegeSweepOperation.REINSTALL -> R.string.task_operation_reinstall
}

private fun QueuedTaskSummary.latencyOperation(): ServiceQueueOperation? =
    latencyOperation(queueKind, operationId)

private fun ProvisionalTaskIdentity.latencyOperation(): ServiceQueueOperation? =
    latencyOperation(queueKind, operationId)

private fun latencyOperation(
    queueKind: TaskQueueKind,
    operationId: String,
): ServiceQueueOperation? = when {
    queueKind == TaskQueueKind.PRIVILEGE -> ServiceQueueOperation.PRIVILEGE_SWEEP
    operationId == DataTaskKind.APP_EXPORT.name -> ServiceQueueOperation.EXPORT
    else -> null
}

internal fun TaskDetailUiState.loggerLatencyOperation(): ServiceQueueOperation? =
    if (phase in ACTIVE_LOGGER_PHASES) {
        summary?.latencyOperation() ?: provisionalIdentity?.latencyOperation()
    } else {
        null
    }

private val ACTIVE_LOGGER_PHASES = setOf(
    TaskLifecyclePhase.STARTING,
    TaskLifecyclePhase.QUEUED,
    TaskLifecyclePhase.RUNNING,
    TaskLifecyclePhase.STOPPING,
)

internal fun taskDetailRouteTag(taskId: UUID): String = "task-detail-route-$taskId"

internal const val TASK_DETAIL_SCRIM_TAG = "task-detail-scrim"
internal const val TASK_DETAIL_LOGGER_TAG = "task-detail-logger"
internal const val TASK_DETAIL_BACKGROUND_TAG = "task-detail-background"
internal const val TASK_DETAIL_STOPPING_TAG = "task-detail-stopping"

internal fun taskDetailActionTag(action: TaskAction): String =
    "task-detail-action-$action"
