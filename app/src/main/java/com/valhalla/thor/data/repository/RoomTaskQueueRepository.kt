// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.service.DATA_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.PRIVILEGED_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.TaskActionPolicy
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskLogLevel
import com.valhalla.thor.domain.model.TaskLogLine
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.TaskQueueRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

private const val MAX_PROJECTED_LINES = 64
private const val MAX_PROJECTED_WARNINGS = 4
private val STABLE_CODE = Regex("[A-Z0-9_]{1,64}")

internal interface DataTaskProjectionSource {
    fun observeRetained(): Flow<List<DataTaskSnapshot>>
    fun observe(taskId: UUID): Flow<DataTaskSnapshot?>
}

@Single(binds = [DataTaskProjectionSource::class])
internal class RoomDataTaskProjectionSource(
    private val dao: DataTaskDao,
) : DataTaskProjectionSource {
    override fun observeRetained(): Flow<List<DataTaskSnapshot>> = dao.observeRetained()
    override fun observe(taskId: UUID): Flow<DataTaskSnapshot?> = dao.observeTask(taskId.toString())
}

internal interface SweepTaskProjectionSource {
    fun observeRetained(): Flow<List<StoredPrivilegeSweep>>
    fun observe(taskId: UUID): Flow<StoredPrivilegeSweep?>
}

@Single(binds = [SweepTaskProjectionSource::class])
internal class RoomSweepTaskProjectionSource(
    private val store: PrivilegeSweepStore,
) : SweepTaskProjectionSource {
    override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = store.observeRetained()
    override fun observe(taskId: UUID): Flow<StoredPrivilegeSweep?> = store.observe(taskId)
}

/** Read-only, queue-neutral projection over the two independently ordered durable queues. */
@Single(binds = [TaskQueueRepository::class])
class RoomTaskQueueRepository internal constructor(
    private val dataTasks: DataTaskProjectionSource,
    private val sweeps: SweepTaskProjectionSource,
) : TaskQueueRepository {

    override val tasks: Flow<List<QueuedTaskSummary>> = combine(
        dataTasks.observeRetained(),
        sweeps.observeRetained(),
    ) { data, privilege ->
        // Sequences are local to their queue. Grouping the two independently sorted sections avoids
        // presenting timestamps or colliding sequence values as a cross-queue execution order.
        data.sortedWith(compareBy(DataTaskSnapshot::queueSequence, DataTaskSnapshot::taskId))
            .map(DataTaskSnapshot::toQueuedSummary) +
                privilege.sortedWith(
                    compareBy(
                        StoredPrivilegeSweep::queueSequence,
                        StoredPrivilegeSweep::requestId
                    )
                )
                    .map(StoredPrivilegeSweep::toQueuedSummary)
    }

    override fun observe(taskId: UUID): Flow<QueuedTaskDetail?> = combine(
        dataTasks.observe(taskId),
        sweeps.observe(taskId),
    ) { data, privilege ->
        when {
            data != null && privilege != null -> null
            data != null -> data.toQueuedDetail()
            privilege != null -> privilege.toQueuedDetail()
            else -> null
        }
    }
}

internal fun DataTaskSnapshot.toQueuedSummary(): QueuedTaskSummary {
    val phase = state.toLifecyclePhase()
    val requirement = actionRequirement()
    return QueuedTaskSummary(
        taskId = taskId,
        queueKind = TaskQueueKind.DATA,
        operationId = kind.name,
        titleArguments = titleArguments(),
        sequence = queueSequence,
        phase = phase,
        progress = TaskProgress(completed, total, stage?.name),
        activeItemLabel = items.firstOrNull { it.state == DataTaskItemState.RUNNING }?.packageName,
        actionRequirement = requirement,
        actions = TaskActionPolicy.actionsFor(phase, requirement),
        terminalAtEpochMs = terminalAtEpochMs,
        retainUntilEpochMs = retainUntilEpochMs,
        rootLaneDegraded = false,
    )
}

