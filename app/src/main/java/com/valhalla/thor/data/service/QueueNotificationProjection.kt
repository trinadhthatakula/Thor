// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.content.Context
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class QueueNotificationSnapshot(
    val task: QueuedTaskSummary,
    val laterQueuedTasks: Int,
)

/** Uses the same Room projection as Queue; sequences never compare across queues. */
internal fun queueNotificationSnapshot(
    activeId: UUID?,
    tasks: List<QueuedTaskSummary>,
): QueueNotificationSnapshot? {
    val active = tasks.singleOrNull { it.taskId == activeId } ?: return null
    if (active.phase != TaskLifecyclePhase.RUNNING && active.phase != TaskLifecyclePhase.STOPPING) {
        return null
    }
    return QueueNotificationSnapshot(
        task = active,
        laterQueuedTasks = tasks.count {
            it.taskId != active.taskId && it.queueKind == active.queueKind &&
                it.phase == TaskLifecyclePhase.QUEUED
        },
    )
}

/** One aggregate observer per service. Slow notification IPC never backpressures Room writers. */
internal fun CoroutineScope.observeQueueNotifications(
    activeId: StateFlow<UUID?>,
    tasks: Flow<List<QueuedTaskSummary>>,
    publish: (QueueNotificationSnapshot) -> Unit,
): Job = launch {
    combine(activeId, tasks, ::queueNotificationSnapshot)
        .distinctUntilChanged()
        .conflate()
        .catch { /* Keep the last known notification; a failed read must not select another task. */ }
        .collect { snapshot ->
            if (snapshot != null && activeId.value == snapshot.task.taskId) publish(snapshot)
            delay(1_000L)
        }
}

internal fun Context.queueNotificationStatus(snapshot: QueueNotificationSnapshot): String {
    val operation = when (snapshot.task.operationId) {
        "ARCHIVE_BACKUP" -> R.string.task_operation_archive_backup
        "ARCHIVE_RESTORE" -> R.string.task_operation_archive_restore
        "APP_EXPORT" -> R.string.task_operation_app_export
        "SHARE_PREPARE" -> R.string.task_operation_share_prepare
        "FREEZE" -> R.string.task_operation_freeze
        "UNFREEZE" -> R.string.task_operation_unfreeze
        "CLEAR_CACHE" -> R.string.task_operation_clear_cache
        "REINSTALL" -> R.string.task_operation_reinstall
        else -> R.string.task_queue_title
    }
    val progress = getString(
        R.string.task_queue_notification_status,
        getString(operation), snapshot.task.progress.completed, snapshot.task.progress.total,
    )
    return if (snapshot.task.phase == TaskLifecyclePhase.STOPPING) {
        "${getString(R.string.task_state_stopping)} · $progress"
    } else progress
}
