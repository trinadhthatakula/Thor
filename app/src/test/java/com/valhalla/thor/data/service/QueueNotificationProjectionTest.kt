// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.service

import com.valhalla.thor.domain.model.QueuedTaskSummary
import com.valhalla.thor.domain.model.TaskLifecyclePhase
import com.valhalla.thor.domain.model.TaskProgress
import com.valhalla.thor.domain.model.TaskQueueKind
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueNotificationProjectionTest {
    @Test fun `later count is queued tasks in this queue not targets or blocked rows`() {
        for (queue in TaskQueueKind.entries) {
            val active = task(10, queue, TaskLifecyclePhase.RUNNING)
            val rows = listOf(
                active, task(9, queue), task(11, queue), task(12, queue),
                task(13, queue, TaskLifecyclePhase.WAITING_FOR_AUTH),
                task(14, queue, TaskLifecyclePhase.CANCELLED),
                task(15, queue, TaskLifecyclePhase.STOPPING),
                task(16, queue, TaskLifecyclePhase.START_BLOCKED_NOTIFICATION),
                task(17, TaskQueueKind.entries.first { it != queue }),
            )
            val snapshot = requireNotNull(queueNotificationSnapshot(active.taskId, rows))
            // The older row may have resumed from authentication while this task was active.
            assertEquals(3, snapshot.laterQueuedTasks)
            assertEquals(TaskProgress(3, 100, null), snapshot.task.progress)
            assertEquals(active.taskId, snapshot.task.taskId)
            assertNull(queueNotificationSnapshot(null, rows))
            assertNull(queueNotificationSnapshot(UUID.randomUUID(), rows))
            assertNull(queueNotificationSnapshot(rows[1].taskId, rows))
            assertEquals(3, queueNotificationSnapshot(active.taskId,
                rows.map { if (it == active) it.copy(phase = TaskLifecyclePhase.STOPPING) else it })?.laterQueuedTasks)
        }
    }

    @Test fun `observer conflates progress and stops publishing after cancellation`() = runTest {
        val active = task(10, TaskQueueKind.DATA, TaskLifecyclePhase.RUNNING)
        val activeId = MutableStateFlow<UUID?>(active.taskId)
        val rows = MutableStateFlow(listOf(active))
        val published = mutableListOf<QueueNotificationSnapshot>()
        val job = backgroundScope.observeQueueNotifications(activeId, rows, published::add)
        runCurrent()
        assertEquals(listOf(3L), published.map { it.task.progress.completed })
        rows.value = listOf(active.copy(progress = TaskProgress(4, 100, null)))
        runCurrent()
        rows.value = listOf(active.copy(progress = TaskProgress(5, 100, null)), task(11, TaskQueueKind.DATA))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, published.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(3L, 5L), published.map { it.task.progress.completed })
        assertEquals(1, published.last().laterQueuedTasks)
        job.cancel()
        rows.value = listOf(active.copy(progress = TaskProgress(6, 100, null)))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, published.size)
    }

    @Test fun `handover drops pending old task progress and clearing identity never resurrects it`() = runTest {
        val old = task(10, TaskQueueKind.DATA, TaskLifecyclePhase.RUNNING)
        val next = task(11, TaskQueueKind.DATA, TaskLifecyclePhase.RUNNING)
        val activeId = MutableStateFlow<UUID?>(old.taskId)
        val rows = MutableStateFlow(listOf(old))
        val published = mutableListOf<QueueNotificationSnapshot>()
        backgroundScope.observeQueueNotifications(activeId, rows, published::add)
        runCurrent()
        rows.value = listOf(old.copy(progress = TaskProgress(9, 100, null)))
        runCurrent()
        activeId.value = next.taskId
        rows.value = listOf(old, next)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(old.taskId, next.taskId), published.map { it.task.taskId })
        assertEquals(listOf(3L, 3L), published.map { it.task.progress.completed })

        rows.value = listOf(next.copy(progress = TaskProgress(8, 100, null)))
        runCurrent()
        activeId.value = null
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, published.size)
    }

    private fun task(sequence: Long, queue: TaskQueueKind, phase: TaskLifecyclePhase = TaskLifecyclePhase.QUEUED) =
        QueuedTaskSummary(
            UUID(queue.ordinal.toLong(), sequence), queue, "APP_EXPORT", emptyList(), sequence,
            phase, TaskProgress(3, 100, null), "example.app", null, emptySet(), null, null, false,
        )
}