internal fun StoredPrivilegeSweep.toQueuedSummary(): QueuedTaskSummary {
    val unknown = targetSnapshots.firstOrNull {
        it.state == PrivilegeSweepTargetState.UNKNOWN ||
                it.state == PrivilegeSweepTargetState.LEGACY_UNKNOWN
    }
    val phase = when {
        requestState == PrivilegeSweepRequestState.BLOCKED && unknown != null ->
            TaskLifecyclePhase.INTERRUPTED_REVIEW

        else -> requestState.toLifecyclePhase(blockReason)
    }
    val requirement = when {
        requestState == PrivilegeSweepRequestState.BLOCKED && unknown != null ->
            TaskActionRequirement.SweepRetryAuthorization(
                unknown.ordinal,
                unknown.packageName,
                operation,
            )

        requestState == PrivilegeSweepRequestState.BLOCKED &&
                blockReason == PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED ->
            TaskActionRequirement.PrivilegeAuthorization

        requestState == PrivilegeSweepRequestState.BLOCKED &&
                blockReason == PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION ->
            TaskActionRequirement.NotificationSettings(PRIVILEGED_FOREGROUND_CHANNEL_ID)

        else -> null
    }
    return QueuedTaskSummary(
        taskId = requestId,
        queueKind = TaskQueueKind.PRIVILEGE,
        operationId = operation.name,
        titleArguments = emptyList(),
        sequence = queueSequence,
        phase = phase,
        progress = TaskProgress(
            completed = (succeeded + failed + busy).toLong(),
            total = targetSnapshots.size.toLong(),
            stageLabel = null,
        ),
        activeItemLabel = targetSnapshots.firstOrNull {
            it.state == PrivilegeSweepTargetState.RUNNING
        }?.packageName,
        actionRequirement = requirement,
        actions = TaskActionPolicy.actionsFor(phase, requirement),
        terminalAtEpochMs = terminalAtEpochMs,
        retainUntilEpochMs = retainUntilEpochMs,
        rootLaneDegraded = targetSnapshots.any { it.rootLaneDegraded },
    )
}

internal fun DataTaskSnapshot.toQueuedDetail(): QueuedTaskDetail = QueuedTaskDetail(
    summary = toQueuedSummary(),
    lines = items.filter { it.state != DataTaskItemState.PENDING }.map { item ->
        TaskLogLine(
            order = item.ordinal.toLong(),
            messageCode = "TASK_ITEM_${item.state.name}",
            arguments = listOf(item.packageName),
            level = when (item.state) {
                DataTaskItemState.SUCCEEDED -> TaskLogLevel.SUCCESS
                DataTaskItemState.FAILED -> TaskLogLevel.ERROR
                DataTaskItemState.CANCELLED -> TaskLogLevel.WARNING
                DataTaskItemState.PENDING,
                DataTaskItemState.RUNNING,
                    -> TaskLogLevel.INFO
            },
        )
    }.boundedSnapshotLines(pendingCount = items.count { it.state == DataTaskItemState.PENDING }),
    resultCode = resultCode?.value,
    warningCodes = warnings.asSequence()
        .filter(STABLE_CODE::matches)
        .distinct()
        .take(MAX_PROJECTED_WARNINGS)
        .toList(),
)

internal fun StoredPrivilegeSweep.toQueuedDetail(): QueuedTaskDetail = QueuedTaskDetail(
    summary = toQueuedSummary(),
    lines = targetSnapshots.filter { it.state != PrivilegeSweepTargetState.PENDING }.map { target ->
        TaskLogLine(
            order = target.ordinal.toLong(),
            messageCode = "SWEEP_TARGET_${target.state.name}",
            arguments = listOf(target.packageName),
            level = when (target.state) {
                PrivilegeSweepTargetState.SUCCEEDED -> TaskLogLevel.SUCCESS
                PrivilegeSweepTargetState.FAILED -> TaskLogLevel.ERROR
                PrivilegeSweepTargetState.BUSY,
                PrivilegeSweepTargetState.CANCELLED,
                PrivilegeSweepTargetState.UNKNOWN,
                PrivilegeSweepTargetState.LEGACY_UNKNOWN,
                    -> TaskLogLevel.WARNING

                PrivilegeSweepTargetState.PENDING,
                PrivilegeSweepTargetState.RUNNING,
                    -> TaskLogLevel.INFO
            },
        )
    }.boundedSnapshotLines(
        pendingCount = targetSnapshots.count { it.state == PrivilegeSweepTargetState.PENDING },
    ),
    resultCode = terminalState?.name,
    warningCodes = emptyList(),
)

/** A bounded snapshot window, not an event journal. Pending children consume only one row. */
private fun List<TaskLogLine>.boundedSnapshotLines(pendingCount: Int): List<TaskLogLine> {
    val budget = MAX_PROJECTED_LINES - if (pendingCount > 0) 1 else 0
    val sorted = sortedBy(TaskLogLine::order)
    val visible = if (size <= budget) {
        sorted
    } else {
        // A resumed low-ordinal target must not disappear behind later, already-finished targets.
        val (running, outcomes) = sorted.partition {
            it.messageCode == "TASK_ITEM_RUNNING" || it.messageCode == "SWEEP_TARGET_RUNNING"
        }
        val active = running.takeLast(budget - 1)
        (active + outcomes.takeLast(budget - 1 - active.size)).sortedBy(TaskLogLine::order)
    }
    val omitted = size - visible.size
    return buildList {
        if (omitted > 0) {
            add(TaskLogLine(0, "TASK_RESULTS_OMITTED", listOf(omitted.toString())))
        }
        addAll(visible)
        if (pendingCount > 0) {
            add(TaskLogLine(0, "TASK_PENDING_COUNT", listOf(pendingCount.toString())))
        }
    }.mapIndexed { index, line -> line.copy(order = index.toLong()) }
}

