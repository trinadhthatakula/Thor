// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentity
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

sealed interface TaskNavigationRequest {
    data class OpenDurable(val taskId: UUID) : TaskNavigationRequest

    data class OpenProvisional(
        val taskId: UUID,
        val identity: ProvisionalTaskIdentity,
    ) : TaskNavigationRequest

    data class Accepted(
        val provisionalTaskId: UUID,
        val canonicalTaskId: UUID,
    ) : TaskNavigationRequest

    data class Rejected(val provisionalTaskId: UUID) : TaskNavigationRequest
}

/** Ordered, one-shot process-local navigation requests from task producers and trampolines. */
@Single
class TaskNavigationTargets(
    private val provisionalIdentityRegistry: ProvisionalTaskIdentityRegistry,
    private val aliasStore: TaskNavigationAliasStore,
) {
    internal constructor(provisionalIdentityRegistry: ProvisionalTaskIdentityRegistry) : this(
        provisionalIdentityRegistry,
        InMemoryTaskNavigationAliasStore(),
    )
    private val recoveryLock = Any()
    private val recoveredRequests = ArrayDeque<TaskNavigationRequest>()
    private val channel = Channel<TaskNavigationRequest>(
        capacity = Channel.BUFFERED,
        onUndeliveredElement = { request ->
            synchronized(recoveryLock) { recoveredRequests.addLast(request) }
        },
    )
    private val deliveryMutex = Mutex()
    private var inFlight: TaskNavigationRequest? = null

    val requests: Flow<TaskNavigationRequest> = channel.receiveAsFlow()

    internal suspend fun collectWhileResumed(
        lifecycle: Lifecycle,
        onResumed: () -> Unit = {},
        onRequest: suspend (TaskNavigationRequest) -> Unit,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            onResumed()
            deliveryMutex.withLock {
                while (currentCoroutineContext().isActive) {
                    val request = nextRequest()
                    onRequest(request)
                    synchronized(recoveryLock) {
                        if (inFlight === request) inFlight = null
                    }
                }
            }
        }
    }

    private suspend fun nextRequest(): TaskNavigationRequest {
        synchronized(recoveryLock) {
            inFlight?.let { return it }
            recoveredRequests.removeFirstOrNull()?.let { request ->
                inFlight = request
                return request
            }
        }
        return channel.receive().also { request ->
            synchronized(recoveryLock) { inFlight = request }
        }
    }

    fun requestOpen(taskId: UUID) {
        publish(TaskNavigationRequest.OpenDurable(taskId))
    }

    fun requestOpenProvisional(
        taskId: UUID,
        identity: ProvisionalTaskIdentity,
    ) {
        provisionalIdentityRegistry.register(taskId, identity)
        publish(TaskNavigationRequest.OpenProvisional(taskId, identity))
    }

    suspend fun requestAccepted(
        provisionalTaskId: UUID,
        canonicalTaskId: UUID,
    ) {
        aliasStore.record(provisionalTaskId, canonicalTaskId)
        publish(TaskNavigationRequest.Accepted(provisionalTaskId, canonicalTaskId))
    }

    fun restoredCanonicalTaskId(provisionalTaskId: UUID): UUID? =
        aliasStore.canonicalTaskId(provisionalTaskId)

    fun requestRejected(provisionalTaskId: UUID) {
        provisionalIdentityRegistry.reject(provisionalTaskId)
        publish(TaskNavigationRequest.Rejected(provisionalTaskId))
    }

    private fun publish(request: TaskNavigationRequest) {
        check(channel.trySend(request).isSuccess) { "Task navigation request buffer is full" }
    }
}

internal sealed interface TaskNavigationEffect {
    data class Open(val taskId: UUID) : TaskNavigationEffect

    data class Replace(
        val provisionalTaskId: UUID,
        val canonicalTaskId: UUID,
    ) : TaskNavigationEffect

