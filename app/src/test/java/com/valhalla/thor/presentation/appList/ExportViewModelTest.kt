// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.FakeAppBundleBuilder
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

        /** Every `status` call, in order — the only way to see that a *new* watcher attached. */
        val statusCalls: MutableList<UUID> = mutableListOf()

        override suspend fun startExport(request: AppExportRequest): UUID? = null

        override fun status(jobId: UUID): Flow<ThorJobStatus> {
            statusCalls += jobId
            return statusesFor[jobId] ?: flow { error("no status flow for $jobId") }
        }

        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = running
    }

    private fun viewModel(launcher: FakeLauncher) = ExportViewModel(
        // Never invoked by these tests — nothing here calls `start` — but the constructor needs one,
        // and a real use case over the existing fakes is cheaper than a fourth port.
        exportUseCase = ExportAppUseCase(
            bundleBuilder = FakeAppBundleBuilder(),
            preferenceRepository = FakePreferenceRepository(),
            fileStore = FakeAppBundleFileStore(),
            ioDispatcher = dispatcher,
        ),
        launcher = launcher,
        registry = JobRegistry(),
    )

    private val first = UUID.fromString("00000000-0000-0000-0000-00000000e401")
    private val second = UUID.fromString("00000000-0000-0000-0000-00000000e402")

    // --- tests ---------------------------------------------------------------------------------

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
