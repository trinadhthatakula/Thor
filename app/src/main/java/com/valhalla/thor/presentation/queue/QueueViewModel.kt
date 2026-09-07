// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.queue

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskAction
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.TaskActionController
import com.valhalla.thor.domain.repository.TaskActionDispatch
import com.valhalla.thor.domain.repository.TaskQueueRepository
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.koin.core.annotation.KoinViewModel

@Immutable
data class QueueUiState(
    val isLoading: Boolean = true,
    val observationUnavailable: Boolean = false,
    val running: RunningSectionUiState = RunningSectionUiState(),
    val queued: QueueLaneSectionUiState = QueueLaneSectionUiState(),
    val recent: QueueLaneSectionUiState = QueueLaneSectionUiState(),
) {
    val isEmpty: Boolean
        get() = running.data == null &&
                running.privilege == null &&
                queued.data.isEmpty() &&
                queued.privilege.isEmpty() &&
                recent.data.isEmpty() &&
                recent.privilege.isEmpty()
}

@Immutable
data class RunningSectionUiState(
    val data: QueuedTaskSummary? = null,
    val privilege: QueuedTaskSummary? = null,
)

@Immutable
data class QueueLaneSectionUiState(
    val data: List<QueuedTaskSummary> = emptyList(),
    val privilege: List<QueuedTaskSummary> = emptyList(),
)

fun interface QueueClock {
    fun nowEpochMillis(): Long
}

@Single(binds = [QueueClock::class])
internal class WallQueueClock : QueueClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class QueueViewModel(
    taskQueueRepository: TaskQueueRepository,
    private val taskActionController: TaskActionController,
    private val clock: QueueClock,
) : ViewModel() {

    val uiState = flow {
        var lastKnown = QueueUiState()
        emitAll(taskQueueRepository.tasks
            .flatMapLatest { tasks -> queueUiStates(tasks, clock) }
            .onEach { lastKnown = it }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(lastKnown.copy(isLoading = false, observationUnavailable = true))
            })
    }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = QueueUiState(),
        )

    private val actionResultChannel = Channel<TaskActionDispatch>(Channel.BUFFERED)
    val actionResults: Flow<TaskActionDispatch> = actionResultChannel.receiveAsFlow()

    fun perform(taskId: UUID, action: TaskAction) {
        viewModelScope.launch {
            actionResultChannel.send(taskActionController.perform(taskId, action))
        }
    }
}

private fun queueUiStates(
    tasks: List<QueuedTaskSummary>,
    clock: QueueClock,
): Flow<QueueUiState> = flow {
    var evaluationTime = clock.nowEpochMillis()
    while (true) {
        emit(buildQueueUiState(tasks, evaluationTime))
        val nextDeadline = tasks.asSequence()
            .filter { it.phase in TERMINAL_PHASES && it.terminalAtEpochMs != null }
            .mapNotNull(QueuedTaskSummary::retainUntilEpochMs)
            .filter { it > evaluationTime }
            .minOrNull()
            ?: break
        delay((nextDeadline - evaluationTime).coerceAtLeast(1L).milliseconds)
        evaluationTime = maxOf(clock.nowEpochMillis(), nextDeadline)
    }
}

internal fun buildQueueUiState(
    tasks: List<QueuedTaskSummary>,
    nowEpochMs: Long,
): QueueUiState {
    val data = tasks.filter { it.queueKind == TaskQueueKind.DATA }.sortedWith(QUEUE_ORDER)
    val privilege = tasks.filter { it.queueKind == TaskQueueKind.PRIVILEGE }.sortedWith(QUEUE_ORDER)

    return QueueUiState(
        isLoading = false,
        running = RunningSectionUiState(
            data = data.firstOrNull { it.phase in RUNNING_PHASES },
            privilege = privilege.firstOrNull { it.phase in RUNNING_PHASES },
        ),
        queued = QueueLaneSectionUiState(
            data = data.filter { it.phase in QUEUED_PHASES },
            privilege = privilege.filter { it.phase in QUEUED_PHASES },
        ),
        recent = QueueLaneSectionUiState(
            data = data.filter { it.isRetainedTerminal(nowEpochMs) },
            privilege = privilege.filter { it.isRetainedTerminal(nowEpochMs) },
        ),
    )
}

private fun QueuedTaskSummary.isRetainedTerminal(nowEpochMs: Long): Boolean =
    phase in TERMINAL_PHASES &&
            terminalAtEpochMs != null &&
            retainUntilEpochMs?.let { nowEpochMs < it } == true

private val QUEUE_ORDER = compareBy(
    QueuedTaskSummary::sequence,
    QueuedTaskSummary::taskId,
)

private val RUNNING_PHASES = setOf(
    TaskLifecyclePhase.RUNNING,
    TaskLifecyclePhase.STOPPING,
)

private val QUEUED_PHASES = setOf(
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

private val TERMINAL_PHASES = setOf(
    TaskLifecyclePhase.SUCCEEDED,
    TaskLifecyclePhase.PARTIAL,
    TaskLifecyclePhase.FAILED,
    TaskLifecyclePhase.CANCELLED,
    TaskLifecyclePhase.EXPIRED,
)