    data object ShowQueued : TaskNavigationEffect
    data object None : TaskNavigationEffect
}

/** Tracks only the process-local presentation of caller-owned task IDs. */
internal class TaskNavigationCoordinator(
    private val provisionalIdentityRegistry: ProvisionalTaskIdentityRegistry,
) {
    private val dispositions = mutableMapOf<UUID, ProvisionalDisposition>()

    fun handle(
        request: TaskNavigationRequest,
        visibleTaskIds: Set<UUID>,
        topTaskQueueKind: TaskQueueKind?,
    ): TaskNavigationEffect = when (request) {
        is TaskNavigationRequest.OpenDurable -> TaskNavigationEffect.Open(request.taskId)

        is TaskNavigationRequest.OpenProvisional -> {
            provisionalIdentityRegistry.register(request.taskId, request.identity)
            if (topTaskQueueKind == request.identity.queueKind) {
                dispositions[request.taskId] = ProvisionalDisposition.SUPPRESSED
                provisionalIdentityRegistry.markSameQueueSuppressed(request.taskId)
                TaskNavigationEffect.None
            } else {
                dispositions[request.taskId] = ProvisionalDisposition.OPENED
                TaskNavigationEffect.Open(request.taskId)
            }
        }

        is TaskNavigationRequest.Accepted -> {
            val provisionalState = provisionalIdentityRegistry.currentState(request.provisionalTaskId)
            val disposition = dispositions.remove(request.provisionalTaskId)
                ?: when {
                    provisionalState?.sameQueueSuppressed == true ->
                        ProvisionalDisposition.SUPPRESSED

                    request.provisionalTaskId in visibleTaskIds && provisionalState != null ->
                        ProvisionalDisposition.OPENED

                    else -> null
                }
            when (disposition) {
                ProvisionalDisposition.OPENED -> {
                    if (request.provisionalTaskId !in visibleTaskIds) {
                        provisionalIdentityRegistry.clear(request.provisionalTaskId)
                        TaskNavigationEffect.None
                    } else if (request.provisionalTaskId == request.canonicalTaskId) {
                        TaskNavigationEffect.None
                    } else {
                        provisionalIdentityRegistry.move(
                            request.provisionalTaskId,
                            request.canonicalTaskId,
                        )
                        TaskNavigationEffect.Replace(
                            request.provisionalTaskId,
                            request.canonicalTaskId,
                        )
                    }
                }

                ProvisionalDisposition.SUPPRESSED -> {
                    provisionalIdentityRegistry.clear(request.provisionalTaskId)
                    TaskNavigationEffect.ShowQueued
                }

                ProvisionalDisposition.DISMISSED,
                null,
                    -> {
                        provisionalIdentityRegistry.clear(request.provisionalTaskId)
                        TaskNavigationEffect.None
                    }
            }
        }

        is TaskNavigationRequest.Rejected -> {
            val disposition = dispositions.remove(request.provisionalTaskId)
            val provisionalState =
                provisionalIdentityRegistry.currentState(request.provisionalTaskId)
            when {
                disposition == ProvisionalDisposition.SUPPRESSED ||
                    disposition == ProvisionalDisposition.DISMISSED ||
                    provisionalState?.sameQueueSuppressed == true ||
                    provisionalState == null -> {
                    provisionalIdentityRegistry.clear(request.provisionalTaskId)
                }

                else -> provisionalIdentityRegistry.reject(request.provisionalTaskId)
            }
            TaskNavigationEffect.None
        }
    }

    fun onDetailDismissed(taskId: UUID) {
        if (dispositions[taskId] == ProvisionalDisposition.OPENED) {
            dispositions[taskId] = ProvisionalDisposition.DISMISSED
        }
        provisionalIdentityRegistry.clear(taskId)
    }

    private enum class ProvisionalDisposition {
        OPENED,
        SUPPRESSED,
        DISMISSED,
    }
}
