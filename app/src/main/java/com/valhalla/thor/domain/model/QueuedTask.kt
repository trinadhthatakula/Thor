// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID

enum class TaskQueueKind { DATA, PRIVILEGE }

enum class TaskLifecyclePhase {
    STARTING,
    QUEUED,
    RUNNING,
    STOPPING,
    WAITING_FOR_AUTH,
    WAITING_FOR_SOURCE,
    WAITING_FOR_PRIVILEGE,
    INTERRUPTED_REVIEW,
    READY,
    READY_PARTIAL,
    START_BLOCKED,
    START_BLOCKED_NOTIFICATION,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    OBSERVER_FAILURE;

    val isPersistable: Boolean
        get() = this != STARTING && this != OBSERVER_FAILURE
}

enum class TaskAction {
    CANCEL,
    AUTHENTICATE_ARCHIVE,
    PROVIDE_SOURCE,
    REVIEW_RESTORE,
    AUTHORIZE_PRIVILEGE,
    AUTHORIZE_SWEEP_RETRY,
    RETRY,
    RESUME,
    SHARE,
    OPEN_NOTIFICATION_SETTINGS,
    ACKNOWLEDGE,
}

sealed interface TaskActionRequirement {
    data class ArchiveAuthentication(
        val packageName: String,
        val kind: DataTaskKind,
    ) : TaskActionRequirement

    data class RestoreSource(val expectedPackageName: String) : TaskActionRequirement

    data class RestoreInterruptionReview(
        val breadcrumb: RestoreMutationBreadcrumb,
    ) : TaskActionRequirement

    data object PrivilegeAuthorization : TaskActionRequirement

    data class SweepRetryAuthorization(
        val targetOrdinal: Int,
        val packageName: String,
        val operation: PrivilegeSweepOperation,
    ) : TaskActionRequirement

    data class PreparedShare(val outputIds: List<UUID>) : TaskActionRequirement

    data class NotificationSettings(val channelId: String) : TaskActionRequirement
}

object TaskActionPolicy {
    fun actionsFor(
        phase: TaskLifecyclePhase,
        requirement: TaskActionRequirement?,
    ): Set<TaskAction> = when (phase) {
        TaskLifecyclePhase.QUEUED,
        TaskLifecyclePhase.RUNNING,
            -> setOf(TaskAction.CANCEL)

        TaskLifecyclePhase.WAITING_FOR_AUTH -> when (requirement) {
            is TaskActionRequirement.ArchiveAuthentication ->
                setOf(TaskAction.AUTHENTICATE_ARCHIVE)

            else -> emptySet()
        }

        TaskLifecyclePhase.WAITING_FOR_SOURCE -> when (requirement) {
            is TaskActionRequirement.RestoreSource -> setOf(TaskAction.PROVIDE_SOURCE)
            else -> emptySet()
        }

        TaskLifecyclePhase.WAITING_FOR_PRIVILEGE -> when (requirement) {
            TaskActionRequirement.PrivilegeAuthorization ->
                setOf(TaskAction.AUTHORIZE_PRIVILEGE)

            else -> emptySet()
        }

        TaskLifecyclePhase.INTERRUPTED_REVIEW -> when (requirement) {
            is TaskActionRequirement.RestoreInterruptionReview ->
                setOf(TaskAction.REVIEW_RESTORE)

            is TaskActionRequirement.SweepRetryAuthorization ->
                setOf(TaskAction.AUTHORIZE_SWEEP_RETRY)

            else -> emptySet()
        }

        TaskLifecyclePhase.READY,
        TaskLifecyclePhase.READY_PARTIAL,
            -> when (requirement) {
            is TaskActionRequirement.PreparedShare -> setOf(TaskAction.SHARE)
            else -> emptySet()
        }

        TaskLifecyclePhase.START_BLOCKED -> setOf(TaskAction.RETRY)
        TaskLifecyclePhase.START_BLOCKED_NOTIFICATION -> when (requirement) {
            is TaskActionRequirement.NotificationSettings ->
                setOf(TaskAction.OPEN_NOTIFICATION_SETTINGS)

            else -> emptySet()
        }

        TaskLifecyclePhase.SUCCEEDED,
        TaskLifecyclePhase.PARTIAL,
        TaskLifecyclePhase.FAILED,
        TaskLifecyclePhase.CANCELLED,
        TaskLifecyclePhase.EXPIRED,
            -> setOf(TaskAction.ACKNOWLEDGE)

        TaskLifecyclePhase.STARTING,
        TaskLifecyclePhase.STOPPING,
        TaskLifecyclePhase.OBSERVER_FAILURE,
            -> emptySet()
    }
}

data class TaskProgress(
    val completed: Long,
    val total: Long,
    val stageLabel: String?,
)

data class QueuedTaskSummary(
    val taskId: UUID,
    val queueKind: TaskQueueKind,
    val operationId: String,
    val titleArguments: List<String>,
    val sequence: Long,
    val phase: TaskLifecyclePhase,
    val progress: TaskProgress,
    val activeItemLabel: String?,
    val actionRequirement: TaskActionRequirement?,
    val actions: Set<TaskAction>,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
    val rootLaneDegraded: Boolean,
)

enum class TaskLogLevel { INFO, SUCCESS, WARNING, ERROR }

data class TaskLogLine(
    val order: Long,
    val messageCode: String,
    val arguments: List<String> = emptyList(),
    val level: TaskLogLevel = TaskLogLevel.INFO,
)

data class QueuedTaskDetail(
    val summary: QueuedTaskSummary,
    val lines: List<TaskLogLine>,
    val resultCode: String?,
    val warningCodes: List<String>,
)
