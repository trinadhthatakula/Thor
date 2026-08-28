// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.app.Application
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

    private fun setSheet(systemRepository: SystemRepository) {
        val preferences = FakePreferenceRepository()
        val exportUseCase = ExportAppUseCase(
            bundleBuilder = FakeAppBundleBuilder(),
            preferenceRepository = preferences,
            fileStore = FakeAppBundleFileStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val launcher = IdleExportJobLauncher()
        val testModule = module {
            single<PreferenceRepository> { preferences }
            single<SystemRepository> { systemRepository }
            single { exportUseCase }
            single<ExportJobLauncher> { launcher }
            single { JobRegistry() }
            viewModel { ExportViewModel(get(), get(), get()) }
        }
        val testKoin = KoinApplication.init().also { it.koin.loadModules(listOf(testModule)) }
        composeRule.setContent {
            KoinIsolatedContext(testKoin) {
                ExportBottomSheet(userApp("com.example.game"), onDismiss = {})
            }
        }
    }

    private class ProbeControl(
        val started: CompletableDeferred<Job> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class IdleExportJobLauncher : ExportJobLauncher {
        override suspend fun startExport(request: AppExportRequest): UUID? = null
        override fun status(jobId: UUID): Flow<ThorJobStatus> = emptyFlow()
        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> = flowOf(null)
    }
}