private fun DataTaskSnapshot.actionRequirement(): TaskActionRequirement? = when (state) {
    DataTaskState.WAITING_FOR_AUTH -> archivePackageName()?.let {
        TaskActionRequirement.ArchiveAuthentication(it, kind)
    }

    DataTaskState.WAITING_FOR_SOURCE -> (detail as? StoredDataTaskDetail.ArchiveRestore)?.let {
        TaskActionRequirement.RestoreSource(it.expectedPackageName)
    }

    DataTaskState.INTERRUPTED_REVIEW ->
        (detail as? StoredDataTaskDetail.ArchiveRestore)?.mutationBreadcrumb?.let {
            TaskActionRequirement.RestoreInterruptionReview(it)
        }

    DataTaskState.READY,
    DataTaskState.READY_PARTIAL,
        -> outputs.filter { it.state == DataTaskOutputState.READY }.map { it.outputId }
        .takeIf(List<UUID>::isNotEmpty)
        ?.let(TaskActionRequirement::PreparedShare)

    DataTaskState.START_BLOCKED_NOTIFICATION ->
        TaskActionRequirement.NotificationSettings(DATA_FOREGROUND_CHANNEL_ID)

    else -> null
}

private fun DataTaskSnapshot.archivePackageName(): String? = when (val stored = detail) {
    is StoredDataTaskDetail.ArchiveBackup -> stored.packageName
    is StoredDataTaskDetail.ArchiveRestore -> stored.expectedPackageName
    is StoredDataTaskDetail.AppExport,
    is StoredDataTaskDetail.SharePrepare,
        -> null
}

private fun DataTaskSnapshot.titleArguments(): List<String> =
    archivePackageName()?.let(::listOf) ?: emptyList()

private fun DataTaskState.toLifecyclePhase(): TaskLifecyclePhase = when (this) {
    DataTaskState.QUEUED,
    DataTaskState.STAGING_SOURCE,
        -> TaskLifecyclePhase.QUEUED

    DataTaskState.RUNNING -> TaskLifecyclePhase.RUNNING
    DataTaskState.CANCEL_REQUESTED -> TaskLifecyclePhase.STOPPING
    DataTaskState.WAITING_FOR_AUTH -> TaskLifecyclePhase.WAITING_FOR_AUTH
    DataTaskState.WAITING_FOR_SOURCE -> TaskLifecyclePhase.WAITING_FOR_SOURCE
    DataTaskState.INTERRUPTED_REVIEW -> TaskLifecyclePhase.INTERRUPTED_REVIEW
    DataTaskState.READY -> TaskLifecyclePhase.READY
    DataTaskState.READY_PARTIAL -> TaskLifecyclePhase.READY_PARTIAL
    DataTaskState.START_BLOCKED -> TaskLifecyclePhase.START_BLOCKED
    DataTaskState.START_BLOCKED_NOTIFICATION -> TaskLifecyclePhase.START_BLOCKED_NOTIFICATION
    DataTaskState.SUCCEEDED -> TaskLifecyclePhase.SUCCEEDED
    DataTaskState.PARTIAL -> TaskLifecyclePhase.PARTIAL
    DataTaskState.FAILED -> TaskLifecyclePhase.FAILED
    DataTaskState.CANCELLED -> TaskLifecyclePhase.CANCELLED
    DataTaskState.EXPIRED -> TaskLifecyclePhase.EXPIRED
}

private fun PrivilegeSweepRequestState.toLifecyclePhase(
    blockReason: PrivilegeSweepBlockReason?,
): TaskLifecyclePhase = when (this) {
    PrivilegeSweepRequestState.QUEUED -> TaskLifecyclePhase.QUEUED
    PrivilegeSweepRequestState.RUNNING -> TaskLifecyclePhase.RUNNING
    PrivilegeSweepRequestState.CANCEL_REQUESTED -> TaskLifecyclePhase.STOPPING
    PrivilegeSweepRequestState.BLOCKED -> when (blockReason) {
        PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED ->
            TaskLifecyclePhase.WAITING_FOR_PRIVILEGE

        PrivilegeSweepBlockReason.START_BLOCKED -> TaskLifecyclePhase.START_BLOCKED
        PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION ->
            TaskLifecyclePhase.START_BLOCKED_NOTIFICATION

        null -> TaskLifecyclePhase.INTERRUPTED_REVIEW
    }

    PrivilegeSweepRequestState.SUCCEEDED -> TaskLifecyclePhase.SUCCEEDED
    PrivilegeSweepRequestState.PARTIAL -> TaskLifecyclePhase.PARTIAL
    PrivilegeSweepRequestState.CANCELLED -> TaskLifecyclePhase.CANCELLED
    PrivilegeSweepRequestState.FAILED -> TaskLifecyclePhase.FAILED
}
