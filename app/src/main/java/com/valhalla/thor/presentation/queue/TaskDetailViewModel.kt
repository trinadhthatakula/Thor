// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskActionPolicy
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskLogLine
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskActionRejection
import com.valhalla.thor.domain.repository.TaskQueueRepository
import com.valhalla.thor.presentation.navigation.ThorRoute
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Single

/** Optional exact-task progress that is newer than the last Room projection. */
interface TaskProgressOverlaySource {
    fun observe(taskId: UUID): Flow<TaskProgress?>
}

@Single(binds = [TaskProgressOverlaySource::class])
internal class EmptyTaskProgressOverlaySource : TaskProgressOverlaySource {
    override fun observe(taskId: UUID): Flow<TaskProgress?> = flowOf(null)
}

@Immutable
data class ProvisionalTaskIdentity(
    val queueKind: TaskQueueKind,
    val operationId: String,
)

@Immutable
data class ProvisionalTaskState(
    val identity: ProvisionalTaskIdentity,
    val rejected: Boolean = false,
    val sameQueueSuppressed: Boolean = false,
)

/** Bounded process-memory identity for a task accepted before Room emits its row. */
@Single
class ProvisionalTaskIdentityRegistry {
    private val states = MutableStateFlow<Map<UUID, ProvisionalTaskState>>(emptyMap())

    fun register(taskId: UUID, identity: ProvisionalTaskIdentity) {
        states.update { current ->
            LinkedHashMap(current).apply {
                remove(taskId)
                put(taskId, ProvisionalTaskState(identity))
                while (size > MAX_IDENTITIES) {
                    remove(keys.first())
                }
            }
        }
    }

    fun current(taskId: UUID): ProvisionalTaskIdentity? = currentState(taskId)?.identity

    fun currentState(taskId: UUID): ProvisionalTaskState? = states.value[taskId]

    fun observe(taskId: UUID): Flow<ProvisionalTaskState?> = states
        .map { it[taskId] }
        .distinctUntilChanged()

    fun reject(taskId: UUID) {
        states.update { current ->
            val state = current[taskId] ?: return@update current
            current + (taskId to state.copy(rejected = true))
        }
    }

    fun markSameQueueSuppressed(taskId: UUID) {
        states.update { current ->
            val state = current[taskId] ?: return@update current
            current + (taskId to state.copy(sameQueueSuppressed = true))
        }
    }

    fun move(fromTaskId: UUID, toTaskId: UUID) {
        states.update { current ->
            val state = current[fromTaskId] ?: return@update current
            LinkedHashMap(current).apply {
                remove(fromTaskId)
                put(toTaskId, state)
            }
        }
    }

    fun clear(taskId: UUID) {
        states.update { current ->
            if (taskId !in current) current else current - taskId
        }
    }

    private companion object {
        const val MAX_IDENTITIES = 64
    }
}

@Immutable
data class TaskDetailUiState(
    val taskId: UUID,
    val phase: TaskLifecyclePhase,
    val detail: QueuedTaskDetail? = null,
    val provisionalIdentity: ProvisionalTaskIdentity? = null,
) {
    val summary: QueuedTaskSummary?
        get() = detail?.summary

    val lines: List<TaskLogLine>
        get() = detail?.lines.orEmpty()

    val actions: Set<TaskAction>
        get() = summary?.actions.orEmpty()
}

@Immutable
data class TaskDetailActionResult(
    val action: TaskAction,
    val dispatch: TaskActionDispatch,
)

private sealed interface TaskDetailObservation {
    data class Value(val detail: QueuedTaskDetail?) : TaskDetailObservation
    data object Failure : TaskDetailObservation
}

