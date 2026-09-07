// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import com.valhalla.thor.domain.model.AppShareRequest
import com.valhalla.thor.domain.model.AppShareTarget
import com.valhalla.thor.domain.repository.ShareTaskLauncher
import com.valhalla.thor.presentation.navigation.TaskNavigationRequest
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareSubmissionCoordinatorTest {
    @Test
    fun `submission survives cancellation of the returned observer`() = runTest {
        val accepted = CompletableDeferred<UUID?>()
        val calls = mutableListOf<Pair<UUID, AppShareRequest>>()
        val launcher = ShareTaskLauncher { id, request ->
            calls += id to request
            accepted.await()
        }
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = ShareSubmissionCoordinator(launcher, targets, StandardTestDispatcher(testScheduler))
        val taskId = UUID.randomUUID()
        val request = AppShareRequest(listOf(AppShareTarget("com.example.one", "One")))

        val observer = coordinator.submit(taskId, request)
        runCurrent()
        observer.cancel()
        accepted.complete(taskId)
        runCurrent()

        assertEquals(listOf(taskId to request), calls)
        assertEquals(TaskNavigationRequest.Accepted(taskId, taskId), targets.requests.first())
    }

    @Test
    fun `distinct batches retain exact identities while acceptance overlaps`() = runTest {
        val firstGate = CompletableDeferred<UUID?>()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val calls = mutableListOf<UUID>()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = ShareSubmissionCoordinator(
            ShareTaskLauncher { id, _ ->
                calls += id
                if (id == firstId) firstGate.await() else id
            }, targets, StandardTestDispatcher(testScheduler),
        )
        val request = AppShareRequest(listOf(AppShareTarget("com.example.one", "One")))
        coordinator.submit(firstId, request)
        coordinator.submit(secondId, request)
        runCurrent()

        assertEquals(TaskNavigationRequest.Accepted(secondId, secondId), targets.requests.first())
        firstGate.complete(firstId)
        runCurrent()
        assertEquals(TaskNavigationRequest.Accepted(firstId, firstId), targets.requests.first())
        assertEquals(listOf(firstId, secondId), calls)
    }

    @Test
    fun `rejection reports only its exact candidate and later submissions still work`() = runTest {
        val failed = UUID.randomUUID()
        val succeeded = UUID.randomUUID()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = ShareSubmissionCoordinator(
            ShareTaskLauncher { id, _ -> if (id == failed) error("insert failed") else id },
            targets, StandardTestDispatcher(testScheduler),
        )
        val request = AppShareRequest(listOf(AppShareTarget("com.example.one", "One")))
        val first = coordinator.submit(failed, request)
        runCurrent()
        assertEquals(TaskNavigationRequest.Rejected(failed), targets.requests.first())
        assertEquals(null, first.await())
        val second = coordinator.submit(succeeded, request)
        runCurrent()
        assertEquals(TaskNavigationRequest.Accepted(succeeded, succeeded), targets.requests.first())
        assertEquals(succeeded, second.await())
        assertTrue(first.isCompleted)
    }
}
