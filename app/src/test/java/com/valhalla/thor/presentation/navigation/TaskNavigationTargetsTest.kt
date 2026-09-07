// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation3.runtime.NavKey
import com.valhalla.thor.presentation.main.replaceRestoredTaskAliases
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskNavigationTargetsTest {

    @Test
    fun `only resumed owner consumes requests and paused requests remain buffered`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val ownerA = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val ownerB = TestLifecycleOwner(Lifecycle.State.CREATED)
            val receivedA = mutableListOf<TaskNavigationRequest>()
            val receivedB = mutableListOf<TaskNavigationRequest>()
            val collectorA = launch {
                targets.collectWhileResumed(ownerA.lifecycle) { receivedA += it }
            }
            val collectorB = launch {
                targets.collectWhileResumed(ownerB.lifecycle) { receivedB += it }
            }
            runCurrent()

            ownerA.moveTo(Lifecycle.State.CREATED)
            ownerB.moveTo(Lifecycle.State.RESUMED)
            runCurrent()
            targets.requestOpen(FIRST_ID)
            runCurrent()

            assertEquals(emptyList<TaskNavigationRequest>(), receivedA)
            assertEquals(listOf(TaskNavigationRequest.OpenDurable(FIRST_ID)), receivedB)

            ownerB.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            targets.requestOpen(SECOND_ID)
            runCurrent()
            assertEquals(1, receivedB.size)

            ownerA.moveTo(Lifecycle.State.RESUMED)
            runCurrent()
            assertEquals(listOf(TaskNavigationRequest.OpenDurable(SECOND_ID)), receivedA)

            collectorA.cancel()
            collectorB.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `request delivered as owner pauses is retried by the next resumed owner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val ownerA = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val ownerB = TestLifecycleOwner(Lifecycle.State.CREATED)
            val receivedA = mutableListOf<TaskNavigationRequest>()
            val receivedB = mutableListOf<TaskNavigationRequest>()
            val collectorA = launch {
                targets.collectWhileResumed(ownerA.lifecycle) { receivedA += it }
            }
            runCurrent()

            targets.requestOpen(FIRST_ID)
            ownerA.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            val collectorB = launch {
                targets.collectWhileResumed(ownerB.lifecycle) { receivedB += it }
            }
            ownerB.moveTo(Lifecycle.State.RESUMED)
            runCurrent()

            assertTrue(receivedA.isEmpty())
            assertEquals(listOf(TaskNavigationRequest.OpenDurable(FIRST_ID)), receivedB)
            collectorA.cancel()
            collectorB.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `request interrupted by pause is retried by the next resumed owner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val ownerA = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val ownerB = TestLifecycleOwner(Lifecycle.State.CREATED)
            val firstAttempt = CompletableDeferred<Unit>()
            val receivedB = mutableListOf<TaskNavigationRequest>()
            val collectorA = launch {
                targets.collectWhileResumed(ownerA.lifecycle) {
                    firstAttempt.complete(Unit)
                    awaitCancellation()
                }
            }
            runCurrent()
            targets.requestOpen(FIRST_ID)
            runCurrent()
            assertTrue(firstAttempt.isCompleted)

            ownerA.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            val collectorB = launch {
                targets.collectWhileResumed(ownerB.lifecycle) { receivedB += it }
            }
            ownerB.moveTo(Lifecycle.State.RESUMED)
            runCurrent()

            assertEquals(listOf(TaskNavigationRequest.OpenDurable(FIRST_ID)), receivedB)
            collectorA.cancel()
            collectorB.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `resume callback runs before buffered request delivery in every resumed epoch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val events = mutableListOf<String>()
            val collector = launch {
                targets.collectWhileResumed(
                    lifecycle = owner.lifecycle,
                    onResumed = { events += "resume" },
                    onRequest = { events += "request" },
                )
            }
            runCurrent()
            owner.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            targets.requestOpen(FIRST_ID)
            runCurrent()
            owner.moveTo(Lifecycle.State.RESUMED)
            runCurrent()

            assertEquals(listOf("resume", "resume", "request"), events)
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `durable target published before collection is retained and consumed once`() = runTest {
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        targets.requestOpen(FIRST_ID)

        assertEquals(
            TaskNavigationRequest.OpenDurable(FIRST_ID),
            targets.requests.first(),
        )

        val replay = async { targets.requests.first() }
        runCurrent()
        assertFalse(replay.isCompleted)
        replay.cancel()
    }

    @Test
    fun `provisional open and canonical acceptance retain their order`() = runTest {
        val registry = ProvisionalTaskIdentityRegistry()
        val targets = TaskNavigationTargets(registry)
        val identity = ProvisionalTaskIdentity(
            queueKind = TaskQueueKind.DATA,
            operationId = DataTaskKind.APP_EXPORT.name,
        )

        targets.requestOpenProvisional(FIRST_ID, identity)
        targets.requestAccepted(FIRST_ID, SECOND_ID)

        assertEquals(identity, registry.current(FIRST_ID))
        assertEquals(
            listOf(
                TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
                TaskNavigationRequest.Accepted(FIRST_ID, SECOND_ID),
            ),
            targets.requests.take(2).toList(),
        )
    }

    @Test
    fun `restored stale provisional route resolves without a replayed navigation event`() = runTest {
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val stack = mutableListOf<NavKey>(
            ThorRoute.Home,
            ThorRoute.TaskDetail(FIRST_ID.toString()),
        )
        targets.requestAccepted(FIRST_ID, SECOND_ID)

        replaceRestoredTaskAliases(
            backStacks = listOf(stack),
            canonicalTaskId = targets::restoredCanonicalTaskId,
        )

        assertEquals(
            listOf(ThorRoute.Home, ThorRoute.TaskDetail(SECOND_ID.toString())),
            stack,
        )
    }

    @Test
    fun `canonical acceptance replaces a visible provisional identity`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val coordinator = TaskNavigationCoordinator(registry)
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)

        assertEquals(
            TaskNavigationEffect.Open(FIRST_ID),
            coordinator.handle(
                TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
                visibleTaskIds = emptySet(),
                topTaskQueueKind = null,
            ),
        )
        assertEquals(
            TaskNavigationEffect.Replace(FIRST_ID, SECOND_ID),
            coordinator.handle(
                TaskNavigationRequest.Accepted(FIRST_ID, SECOND_ID),
                visibleTaskIds = setOf(FIRST_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertNull(registry.current(FIRST_ID))
        assertEquals(identity, registry.current(SECOND_ID))
    }

    @Test
    fun `accepted visible provisional survives coordinator recreation`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)
        registry.register(FIRST_ID, identity)
        val recreatedCoordinator = TaskNavigationCoordinator(registry)

        assertEquals(
            TaskNavigationEffect.Replace(FIRST_ID, SECOND_ID),
            recreatedCoordinator.handle(
                TaskNavigationRequest.Accepted(FIRST_ID, SECOND_ID),
                visibleTaskIds = setOf(FIRST_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertNull(registry.current(FIRST_ID))
        assertEquals(identity, registry.current(SECOND_ID))
    }

    @Test
    fun `rejected visible provisional survives coordinator recreation`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)
        registry.register(FIRST_ID, identity)
        registry.reject(FIRST_ID)
        val recreatedCoordinator = TaskNavigationCoordinator(registry)

        assertEquals(
            TaskNavigationEffect.None,
            recreatedCoordinator.handle(
                TaskNavigationRequest.Rejected(FIRST_ID),
                visibleTaskIds = setOf(FIRST_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertEquals(identity, registry.current(FIRST_ID))
        assertEquals(true, registry.currentState(FIRST_ID)?.rejected)
    }

    @Test
    fun `rejection consumed by another coordinator preserves owner tombstone`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val owner = TaskNavigationCoordinator(registry)
        val observer = TaskNavigationCoordinator(registry)
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)

        assertEquals(
            TaskNavigationEffect.Open(FIRST_ID),
            owner.handle(
                TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
                visibleTaskIds = emptySet(),
                topTaskQueueKind = null,
            ),
        )
        registry.reject(FIRST_ID)

        assertEquals(
            TaskNavigationEffect.None,
            observer.handle(
                TaskNavigationRequest.Rejected(FIRST_ID),
                visibleTaskIds = emptySet(),
                topTaskQueueKind = null,
            ),
        )
        assertEquals(identity, registry.current(FIRST_ID))
        assertEquals(true, registry.currentState(FIRST_ID)?.rejected)
    }

    @Test
    fun `accepted task does not reopen after provisional detail was dismissed`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val coordinator = TaskNavigationCoordinator(registry)
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)
        coordinator.handle(
            TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
            visibleTaskIds = emptySet(),
            topTaskQueueKind = null,
        )

        coordinator.onDetailDismissed(FIRST_ID)

        assertEquals(
            TaskNavigationEffect.None,
            coordinator.handle(
                TaskNavigationRequest.Accepted(FIRST_ID, SECOND_ID),
                visibleTaskIds = emptySet(),
                topTaskQueueKind = null,
            ),
        )
        assertNull(registry.current(FIRST_ID))
        assertNull(registry.current(SECOND_ID))
    }

    @Test
    fun `suppressed acceptance reports queued after coordinator recreation`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)
        val originalCoordinator = TaskNavigationCoordinator(registry)
        assertEquals(
            TaskNavigationEffect.None,
            originalCoordinator.handle(
                TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
                visibleTaskIds = setOf(SECOND_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )

        val recreatedCoordinator = TaskNavigationCoordinator(registry)

        assertEquals(
            TaskNavigationEffect.ShowQueued,
            recreatedCoordinator.handle(
                TaskNavigationRequest.Accepted(FIRST_ID, FIRST_ID),
                visibleTaskIds = setOf(SECOND_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertNull(registry.current(FIRST_ID))
    }

    @Test
    fun `same queue provisional is suppressed and reports queued after acceptance`() {
        val registry = ProvisionalTaskIdentityRegistry()
        val coordinator = TaskNavigationCoordinator(registry)
        val identity = ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name)

        assertEquals(
            TaskNavigationEffect.None,
            coordinator.handle(
                TaskNavigationRequest.OpenProvisional(FIRST_ID, identity),
                visibleTaskIds = setOf(SECOND_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertEquals(
            TaskNavigationEffect.ShowQueued,
            coordinator.handle(
                TaskNavigationRequest.Accepted(FIRST_ID, FIRST_ID),
                visibleTaskIds = setOf(SECOND_ID),
                topTaskQueueKind = TaskQueueKind.DATA,
            ),
        )
        assertNull(registry.current(FIRST_ID))
    }

    private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private companion object {
        val FIRST_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val SECOND_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
