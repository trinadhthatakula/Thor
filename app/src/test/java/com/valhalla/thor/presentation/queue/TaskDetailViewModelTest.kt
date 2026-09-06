// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.lifecycle.SavedStateHandle
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionPolicy
import com.valhalla.thor.domain.model.TaskActionRequirement
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskLogLine
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.domain.repository.TaskQueueRepository
import com.valhalla.thor.domain.repository.TaskUiRoute
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.navigation.ThorRoute
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `provisional starting survives initial Room absence then becomes exact durable task`() =
        runTest {
            val observed = MutableStateFlow<QueuedTaskDetail?>(null)
            val repository = FakeTaskQueueRepository(observed)
            val identity = ProvisionalTaskIdentity(
                queueKind = TaskQueueKind.DATA,
                operationId = DataTaskKind.APP_EXPORT.name,
            )
            val viewModel = viewModel(repository, provisionalIdentity = identity)
            val observedPhases = mutableListOf<TaskLifecyclePhase>()
            val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { observedPhases += it.phase }
            }
            runCurrent()

            assertEquals(TASK_ID, viewModel.uiState.value.taskId)
            assertEquals(TaskLifecyclePhase.STARTING, viewModel.uiState.value.phase)
            assertEquals(identity, viewModel.uiState.value.provisionalIdentity)

            observed.value = detail(TaskLifecyclePhase.QUEUED)
            runCurrent()
            assertEquals(TaskLifecyclePhase.QUEUED, viewModel.uiState.value.phase)
            assertNull(viewModel.uiState.value.provisionalIdentity)

            observed.value = detail(TaskLifecyclePhase.RUNNING)
            runCurrent()
            assertEquals(TaskLifecyclePhase.RUNNING, viewModel.uiState.value.phase)
            assertEquals(listOf(TASK_ID), repository.observedTaskIds)
            assertTrue(TaskLifecyclePhase.OBSERVER_FAILURE !in observedPhases)
            collection.cancel()
        }

    @Test
    fun `rejected provisional remains a failed task instead of observer failure`() = runTest {
        val identity = ProvisionalTaskIdentity(
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.APP_EXPORT.name,
        )
        val viewModel = viewModel(
            repository = FakeTaskQueueRepository(flowOf(null)),
            provisionalIdentity = identity,
            rejectedProvisional = true,
        )

        assertEquals(TaskLifecyclePhase.FAILED, viewModel.uiState.value.phase)
        assertEquals(identity, viewModel.uiState.value.provisionalIdentity)
        assertTrue(viewModel.uiState.value.actions.isEmpty())
    }

    @Test
    fun `persisted start rejection replaces provisional state and remains actionable`() = runTest {
        val rejected = detail(
            phase = TaskLifecyclePhase.START_BLOCKED_NOTIFICATION,
            requirement = TaskActionRequirement.NotificationSettings("data-queue"),
            resultCode = "NOTIFICATION_START_REJECTED",
        )
        val viewModel = viewModel(FakeTaskQueueRepository(flowOf(rejected)))

        assertEquals(TaskLifecyclePhase.START_BLOCKED_NOTIFICATION, viewModel.uiState.value.phase)
        assertEquals("NOTIFICATION_START_REJECTED", viewModel.uiState.value.detail?.resultCode)
        assertEquals(
            setOf(TaskAction.OPEN_NOTIFICATION_SETTINGS),
            viewModel.uiState.value.actions,
        )
    }

    @Test
    fun `hot progress overlays an active task and falls back to Room`() = runTest {
        val room = MutableStateFlow(detail(TaskLifecyclePhase.RUNNING, completed = 2, total = 5))
        val overlay = MutableStateFlow<TaskProgress?>(null)
        val viewModel = viewModel(
            repository = FakeTaskQueueRepository(room),
            overlay = FakeTaskProgressOverlaySource(overlay),
        )

        assertEquals(TaskProgress(2, 5, null), viewModel.uiState.value.summary?.progress)

        overlay.value = TaskProgress(3, 5, "PACKAGING")
        assertEquals(TaskProgress(3, 5, "PACKAGING"), viewModel.uiState.value.summary?.progress)

        overlay.value = null
        assertEquals(TaskProgress(2, 5, null), viewModel.uiState.value.summary?.progress)

        room.value = detail(TaskLifecyclePhase.SUCCEEDED, completed = 5, total = 5)
        overlay.value = TaskProgress(4, 5, "STALE")
        assertEquals(TaskProgress(5, 5, null), viewModel.uiState.value.summary?.progress)
    }

    @Test
    fun `cancel targets the selected task and shows stopping until Room settles`() = runTest {
        val observed = MutableStateFlow(detail(TaskLifecyclePhase.RUNNING))
        val controller = FakeTaskActionController()
        val viewModel = viewModel(FakeTaskQueueRepository(observed), controller = controller)

        viewModel.perform(TaskAction.CANCEL)
        assertEquals(
            TaskDetailActionResult(TaskAction.CANCEL, TaskActionDispatch.Applied),
            viewModel.actionResults.firstResult(),
        )
        assertEquals(listOf(TASK_ID to TaskAction.CANCEL), controller.performed)
        assertEquals(TaskLifecyclePhase.STOPPING, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.actions.isEmpty())

        observed.value = detail(TaskLifecyclePhase.STOPPING)
        assertEquals(TaskLifecyclePhase.STOPPING, viewModel.uiState.value.phase)

        observed.value = detail(TaskLifecyclePhase.CANCELLED)
        assertEquals(TaskLifecyclePhase.CANCELLED, viewModel.uiState.value.phase)
        assertEquals(setOf(TaskAction.ACKNOWLEDGE), viewModel.uiState.value.actions)
    }

    @Test
    fun `rejected cancellation does not invent a stopping state`() = runTest {
        val controller = FakeTaskActionController().apply {
            nextDispatch = TaskActionDispatch.Rejected(TaskActionRejection.STALE_PROJECTION)
        }
        val viewModel = viewModel(
            FakeTaskQueueRepository(flowOf(detail(TaskLifecyclePhase.RUNNING))),
            controller = controller,
        )

        viewModel.perform(TaskAction.CANCEL)
        assertEquals(
            TaskDetailActionResult(TaskAction.CANCEL, controller.nextDispatch),
            viewModel.actionResults.firstResult(),
        )
        assertEquals(TaskLifecyclePhase.RUNNING, viewModel.uiState.value.phase)
    }

    @Test
    fun `ordinary cancellation failure clears stopping and reports operation failure`() = runTest {
        val controller = FakeTaskActionController().apply {
            failure = IllegalStateException("controller unavailable")
        }
        val viewModel = viewModel(
            FakeTaskQueueRepository(flowOf(detail(TaskLifecyclePhase.RUNNING))),
            controller = controller,
        )

        viewModel.perform(TaskAction.CANCEL)

        assertEquals(
            TaskDetailActionResult(
                TaskAction.CANCEL,
                TaskActionDispatch.Rejected(TaskActionRejection.OPERATION_FAILED),
            ),
            viewModel.actionResults.firstResult(),
        )
        assertEquals(TaskLifecyclePhase.RUNNING, viewModel.uiState.value.phase)
    }

    @Test
    fun `repository list changes for later tasks do not replace selected detail`() = runTest {
        val selected = MutableStateFlow(detail(TaskLifecyclePhase.RUNNING))
        val repository = FakeTaskQueueRepository(selected)
        val viewModel = viewModel(repository)

        repository.tasks.value = listOf(
            summary(
                taskId = LATER_TASK_ID,
                phase = TaskLifecyclePhase.RUNNING,
                sequence = 2,
            ),
        )
        runCurrent()

        assertEquals(TASK_ID, viewModel.uiState.value.taskId)
        assertEquals(TASK_ID, viewModel.uiState.value.summary?.taskId)
        assertEquals(listOf(TASK_ID), repository.observedTaskIds)
    }

    @Test
    fun `all typed routes survive action dispatch unchanged`() = runTest {
        val breadcrumb = RestoreMutationBreadcrumb("app.restore", "Restore", 9L)
        val routes = listOf(
            TaskUiRoute.AuthenticateArchive(TASK_ID, "app.archive", DataTaskKind.ARCHIVE_BACKUP),
            TaskUiRoute.PickRestoreSource(TASK_ID, "app.restore"),
            TaskUiRoute.ReviewInterruptedRestore(TASK_ID, breadcrumb),
            TaskUiRoute.AuthorizePrivilege(TASK_ID),
            TaskUiRoute.ConfirmSweepRetry(
                TASK_ID,
                targetOrdinal = 3,
                packageName = "app.sweep",
                operation = PrivilegeSweepOperation.CLEAR_CACHE,
            ),
            TaskUiRoute.SharePreparedOutputs(TASK_ID, listOf(OUTPUT_ID)),
            TaskUiRoute.OpenNotificationSettings(TASK_ID, "queue-channel"),
        )
        val controller = FakeTaskActionController()
        val viewModel = viewModel(
            FakeTaskQueueRepository(flowOf(detail(TaskLifecyclePhase.RUNNING))),
            controller = controller,
        )

        routes.forEach { route ->
            controller.nextDispatch = TaskActionDispatch.Route(route)
            viewModel.perform(TaskAction.RETRY)
            val result = viewModel.actionResults.firstResult()
            assertEquals(TaskAction.RETRY, result.action)
            assertSame(route, (result.dispatch as TaskActionDispatch.Route).destination)
        }
    }

    @Test
    fun `expired output rejects share but can be acknowledged`() = runTest {
        val controller = FakeTaskActionController()
        val viewModel = viewModel(
            FakeTaskQueueRepository(flowOf(detail(TaskLifecyclePhase.EXPIRED))),
            controller = controller,
        )

        assertEquals(setOf(TaskAction.ACKNOWLEDGE), viewModel.uiState.value.actions)

        controller.nextDispatch = TaskActionDispatch.Rejected(TaskActionRejection.OUTPUT_EXPIRED)
        viewModel.perform(TaskAction.SHARE)
        assertEquals(
            TaskDetailActionResult(TaskAction.SHARE, controller.nextDispatch),
            viewModel.actionResults.firstResult(),
        )

        controller.nextDispatch = TaskActionDispatch.Applied
        viewModel.perform(TaskAction.ACKNOWLEDGE)
        assertEquals(
            TaskDetailActionResult(TaskAction.ACKNOWLEDGE, TaskActionDispatch.Applied),
            viewModel.actionResults.firstResult(),
        )
        assertEquals(
            listOf(
                TASK_ID to TaskAction.SHARE,
                TASK_ID to TaskAction.ACKNOWLEDGE,
            ),
            controller.performed,
        )
    }

    @Test
    fun `recreation stores only the task id and reconstructs from Room`() = runTest {
        val savedState = SavedStateHandle(
            mapOf(TaskDetailViewModel.TASK_ID_KEY to TASK_ID.toString()),
        )
        val repository = FakeTaskQueueRepository(flowOf(detail(TaskLifecyclePhase.READY)))
        val first = viewModel(repository, savedState = savedState)

        assertEquals(TaskLifecyclePhase.READY, first.uiState.value.phase)
        assertEquals(setOf(TaskDetailViewModel.TASK_ID_KEY), savedState.keys())

        val recreated = viewModel(
            repository,
            savedState = SavedStateHandle(
                mapOf(TaskDetailViewModel.TASK_ID_KEY to savedState.get<String>(TaskDetailViewModel.TASK_ID_KEY)),
            ),
        )
        assertEquals(TASK_ID, recreated.uiState.value.taskId)
        assertEquals(TaskLifecyclePhase.READY, recreated.uiState.value.phase)
    }

    @Test
    fun `malformed route task id fails before repository observation`() {
        val repository = FakeTaskQueueRepository(flowOf(null))

        assertThrows(IllegalArgumentException::class.java) {
            viewModel(repository, routeTaskId = "not-a-uuid")
        }

        assertTrue(repository.observedTaskIds.isEmpty())
    }

    @Test
    fun `route task id must match restored state`() {
        val repository = FakeTaskQueueRepository(flowOf(null))
        val savedState = SavedStateHandle(
            mapOf(TaskDetailViewModel.TASK_ID_KEY to LATER_TASK_ID.toString()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            viewModel(repository, savedState = savedState)
        }

        assertEquals(LATER_TASK_ID.toString(), savedState[TaskDetailViewModel.TASK_ID_KEY])
        assertTrue(repository.observedTaskIds.isEmpty())
    }

    @Test
    fun `missing task becomes observer failure without performing writes`() = runTest {
        val controller = FakeTaskActionController()
        val viewModel = viewModel(
            FakeTaskQueueRepository(flowOf(null)),
            controller = controller,
        )

        assertEquals(TaskLifecyclePhase.OBSERVER_FAILURE, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.detail)
        assertTrue(viewModel.uiState.value.actions.isEmpty())
        assertTrue(controller.performed.isEmpty())
    }

    @Test
    fun `observer exception becomes presentation failure without performing writes`() = runTest {
        val controller = FakeTaskActionController()
        val viewModel = viewModel(
            FakeTaskQueueRepository(
                flow {
                    throw IllegalStateException("database unavailable")
                },
            ),
            controller = controller,
            provisionalIdentity = ProvisionalTaskIdentity(
                TaskQueueKind.DATA,
                DataTaskKind.APP_EXPORT.name,
            ),
        )

        assertEquals(TaskLifecyclePhase.OBSERVER_FAILURE, viewModel.uiState.value.phase)
        assertTrue(controller.performed.isEmpty())
    }

    private fun viewModel(
        repository: TaskQueueRepository,
        overlay: TaskProgressOverlaySource = FakeTaskProgressOverlaySource(flowOf(null)),
        controller: FakeTaskActionController = FakeTaskActionController(),
        savedState: SavedStateHandle = SavedStateHandle(
            mapOf(TaskDetailViewModel.TASK_ID_KEY to TASK_ID.toString()),
        ),
        provisionalIdentity: ProvisionalTaskIdentity? = null,
        rejectedProvisional: Boolean = false,
        routeTaskId: String = TASK_ID.toString(),
    ): TaskDetailViewModel {
        val registry = ProvisionalTaskIdentityRegistry()
        provisionalIdentity?.let { registry.register(TASK_ID, it) }
        if (rejectedProvisional) registry.reject(TASK_ID)
        return TaskDetailViewModel(
            route = ThorRoute.TaskDetail(routeTaskId),
            savedStateHandle = savedState,
            taskQueueRepository = repository,
            progressOverlaySource = overlay,
            taskActionController = controller,
            provisionalIdentityRegistry = registry,
        )
    }

    private fun detail(
        phase: TaskLifecyclePhase,
        completed: Long = 0,
        total: Long = 2,
        requirement: TaskActionRequirement? = null,
        resultCode: String? = null,
    ) = QueuedTaskDetail(
        summary = summary(
            taskId = TASK_ID,
            phase = phase,
            sequence = 1,
            completed = completed,
            total = total,
            requirement = requirement,
        ),
        lines = listOf(
            TaskLogLine(2, "TASK_ITEM_RUNNING", listOf("app.second")),
            TaskLogLine(1, "TASK_ITEM_SUCCEEDED", listOf("app.first")),
        ),
        resultCode = resultCode,
        warningCodes = emptyList(),
    )

    private fun summary(
        taskId: UUID,
        phase: TaskLifecyclePhase,
        sequence: Long,
        completed: Long = 0,
        total: Long = 2,
        requirement: TaskActionRequirement? = null,
    ) = QueuedTaskSummary(
        taskId = taskId,
        queueKind = TaskQueueKind.DATA,
        operationId = DataTaskKind.APP_EXPORT.name,
        titleArguments = listOf("Example"),
        sequence = sequence,
        phase = phase,
        progress = TaskProgress(completed, total, null),
        activeItemLabel = "Example",
        actionRequirement = requirement,
        actions = TaskActionPolicy.actionsFor(phase, requirement),
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        rootLaneDegraded = false,
    )

    private suspend fun Flow<TaskDetailActionResult>.firstResult(): TaskDetailActionResult =
        first()

    private class FakeTaskQueueRepository(
        private val detailFlow: Flow<QueuedTaskDetail?>,
    ) : TaskQueueRepository {
        override val tasks = MutableStateFlow<List<QueuedTaskSummary>>(emptyList())
        val observedTaskIds = mutableListOf<UUID>()

        override fun observe(taskId: UUID): Flow<QueuedTaskDetail?> {
            observedTaskIds += taskId
            return detailFlow
        }
    }

    private class FakeTaskProgressOverlaySource(
        private val progress: Flow<TaskProgress?>,
    ) : TaskProgressOverlaySource {
        override fun observe(taskId: UUID): Flow<TaskProgress?> = progress
    }

    private class FakeTaskActionController : TaskActionController {
        var nextDispatch: TaskActionDispatch = TaskActionDispatch.Applied
        var failure: Exception? = null
        val performed = mutableListOf<Pair<UUID, TaskAction>>()

        override suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch {
            performed += taskId to action
            failure?.let { throw it }
            return nextDispatch
        }

        override suspend fun submitArchivePassphrase(
            taskId: UUID,
            passphrase: CharArray,
        ): TaskActionDispatch = error("not used")

        override suspend fun submitRestoreSource(
            taskId: UUID,
            transientSourceToken: UUID,
        ): TaskActionDispatch = error("not used")

        override suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch =
            error("not used")

        override suspend fun authorizeSweepTargetRetry(
            taskId: UUID,
            targetOrdinal: Int,
        ): TaskActionDispatch = error("not used")
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LATER_TASK_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val OUTPUT_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
