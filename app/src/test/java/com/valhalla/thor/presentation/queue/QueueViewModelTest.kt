// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskQueueRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tasks = MutableStateFlow<List<QueuedTaskSummary>>(emptyList())
    private val repository = FakeTaskQueueRepository(tasks)
    private val controller = RecordingTaskActionController()
    private val clock = QueueClock { NOW }

    @Test
    fun `running exposes at most one task per independent queue`() = runTest {
        tasks.value = listOf(
            task(DATA_RUNNING_2, TaskQueueKind.DATA, 2, TaskLifecyclePhase.RUNNING),
            task(PRIVILEGE_RUNNING, TaskQueueKind.PRIVILEGE, 7, TaskLifecyclePhase.RUNNING),
            task(DATA_RUNNING_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.STOPPING),
        )

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(DATA_RUNNING_1, viewModel.uiState.value.running.data?.taskId)
        assertEquals(PRIVILEGE_RUNNING, viewModel.uiState.value.running.privilege?.taskId)
    }

    @Test
    fun `queued tasks preserve FIFO independently in both queues`() = runTest {
        tasks.value = listOf(
            task(PRIVILEGE_2, TaskQueueKind.PRIVILEGE, 2, TaskLifecyclePhase.QUEUED),
            task(DATA_2, TaskQueueKind.DATA, 2, TaskLifecyclePhase.WAITING_FOR_AUTH),
            task(PRIVILEGE_1, TaskQueueKind.PRIVILEGE, 1, TaskLifecyclePhase.WAITING_FOR_PRIVILEGE),
            task(DATA_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.QUEUED),
        )

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(listOf(DATA_1, DATA_2), viewModel.uiState.value.queued.data.map { it.taskId })
        assertEquals(
            listOf(PRIVILEGE_1, PRIVILEGE_2),
            viewModel.uiState.value.queued.privilege.map { it.taskId },
        )
    }

    @Test
    fun `all action-required phases stay in the queued section`() = runTest {
        val phases = listOf(
            TaskLifecyclePhase.QUEUED,
            TaskLifecyclePhase.WAITING_FOR_AUTH,
            TaskLifecyclePhase.WAITING_FOR_SOURCE,
            TaskLifecyclePhase.WAITING_FOR_PRIVILEGE,
            TaskLifecyclePhase.INTERRUPTED_REVIEW,
            TaskLifecyclePhase.READY,
            TaskLifecyclePhase.READY_PARTIAL,
            TaskLifecyclePhase.START_BLOCKED,
            TaskLifecyclePhase.START_BLOCKED_NOTIFICATION,
        )
        tasks.value = phases.mapIndexed { index, phase ->
            task(uuid(index + 100), TaskQueueKind.DATA, index.toLong(), phase)
        }

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(phases, viewModel.uiState.value.queued.data.map { it.phase })
        assertNull(viewModel.uiState.value.running.data)
        assertEquals(emptyList<QueuedTaskSummary>(), viewModel.uiState.value.recent.data)
    }

    @Test
    fun `terminal phases within retention stay in recent`() = runTest {
        val phases = listOf(
            TaskLifecyclePhase.SUCCEEDED,
            TaskLifecyclePhase.PARTIAL,
            TaskLifecyclePhase.FAILED,
            TaskLifecyclePhase.CANCELLED,
            TaskLifecyclePhase.EXPIRED,
        )
        tasks.value = phases.mapIndexed { index, phase ->
            task(
                taskId = uuid(index + 200),
                queueKind = TaskQueueKind.DATA,
                sequence = index.toLong(),
                phase = phase,
                terminalAtEpochMs = NOW - 1,
                retainUntilEpochMs = NOW + 1,
            )
        }

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(phases, viewModel.uiState.value.recent.data.map { it.phase })
    }

    @Test
    fun `recent excludes missing expired and exact-boundary retention metadata`() = runTest {
        tasks.value = listOf(
            terminal(uuid(301), retainUntilEpochMs = NOW + 1),
            terminal(uuid(302), retainUntilEpochMs = NOW),
            terminal(uuid(303), retainUntilEpochMs = NOW - 1),
            terminal(uuid(304), retainUntilEpochMs = null),
            terminal(uuid(305), retainUntilEpochMs = NOW + 1, terminalAtEpochMs = null),
        )

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(listOf(uuid(301)), viewModel.uiState.value.recent.data.map { it.taskId })
    }

    @Test
    fun `recent entry expires at its retention deadline without a repository emission`() = runTest {
        tasks.value = listOf(terminal(uuid(306), retainUntilEpochMs = NOW + 1_000))
        val tickingClock = QueueClock { NOW + testScheduler.currentTime }
        val viewModel = QueueViewModel(repository, controller, tickingClock)
        runCurrent()

        assertEquals(listOf(uuid(306)), viewModel.uiState.value.recent.data.map { it.taskId })

        advanceTimeBy(999.milliseconds)
        assertEquals(listOf(uuid(306)), viewModel.uiState.value.recent.data.map { it.taskId })

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(emptyList<QueuedTaskSummary>(), viewModel.uiState.value.recent.data)
    }

    @Test
    fun `state reconstructs entirely from the current repository snapshot`() = runTest {
        tasks.value = listOf(task(DATA_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.QUEUED))
        val first = QueueViewModel(repository, controller, clock)
        runCurrent()

        val second = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(first.uiState.value, second.uiState.value)
        assertFalse(second.uiState.value.isLoading)
    }

    @Test
    fun `later repository emission replaces removed rows`() = runTest {
        tasks.value = listOf(task(DATA_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.QUEUED))
        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        tasks.value = listOf(
            task(PRIVILEGE_1, TaskQueueKind.PRIVILEGE, 1, TaskLifecyclePhase.RUNNING),
        )
        runCurrent()

        assertEquals(emptyList<QueuedTaskSummary>(), viewModel.uiState.value.queued.data)
        assertEquals(PRIVILEGE_1, viewModel.uiState.value.running.privilege?.taskId)
    }

    @Test
    fun `perform action forwards exact task and action and preserves dispatch`() = runTest {
        val expected = TaskActionDispatch.Rejected(
            com.valhalla.thor.domain.repository.TaskActionRejection.STALE_PROJECTION,
        )
        controller.nextDispatch = expected
        val viewModel = QueueViewModel(repository, controller, clock)
        val event = async { viewModel.actionResults.first() }

        viewModel.perform(DATA_2, TaskAction.PROVIDE_SOURCE)
        runCurrent()

        assertEquals(listOf(DATA_2 to TaskAction.PROVIDE_SOURCE), controller.performed)
        assertEquals(expected, event.await())
    }

    @Test
    fun `projection preserves only the actions advertised by the summary`() = runTest {
        val advertised = setOf(TaskAction.SHARE)
        tasks.value = listOf(
            task(DATA_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.READY, actions = advertised),
        )

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(advertised, viewModel.uiState.value.queued.data.single().actions)
    }

    @Test
    fun `equal sequence values remain in separate queue lanes`() = runTest {
        tasks.value = listOf(
            task(DATA_1, TaskQueueKind.DATA, 1, TaskLifecyclePhase.QUEUED),
            task(PRIVILEGE_1, TaskQueueKind.PRIVILEGE, 1, TaskLifecyclePhase.QUEUED),
        )

        val viewModel = QueueViewModel(repository, controller, clock)
        runCurrent()

        assertEquals(DATA_1, viewModel.uiState.value.queued.data.single().taskId)
        assertEquals(PRIVILEGE_1, viewModel.uiState.value.queued.privilege.single().taskId)
    }

    private fun terminal(
        taskId: UUID,
        retainUntilEpochMs: Long?,
        terminalAtEpochMs: Long? = NOW - 1,
    ) = task(
        taskId = taskId,
        queueKind = TaskQueueKind.DATA,
        sequence = taskId.leastSignificantBits,
        phase = TaskLifecyclePhase.SUCCEEDED,
        terminalAtEpochMs = terminalAtEpochMs,
        retainUntilEpochMs = retainUntilEpochMs,
    )

    private fun task(
        taskId: UUID,
        queueKind: TaskQueueKind,
        sequence: Long,
        phase: TaskLifecyclePhase,
        terminalAtEpochMs: Long? = null,
        retainUntilEpochMs: Long? = null,
        actions: Set<TaskAction> = emptySet(),
    ) = QueuedTaskSummary(
        taskId = taskId,
        queueKind = queueKind,
        operationId = DataTaskKind.ARCHIVE_BACKUP.name,
        titleArguments = listOf("Example"),
        sequence = sequence,
        phase = phase,
        progress = TaskProgress(completed = 1, total = 2, stageLabel = null),
        activeItemLabel = "Example",
        actionRequirement = null,
        actions = actions,
        terminalAtEpochMs = terminalAtEpochMs,
        retainUntilEpochMs = retainUntilEpochMs,
        rootLaneDegraded = false,
    )

    private fun uuid(value: Int): UUID = UUID(0, value.toLong())

    private class FakeTaskQueueRepository(
        override val tasks: Flow<List<QueuedTaskSummary>>,
    ) : TaskQueueRepository {
        override fun observe(taskId: UUID): Flow<QueuedTaskDetail?> = flowOf(null)
    }

    private class RecordingTaskActionController : TaskActionController {
        val performed = mutableListOf<Pair<UUID, TaskAction>>()
        var nextDispatch: TaskActionDispatch = TaskActionDispatch.Applied

        override suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch {
            performed += taskId to action
            return nextDispatch
        }

        override suspend fun submitArchivePassphrase(
            taskId: UUID,
            passphrase: CharArray,
        ): TaskActionDispatch = unsupported()

        override suspend fun submitRestoreSource(
            taskId: UUID,
            transientSourceToken: UUID,
        ): TaskActionDispatch = unsupported()

        override suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch =
            unsupported()

        override suspend fun authorizeSweepTargetRetry(
            taskId: UUID,
            targetOrdinal: Int,
        ): TaskActionDispatch = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    private companion object {
        const val NOW = 1_000_000L
        val DATA_RUNNING_1: UUID = UUID(0, 1)
        val DATA_RUNNING_2: UUID = UUID(0, 2)
        val PRIVILEGE_RUNNING: UUID = UUID(0, 3)
        val DATA_1: UUID = UUID(0, 11)
        val DATA_2: UUID = UUID(0, 12)
        val PRIVILEGE_1: UUID = UUID(0, 21)
        val PRIVILEGE_2: UUID = UUID(0, 22)
    }
}
