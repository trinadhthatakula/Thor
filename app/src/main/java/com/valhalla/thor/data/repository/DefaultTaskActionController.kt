// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.job.ArchiveKeyHolder
import com.valhalla.thor.data.backup.job.DataQueueWakeSignal
import com.valhalla.thor.data.backup.job.DataTaskCancellationCoordinator
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.backup.job.RestoreSourceGrantHolder
import com.valhalla.thor.data.backup.job.RestoreSourceStager
import com.valhalla.thor.data.freezer.PrivilegeQueueWakeSignal
import com.valhalla.thor.data.freezer.PrivilegeSweepCancellationCoordinator
import com.valhalla.thor.data.freezer.PrivilegeSweepClock
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.domain.repository.TaskUiRoute
import com.valhalla.thor.domain.usecase.ArchiveAuthenticationOutcome
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

private const val DATA_HANDOFF_LOCK_STRIPES = 16

internal interface DataTaskActionPort {
    suspend fun load(taskId: UUID): DataTaskSnapshot?
    suspend fun cancel(taskId: UUID)
    suspend fun acknowledge(taskId: UUID): Boolean
    suspend fun prepareArchiveKey(task: DataTaskSnapshot, passphrase: CharArray): String?
    fun dropArchiveKey(taskId: UUID, token: String)
    fun authorizeRestoreSourceToken(taskId: UUID, token: UUID): Boolean
    fun dropUnacceptedRestoreSourceToken(taskId: UUID, token: UUID)
    fun dropRestoreSourceToken(taskId: UUID, token: UUID)
    suspend fun resume(
        taskId: UUID,
        expectedState: DataTaskState,
        expectedInterruption: DataTaskInterruption,
        sourceToken: UUID? = null,
    ): Boolean

    fun wake(taskId: UUID): ServiceStartResult
    suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean): Boolean
}

