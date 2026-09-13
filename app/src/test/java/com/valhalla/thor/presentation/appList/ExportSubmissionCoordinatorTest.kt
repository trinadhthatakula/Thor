// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.presentation.navigation.TaskNavigationRequest
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportSubmissionCoordinatorTest {

    @Test
    fun `same package submissions share one acceptance and fan out the canonical id`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val launcher = BlockingLauncher()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = coordinator(launcher, targets, dispatcher)

        val firstResult = coordinator.submit(
            FIRST,
            PACKAGE,
            "First",
            BundleFormat.APK,
            treeUri = FIRST_TREE,
        )
        val secondResult = coordinator.submit(
            SECOND,
            PACKAGE,
            "Second",
            BundleFormat.APKS,
            treeUri = SECOND_TREE,
        )
        runCurrent()

        assertEquals(1, launcher.calls.size)
        assertEquals(FIRST, launcher.calls.single().taskId)
        assertEquals(BundleFormat.APK, launcher.calls.single().request.format)
        assertEquals(FIRST_TREE, launcher.calls.single().request.treeUri)

        launcher.calls.single().result.complete(CANONICAL)
        advanceUntilIdle()

        assertEquals(CANONICAL, firstResult.await())
        assertEquals(CANONICAL, secondResult.await())
        assertEquals(
            listOf(
                TaskNavigationRequest.Accepted(FIRST, CANONICAL),
                TaskNavigationRequest.Accepted(SECOND, CANONICAL),
            ),
            targets.requests.take(2).toList(),
        )
    }

    @Test
    fun `same package rejection is delivered to every candidate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val launcher = BlockingLauncher()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = coordinator(launcher, targets, dispatcher)

        val firstResult = coordinator.submit(FIRST, PACKAGE, "First", BundleFormat.APK, treeUri = null)
        val secondResult = coordinator.submit(SECOND, PACKAGE, "Second", BundleFormat.APK, treeUri = null)
        runCurrent()
        assertEquals(1, launcher.calls.size)

        launcher.calls.single().result.complete(null)
        advanceUntilIdle()

        assertEquals(null, firstResult.await())
        assertEquals(null, secondResult.await())
        assertEquals(
            listOf(TaskNavigationRequest.Rejected(FIRST), TaskNavigationRequest.Rejected(SECOND)),
            targets.requests.take(2).toList(),
        )
    }

    @Test
    fun `different packages remain concurrent and settled package may submit again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val launcher = BlockingLauncher()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = coordinator(launcher, targets, dispatcher)

        val firstResult = coordinator.submit(FIRST, PACKAGE, "First", BundleFormat.APK, treeUri = null)
        val otherResult = coordinator.submit(SECOND, OTHER_PACKAGE, "Other", BundleFormat.APK, treeUri = null)
        runCurrent()
        assertEquals(2, launcher.calls.size)

        launcher.calls[0].result.complete(CANONICAL)
        launcher.calls[1].result.complete(OTHER_CANONICAL)
        advanceUntilIdle()
        assertEquals(CANONICAL, firstResult.await())
        assertEquals(OTHER_CANONICAL, otherResult.await())

        coordinator.submit(THIRD, PACKAGE, "Third", BundleFormat.APK, treeUri = null)
        runCurrent()
        assertEquals(3, launcher.calls.size)
    }

    @Test
    fun `cancelling one result handle does not cancel shared acceptance or navigation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val launcher = BlockingLauncher()
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val coordinator = coordinator(launcher, targets, dispatcher)

        val abandoned = coordinator.submit(FIRST, PACKAGE, "First", BundleFormat.APK, treeUri = null)
        val observed = coordinator.submit(SECOND, PACKAGE, "Second", BundleFormat.APK, treeUri = null)
        runCurrent()
        abandoned.cancel()
        launcher.calls.single().result.complete(CANONICAL)
        advanceUntilIdle()

        assertTrue(abandoned.isCancelled)
        assertEquals(CANONICAL, observed.await())
        assertEquals(
            listOf(
                TaskNavigationRequest.Accepted(FIRST, CANONICAL),
                TaskNavigationRequest.Accepted(SECOND, CANONICAL),
            ),
            targets.requests.take(2).toList(),
        )
    }

    private fun coordinator(
        launcher: ExportJobLauncher,
        targets: TaskNavigationTargets,
        dispatcher: CoroutineDispatcher,
    ) = ExportSubmissionCoordinator(
        launcher = launcher,
        taskNavigationTargets = targets,
        ioDispatcher = dispatcher,
    )

    private class BlockingLauncher : ExportJobLauncher {
        data class Call(
            val taskId: UUID,
            val request: AppExportRequest,
            val result: CompletableDeferred<UUID?> = CompletableDeferred(),
        )

        val calls = mutableListOf<Call>()

        override suspend fun startExport(request: AppExportRequest): UUID? = error("legacy call")

        override suspend fun startExport(taskId: UUID, request: AppExportRequest): UUID? {
            val call = Call(taskId, request)
            calls += call
            return call.result.await()
        }

        override fun status(jobId: UUID): Flow<ThorJobStatus> = flowOf(ThorJobStatus.Running)

        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = flowOf(null)
    }

    private companion object {
        const val PACKAGE = "com.example.same"
        const val OTHER_PACKAGE = "com.example.other"
        const val FIRST_TREE = "content://example/first"
        const val SECOND_TREE = "content://example/second"
        val FIRST: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val SECOND: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val THIRD: UUID = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val CANONICAL: UUID = UUID.fromString("40000000-0000-0000-0000-000000000004")
        val OTHER_CANONICAL: UUID = UUID.fromString("50000000-0000-0000-0000-000000000005")
    }
}
