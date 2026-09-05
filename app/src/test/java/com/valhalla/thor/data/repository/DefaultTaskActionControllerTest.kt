// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.data.source.local.room.DataTaskItemSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.domain.repository.TaskUiRoute
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultTaskActionControllerTest {

    @Test
    fun `cancel targets exactly one owning queue and rejects ambiguous identity`() = runTest {
        val dataOnly = dataTask(state = DataTaskState.RUNNING)
        val duplicateId = UUID.randomUUID()
        val dataDuplicate = dataTask(taskId = duplicateId, state = DataTaskState.RUNNING)
        val sweepDuplicate =
            sweep(requestId = duplicateId, state = PrivilegeSweepRequestState.RUNNING)
        val data = FakeDataTaskActionPort(dataOnly, dataDuplicate)
        val sweeps = FakeSweepTaskActionPort(sweepDuplicate)
        val controller = DefaultTaskActionController(data, sweeps)

        assertEquals(
            TaskActionDispatch.Applied,
            controller.perform(dataOnly.taskId, TaskAction.CANCEL)
        )
        assertEquals(listOf(dataOnly.taskId), data.cancelled)
        assertTrue(sweeps.cancelled.isEmpty())

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
            controller.perform(duplicateId, TaskAction.CANCEL),
        )
        assertFalse(duplicateId in data.cancelled)
        assertFalse(duplicateId in sweeps.cancelled)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.NOT_FOUND),
            controller.perform(UUID.randomUUID(), TaskAction.CANCEL),
        )
    }

    @Test
    fun `acknowledge retains outputs and rejects action-required rows`() = runTest {
        val output = output()
        val terminal = dataTask(state = DataTaskState.SUCCEEDED, outputs = listOf(output))
        val waiting = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
        val data = FakeDataTaskActionPort(terminal, waiting)
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Applied,
            controller.perform(terminal.taskId, TaskAction.ACKNOWLEDGE)
        )
        assertEquals(listOf(terminal.taskId), data.acknowledged)
        assertEquals(listOf(output), data.load(terminal.taskId)?.outputs)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            controller.perform(waiting.taskId, TaskAction.ACKNOWLEDGE),
        )
    }

    @Test
    fun `archive authentication keeps caller passphrase transient and wakes after durable resume`() =
        runTest {
            val task = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
            val data = FakeDataTaskActionPort(task)
            val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())
            val passphrase = "correct horse battery staple".toCharArray()
            val original = passphrase.copyOf()

            assertEquals(
                TaskActionDispatch.Applied,
                controller.submitArchivePassphrase(task.taskId, passphrase)
            )

            assertArrayEquals(original, passphrase)
            assertTrue(data.authenticatedWith === passphrase)
            assertEquals(listOf(task.taskId), data.resumed)
            assertEquals(listOf(task.taskId), data.woken)
            assertTrue(data.persistedSecrets.isEmpty())
        }

    @Test
    fun `archive authentication is serialized for the same task`() = runTest {
        val task = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
        val firstPrepared = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var prepareCalls = 0
        val data = FakeDataTaskActionPort(task).apply {
            prepareArchiveKeyHandler = {
                prepareCalls += 1
                if (prepareCalls == 1) {
                    firstPrepared.complete(Unit)
                    releaseFirst.await()
                }
                true
            }
        }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        val first = async {
            controller.submitArchivePassphrase(task.taskId, "first".toCharArray())
        }
        firstPrepared.await()
        val second = async {
            controller.submitArchivePassphrase(task.taskId, "second".toCharArray())
        }
        runCurrent()

        assertEquals(1, prepareCalls)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `restore source requires the matching in-memory token`() = runTest {
        val task = dataTask(
            state = DataTaskState.WAITING_FOR_SOURCE,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            detail = restoreDetail(null),
            interruption = DataTaskInterruption.SOURCE_REQUIRED,
        )
        val token = UUID.randomUUID()
        val data = FakeDataTaskActionPort(task).apply { sourceTokens[task.taskId] = token }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED),
            controller.submitRestoreSource(task.taskId, UUID.randomUUID()),
        )
        assertTrue(data.resumed.isEmpty())

        assertEquals(TaskActionDispatch.Applied, controller.submitRestoreSource(task.taskId, token))
        assertEquals(listOf(task.taskId), data.resumed)
        assertEquals(token, data.submittedSourceTokens.single())
        assertTrue(data.persistedSecrets.isEmpty())
    }

    @Test
    fun `concurrent restore submissions cannot revoke the winning authorization`() = runTest {
        val task = dataTask(
            state = DataTaskState.WAITING_FOR_SOURCE,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            detail = restoreDetail(null),
            interruption = DataTaskInterruption.SOURCE_REQUIRED,
        )
        val token = UUID.randomUUID()
        val firstResumeEntered = CompletableDeferred<Unit>()
        val releaseFirstResume = CompletableDeferred<Unit>()
        var resumeCalls = 0
        val data = FakeDataTaskActionPort(task).apply { sourceTokens[task.taskId] = token }
        data.resumeHandler = {
            resumeCalls += 1
            firstResumeEntered.complete(Unit)
            releaseFirstResume.await()
            data.replace(
                task.copy(
                    state = DataTaskState.STAGING_SOURCE,
                    interruption = DataTaskInterruption.NONE,
                )
            )
            true
        }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        val first = async { controller.submitRestoreSource(task.taskId, token) }
        firstResumeEntered.await()
        val second = async { controller.submitRestoreSource(task.taskId, token) }
        runCurrent()

        assertEquals(1, data.authorizedSourceTokens.size)
        assertEquals(1, resumeCalls)
        releaseFirstResume.complete(Unit)

        assertEquals(TaskActionDispatch.Applied, first.await())
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            second.await(),
        )
        assertEquals(1, data.authorizedSourceTokens.size)
        assertEquals(1, resumeCalls)
        assertTrue(data.revokedSourceTokens.isEmpty())
    }

    @Test
    fun `stale restore resume revokes the exact source authorization`() = runTest {
        val task = dataTask(
            state = DataTaskState.WAITING_FOR_SOURCE,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            detail = restoreDetail(null),
            interruption = DataTaskInterruption.SOURCE_REQUIRED,
        )
        val token = UUID.randomUUID()
        val data = FakeDataTaskActionPort(task).apply {
            sourceTokens[task.taskId] = token
            resumeHandler = { false }
        }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
            controller.submitRestoreSource(task.taskId, token),
        )
        assertEquals(listOf(task.taskId to token), data.revokedSourceTokens)
        assertTrue(data.woken.isEmpty())
    }

    @Test
    fun `interrupted restore routes to review before explicit resume`() = runTest {
        val breadcrumb = RestoreMutationBreadcrumb("app.restore", "Restore", 99L)
        val task = dataTask(
            state = DataTaskState.INTERRUPTED_REVIEW,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            detail = restoreDetail(breadcrumb),
            interruption = DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
        )
        val data = FakeDataTaskActionPort(task)
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Route(TaskUiRoute.ReviewInterruptedRestore(task.taskId, breadcrumb)),
            controller.perform(task.taskId, TaskAction.REVIEW_RESTORE),
        )
        assertTrue(data.resumed.isEmpty())

        assertEquals(TaskActionDispatch.Applied, controller.perform(task.taskId, TaskAction.RESUME))
        assertEquals(listOf(task.taskId), data.resumed)
    }

    @Test
    fun `privilege authorization refreshes before requeue and rejects stale denial`() = runTest {
        val task = sweep(
            state = PrivilegeSweepRequestState.BLOCKED,
            blockReason = PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED,
        )
        val denied = FakeSweepTaskActionPort(task).apply { refreshedPrivilege = false }
        val deniedController = DefaultTaskActionController(FakeDataTaskActionPort(), denied)

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.AUTHORIZATION_NOT_GRANTED),
            deniedController.privilegeAuthorizationReturned(task.requestId),
        )
        assertEquals(listOf("refresh"), denied.events)

        val granted = FakeSweepTaskActionPort(task).apply { refreshedPrivilege = true }
        val grantedController = DefaultTaskActionController(FakeDataTaskActionPort(), granted)
        assertEquals(
            TaskActionDispatch.Applied,
            grantedController.privilegeAuthorizationReturned(task.requestId)
        )
        assertEquals(listOf("refresh", "resume", "wake"), granted.events)
    }

    @Test
    fun `unknown sweep target requires exact target authorization`() = runTest {
        val task = sweep(
            targets = listOf(target(0, PrivilegeSweepTargetState.LEGACY_UNKNOWN)),
            state = PrivilegeSweepRequestState.BLOCKED,
        )
        val sweeps = FakeSweepTaskActionPort(task)
        val controller = DefaultTaskActionController(FakeDataTaskActionPort(), sweeps)

        assertEquals(
            TaskActionDispatch.Route(
                TaskUiRoute.ConfirmSweepRetry(
                    task.requestId,
                    0,
                    "app.sweep0",
                    PrivilegeSweepOperation.CLEAR_CACHE,
                ),
            ),
            controller.perform(task.requestId, TaskAction.AUTHORIZE_SWEEP_RETRY),
        )
        assertEquals(
            TaskActionDispatch.Applied,
            controller.authorizeSweepTargetRetry(task.requestId, 0)
        )
        assertEquals(listOf(task.requestId to 0), sweeps.authorizedTargets)
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.INVALID_STATE),
            controller.authorizeSweepTargetRetry(task.requestId, 1),
        )
    }

    @Test
    fun `share and settings return typed routes while expired output cannot share`() = runTest {
        val readyOutput = output()
        val ready = dataTask(state = DataTaskState.READY, outputs = listOf(readyOutput))
        val expired = dataTask(state = DataTaskState.EXPIRED, outputs = listOf(readyOutput))
        val blocked = dataTask(state = DataTaskState.START_BLOCKED_NOTIFICATION)
        val controller = DefaultTaskActionController(
            FakeDataTaskActionPort(ready, expired, blocked),
            FakeSweepTaskActionPort(),
        )

        assertEquals(
            TaskActionDispatch.Route(
                TaskUiRoute.SharePreparedOutputs(
                    ready.taskId,
                    listOf(readyOutput.outputId)
                )
            ),
            controller.perform(ready.taskId, TaskAction.SHARE),
        )
        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.OUTPUT_EXPIRED),
            controller.perform(expired.taskId, TaskAction.SHARE),
        )
        assertEquals(
            TaskActionDispatch.Route(
                TaskUiRoute.OpenNotificationSettings(
                    blocked.taskId,
                    "thor.jobs.data"
                )
            ),
            controller.perform(blocked.taskId, TaskAction.OPEN_NOTIFICATION_SETTINGS),
        )
    }

    @Test
    fun `rejected wake is persisted as a blocked retry`() = runTest {
        val task = dataTask(state = DataTaskState.START_BLOCKED)
        val data = FakeDataTaskActionPort(task).apply {
            wakeResult =
                ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
        }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.START_REJECTED),
            controller.perform(task.taskId, TaskAction.RETRY),
        )
        assertEquals(listOf(task.taskId to true), data.startBlocks)
    }

    @Test
    fun `cancellation after key preparation completes durable resume without dropping the key`() =
        runTest {
            val task = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
            val resumeEntered = CompletableDeferred<Unit>()
            val releaseResume = CompletableDeferred<Unit>()
            val data = FakeDataTaskActionPort(task).apply {
                resumeHandler = {
                    resumeEntered.complete(Unit)
                    releaseResume.await()
                    true
                }
            }
            val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

            val action = async {
                controller.submitArchivePassphrase(task.taskId, "secret".toCharArray())
            }
            resumeEntered.await()
            action.cancel()
            runCurrent()
            assertFalse(action.isCompleted)
            releaseResume.complete(Unit)

            assertCancelled { action.await() }
            assertEquals(listOf(task.taskId), data.resumed)
            assertEquals(listOf(task.taskId), data.woken)
            assertTrue(data.droppedKeyTokens.isEmpty())
        }

    @Test
    fun `stale archive resume drops only the prepared key generation`() = runTest {
        val task = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
        val data = FakeDataTaskActionPort(task).apply { resumeHandler = { false } }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        assertEquals(
            TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION),
            controller.submitArchivePassphrase(task.taskId, "secret".toCharArray()),
        )
        assertEquals(listOf(task.taskId to "key-token"), data.droppedKeyTokens)
        assertTrue(data.woken.isEmpty())
    }

    @Test
    fun `cancellation cannot interrupt rejected wake settlement`() = runTest {
        val task = dataTask(state = DataTaskState.START_BLOCKED)
        val settlementEntered = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()
        val data = FakeDataTaskActionPort(task).apply {
            wakeResult =
                ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
            markStartBlockedHandler = {
                settlementEntered.complete(Unit)
                releaseSettlement.await()
                true
            }
        }
        val controller = DefaultTaskActionController(data, FakeSweepTaskActionPort())

        val action = async { controller.perform(task.taskId, TaskAction.RETRY) }
        settlementEntered.await()
        action.cancel()
        runCurrent()
        assertFalse(action.isCompleted)
        releaseSettlement.complete(Unit)

        assertCancelled { action.await() }
        assertEquals(listOf(task.taskId to true), data.startBlocks)
    }

    private suspend fun assertCancelled(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: settlement completed before caller cancellation was rethrown.
        }
    }

    private class FakeDataTaskActionPort(vararg initial: DataTaskSnapshot) : DataTaskActionPort {
        private val tasks = initial.associateBy { it.taskId }.toMutableMap()
        val cancelled = mutableListOf<UUID>()
        val acknowledged = mutableListOf<UUID>()
        val resumed = mutableListOf<UUID>()
        val woken = mutableListOf<UUID>()
        val sourceTokens = mutableMapOf<UUID, UUID>()
        val authorizedSourceTokens = mutableListOf<Pair<UUID, UUID>>()
        val submittedSourceTokens = mutableListOf<UUID>()
        val startBlocks = mutableListOf<Pair<UUID, Boolean>>()
        val droppedKeyTokens = mutableListOf<Pair<UUID, String>>()
        val revokedSourceTokens = mutableListOf<Pair<UUID, UUID>>()
        val persistedSecrets = mutableListOf<String>()
        var authenticatedWith: CharArray? = null
        var prepareArchiveKeyHandler: suspend () -> Boolean = { true }
        var resumeHandler: suspend (UUID?) -> Boolean = { true }
        var markStartBlockedHandler: suspend () -> Boolean = { true }
        var wakeResult: ServiceStartResult = ServiceStartResult.Requested

        override suspend fun load(taskId: UUID): DataTaskSnapshot? = tasks[taskId]

        fun replace(task: DataTaskSnapshot) {
            tasks[task.taskId] = task
        }

        override suspend fun cancel(taskId: UUID) {
            cancelled += taskId
        }

        override suspend fun acknowledge(taskId: UUID): Boolean {
            acknowledged += taskId
            return true
        }

        override suspend fun prepareArchiveKey(
            task: DataTaskSnapshot,
            passphrase: CharArray
        ): String? {
            authenticatedWith = passphrase
            return if (prepareArchiveKeyHandler()) "key-token" else null
        }

        override fun dropArchiveKey(taskId: UUID, token: String) {
            droppedKeyTokens += taskId to token
        }

        override fun authorizeRestoreSourceToken(taskId: UUID, token: UUID): Boolean =
            (sourceTokens[taskId] == token).also { authorized ->
                if (authorized) authorizedSourceTokens += taskId to token
            }

        override fun revokeRestoreSourceToken(taskId: UUID, token: UUID) {
            revokedSourceTokens += taskId to token
        }

        override suspend fun resume(
            taskId: UUID,
            expectedState: DataTaskState,
            expectedInterruption: DataTaskInterruption,
            sourceToken: UUID?,
        ): Boolean {
            resumed += taskId
            sourceToken?.let(submittedSourceTokens::add)
            return resumeHandler(sourceToken)
        }

        override fun wake(taskId: UUID): ServiceStartResult {
            woken += taskId
            return wakeResult
        }

        override suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean): Boolean {
            startBlocks += taskId to notificationBlocked
            return markStartBlockedHandler()
        }
    }

    private class FakeSweepTaskActionPort(vararg initial: StoredPrivilegeSweep) :
        SweepTaskActionPort {
        private val tasks = initial.associateBy { it.requestId }.toMutableMap()
        val cancelled = mutableListOf<UUID>()
        val acknowledged = mutableListOf<UUID>()
        val authorizedTargets = mutableListOf<Pair<UUID, Int>>()
        val events = mutableListOf<String>()
        var refreshedPrivilege = true
        var wakeResult: ServiceStartResult = ServiceStartResult.Requested

        override suspend fun load(taskId: UUID): StoredPrivilegeSweep? = tasks[taskId]
        override suspend fun cancel(taskId: UUID) {
            cancelled += taskId
        }

        override suspend fun acknowledge(taskId: UUID): Boolean {
            acknowledged += taskId
            return true
        }

        override suspend fun refreshPrivilegeAndRead(): Boolean {
            events += "refresh"
            return refreshedPrivilege
        }

        override suspend fun resumeBlocked(
            taskId: UUID,
            expectedReason: PrivilegeSweepBlockReason
        ): Boolean {
            events += "resume"
            return true
        }

        override suspend fun authorizeTargetRetry(taskId: UUID, ordinal: Int): Boolean {
            authorizedTargets += taskId to ordinal
            return true
        }

        override fun wake(taskId: UUID): ServiceStartResult {
            events += "wake"
            return wakeResult
        }

        override suspend fun markStartBlocked(taskId: UUID, notificationBlocked: Boolean): Boolean =
            true
    }

    private fun dataTask(
        taskId: UUID = UUID.randomUUID(),
        state: DataTaskState,
        kind: DataTaskKind = DataTaskKind.ARCHIVE_BACKUP,
        detail: StoredDataTaskDetail = backupDetail(),
        interruption: DataTaskInterruption = when (state) {
            DataTaskState.WAITING_FOR_AUTH -> DataTaskInterruption.AUTHENTICATION_REQUIRED
            DataTaskState.WAITING_FOR_SOURCE -> DataTaskInterruption.SOURCE_REQUIRED
            else -> DataTaskInterruption.NONE
        },
        outputs: List<DataTaskOutputSnapshot> = emptyList(),
    ) = DataTaskSnapshot(
        taskId = taskId,
        queueSequence = 1L,
        payloadSchemaVersion = 1,
        kind = kind,
        state = state,
        targetKey = "package:app.test",
        detail = detail,
        stage = null,
        completed = 0L,
        total = 1L,
        attemptCount = 0,
        interruption = interruption,
        resultCode = null,
        cancelRequestedAtEpochMs = null,
        createdAtEpochMs = 1L,
        claimedAtEpochMs = null,
        startedAtEpochMs = null,
        updatedAtEpochMs = 1L,
        terminalAtEpochMs = if (state in setOf(
                DataTaskState.SUCCEEDED,
                DataTaskState.EXPIRED
            )
        ) 2L else null,
        retainUntilEpochMs = if (state in setOf(
                DataTaskState.SUCCEEDED,
                DataTaskState.EXPIRED
            )
        ) 86_400_002L else null,
        acknowledgedAtEpochMs = null,
        items = listOf(
            DataTaskItemSnapshot(
                ordinal = 0,
                packageName = "app.test",
                displayLabel = "Test",
                state = DataTaskItemState.PENDING,
                attemptCount = 0,
                resultCode = null,
                deterministicStagingIdentity = "item-0",
                startedAtEpochMs = null,
                finishedAtEpochMs = null,
            ),
        ),
        outputs = outputs,
    )

    private fun backupDetail() = StoredDataTaskDetail.ArchiveBackup(
        packageName = "app.test",
        dataClassIds = emptyList(),
        includeBundle = true,
        kdfSaltBase64 = Base64.getEncoder().encodeToString(ByteArray(32)),
        destination = StoredDataDestination.ArchiveStore,
        deterministicStagingIdentity = "staging-id",
    )

    private fun restoreDetail(breadcrumb: RestoreMutationBreadcrumb?) =
        StoredDataTaskDetail.ArchiveRestore(
            expectedPackageName = "app.restore",
            dataClassIds = emptyList(),
            restoreObb = false,
            source = StoredRestoreSource.PrivateCopy(
                "data_tasks/00000000-0000-0000-0000-000000000000/restore-source.thor",
            ),
            mutationBreadcrumb = breadcrumb,
            deterministicStagingIdentity = "staging-id",
        )

    private fun output() = DataTaskOutputSnapshot(
        outputId = UUID.randomUUID(),
        itemOrdinal = 0,
        privateRelativePath = "data_tasks/output/file.apk",
        displayName = "file.apk",
        mimeType = "application/vnd.android.package-archive",
        byteSize = 1L,
        state = DataTaskOutputState.READY,
        expiresAtEpochMs = 86_400_000L,
    )

    private fun sweep(
        requestId: UUID = UUID.randomUUID(),
        state: PrivilegeSweepRequestState,
        blockReason: PrivilegeSweepBlockReason? = null,
        targets: List<StoredPrivilegeSweepTarget> = listOf(
            target(
                0,
                PrivilegeSweepTargetState.PENDING
            )
        ),
    ) = StoredPrivilegeSweep(
        requestId = requestId,
        workId = UUID.randomUUID(),
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        freezerMode = null,
        userId = 0,
        source = PrivilegeSweepSource.APP_LIST,
        createdAtEpochMs = 1L,
        targets = targets.map { it.packageName },
        terminalState = null,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = targets.count { it.state == PrivilegeSweepTargetState.UNKNOWN || it.state == PrivilegeSweepTargetState.LEGACY_UNKNOWN },
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        targetSnapshots = targets.map { it.copy(requestId = requestId) },
        requestState = state,
        blockReason = blockReason,
        queueSequence = 1L,
    )

    private fun target(ordinal: Int, state: PrivilegeSweepTargetState) = StoredPrivilegeSweepTarget(
        requestId = UUID.randomUUID(),
        ordinal = ordinal,
        packageName = "app.sweep$ordinal",
        state = state,
        claimToken = null,
        claimLeaseExpiresAtEpochMs = null,
        attemptCount = 0,
        startedAtEpochMs = null,
        finishedAtEpochMs = null,
        resultCode = null,
        rootLaneDegraded = false,
    )
}
