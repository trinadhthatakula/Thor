// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.AnalyzedPackage
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.util.UiText
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class InstallerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var fixtureNumber = 0

    @Test
    fun `startInstallation maps every typed execution failure to stable user text`() = runTest {
        executionFailures().forEach { failure ->
            val fixture = fixture(failure)
            fixture.parseReadyPackage()

            val completion = fixture.startAndObserveCompletion()
            runCurrent()

            assertNull(completion.await())
            assertEquals(
                InstallState.Error(UiText.StringResource(R.string.unknown_error_occurred)),
                fixture.eventBus.latest,
            )
            fixture.assertRealInstallCall()
        }
    }

    @Test
    fun `startInstallation keeps structured cancellation identity`() = runTest {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("installer.root"),
            CancellationException("cancelled"),
        )
        val fixture = fixture(cancellation)
        fixture.parseReadyPackage()
        val ready = fixture.eventBus.latest

        val completion = fixture.startAndObserveCompletion()
        runCurrent()

        assertSame(cancellation, completion.await())
        assertSame(
            "cancellation must not be presented as a stable error",
            ready,
            fixture.eventBus.latest
        )
        fixture.assertRealInstallCall()
    }

    @Test
    fun `startInstallation leaves an ordinary failure unchanged`() {
        val failure = IllegalStateException("ordinary failure")
        val completionCause = AtomicReference<Throwable?>()
        var fixture: Fixture? = null

        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest {
                val currentFixture = fixture(failure)
                fixture = currentFixture
                currentFixture.parseReadyPackage()

                val parent = currentFixture.viewModel.viewModelScope.coroutineContext[Job]!!
                val existingChildren = parent.children.toSet()
                currentFixture.viewModel.startInstallation()
                val installJob = (parent.children.toSet() - existingChildren).single()
                installJob.invokeOnCompletion(completionCause::set)
                runCurrent()
            }
        }

        assertSame(failure, thrown)
        assertSame(failure, completionCause.get())
        fixture!!.assertRealInstallCall()
    }

    private fun fixture(failure: Throwable): Fixture {
        val uri = "content://com.example.provider/package.apk".toUri()
        val staged = StagedPackage(
            file = temporaryFolder.newFile("package-${fixtureNumber++}.apk"),
            displayName = "package.apk",
        )
        val analyzed = AnalyzedPackage(
            metadata = AppMetadata(
                label = "Example",
                packageName = "com.example.package",
                version = "1.0",
                versionCode = 1L,
                iconPath = null,
            ),
            staged = staged,
        )
        val analyzer = SuccessfulAnalyzer(analyzed)
        val repository = FailingInstallerRepository(failure)
        val eventBus = InstallerEventBus()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = InstallerViewModel(
            repository = repository,
            analyzer = analyzer,
            eventBus = eventBus,
            packageManager = application.packageManager,
            systemRepository = FakeSystemRepository(),
            preferenceRepository = FakePreferenceRepository(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        return Fixture(viewModel, analyzer, repository, eventBus, analyzed, uri)
    }

    private fun Fixture.parseReadyPackage() {
        viewModel.parsePackage(uri)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        assertEquals(listOf(uri), analyzer.analyzedUris)
        assertTrue(eventBus.latest is InstallState.ReadyToInstall)
    }

    private fun Fixture.startAndObserveCompletion(): CompletableDeferred<Throwable?> {
        val parent = viewModel.viewModelScope.coroutineContext[Job]!!
        val existingChildren = parent.children.toSet()

        viewModel.startInstallation()
        val installJob = (parent.children.toSet() - existingChildren).single()
        return CompletableDeferred<Throwable?>().also { completion ->
            installJob.invokeOnCompletion(completion::complete)
        }
    }

    private fun Fixture.assertRealInstallCall() {
        val call = repository.calls.single()
        assertSame(analyzed.staged, call.staged)
        assertEquals(uri, call.uri)
        assertEquals(InstallMode.ROOT, call.mode)
        assertEquals(false, call.canDowngrade)
        assertNull(call.grantAllPermissions)
    }

    private fun executionFailures(): List<PrivilegeExecutionException> = listOf(
        ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE),
        ShellLaneDegraded(PrivilegeExecutionLane.INTERACTIVE),
        ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE),
        ShellCommandTimedOut(PrivilegeCommandClass("installer.root")),
    )

    private data class Fixture(
        val viewModel: InstallerViewModel,
        val analyzer: SuccessfulAnalyzer,
        val repository: FailingInstallerRepository,
        val eventBus: InstallerEventBus,
        val analyzed: AnalyzedPackage,
        val uri: Uri,
    )

    private class SuccessfulAnalyzer(
        private val analyzed: AnalyzedPackage,
    ) : AppAnalyzer {
        val analyzedUris = mutableListOf<Uri>()

        override suspend fun analyze(uri: Uri): Result<AnalyzedPackage> {
            analyzedUris += uri
            return Result.success(analyzed)
        }

        override fun discard(analyzed: AnalyzedPackage?) = Unit
    }

    private class FailingInstallerRepository(
        private val failure: Throwable,
    ) : InstallerRepository {
        val calls = mutableListOf<InstallCall>()

        override suspend fun installPackage(
            staged: StagedPackage,
            uri: Uri,
            mode: InstallMode,
            canDowngrade: Boolean,
            grantAllPermissions: Boolean?,
            execution: PrivilegeExecutionContext,
            onInvocationStarted: () -> Unit,
        ) {
            onInvocationStarted()
            calls += InstallCall(staged, uri, mode, canDowngrade, grantAllPermissions)
            throw failure
        }
    }

    private data class InstallCall(
        val staged: StagedPackage,
        val uri: Uri,
        val mode: InstallMode,
        val canDowngrade: Boolean,
        val grantAllPermissions: Boolean?,
    )
}
