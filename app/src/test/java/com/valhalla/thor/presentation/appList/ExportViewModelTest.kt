// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.presentation.common.JobPhase
import com.valhalla.thor.presentation.navigation.TaskNavigationRequest
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentity
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The export sheet's view model — specifically the `runningJobFor` reattach guard.
 *
 * `UnconfinedTestDispatcher`, the rule's default and deliberately *not* the `StandardTestDispatcher`
 * its backup sibling uses: `viewModelScope` is `Dispatchers.Main.immediate`, so an unconfined
 * dispatcher reproduces the one ordering that matters here — a collector that throws before its own
 * `launch` has returned, which means `watching` is assigned a Job that is already dead. A standard
 * dispatcher defers the body past the assignment and cannot reach that state at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    // --- doubles -------------------------------------------------------------------------------

    /**
     * @param running what `runningJobFor` emits. A `MutableSharedFlow` rather than a `MutableStateFlow`
     *   because one test re-emits the *same* id, which a state flow would conflate away — and that
     *   re-emission is the case the guard exists for.
     */
    private class FakeLauncher(
        val running: MutableSharedFlow<UUID?> = MutableSharedFlow(replay = 1),
    ) : ExportJobLauncher {

        /**
         * Per-id status flows. An id with no entry here gets one that throws on collect, which is how
         * a test stages a watcher failure without needing the export machinery to fail for real.
         */
        val statusesFor: MutableMap<UUID, MutableStateFlow<ThorJobStatus>> = mutableMapOf()
        private val runningByTarget = mutableMapOf("com.example.app" to running)

        fun runningFor(packageName: String): MutableSharedFlow<UUID?> =
            runningByTarget.getOrPut(packageName) { MutableSharedFlow(replay = 1) }

        /** Every `status` call, in order — the only way to see that a *new* watcher attached. */
        val statusCalls: MutableList<UUID> = mutableListOf()
        val requestedTaskIds: MutableList<UUID> = mutableListOf()
        var startResult: UUID? = null
        var startGate: CompletableDeferred<UUID?>? = null
        var startFailure: Exception? = null
        var legacyStartCalls: Int = 0

        override suspend fun startExport(request: AppExportRequest): UUID? {
            legacyStartCalls += 1
            return null
        }

        override suspend fun startExport(
            taskId: UUID,
            request: AppExportRequest,
        ): UUID? {
            requestedTaskIds += taskId
            startFailure?.let { throw it }
            return startGate?.await() ?: startResult
        }

        override fun status(jobId: UUID): Flow<ThorJobStatus> {
            statusCalls += jobId
            return statusesFor[jobId] ?: flow { error("no status flow for $jobId") }
        }

        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> =
            runningFor(target)
    }

    internal fun viewModel(
        launcher: ExportJobLauncher,
        taskNavigationTargets: TaskNavigationTargets =
            TaskNavigationTargets(ProvisionalTaskIdentityRegistry()),
    ): ExportViewModel = ExportViewModel(
            submissionCoordinator = ExportSubmissionCoordinator(
                launcher = launcher,
                taskNavigationTargets = taskNavigationTargets,
                ioDispatcher = dispatcher,
            ),
            launcher = launcher,
            registry = JobRegistry(),
            taskNavigationTargets = taskNavigationTargets,
        )

    private fun storedViewModel(
        store: ViewModelStore,
        launcher: FakeLauncher,
        taskNavigationTargets: TaskNavigationTargets,
    ): ExportViewModel {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        return ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    viewModel(launcher, taskNavigationTargets) as T
            },
        )[ExportViewModel::class.java]
    }

    private val first = UUID.fromString("00000000-0000-0000-0000-00000000e401")
    private val second = UUID.fromString("00000000-0000-0000-0000-00000000e402")

    private suspend fun attachReady(
        viewModel: ExportViewModel,
        launcher: FakeLauncher,
        packageName: String = "com.example.app",
    ) {
        viewModel.attach(packageName)
        launcher.runningFor(packageName).emit(null)
    }

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun `submission survives the sheet view model store clearing during durable acceptance`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher().apply { startGate = CompletableDeferred() }
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val store = ViewModelStore()
            val vm = storedViewModel(
                store = store,
                launcher = launcher,
                taskNavigationTargets = targets,
            )
            val requests = mutableListOf<TaskNavigationRequest>()
            backgroundScope.launch(dispatcher) { targets.requests.take(2).toList(requests) }
            attachReady(vm, launcher)

            vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
            val open = requests.single() as TaskNavigationRequest.OpenProvisional
            assertEquals(listOf(open.taskId), launcher.requestedTaskIds)

            store.clear()
            launcher.startGate?.complete(second)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(open, TaskNavigationRequest.Accepted(open.taskId, second)),
                requests,
            )
        }

    @Test
    fun `submission failure after the sheet view model clears rejects the exact task`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher().apply { startGate = CompletableDeferred() }
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val store = ViewModelStore()
            val vm = storedViewModel(
                store = store,
                launcher = launcher,
                taskNavigationTargets = targets,
            )
            val requests = mutableListOf<TaskNavigationRequest>()
            backgroundScope.launch(dispatcher) { targets.requests.take(2).toList(requests) }
            attachReady(vm, launcher)

            vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
            val open = requests.single() as TaskNavigationRequest.OpenProvisional
            store.clear()
            launcher.startGate?.completeExceptionally(IllegalStateException("acceptance failed"))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(open, TaskNavigationRequest.Rejected(open.taskId)),
                requests,
            )
        }

    @Test
    fun `start waits for active export discovery before accepting a tap`() = runTest(dispatcher) {
        val launcher = FakeLauncher().apply { startGate = CompletableDeferred() }
        val vm = viewModel(launcher)
        vm.attach("com.example.app")

        vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
        assertTrue(launcher.requestedTaskIds.isEmpty())

        launcher.running.emit(null)
        vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)

        assertEquals(1, launcher.requestedTaskIds.size)
        launcher.startGate?.complete(second)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `a second export tap before acceptance is ignored`() = runTest(dispatcher) {
        val launcher = FakeLauncher().apply { startGate = CompletableDeferred() }
        val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
        val vm = viewModel(launcher, targets)
        attachReady(vm, launcher)

        vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
        vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
        val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional

        assertEquals(listOf(open.taskId), launcher.requestedTaskIds)
        launcher.startGate?.complete(second)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `start opens provisional detail before acceptance and resolves a coalesced id`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher().apply {
                startGate = CompletableDeferred()
            }
            val identityRegistry = ProvisionalTaskIdentityRegistry()
            val targets = TaskNavigationTargets(identityRegistry)
            val vm = viewModel(launcher, targets)
            attachReady(vm, launcher)

            vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)

            val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional
            assertEquals(
                ProvisionalTaskIdentity(TaskQueueKind.DATA, DataTaskKind.APP_EXPORT.name),
                open.identity,
            )
            assertEquals(listOf(open.taskId), launcher.requestedTaskIds)
            assertEquals(0, launcher.legacyStartCalls)
            assertTrue(vm.phase.value.running)

            launcher.startGate?.complete(second)
            testScheduler.advanceUntilIdle()

            assertEquals(
                TaskNavigationRequest.Accepted(open.taskId, second),
                targets.requests.first(),
            )
        }

    @Test
    fun `start rejection marks the exact provisional detail failed`() = runTest(dispatcher) {
        val launcher = FakeLauncher().apply {
            startGate = CompletableDeferred()
        }
        val identityRegistry = ProvisionalTaskIdentityRegistry()
        val targets = TaskNavigationTargets(identityRegistry)
        val vm = viewModel(launcher, targets)
        attachReady(vm, launcher)

        vm.start("com.example.app", "Example", BundleFormat.APK, treeUri = null)
        val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional

        launcher.startGate?.complete(null)
        testScheduler.advanceUntilIdle()

        assertEquals(TaskNavigationRequest.Rejected(open.taskId), targets.requests.first())
        assertEquals(true, identityRegistry.currentState(open.taskId)?.rejected)
        assertEquals(false, vm.phase.value.running)
        assertTrue(vm.phase.value.settled)
    }

    @Test
    fun `a watcher that throws does not lock the sheet out of every later job`() =
        runTest(dispatcher) {
            // The guard used to read `watching == null`, and `watch`'s own `onFailure` cannot null the
            // field its `launchGuarded` is still being assigned into. So a collector that threw parked
            // a *completed* Job in `watching`, and from then on `runningJobFor` had nothing it could
            // hand over: every later export ran with the form still showing over it, and a tap on that
            // form appended a second export into the same staging directory.
            val launcher = FakeLauncher()
            val vm = viewModel(launcher)
            vm.attach("com.example.app")

            // No entry in `statusesFor`, so this one throws on collect.
            launcher.running.emit(first)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(first), launcher.statusCalls)
            assertEquals(false, vm.phase.value.running)
            assertTrue(vm.phase.value.settled)

            // The second job gets a status flow of its own on purpose: sharing one would let the dead
            // watcher answer for it, and a view model that never reattached would pass anyway.
            launcher.statusesFor[second] = MutableStateFlow(ThorJobStatus.Running)
            launcher.running.emit(second)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(first, second), launcher.statusCalls)
            assertEquals(true, vm.phase.value.running)
        }

    @Test
    fun `a live watcher survives the same id being re-emitted`() = runTest(dispatcher) {
        // What the guard was there for in the first place, and what the liveness form must not lose:
        // re-watching restarts the collector, and with it `watch`'s `finished = null`, over a job that
        // may already have reported its outcome.
        val launcher = FakeLauncher()
        launcher.statusesFor[first] = MutableStateFlow(ThorJobStatus.Running)
        val vm = viewModel(launcher)
        vm.attach("com.example.app")

        launcher.running.emit(first)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(first), launcher.statusCalls)

        launcher.running.emit(first)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(first), launcher.statusCalls)
        assertEquals(true, vm.phase.value.running)
    }

    @Test
    fun `attach to another package cancels old discovery and exact job watcher and clears phase`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher()
            val firstStatus = MutableStateFlow<ThorJobStatus>(ThorJobStatus.Running)
            val secondStatus = MutableStateFlow<ThorJobStatus>(ThorJobStatus.Running)
            launcher.statusesFor[first] = firstStatus
            launcher.statusesFor[second] = secondStatus
            val firstRunning = launcher.runningFor("com.example.first")
            val secondRunning = launcher.runningFor("com.example.second")
            val vm = viewModel(launcher)

            vm.attach("com.example.first")
            firstRunning.emit(first)
            testScheduler.advanceUntilIdle()
            assertEquals(1, firstRunning.subscriptionCount.value)
            assertEquals(1, firstStatus.subscriptionCount.value)
            assertEquals(true, vm.phase.value.running)

            vm.attach("com.example.second")
            testScheduler.advanceUntilIdle()
            assertEquals(0, firstRunning.subscriptionCount.value)
            assertEquals(0, firstStatus.subscriptionCount.value)
            assertEquals(JobPhase(), vm.phase.value)

            firstRunning.emit(first)
            secondRunning.emit(second)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(first, second), launcher.statusCalls)
            assertEquals(1, secondStatus.subscriptionCount.value)
        }

    @Test
    fun `switching packages detaches from late acceptance without cancelling submission`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher().apply { startGate = CompletableDeferred() }
            launcher.statusesFor[first] = MutableStateFlow(ThorJobStatus.Running)
            launcher.statusesFor[second] = MutableStateFlow(ThorJobStatus.Running)
            val targets = TaskNavigationTargets(ProvisionalTaskIdentityRegistry())
            val vm = viewModel(launcher, targets)

            attachReady(vm, launcher, "com.example.first")
            vm.start("com.example.first", "First", BundleFormat.APK, treeUri = null)
            val open = targets.requests.first() as TaskNavigationRequest.OpenProvisional

            vm.attach("com.example.second")
            assertEquals(JobPhase(), vm.phase.value)
            launcher.startGate?.complete(first)
            testScheduler.advanceUntilIdle()

            assertEquals(TaskNavigationRequest.Accepted(open.taskId, first), targets.requests.first())
            assertTrue(first !in launcher.statusCalls)

            launcher.runningFor("com.example.second").emit(second)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(second), launcher.statusCalls)
        }

    @Test
    fun `attach is idempotent for the package it is already on`() = runTest(dispatcher) {
        // The sheet calls it from a `LaunchedEffect` a recomposition can re-run. A second collector on
        // `runningJobFor` would be a second claimant on the same guard.
        val launcher = FakeLauncher()
        launcher.statusesFor[first] = MutableStateFlow(ThorJobStatus.Running)
        val vm = viewModel(launcher)
        vm.attach("com.example.app")
        vm.attach("com.example.app")

        launcher.running.emit(first)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(first), launcher.statusCalls)
    }
}