@KoinViewModel
class TaskDetailViewModel(
    @InjectedParam route: ThorRoute.TaskDetail,
    savedStateHandle: SavedStateHandle,
    taskQueueRepository: TaskQueueRepository,
    progressOverlaySource: TaskProgressOverlaySource,
    private val taskActionController: TaskActionController,
    private val provisionalIdentityRegistry: ProvisionalTaskIdentityRegistry,
) : ViewModel() {

    val taskId: UUID = canonicalTaskId(route.taskId)

    init {
        val restoredTaskId = savedStateHandle.get<String>(TASK_ID_KEY)
        require(restoredTaskId == null || restoredTaskId == route.taskId) {
            "Task detail route does not match restored state"
        }
        savedStateHandle[TASK_ID_KEY] = route.taskId
    }

    private val cancellationRequested = MutableStateFlow(false)

    private val observedDetail: Flow<TaskDetailObservation> = combine(
        taskQueueRepository.observe(taskId),
        progressOverlaySource.observe(taskId)
            .onStart { emit(null) }
            .catch { emit(null) },
    ) { detail, overlay ->
        val observation: TaskDetailObservation = TaskDetailObservation.Value(
            detail
                ?.takeIf { it.summary.taskId == taskId }
                ?.withProgressOverlay(overlay)
                ?.withSortedLines(),
        )
        observation
    }.catch {
        emit(TaskDetailObservation.Failure)
    }.transform { observation ->
        emit(observation)
        if (observation is TaskDetailObservation.Value && observation.detail != null) {
            provisionalIdentityRegistry.clear(taskId)
        }
    }

    val uiState = combine(
        observedDetail,
        provisionalIdentityRegistry.observe(taskId),
        cancellationRequested,
    ) { observation, provisionalState, stopping ->
        val detail = (observation as? TaskDetailObservation.Value)?.detail
        when {
            observation is TaskDetailObservation.Failure -> TaskDetailUiState(
                taskId = taskId,
                phase = TaskLifecyclePhase.OBSERVER_FAILURE,
            )

            detail == null && provisionalState?.rejected == true -> TaskDetailUiState(
                taskId = taskId,
                phase = TaskLifecyclePhase.FAILED,
                provisionalIdentity = provisionalState.identity,
            )

            detail == null && provisionalState != null -> TaskDetailUiState(
                taskId = taskId,
                phase = TaskLifecyclePhase.STARTING,
                provisionalIdentity = provisionalState.identity,
            )

            detail == null -> TaskDetailUiState(
                taskId = taskId,
                phase = TaskLifecyclePhase.OBSERVER_FAILURE,
            )

            stopping && detail.summary.phase in CANCELLABLE_PHASES -> {
                val stoppingSummary = detail.summary.copy(
                    phase = TaskLifecyclePhase.STOPPING,
                    actions = TaskActionPolicy.actionsFor(
                        TaskLifecyclePhase.STOPPING,
                        requirement = null,
                    ),
                )
                TaskDetailUiState(
                    taskId = taskId,
                    phase = TaskLifecyclePhase.STOPPING,
                    detail = detail.copy(summary = stoppingSummary),
                )
            }

            else -> TaskDetailUiState(
                taskId = taskId,
                phase = detail.summary.phase,
                detail = detail,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = provisionalIdentityRegistry.currentState(taskId).let { provisional ->
            TaskDetailUiState(
                taskId = taskId,
                phase = if (provisional?.rejected == true) {
                    TaskLifecyclePhase.FAILED
                } else {
                    TaskLifecyclePhase.STARTING
                },
                provisionalIdentity = provisional?.identity,
            )
        },
    )

    private val actionResultChannel = Channel<TaskDetailActionResult>(Channel.BUFFERED)
    val actionResults: Flow<TaskDetailActionResult> = actionResultChannel.receiveAsFlow()

    fun perform(action: TaskAction) {
        viewModelScope.launch {
            val cancellation = action == TaskAction.CANCEL
            if (cancellation) cancellationRequested.value = true
            val dispatch = try {
                taskActionController.perform(taskId, action)
            } catch (exception: CancellationException) {
                if (cancellation) cancellationRequested.value = false
                throw exception
            } catch (exception: Exception) {
                if (cancellation) cancellationRequested.value = false
                Logger.e(TAG, "task action failed for $taskId", exception)
                TaskActionDispatch.Rejected(TaskActionRejection.OPERATION_FAILED)
            }
            if (cancellation && dispatch != TaskActionDispatch.Applied) {
                cancellationRequested.value = false
            }
            actionResultChannel.send(TaskDetailActionResult(action, dispatch))
        }
    }

    private fun QueuedTaskDetail.withProgressOverlay(
        overlay: TaskProgress?,
    ): QueuedTaskDetail {
        if (overlay == null || summary.phase !in OVERLAY_PHASES) return this
        return copy(summary = summary.copy(progress = overlay))
    }

    private fun QueuedTaskDetail.withSortedLines(): QueuedTaskDetail =
        if (lines.zipWithNext().all { (first, second) -> first.order <= second.order }) this
        else copy(lines = lines.sortedBy(TaskLogLine::order))

    private fun canonicalTaskId(raw: String): UUID {
        val parsed = runCatching { UUID.fromString(raw) }.getOrNull()
        require(parsed != null && parsed.toString() == raw) { "Invalid task detail id" }
        return parsed
    }

    companion object {
        const val TASK_ID_KEY = "taskId"
        private const val TAG = "TaskDetailViewModel"

        private val CANCELLABLE_PHASES = setOf(
            TaskLifecyclePhase.QUEUED,
            TaskLifecyclePhase.RUNNING,
        )
        private val OVERLAY_PHASES = setOf(
            TaskLifecyclePhase.RUNNING,
            TaskLifecyclePhase.STOPPING,
        )
    }
}
