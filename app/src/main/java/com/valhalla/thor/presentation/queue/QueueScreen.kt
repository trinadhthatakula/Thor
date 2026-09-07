// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import java.util.UUID
import org.koin.androidx.compose.koinViewModel

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    onTaskSelected: (UUID) -> Unit,
    onActionDispatch: (TaskActionDispatch) -> Unit = {},
    viewModel: QueueViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.actionResults, onEvent = onActionDispatch)

    QueueContent(
        state = state,
        onBack = onBack,
        onTaskSelected = onTaskSelected,
        onAction = viewModel::perform,
    )
}

@Composable
internal fun QueueContent(
    state: QueueUiState,
    onBack: () -> Unit,
    onTaskSelected: (UUID) -> Unit,
    onAction: (UUID, TaskAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.task_queue_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.task_queue_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(QUEUE_BACK_TAG),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                windowInsets = WindowInsets(0,0,0,0)
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.observationUnavailable) {
            Text(
                text = stringResource(R.string.task_reason_observer_failure),
                modifier = Modifier.padding(padding).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            QueueList(
                state = state,
                onTaskSelected = onTaskSelected,
                onAction = onAction,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            )
        }
    }
}

@Composable
private fun QueueList(
    state: QueueUiState,
    onTaskSelected: (UUID) -> Unit,
    onAction: (UUID, TaskAction) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "guardian-providers") {
            GuardianRoster()
        }
        if (state.isEmpty) {
            item(key = "queue-empty") {
                Text(
                    text = stringResource(R.string.task_queue_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        queueSection(
            titleRes = R.string.task_queue_section_running,
            emptyRes = R.string.task_queue_empty_running,
            data = listOfNotNull(state.running.data),
            privilege = listOfNotNull(state.running.privilege),
            onTaskSelected = onTaskSelected,
            onAction = onAction,
        )
        queueSection(
            titleRes = R.string.task_queue_section_queued,
            emptyRes = R.string.task_queue_empty_queued,
            data = state.queued.data,
            privilege = state.queued.privilege,
            onTaskSelected = onTaskSelected,
            onAction = onAction,
        )
        queueSection(
            titleRes = R.string.task_queue_section_recent,
            emptyRes = R.string.task_queue_empty_recent,
            data = state.recent.data,
            privilege = state.recent.privilege,
            onTaskSelected = onTaskSelected,
            onAction = onAction,
        )
    }
}

private fun LazyListScope.queueSection(
    @StringRes titleRes: Int,
    @StringRes emptyRes: Int,
    data: List<QueuedTaskSummary>,
    privilege: List<QueuedTaskSummary>,
    onTaskSelected: (UUID) -> Unit,
    onAction: (UUID, TaskAction) -> Unit,
) {
    item(key = "section-$titleRes") {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 10.dp)
                .semantics { heading() },
        )
    }

    if (data.isEmpty() && privilege.isEmpty()) {
        item(key = "empty-$titleRes") {
            Text(
                text = stringResource(emptyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    queueLane(
        sectionKey = titleRes,
        queueKind = TaskQueueKind.DATA,
        tasks = data,
        onTaskSelected = onTaskSelected,
        onAction = onAction,
    )
    queueLane(
        sectionKey = titleRes,
        queueKind = TaskQueueKind.PRIVILEGE,
        tasks = privilege,
        onTaskSelected = onTaskSelected,
        onAction = onAction,
    )
}

private fun LazyListScope.queueLane(
    sectionKey: Int,
    queueKind: TaskQueueKind,
    tasks: List<QueuedTaskSummary>,
    onTaskSelected: (UUID) -> Unit,
    onAction: (UUID, TaskAction) -> Unit,
) {
    if (tasks.isEmpty()) return

    item(key = "lane-$sectionKey-$queueKind") {
        Text(
            text = stringResource(queueKind.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    items(
        items = tasks,
        key = { task -> "${task.queueKind}:${task.taskId}" },
    ) { task ->
        QueueTaskRow(
            task = task,
            onSelected = { onTaskSelected(task.taskId) },
            onAction = { action -> onAction(task.taskId, action) },
        )
    }
}

@Composable
private fun QueueTaskRow(
    task: QueuedTaskSummary,
    onSelected: () -> Unit,
    onAction: (TaskAction) -> Unit,
) {
    val actions = task.actions - TaskAction.ACKNOWLEDGE
    Card(
        onClick = onSelected,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(queueRowTag(task.taskId)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(task.operationLabelRes()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = task.itemSummary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(task.phase.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.task_queue_progress,
                        task.progress.completed,
                        task.progress.total,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(task.queueKind.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.rootLaneDegraded) {
                Text(
                    text = stringResource(R.string.task_reason_root_lane_degraded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TaskAction.entries.forEach { action ->
                        if (action in actions) {
                            TextButton(
                                onClick = { onAction(action) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag(queueActionTag(task.taskId, action)),
                            ) {
                                Text(stringResource(action.labelRes()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedTaskSummary.itemSummary(): String =
    titleArguments.firstOrNull { it.isNotBlank() }
        ?: activeItemLabel?.takeIf { it.isNotBlank() }
        ?: pluralStringResource(
            R.plurals.profile_app_count,
            progress.total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            progress.total,
        )

@StringRes
private fun QueuedTaskSummary.operationLabelRes(): Int = when (operationId) {
    "ARCHIVE_BACKUP" -> R.string.task_operation_archive_backup
    "ARCHIVE_RESTORE" -> R.string.task_operation_archive_restore
    "APP_EXPORT" -> R.string.task_operation_app_export
    "SHARE_PREPARE" -> R.string.task_operation_share_prepare
    "FREEZE" -> R.string.task_operation_freeze
    "UNFREEZE" -> R.string.task_operation_unfreeze
    "CLEAR_CACHE" -> R.string.task_operation_clear_cache
    "REINSTALL" -> R.string.task_operation_reinstall
    else -> queueKind.labelRes()
}

@StringRes
private fun TaskQueueKind.labelRes(): Int = when (this) {
    TaskQueueKind.DATA -> R.string.task_queue_kind_data
    TaskQueueKind.PRIVILEGE -> R.string.task_queue_kind_privilege
}

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

internal const val QUEUE_BACK_TAG = "queue-back"
internal fun queueRowTag(taskId: UUID): String = "queue-row-$taskId"
internal fun queueActionTag(taskId: UUID, action: TaskAction): String =
    "queue-action-$taskId-$action"
