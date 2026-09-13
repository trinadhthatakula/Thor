// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.FakeAppBundleBuilder
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentityRegistry
import com.valhalla.thor.presentation.userApp
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ExportBottomSheetTest {

    private val effectScheduler = TestCoroutineScheduler()
    private val effectDispatcher = StandardTestDispatcher(effectScheduler)
    private val effectParent = Job()
    private val uncaughtEffectFailures = ConcurrentLinkedQueue<Throwable>()
    private val effectExceptionHandler = CoroutineExceptionHandler { _, failure ->
        uncaughtEffectFailures += failure
    }

    @get:Rule
    val composeRule = createComposeRule(
        effectContext = effectParent + effectDispatcher + effectExceptionHandler,
    )

    @Test
    fun `first export reopening after task navigation does not replay the completed result`() {
        val backStack = mutableStateListOf(ExportTestRoute.APPS)
        val launcher = ControllableExportJobLauncher()
        setSheet(FakeSystemRepository(), launcher, backStack = backStack)
        composeRule.onNodeWithText("Open export").performClick()
        composeRule.onNodeWithText("Background").assertExists()

        composeRule.runOnIdle { backStack.add(ExportTestRoute.TASK) }
        composeRule.onNodeWithText("Close task").assertExists()
        composeRule.runOnIdle {
            launcher.status.value = ThorJobStatus.Succeeded()
            launcher.running.value = null
        }
        composeRule.onNodeWithText("Close task").performClick()
        composeRule.onNodeWithText("Selected fixture").assertExists()
        composeRule.onNodeWithText("EXPORT").assertDoesNotExist()
        composeRule.onNodeWithText("Open export").performClick()

        composeRule.onNodeWithText("Exported.").assertDoesNotExist()
        composeRule.onNodeWithText("Downloads/Thor").assertExists()
        composeRule.mainClock.advanceTimeBy(4_000)
        composeRule.onNodeWithText("Downloads/Thor").assertExists()
    }

    @Test
    fun `first export reopening after task navigation reattaches to unfinished work`() {
        val backStack = mutableStateListOf(ExportTestRoute.APPS)
        val launcher = ControllableExportJobLauncher()
        setSheet(FakeSystemRepository(), launcher, backStack = backStack)
        composeRule.onNodeWithText("Open export").performClick()
        composeRule.onNodeWithText("Background").assertExists()

        composeRule.runOnIdle { backStack.add(ExportTestRoute.TASK) }
        composeRule.onNodeWithText("Close task").performClick()
        composeRule.onNodeWithText("Open export").performClick()

        composeRule.onNodeWithText("Background").assertExists()
        composeRule.onNodeWithText("Downloads/Thor").assertDoesNotExist()
    }

    @Test
    fun `reopening after a backgrounded export finishes shows the form instead of its old result`() {
        val shown = mutableStateOf(true)
        val launcher = ControllableExportJobLauncher()
        setSheet(FakeSystemRepository(), launcher, shown)
        composeRule.onNodeWithText("Background").assertExists()

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            launcher.status.value = ThorJobStatus.Succeeded()
            launcher.running.value = null
            shown.value = true
        }

        composeRule.onNodeWithText("Downloads/Thor").assertExists()
        composeRule.onNodeWithText("Exported.").assertDoesNotExist()
    }

    @Test
    fun `reopening while an export is still active watches it instead of offering a duplicate`() {
        val shown = mutableStateOf(true)
        val launcher = ControllableExportJobLauncher()
        setSheet(FakeSystemRepository(), launcher, shown)
        composeRule.onNodeWithText("Background").assertExists()

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { shown.value = true }

        composeRule.onNodeWithText("Background").assertExists()
        composeRule.onNodeWithText("Downloads/Thor").assertDoesNotExist()
    }

    @Test
    fun `sheet effect maps lane busy to the stable unreadable warning`() =
        assertTypedFailureMapped(ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE))

    @Test
    fun `sheet effect maps lane degraded to the stable unreadable warning`() =
        assertTypedFailureMapped(ShellLaneDegraded(PrivilegeExecutionLane.ARCHIVE))

    @Test
    fun `sheet effect maps transport death to the stable unreadable warning`() =
        assertTypedFailureMapped(ShellTransportDied(PrivilegeExecutionLane.ARCHIVE))

    @Test
    fun `sheet effect maps command timeout to the stable unreadable warning`() =
        assertTypedFailureMapped(ShellCommandTimedOut(PrivilegeCommandClass("obb.probe")))

    @Test
    fun `sheet effect keeps structured cancellation identity`() {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("obb.probe"),
            CancellationException("cancelled"),
        )
        val (repository, probe) = gatedFailure(cancellation)

        val effectJob = startSheetAndCaptureProbeJob(repository, probe)
        val completion = observeCompletion(effectJob)
        probe.release.complete(Unit)
        composeRule.waitForIdle()

        assertSame(cancellation, runBlocking { completion.await() })
        assertTrue(uncaughtEffectFailures.isEmpty())
        composeRule.onNodeWithText("The .xapk will still be built, without it.", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `sheet effect leaves an ordinary programming failure unchanged`() {
        val failure = IllegalStateException("ordinary failure")
        val (repository, probe) = gatedFailure(failure)

        val effectJob = startSheetAndCaptureProbeJob(repository, probe)
        val completion = observeCompletion(effectJob)
        probe.release.complete(Unit)
        composeRule.waitForIdle()

        assertSame(failure, runBlocking { completion.await() })
        assertSame(failure, uncaughtEffectFailures.single())
    }

    private fun assertTypedFailureMapped(failure: PrivilegeExecutionException) {
        val (repository, probe) = gatedFailure(failure)

        val effectJob = startSheetAndCaptureProbeJob(repository, probe)
        val completion = observeCompletion(effectJob)
        probe.release.complete(Unit)
        composeRule.waitForIdle()

        assertNull(runBlocking { completion.await() })
        assertTrue(uncaughtEffectFailures.isEmpty())
        composeRule.onNodeWithText(".xapk").performClick()
        composeRule.onNodeWithText("The .xapk will still be built, without it.", substring = true)
            .assertExists()
    }

    private fun gatedFailure(failure: Throwable): Pair<FakeSystemRepository, ProbeControl> {
        val probe = ProbeControl()
        val repository = FakeSystemRepository().apply {
            obbProbeFailure = failure
            beforeObbProbeResult = {
                probe.started.complete(currentCoroutineContext()[Job]!!)
                probe.release.await()
            }
        }
        return repository to probe
    }

    private fun startSheetAndCaptureProbeJob(
        repository: FakeSystemRepository,
        probe: ProbeControl,
    ): Job {
        setSheet(repository)
        effectScheduler.runCurrent()
        composeRule.waitUntil(timeoutMillis = 5_000) { probe.started.isCompleted }
        val effectJob = runBlocking { probe.started.await() }

        assertTrue(effectJob.isActive)
        assertFalse(effectJob === effectParent)
        assertEquals(
            1,
            repository.calls.count { it == "probeObb:com.example.game" },
        )
        return effectJob
    }

    private fun observeCompletion(job: Job): CompletableDeferred<Throwable?> =
        CompletableDeferred<Throwable?>().also { completion ->
            job.invokeOnCompletion(completion::complete)
        }

    private fun setSheet(
        systemRepository: SystemRepository,
        launcher: ExportJobLauncher = IdleExportJobLauncher(),
        shown: MutableState<Boolean> = mutableStateOf(true),
        backStack: SnapshotStateList<ExportTestRoute>? = null,
    ) {
        val preferences = FakePreferenceRepository()
        val exportUseCase = ExportAppUseCase(
            bundleBuilder = FakeAppBundleBuilder(),
            preferenceRepository = preferences,
            fileStore = FakeAppBundleFileStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val testModule = module {
            single<PreferenceRepository> { preferences }
            single<SystemRepository> { systemRepository }
            single { exportUseCase }
            single<ExportJobLauncher> { launcher }
            single { JobRegistry() }
            single { ProvisionalTaskIdentityRegistry() }
            single { TaskNavigationTargets(get()) }
            single {
                ExportSubmissionCoordinator(
                    launcher = get(),
                    taskNavigationTargets = get(),
                    ioDispatcher = Dispatchers.Unconfined,
                )
            }
            viewModel { ExportViewModel(get(), get(), get(), get()) }
        }
        val testKoin = KoinApplication.init().also { it.koin.loadModules(listOf(testModule)) }
        composeRule.setContent {
            KoinIsolatedContext(testKoin) {
                if (backStack != null) {
                    ExportNavigationHost(backStack)
                } else if (shown.value) {
                    ExportBottomSheet(userApp("com.example.game"), onDismiss = { shown.value = false })
                }
            }
        }
    }

    private enum class ExportTestRoute : NavKey { APPS, TASK }

    @Composable
    private fun ExportNavigationHost(backStack: SnapshotStateList<ExportTestRoute>) {
        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { route ->
                NavEntry(route) {
                    when (route) {
                        ExportTestRoute.APPS -> {
                            // Match AppList's saved selection and AppInfoSheet's unsaved overlay.
                            val selected by rememberSaveable { mutableStateOf(true) }
                            var exportShown by remember { mutableStateOf(false) }
                            if (selected) {
                                Text("Selected fixture")
                                Button(onClick = { exportShown = true }) { Text("Open export") }
                                if (exportShown) {
                                    ExportBottomSheet(userApp("com.example.game")) { exportShown = false }
                                }
                            }
                        }
                        ExportTestRoute.TASK -> Button(onClick = { backStack.removeAt(backStack.lastIndex) }) {
                            Text("Close task")
                        }
                    }
                }
            },
        )
        NavDisplay(entries = entries, onBack = { backStack.removeAt(backStack.lastIndex) })
    }

    private class ProbeControl(
        val started: CompletableDeferred<Job> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class ControllableExportJobLauncher : ExportJobLauncher {
        private val taskId = UUID.fromString("00000000-0000-0000-0000-00000000e501")
        val running = MutableStateFlow<UUID?>(taskId)
        val status = MutableStateFlow<ThorJobStatus>(ThorJobStatus.Running)

        override suspend fun startExport(request: AppExportRequest): UUID? =
            error("Reopening must not submit an export")

        override fun status(jobId: UUID): Flow<ThorJobStatus> {
            check(jobId == taskId)
            return status
        }

        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> {
            check(kind == ThorJobKind.APP_EXPORT && target == "com.example.game")
            return running
        }
    }

    private class IdleExportJobLauncher : ExportJobLauncher {
        override suspend fun startExport(request: AppExportRequest): UUID? = null
        override fun status(jobId: UUID): Flow<ThorJobStatus> = emptyFlow()
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = flowOf(null)
    }
}
