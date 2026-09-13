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
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import com.valhalla.thor.util.UiText
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `saved off asks once before legacy Root install`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        val confirmation = fixture.viewModel.legacyInstallConfirmation.value!!
        assertEquals(fixture.analyzed.metadata, confirmation.meta)
        assertTrue(fixture.repository.calls.isEmpty())
        fixture.viewModel.startInstallation()
        runCurrent()
        assertTrue(fixture.repository.calls.isEmpty())
    }

    @Test
    fun `confirming legacy install starts exactly once without writing the saved setting`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        val confirmation = fixture.viewModel.legacyInstallConfirmation.value!!
        fixture.viewModel.confirmLegacyInstallation(confirmation.id)
        fixture.viewModel.confirmLegacyInstallation(confirmation.id)
        runCurrent()
        assertTrue(fixture.repository.calls.single().bypassLowTargetSdkBlock)
        assertEquals(0, fixture.preferences.legacyWrites)
    }

    @Test
    fun `saved on installs legacy Shizuku without a StateFlow collector and keeps permission choice`() = runTest {
        val fixture = fixture(targetSdk = 23, allowLegacyApkInstall = true)
        fixture.parseReadyPackage()
        fixture.viewModel.setInstallMode(InstallMode.SHIZUKU)
        fixture.viewModel.setGrantAllPermissions(false)
        fixture.viewModel.startInstallation()
        runCurrent()
        val call = fixture.repository.calls.single()
        assertEquals(InstallMode.SHIZUKU, call.mode)
        assertTrue(call.bypassLowTargetSdkBlock)
        assertEquals(false, call.grantAllPermissions)
    }

    @Test
    fun `dismissing and turning saved consent off restore the legacy prompt`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        fixture.viewModel.dismissLegacyInstallConfirmation(fixture.viewModel.legacyInstallConfirmation.value!!.id)
        assertNull(fixture.viewModel.legacyInstallConfirmation.value)
        fixture.preferences.setAllowLegacyApkInstall(true)
        fixture.viewModel.startInstallation()
        runCurrent()
        assertTrue(fixture.repository.calls.single().bypassLowTargetSdkBlock)
        fixture.preferences.setAllowLegacyApkInstall(false)
        fixture.viewModel.startInstallation()
        runCurrent()
        assertTrue(fixture.viewModel.legacyInstallConfirmation.value != null)
    }

    @Test
    fun `stale legacy confirmation callbacks cannot approve a changed selection`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.setInstallMode(InstallMode.SHIZUKU)
        fixture.viewModel.startInstallation()
        runCurrent()
        val stale = fixture.viewModel.legacyInstallConfirmation.value!!.id
        fixture.viewModel.setGrantAllPermissions(false)
        fixture.viewModel.confirmLegacyInstallation(stale)
        runCurrent()
        assertTrue(fixture.repository.calls.isEmpty())
    }

    @Test
    fun `a failed confirmed legacy install requires fresh confirmation`() = runTest {
        val fixture = fixture(
            failure = ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE),
            targetSdk = 23,
        )
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        val first = fixture.viewModel.legacyInstallConfirmation.value!!.id
        fixture.viewModel.confirmLegacyInstallation(first)
        runCurrent()
        assertEquals(1, fixture.repository.calls.size)

        fixture.viewModel.startInstallation()
        runCurrent()
        val retry = fixture.viewModel.legacyInstallConfirmation.value!!.id
        assertTrue(retry > first)
    }

    @Test
    fun `legacy setting read failure fails closed to confirmation`() = runTest {
        val fixture = fixture(targetSdk = 23, legacyReadHook = { throw IOException("unreadable") })
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()

        assertEquals(1, fixture.preferences.legacyReadCalls)
        assertTrue(fixture.repository.calls.isEmpty())
        assertTrue(fixture.viewModel.legacyInstallConfirmation.value != null)
    }

    @Test
    fun `legacy setting read cancellation propagates`() = runTest {
        val cancellation = CancellationException("cancel read")
        val fixture = fixture(targetSdk = 23, legacyReadHook = { throw cancellation })
        fixture.parseReadyPackage()

        val completion = fixture.startAndObserveCompletion()
        runCurrent()

        val propagated = completion.await()
        assertTrue(propagated is CancellationException)
        assertEquals(cancellation.message, propagated?.message)
        assertTrue(fixture.repository.calls.isEmpty())
    }

    @Test
    fun `superseded legacy setting read cannot install or publish a stale confirmation`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val fixture = fixture(targetSdk = 23, legacyReadHook = { gate.await() })
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        fixture.viewModel.setInstallMode(InstallMode.NORMAL)
        gate.complete(true)
        runCurrent()

        assertTrue(fixture.repository.calls.isEmpty())
        assertNull(fixture.viewModel.legacyInstallConfirmation.value)
    }

    @Test
    fun `stale confirmation ID after dismiss cannot approve a new request`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        val dismissed = fixture.viewModel.legacyInstallConfirmation.value!!.id
        fixture.viewModel.dismissLegacyInstallConfirmation(dismissed)
        fixture.viewModel.startInstallation()
        runCurrent()
        val current = fixture.viewModel.legacyInstallConfirmation.value!!

        fixture.viewModel.confirmLegacyInstallation(dismissed)
        runCurrent()

        assertTrue(fixture.repository.calls.isEmpty())
        assertEquals(current, fixture.viewModel.legacyInstallConfirmation.value)
    }

    @Test
    fun `repeated install taps during legacy setting read produce one read and no invocation`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val fixture = fixture(targetSdk = 23, legacyReadHook = { gate.await() })
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        fixture.viewModel.startInstallation()
        runCurrent()

        assertEquals(1, fixture.preferences.legacyReadCalls)
        assertTrue(fixture.repository.calls.isEmpty())
        gate.complete(false)
        runCurrent()
    }

    @Test
    fun `target zero is eligible for saved-off legacy confirmation`() = runTest {
        val fixture = fixture(targetSdk = 0)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        val confirmation = fixture.viewModel.legacyInstallConfirmation.value!!
        fixture.viewModel.confirmLegacyInstallation(confirmation.id)
        runCurrent()

        assertTrue(fixture.repository.calls.single().bypassLowTargetSdkBlock)
    }

    @Test
    fun `unknown modern and unsupported modes never bypass even when saved on`() = runTest {
        for ((target, mode) in listOf(
            null to InstallMode.ROOT,
            24 to InstallMode.ROOT,
            23 to InstallMode.NORMAL,
            23 to InstallMode.DHIZUKU,
            23 to InstallMode.EXTERNAL,
        )) {
            val fixture = fixture(targetSdk = target, allowLegacyApkInstall = true)
            fixture.parseReadyPackage()
            fixture.viewModel.setInstallMode(mode)
            fixture.viewModel.startInstallation()
            runCurrent()
            assertFalse(fixture.repository.calls.single().bypassLowTargetSdkBlock)
        }
    }

    @Test
    @Config(sdk = [34])
    fun `Android 14 accepts target 23 without a bypass`() = runTest {
        val fixture = fixture(targetSdk = 23)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        assertFalse(fixture.repository.calls.single().bypassLowTargetSdkBlock)
    }

    @Test
    @Config(sdk = [33])
    fun `older Android never bypasses even when saved on`() = runTest {
        val fixture = fixture(targetSdk = 22, allowLegacyApkInstall = true)
        fixture.parseReadyPackage()
        fixture.viewModel.startInstallation()
        runCurrent()
        assertFalse(fixture.repository.calls.single().bypassLowTargetSdkBlock)
    }

    private fun fixture(
        failure: Throwable? = null,
        targetSdk: Int? = null,
        allowLegacyApkInstall: Boolean = false,
        legacyReadHook: (suspend () -> Boolean)? = null,
    ): Fixture {
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
                targetSdk = targetSdk,
            ),
            staged = staged,
        )
        val analyzer = SuccessfulAnalyzer(analyzed)
        val repository = FailingInstallerRepository(failure)
        val preferences = TrackingPreferenceRepository(allowLegacyApkInstall, legacyReadHook)
        val eventBus = InstallerEventBus()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = InstallerViewModel(
            repository = repository,
            analyzer = analyzer,
            eventBus = eventBus,
            packageManager = application.packageManager,
            systemRepository = FakeSystemRepository(),
            preferenceRepository = preferences,
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        return Fixture(viewModel, analyzer, repository, eventBus, analyzed, uri, preferences)
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
        val preferences: TrackingPreferenceRepository,
    )

    private class TrackingPreferenceRepository(
        initialAllowLegacyApkInstall: Boolean,
        private val readHook: (suspend () -> Boolean)? = null,
        private val delegate: FakePreferenceRepository = FakePreferenceRepository(
            UserPreferences(allowLegacyApkInstall = initialAllowLegacyApkInstall),
        ),
    ) : PreferenceRepository by delegate {
        var legacyWrites = 0
        var legacyReadCalls = 0

        override suspend fun setAllowLegacyApkInstall(enabled: Boolean) {
            legacyWrites++
            delegate.setAllowLegacyApkInstall(enabled)
        }

        override suspend fun shouldAllowLegacyApkInstall(): Boolean {
            legacyReadCalls++
            return readHook?.invoke() ?: delegate.shouldAllowLegacyApkInstall()
        }
    }

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
        private val failure: Throwable?,
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
            onInstallSucceeded: () -> Unit,
            bypassLowTargetSdkBlock: Boolean,
        ) {
            onInvocationStarted()
            calls += InstallCall(staged, uri, mode, canDowngrade, grantAllPermissions, bypassLowTargetSdkBlock)
            if (failure != null) throw failure
        }
    }

    private data class InstallCall(
        val staged: StagedPackage,
        val uri: Uri,
        val mode: InstallMode,
        val canDowngrade: Boolean,
        val grantAllPermissions: Boolean?,
        val bypassLowTargetSdkBlock: Boolean,
    )
}