@Single(binds = [DataTaskActionPort::class])
internal class RoomDataTaskActionPort(
    dao: DataTaskDao,
    private val cancellation: DataTaskCancellationCoordinator,
    private val cipher: AppArchiveCipher,
    private val keys: ArchiveKeyHolder,
    private val restoreSources: RestoreSourceGrantHolder,
    private val restoreSourceStager: RestoreSourceStager,
    private val archiveSources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val wakeSignal: DataQueueWakeSignal,
) : DataTaskActionPort {
    private val store = DataTaskStore(dao)

    override suspend fun load(taskId: UUID): DataTaskSnapshot? = store.loadTask(taskId)

    override suspend fun cancel(taskId: UUID) {
        cancellation.cancel(taskId)
    }

    override suspend fun acknowledge(taskId: UUID): Boolean =
        store.acknowledgeTerminalTask(taskId, System.currentTimeMillis()) != null

    override suspend fun prepareArchiveKey(
        task: DataTaskSnapshot,
        passphrase: CharArray,
    ): String? = when (val detail = task.detail) {
        is StoredDataTaskDetail.ArchiveBackup -> prepareBackupKey(task.taskId, detail, passphrase)
        is StoredDataTaskDetail.ArchiveRestore -> prepareRestoreKey(task.taskId, detail, passphrase)
        is StoredDataTaskDetail.AppExport,
        is StoredDataTaskDetail.SharePrepare,
            -> null
    }

    override fun dropArchiveKey(taskId: UUID, token: String) {
        keys.drop(taskId.toString(), token)
    }

    override fun authorizeRestoreSourceToken(taskId: UUID, token: UUID): Boolean =
        restoreSources.authorize(taskId, token.toString())

    override fun dropUnacceptedRestoreSourceToken(taskId: UUID, token: UUID) {
        restoreSources.dropIfUnauthorized(taskId, token.toString())
    }

    override fun dropRestoreSourceToken(taskId: UUID, token: UUID) {
        restoreSources.drop(taskId, token.toString())
    }

    override suspend fun resume(
        taskId: UUID,
        expectedState: DataTaskState,
        expectedInterruption: DataTaskInterruption,
        sourceToken: UUID?,
    ): Boolean {
        if (sourceToken != null &&
            restoreSources.currentToken(taskId) != sourceToken.toString()
        ) {
            return false
        }
        return store.resumeFromUserAction(
            taskId = taskId,
            expectedState = expectedState,
            expectedInterruption = expectedInterruption,
            nowMs = System.currentTimeMillis(),
        )
    }

    override fun wake(taskId: UUID): ServiceStartResult = wakeSignal.wake(taskId)

    override suspend fun markStartBlocked(
        taskId: UUID,
        notificationBlocked: Boolean,
    ): Boolean {
        val snapshot = store.loadTask(taskId) ?: return false
        if (snapshot.state != DataTaskState.QUEUED && snapshot.state != DataTaskState.STAGING_SOURCE) {
            return false
        }
        return store.compareAndSetStartBlocked(
            taskId = taskId,
            expectedState = snapshot.state,
            blockedState = if (notificationBlocked) {
                DataTaskState.START_BLOCKED_NOTIFICATION
            } else {
                DataTaskState.START_BLOCKED
            },
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun prepareBackupKey(
        taskId: UUID,
        detail: StoredDataTaskDetail.ArchiveBackup,
        passphrase: CharArray,
    ): String? {
        val key = runCatching {
            cipher.deriveKey(
                passphrase,
                Base64.getDecoder().decode(detail.kdfSaltBase64),
                KDF_ITERATIONS,
            )
        }.getOrNull() ?: return null
        return keys.put(taskId.toString(), key)
    }

    private suspend fun prepareRestoreKey(
        taskId: UUID,
        detail: StoredDataTaskDetail.ArchiveRestore,
        passphrase: CharArray,
    ): String? {
        val uri = restoreSourceStager.resolveStoredSourceUri(taskId, detail.source) ?: return null
        val opened = archiveSources.open(uri) as? ArchiveOpenOutcome.Opened ?: return null
        val authenticated = try {
            openArchive.authenticate(opened.source, passphrase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            opened.source.close()
        }
        val key =
            (authenticated as? ArchiveAuthenticationOutcome.Authenticated)?.key ?: return null
        return keys.put(taskId.toString(), key)
    }
}

internal interface SweepTaskActionPort {
    suspend fun load(taskId: UUID): StoredPrivilegeSweep?
    suspend fun cancel(taskId: UUID)
    suspend fun acknowledge(taskId: UUID): Boolean
    suspend fun refreshPrivilegeAndRead(): Boolean
    suspend fun resumeBlocked(taskId: UUID, expectedReason: PrivilegeSweepBlockReason): Boolean
    suspend fun authorizeTargetRetry(taskId: UUID, ordinal: Int): Boolean
    fun wake(taskId: UUID): ServiceStartResult
    suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean): Boolean
}

@Single(binds = [SweepTaskActionPort::class])
internal class RoomSweepTaskActionPort(
    private val store: PrivilegeSweepStore,
    private val cancellation: PrivilegeSweepCancellationCoordinator,
    private val privilegeManager: PrivilegeManager,
    private val wakeSignal: PrivilegeQueueWakeSignal,
    private val clock: PrivilegeSweepClock,
) : SweepTaskActionPort {
    override suspend fun load(taskId: UUID): StoredPrivilegeSweep? = store.load(taskId)

    override suspend fun cancel(taskId: UUID) {
        cancellation.cancel(taskId)
    }

    override suspend fun acknowledge(taskId: UUID): Boolean =
        store.acknowledgeTerminalRequest(taskId, clock.nowMs()) != null

    override suspend fun refreshPrivilegeAndRead(): Boolean =
        privilegeManager.refreshAndAwait().hasAnyPrivilege

    override suspend fun resumeBlocked(
        taskId: UUID,
        expectedReason: PrivilegeSweepBlockReason,
    ): Boolean = store.resumeBlockedRequest(taskId, expectedReason, clock.nowMs())

    override suspend fun authorizeTargetRetry(taskId: UUID, ordinal: Int): Boolean =
        store.authorizeUnknownTargetRetry(taskId, ordinal, clock.nowMs())

    override fun wake(taskId: UUID): ServiceStartResult = wakeSignal.wake(taskId)

    override suspend fun markStartBlocked(
        taskId: UUID,
        notificationBlocked: Boolean,
    ): Boolean = store.markUnclaimedStartBlocked(
        requestId = taskId,
        reason = if (notificationBlocked) {
            PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION
        } else {
            PrivilegeSweepBlockReason.START_BLOCKED
        },
        nowMs = clock.nowMs(),
    )
}

/** Routes an exact displayed request through its owning queue's persistence-first action port. */
@Single(binds = [TaskActionController::class])
class DefaultTaskActionController internal constructor(
    private val data: DataTaskActionPort,
    private val sweeps: SweepTaskActionPort,
) : TaskActionController {
    private val dataHandoffLocks = Array(DATA_HANDOFF_LOCK_STRIPES) { Mutex() }

    override suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch =
        when (val subject = resolve(taskId)) {
            Subject.NotFound -> rejected(TaskActionRejection.NOT_FOUND)
            Subject.Ambiguous -> rejected(TaskActionRejection.STALE_PROJECTION)
            is Subject.Data -> performData(subject.snapshot, action)
            is Subject.Sweep -> performSweep(subject.snapshot, action)
        }

    override suspend fun submitArchivePassphrase(
        taskId: UUID,
        passphrase: CharArray,
    ): TaskActionDispatch = dataHandoffLock(taskId).withLock {
        val subject = resolve(taskId)
        if (subject !is Subject.Data) return@withLock subject.rejection()
        val task = subject.snapshot
        if (task.state != DataTaskState.WAITING_FOR_AUTH ||
            task.interruption != DataTaskInterruption.AUTHENTICATION_REQUIRED
        ) {
            return@withLock rejected(TaskActionRejection.INVALID_STATE)
        }
        val keyToken = data.prepareArchiveKey(task, passphrase)
            ?: return@withLock rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED)
        settleAction {
            if (!data.resume(taskId, task.state, task.interruption)) {
                data.dropArchiveKey(taskId, keyToken)
                rejected(TaskActionRejection.STALE_PROJECTION)
            } else {
                wakeData(taskId)
            }
        }
    }

    override suspend fun submitRestoreSource(
        taskId: UUID,
        transientSourceToken: UUID,
    ): TaskActionDispatch = try {
        dataHandoffLock(taskId).withLock {
            val subject = resolve(taskId)
            if (subject !is Subject.Data) return@withLock subject.rejection()
            val task = subject.snapshot
            if (task.state != DataTaskState.WAITING_FOR_SOURCE ||
                task.interruption != DataTaskInterruption.SOURCE_REQUIRED
            ) {
                return@withLock rejected(TaskActionRejection.INVALID_STATE)
            }
            settleAction {
                if (!data.authorizeRestoreSourceToken(taskId, transientSourceToken)) {
                    rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED)
                } else if (!data.resume(taskId, task.state, task.interruption, transientSourceToken)) {
                    data.dropRestoreSourceToken(taskId, transientSourceToken)
                    rejected(TaskActionRejection.STALE_PROJECTION)
                } else {
                    wakeData(taskId)
                }
            }
        }
    } finally {
        data.dropUnacceptedRestoreSourceToken(taskId, transientSourceToken)
    }

    override suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch {
        val subject = resolve(taskId)
        if (subject !is Subject.Sweep) return subject.rejection()
        if (subject.snapshot.blockReason != PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED) {
            return rejected(TaskActionRejection.INVALID_STATE)
        }
        if (!sweeps.refreshPrivilegeAndRead()) {
            return rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED)
        }
        return transitionAndWake(
            transition = {
                sweeps.resumeBlocked(
                    taskId,
                    PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED,
                )
            },
            wake = { wakeSweep(taskId) },
        )
    }

    override suspend fun authorizeSweepTargetRetry(
        taskId: UUID,
        targetOrdinal: Int,
    ): TaskActionDispatch {
        val subject = resolve(taskId)
        if (subject !is Subject.Sweep) return subject.rejection()
        val target = subject.snapshot.targetSnapshots.singleOrNull { it.ordinal == targetOrdinal }
        if (subject.snapshot.requestState != com.valhalla.thor.domain.repository.PrivilegeSweepRequestState.BLOCKED ||
            target == null ||
            (target.state != PrivilegeSweepTargetState.UNKNOWN &&
                    target.state != PrivilegeSweepTargetState.LEGACY_UNKNOWN)
        ) {
            return rejected(TaskActionRejection.INVALID_STATE)
        }
        return transitionAndWake(
            transition = { sweeps.authorizeTargetRetry(taskId, targetOrdinal) },
            wake = { wakeSweep(taskId) },
        )
    }

    private suspend fun performData(
        task: DataTaskSnapshot,
        action: TaskAction,
    ): TaskActionDispatch {
        val summary = task.toQueuedSummary()
        if (action == TaskAction.SHARE && task.state == DataTaskState.EXPIRED) {
            return rejected(TaskActionRejection.OUTPUT_EXPIRED)
        }
        if (action == TaskAction.RESUME) {
            if (task.state != DataTaskState.INTERRUPTED_REVIEW ||
                task.interruption != DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW ||
                summary.actionRequirement !is TaskActionRequirement.RestoreInterruptionReview
            ) {
                return rejected(TaskActionRejection.INVALID_STATE)
            }
            return transitionAndWake(
                transition = { data.resume(task.taskId, task.state, task.interruption) },
                wake = { wakeData(task.taskId) },
            )
        }
        if (action !in summary.actions) return rejected(TaskActionRejection.INVALID_STATE)
        return when (action) {
            TaskAction.CANCEL -> {
                data.cancel(task.taskId)
                TaskActionDispatch.Applied
            }

            TaskAction.AUTHENTICATE_ARCHIVE -> requirementRoute(
                task.taskId,
                summary.actionRequirement
            )

            TaskAction.PROVIDE_SOURCE -> requirementRoute(task.taskId, summary.actionRequirement)
            TaskAction.REVIEW_RESTORE -> requirementRoute(task.taskId, summary.actionRequirement)
            TaskAction.RETRY -> transitionAndWake(
                transition = { data.resume(task.taskId, task.state, task.interruption) },
                wake = { wakeData(task.taskId) },
            )

            TaskAction.SHARE -> requirementRoute(task.taskId, summary.actionRequirement)
            TaskAction.OPEN_NOTIFICATION_SETTINGS -> requirementRoute(
                task.taskId,
                summary.actionRequirement
            )

            TaskAction.ACKNOWLEDGE -> if (data.acknowledge(task.taskId)) {
                TaskActionDispatch.Applied
            } else {
                rejected(TaskActionRejection.STALE_PROJECTION)
            }

            TaskAction.AUTHORIZE_PRIVILEGE,
            TaskAction.AUTHORIZE_SWEEP_RETRY,
            TaskAction.RESUME,
                -> rejected(TaskActionRejection.INVALID_STATE)
        }
    }

    private suspend fun performSweep(
        task: StoredPrivilegeSweep,
        action: TaskAction,
    ): TaskActionDispatch {
        val summary = task.toQueuedSummary()
        if (action !in summary.actions) return rejected(TaskActionRejection.INVALID_STATE)
        return when (action) {
            TaskAction.CANCEL -> {
                sweeps.cancel(task.requestId)
                TaskActionDispatch.Applied
            }

            TaskAction.AUTHORIZE_PRIVILEGE,
            TaskAction.AUTHORIZE_SWEEP_RETRY,
            TaskAction.OPEN_NOTIFICATION_SETTINGS,
                -> requirementRoute(task.requestId, summary.actionRequirement)

            TaskAction.RETRY -> transitionAndWake(
                transition = {
                    sweeps.resumeBlocked(task.requestId, PrivilegeSweepBlockReason.START_BLOCKED)
                },
                wake = { wakeSweep(task.requestId) },
            )

            TaskAction.ACKNOWLEDGE -> if (sweeps.acknowledge(task.requestId)) {
                TaskActionDispatch.Applied
            } else {
                rejected(TaskActionRejection.STALE_PROJECTION)
            }

            TaskAction.AUTHENTICATE_ARCHIVE,
            TaskAction.PROVIDE_SOURCE,
            TaskAction.REVIEW_RESTORE,
            TaskAction.RESUME,
            TaskAction.SHARE,
                -> rejected(TaskActionRejection.INVALID_STATE)
        }
    }

    private fun dataHandoffLock(taskId: UUID): Mutex =
        dataHandoffLocks[Math.floorMod(taskId.hashCode(), dataHandoffLocks.size)]

    private suspend fun resolve(taskId: UUID): Subject {
        val dataTask = data.load(taskId)
        val sweep = sweeps.load(taskId)
        return when {
            dataTask != null && sweep != null -> Subject.Ambiguous
            dataTask != null -> Subject.Data(dataTask)
            sweep != null -> Subject.Sweep(sweep)
            else -> Subject.NotFound
        }
    }

    private suspend fun transitionAndWake(
        transition: suspend () -> Boolean,
        wake: suspend () -> TaskActionDispatch,
    ): TaskActionDispatch = settleAction {
        if (transition()) wake() else rejected(TaskActionRejection.STALE_PROJECTION)
    }

    private suspend fun settleAction(
        block: suspend () -> TaskActionDispatch,
    ): TaskActionDispatch {
        val callerJob = currentCoroutineContext()[Job]
        val result = withContext(NonCancellable) { block() }
        callerJob?.ensureActive()
        return result
    }

    private suspend fun wakeData(taskId: UUID): TaskActionDispatch =
        wake(taskId, data.wake(taskId), data::markStartBlocked)

    private suspend fun wakeSweep(taskId: UUID): TaskActionDispatch =
        wake(taskId, sweeps.wake(taskId), sweeps::markStartBlocked)

    private suspend fun wake(
        taskId: UUID,
        result: ServiceStartResult,
        markBlocked: suspend (UUID, Boolean) -> Boolean,
    ): TaskActionDispatch = when (result) {
        ServiceStartResult.Requested,
        ServiceStartResult.AlreadyRunning,
            -> TaskActionDispatch.Applied

        is ServiceStartResult.Rejected -> {
            val persisted = markBlocked(
                taskId,
                result.reason == ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED,
            )
            if (persisted) {
                rejected(TaskActionRejection.START_REJECTED)
            } else {
                rejected(TaskActionRejection.STALE_PROJECTION)
            }
        }
    }

    private fun requirementRoute(
        taskId: UUID,
        requirement: TaskActionRequirement?,
    ): TaskActionDispatch = when (requirement) {
        is TaskActionRequirement.ArchiveAuthentication -> TaskActionDispatch.Route(
            TaskUiRoute.AuthenticateArchive(taskId, requirement.packageName, requirement.kind),
        )

        is TaskActionRequirement.RestoreSource -> TaskActionDispatch.Route(
            TaskUiRoute.PickRestoreSource(taskId, requirement.expectedPackageName),
        )

        is TaskActionRequirement.RestoreInterruptionReview -> TaskActionDispatch.Route(
            TaskUiRoute.ReviewInterruptedRestore(taskId, requirement.breadcrumb),
        )

        TaskActionRequirement.PrivilegeAuthorization -> TaskActionDispatch.Route(
            TaskUiRoute.AuthorizePrivilege(taskId),
        )

        is TaskActionRequirement.SweepRetryAuthorization -> TaskActionDispatch.Route(
            TaskUiRoute.ConfirmSweepRetry(
                taskId,
                requirement.targetOrdinal,
                requirement.packageName,
                requirement.operation,
            ),
        )

        is TaskActionRequirement.PreparedShare -> TaskActionDispatch.Route(
            TaskUiRoute.SharePreparedOutputs(taskId, requirement.outputIds),
        )

        is TaskActionRequirement.NotificationSettings -> TaskActionDispatch.Route(
            TaskUiRoute.OpenNotificationSettings(taskId, requirement.channelId),
        )

        null -> rejected(TaskActionRejection.INVALID_STATE)
    }

    private sealed interface Subject {
        data object NotFound : Subject
        data object Ambiguous : Subject
        data class Data(val snapshot: DataTaskSnapshot) : Subject
        data class Sweep(val snapshot: StoredPrivilegeSweep) : Subject
    }

    private fun Subject.rejection(): TaskActionDispatch.Rejected = when (this) {
        Subject.NotFound -> rejected(TaskActionRejection.NOT_FOUND)
        Subject.Ambiguous -> rejected(TaskActionRejection.STALE_PROJECTION)
        is Subject.Data,
        is Subject.Sweep,
            -> rejected(TaskActionRejection.INVALID_STATE)
    }

    private companion object {
        fun rejected(reason: TaskActionRejection) = TaskActionDispatch.Rejected(reason)
    }
}
