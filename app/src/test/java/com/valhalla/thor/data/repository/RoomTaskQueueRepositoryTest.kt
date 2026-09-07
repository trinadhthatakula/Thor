// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.DataTaskItemSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTaskQueueRepositoryTest {

    @Test
    fun `independent queues retain their own fifo while both may run`() = runTest {
        val dataFirst = dataTask(sequence = 1, state = DataTaskState.RUNNING)
        val dataSecond = dataTask(sequence = 2, state = DataTaskState.QUEUED)
        val sweepFirst = sweep(sequence = 1, state = PrivilegeSweepRequestState.RUNNING)
        val sweepSecond = sweep(sequence = 2, state = PrivilegeSweepRequestState.QUEUED)
        val repository = repository(
            data = listOf(dataSecond, dataFirst),
            sweeps = listOf(sweepSecond, sweepFirst),
        )

        val tasks = repository.tasks.first()

        assertEquals(
            listOf(
                dataFirst.taskId,
                dataSecond.taskId,
                sweepFirst.requestId,
                sweepSecond.requestId,
            ),
            tasks.map { it.taskId },
        )
        assertEquals(
            listOf(1L, 2L),
            tasks.filter { it.queueKind == TaskQueueKind.DATA }.map { it.sequence })
        assertEquals(
            listOf(1L, 2L),
            tasks.filter { it.queueKind == TaskQueueKind.PRIVILEGE }.map { it.sequence })
        assertEquals(2, tasks.count { it.phase == TaskLifecyclePhase.RUNNING })
    }

    @Test
    fun `durable state derives waiting review ready and blocked actions`() = runTest {
        val breadcrumb = RestoreMutationBreadcrumb("app.restore", "Restore", 10L)
        val auth = dataTask(state = DataTaskState.WAITING_FOR_AUTH)
        val review = dataTask(
            state = DataTaskState.INTERRUPTED_REVIEW,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            detail = restoreDetail(breadcrumb),
            interruption = DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
        )
        val ready = dataTask(
            state = DataTaskState.READY_PARTIAL,
            outputs = listOf(output(expiresAt = 86_400_100L)),
        )
        val privilege = sweep(
            state = PrivilegeSweepRequestState.BLOCKED,
            blockReason = PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED,
        )
        val unknown = sweep(
            state = PrivilegeSweepRequestState.BLOCKED,
            targets = listOf(
                target(0, PrivilegeSweepTargetState.LEGACY_UNKNOWN, rootLaneDegraded = true),
            ),
        )
        val terminal = dataTask(
            state = DataTaskState.SUCCEEDED,
            terminalAt = 100L,
            retainUntil = 86_400_100L,
        )
        val repository = repository(
            data = listOf(auth, review, ready, terminal),
            sweeps = listOf(privilege, unknown),
        )

        val tasks = repository.tasks.first().associateBy { it.taskId }

        assertEquals(setOf(TaskAction.AUTHENTICATE_ARCHIVE), tasks.getValue(auth.taskId).actions)
        assertEquals(setOf(TaskAction.REVIEW_RESTORE), tasks.getValue(review.taskId).actions)
        assertEquals(
            breadcrumb,
            (tasks.getValue(review.taskId).actionRequirement as TaskActionRequirement.RestoreInterruptionReview).breadcrumb
        )
        assertEquals(setOf(TaskAction.SHARE), tasks.getValue(ready.taskId).actions)
        assertEquals(
            setOf(TaskAction.AUTHORIZE_PRIVILEGE),
            tasks.getValue(privilege.requestId).actions
        )
        assertEquals(
            setOf(TaskAction.AUTHORIZE_SWEEP_RETRY),
            tasks.getValue(unknown.requestId).actions
        )
        assertTrue(tasks.getValue(unknown.requestId).rootLaneDegraded)
        assertEquals(100L, tasks.getValue(terminal.taskId).terminalAtEpochMs)
        assertEquals(86_400_100L, tasks.getValue(terminal.taskId).retainUntilEpochMs)
    }

    @Test
    fun `detail lines are bounded and never project raw diagnostics`() = runTest {
        val task = dataTask(
            state = DataTaskState.FAILED,
            resultCode = DataTaskResultCode("FAILED"),
            items = (0 until 80).map { ordinal ->
                item(ordinal, DataTaskItemState.FAILED, DataTaskResultCode("ITEM_FAILED"))
            },
            warnings = listOf(
                "SAFE_WARNING",
                "content://private/source",
                "java.lang.Error: shell output"
            ),
            failureReason = "content://private/source\n$ cat /data/secret",
        )
        val repository = repository(data = listOf(task))

        val detail = repository.observe(task.taskId).first()
        assertNotNull(detail)
        val requiredDetail = requireNotNull(detail)

        assertTrue(requiredDetail.lines.size <= 64)
        assertEquals(listOf("SAFE_WARNING"), requiredDetail.warningCodes)
        val projected = buildString {
            append(requiredDetail.resultCode)
            append(requiredDetail.warningCodes)
            append(requiredDetail.lines)
        }
        assertFalse(projected.contains("content://"))
        assertFalse(projected.contains("/data/"))
        assertFalse(projected.contains("shell output"))
    }

    @Test
    fun `both queues compact every pending child before the display cap`() {
        for (count in listOf(1, 63, 64, 65, 80)) {
            val details = listOf(
                dataTask(items = List(count) { item(it, DataTaskItemState.PENDING) }).toQueuedDetail(),
                sweep(targets = List(count) { target(it, PrivilegeSweepTargetState.PENDING) }).toQueuedDetail(),
            )
            details.forEach { detail ->
                assertEquals("pending count $count for ${detail.summary.queueKind}", 1, detail.lines.size)
                assertEquals("TASK_PENDING_COUNT", detail.lines.single().messageCode)
                assertEquals(listOf(count.toString()), detail.lines.single().arguments)
            }
        }
    }

    @Test
    fun `claim removes pending count and success keeps result without accumulating snapshots`() {
        val data = dataTask(items = List(80) { item(it, DataTaskItemState.PENDING) })
        val privilege = sweep(targets = List(80) { target(it, PrivilegeSweepTargetState.PENDING) })
        repeat(3) {
            assertEquals(1, data.toQueuedDetail().lines.size)
            assertEquals(1, privilege.toQueuedDetail().lines.size)
            assertEquals(listOf("80"), data.toQueuedDetail().lines.single().arguments)
            assertEquals(listOf("80"), privilege.toQueuedDetail().lines.single().arguments)
            for (state in listOf(DataTaskItemState.RUNNING, DataTaskItemState.SUCCEEDED)) {
                val detail = data.copy(items = data.items.map {
                    if (it.ordinal == 79) it.copy(state = state) else it
                }).toQueuedDetail()
                assertEquals(listOf("TASK_ITEM_${state.name}", "TASK_PENDING_COUNT"), detail.lines.map { it.messageCode })
                assertEquals(listOf("app.item79"), detail.lines.first().arguments)
                assertEquals(listOf("79"), detail.lines.last().arguments)
            }
            for (state in listOf(PrivilegeSweepTargetState.RUNNING, PrivilegeSweepTargetState.SUCCEEDED)) {
                val detail = privilege.copy(targetSnapshots = privilege.targetSnapshots.map {
                    if (it.ordinal == 79) it.copy(state = state) else it
                }).toQueuedDetail()
                assertEquals(listOf("SWEEP_TARGET_${state.name}", "TASK_PENDING_COUNT"), detail.lines.map { it.messageCode })
                assertEquals(listOf("app.sweep79"), detail.lines.first().arguments)
                assertEquals(listOf("79"), detail.lines.last().arguments)
            }
        }
    }

    @Test
    fun `mixed snapshots retain failures cancellation busy and unknown severity`() {
        val states = PrivilegeSweepTargetState.entries.filter { it != PrivilegeSweepTargetState.PENDING }
        val detail = sweep(targets = List(80) { index ->
            target(index, states.getOrNull(index - 70) ?: PrivilegeSweepTargetState.PENDING)
        }).toQueuedDetail()
        assertEquals(states.map { "SWEEP_TARGET_${it.name}" } + "TASK_PENDING_COUNT", detail.lines.map { it.messageCode })
        assertEquals(listOf((80 - states.size).toString()), detail.lines.last().arguments)
        states.forEachIndexed { index, state ->
            val expected = when (state) {
                PrivilegeSweepTargetState.SUCCEEDED -> com.valhalla.thor.domain.model.TaskLogLevel.SUCCESS
                PrivilegeSweepTargetState.FAILED -> com.valhalla.thor.domain.model.TaskLogLevel.ERROR
                PrivilegeSweepTargetState.RUNNING -> com.valhalla.thor.domain.model.TaskLogLevel.INFO
                else -> com.valhalla.thor.domain.model.TaskLogLevel.WARNING
            }
            assertEquals(expected, detail.lines[index].level)
        }
    }

    @Test
    fun `overflow retains latest ordinal results and states how many were omitted`() {
        val data = dataTask(items = List(80) { item(it, DataTaskItemState.FAILED) }.reversed()).toQueuedDetail()
        val privilege = sweep(targets = List(80) { target(it, PrivilegeSweepTargetState.SUCCEEDED) }.reversed()).toQueuedDetail()
        for ((detail, prefix) in listOf(data to "app.item", privilege to "app.sweep")) {
            assertEquals(64, detail.lines.size)
            assertEquals("TASK_RESULTS_OMITTED", detail.lines.first().messageCode)
            assertEquals(listOf("17"), detail.lines.first().arguments)
            assertEquals((17..79).map { listOf("$prefix$it") }, detail.lines.drop(1).map { it.arguments })
            assertFalse(detail.lines.any { it.messageCode == "TASK_PENDING_COUNT" })
        }
    }

    @Test
    fun `overflow reserves running result omission and pending slots in both queues`() {
        for (runningOrdinal in listOf(0, 78)) {
            val data = dataTask(items = List(80) { ordinal -> item(ordinal, when (ordinal) {
                runningOrdinal -> DataTaskItemState.RUNNING
                79 -> DataTaskItemState.PENDING
                else -> DataTaskItemState.SUCCEEDED
            }) }).toQueuedDetail()
            val privilege = sweep(targets = List(80) { ordinal -> target(ordinal, when (ordinal) {
                runningOrdinal -> PrivilegeSweepTargetState.RUNNING
                79 -> PrivilegeSweepTargetState.PENDING
                else -> PrivilegeSweepTargetState.SUCCEEDED
            }) }).toQueuedDetail()
            for ((detail, prefix) in listOf(data to "app.item", privilege to "app.sweep")) {
                assertEquals(64, detail.lines.size)
                assertEquals("TASK_RESULTS_OMITTED", detail.lines.first().messageCode)
                assertEquals(listOf("17"), detail.lines.first().arguments)
                assertEquals(listOf("$prefix$runningOrdinal"), detail.lines.single { it.messageCode.endsWith("_RUNNING") }.arguments)
                assertEquals(listOf("1"), detail.lines.last().arguments)
                assertEquals("TASK_PENDING_COUNT", detail.lines.last().messageCode)
                val latestResults = if (runningOrdinal == 0) (18..78) else (17..77)
                assertEquals(latestResults.map { listOf("$prefix$it") }, detail.lines.filter { it.messageCode.endsWith("_SUCCEEDED") }.map { it.arguments })
            }
        }
    }

    private fun repository(
        data: List<DataTaskSnapshot> = emptyList(),
        sweeps: List<StoredPrivilegeSweep> = emptyList(),
    ) = RoomTaskQueueRepository(
        dataTasks = FakeDataProjectionSource(data),
        sweeps = FakeSweepProjectionSource(sweeps),
    )

    private class FakeDataProjectionSource(initial: List<DataTaskSnapshot>) :
        DataTaskProjectionSource {
        private val retained = MutableStateFlow(initial)
        override fun observeRetained(): Flow<List<DataTaskSnapshot>> = retained
        override fun observe(taskId: UUID): Flow<DataTaskSnapshot?> =
            MutableStateFlow(retained.value.singleOrNull { it.taskId == taskId })
    }

    private class FakeSweepProjectionSource(initial: List<StoredPrivilegeSweep>) :
        SweepTaskProjectionSource {
        private val retained = MutableStateFlow(initial)
        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained
        override fun observe(taskId: UUID): Flow<StoredPrivilegeSweep?> =
            MutableStateFlow(retained.value.singleOrNull { it.requestId == taskId })
    }

    private fun dataTask(
        sequence: Long = 1L,
        state: DataTaskState = DataTaskState.QUEUED,
        kind: DataTaskKind = DataTaskKind.ARCHIVE_BACKUP,
        detail: StoredDataTaskDetail = backupDetail(),
        interruption: DataTaskInterruption = DataTaskInterruption.NONE,
        terminalAt: Long? = null,
        retainUntil: Long? = null,
        items: List<DataTaskItemSnapshot> = listOf(item(0, DataTaskItemState.PENDING)),
        outputs: List<DataTaskOutputSnapshot> = emptyList(),
        warnings: List<String> = emptyList(),
        failureReason: String? = null,
        resultCode: DataTaskResultCode? = null,
    ) = DataTaskSnapshot(
        taskId = UUID.randomUUID(),
        queueSequence = sequence,
        payloadSchemaVersion = 1,
        kind = kind,
        state = state,
        targetKey = "package:app.test",
        detail = detail,
        stage = null,
        completed = 0L,
        total = items.size.toLong(),
        attemptCount = 0,
        interruption = interruption,
        resultCode = resultCode,
        cancelRequestedAtEpochMs = null,
        createdAtEpochMs = 1L,
        claimedAtEpochMs = null,
        startedAtEpochMs = null,
        updatedAtEpochMs = 1L,
        terminalAtEpochMs = terminalAt,
        retainUntilEpochMs = retainUntil,
        acknowledgedAtEpochMs = null,
        items = items,
        outputs = outputs,
        warnings = warnings,
        failureReason = failureReason,
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

    private fun item(
        ordinal: Int,
        state: DataTaskItemState,
        resultCode: DataTaskResultCode? = null,
    ) = DataTaskItemSnapshot(
        ordinal = ordinal,
        packageName = "app.item$ordinal",
        displayLabel = "Item $ordinal",
        state = state,
        attemptCount = 1,
        resultCode = resultCode,
        deterministicStagingIdentity = "item-$ordinal",
        startedAtEpochMs = null,
        finishedAtEpochMs = null,
    )

    private fun output(expiresAt: Long) = DataTaskOutputSnapshot(
        outputId = UUID.randomUUID(),
        itemOrdinal = 0,
        privateRelativePath = "data_tasks/output/file.apk",
        displayName = "file.apk",
        mimeType = "application/vnd.android.package-archive",
        byteSize = 1L,
        state = DataTaskOutputState.READY,
        expiresAtEpochMs = expiresAt,
    )

    private fun sweep(
        sequence: Long = 1L,
        state: PrivilegeSweepRequestState = PrivilegeSweepRequestState.QUEUED,
        blockReason: PrivilegeSweepBlockReason? = null,
        targets: List<StoredPrivilegeSweepTarget> = listOf(
            target(
                0,
                PrivilegeSweepTargetState.PENDING
            )
        ),
        terminal: StoredSweepTerminal? = null,
    ): StoredPrivilegeSweep {
        val requestId = UUID.randomUUID()
        return StoredPrivilegeSweep(
            requestId = requestId,
            workId = UUID.randomUUID(),
            operation = PrivilegeSweepOperation.CLEAR_CACHE,
            freezerMode = null,
            userId = 0,
            source = PrivilegeSweepSource.APP_LIST,
            createdAtEpochMs = 1L,
            targets = targets.map { it.packageName },
            terminalState = terminal,
            succeeded = targets.count { it.state == PrivilegeSweepTargetState.SUCCEEDED },
            failed = targets.count { it.state == PrivilegeSweepTargetState.FAILED },
            busy = targets.count { it.state == PrivilegeSweepTargetState.BUSY },
            unresolved = targets.count { it.state == PrivilegeSweepTargetState.UNKNOWN || it.state == PrivilegeSweepTargetState.LEGACY_UNKNOWN },
            terminalAtEpochMs = null,
            retainUntilEpochMs = null,
            targetSnapshots = targets.map { it.copy(requestId = requestId) },
            requestState = state,
            blockReason = blockReason,
            queueSequence = sequence,
        )
    }

    private fun target(
        ordinal: Int,
        state: PrivilegeSweepTargetState,
        rootLaneDegraded: Boolean = false,
    ) = StoredPrivilegeSweepTarget(
        requestId = UUID.randomUUID(),
        ordinal = ordinal,
        packageName = "app.sweep$ordinal",
        state = state,
        claimToken = null,
        claimLeaseExpiresAtEpochMs = null,
        attemptCount = 1,
        startedAtEpochMs = null,
        finishedAtEpochMs = null,
        resultCode = if (state == PrivilegeSweepTargetState.FAILED) PrivilegeSweepResultCode("FAILED") else null,
        rootLaneDegraded = rootLaneDegraded,
    )
}
