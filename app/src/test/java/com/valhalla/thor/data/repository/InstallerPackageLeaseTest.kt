// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.data.receivers.InstallReceiver
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.PackageOperationBarrier
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.util.UiText
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.fakes.RoboIntentSender
import org.robolectric.shadows.ShadowPackageInstaller

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [InstallerPackageLeaseTest.HeldSession::class])
class InstallerPackageLeaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Application
    private lateinit var coordinator: DefaultPackageOperationCoordinator
    private lateinit var repository: InstallerRepositoryImpl
    private lateinit var obb: ObbInstaller
    private lateinit var system: SystemRepository
    private lateinit var bus: InstallerEventBus
    private var globallyBlocked = false
    private var globalLeaseActive = false
    private var globalChecks = 0
    private val trace = mutableListOf<String>()
    private var installCommand: suspend () -> RootCommandResult = {
        markInstalled()
        RootCommandResult(0, emptyList(), emptyList())
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Environment.getExternalStorageDirectory().mkdirs()
        sources().deleteRecursively()
        receipts().deleteRecursively()
        shadowOf(context.packageManager).removePackage(PACKAGE)
        coordinator = DefaultPackageOperationCoordinator()
        bus = InstallerEventBus()
        HeldSession.nextCommit = CompletableDeferred()
        HeldSession.commits = 0
        HeldSession.abandons = 0
        val preferences = FakePreferenceRepository()
        val commands = object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult {
                trace += "install"
                return installCommand()
            }
        }
        val root = RootSystemGateway(context, commands, preferences, Dispatchers.Unconfined, TestRootAdmission())
        root.userIdProvider = { 0 }
        system = object : SystemRepository by FakeSystemRepository() {
            override suspend fun executeShellCommand(
                command: String,
                execution: PrivilegeExecutionContext,
            ): Result<Pair<Int, String?>> {
                if (command.startsWith("ls -1 ")) {
                    trace += "preflight"
                    return Result.success(0 to "THOR_OK")
                }
                trace += execution.commandClass.value
                val observer = requireNotNull(execution.rootExecutionObserver)
                observer.beforeSubmit()
                observer.onOutcome(terminal())
                return Result.success(0 to null)
            }
        }
        obb = ObbInstaller(context, system, Dispatchers.Unconfined)
        repository = InstallerRepositoryImpl(
            context, bus, root, ShizukuReflector(context), preferences, obb, coordinator,
            Dispatchers.Unconfined, Dispatchers.Unconfined,
            object : PackageOperationBarrier {
                override suspend fun isBlocked(packageName: String, owner: PackageOperationOwner) = false
                override suspend fun <T> withGlobalLease(block: suspend () -> T): PackageLeaseResult<T> {
                    globalChecks++
                    if (globallyBlocked) return PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA)
                    globalLeaseActive = true
                    return try {
                        PackageLeaseResult.Acquired(block())
                    } finally {
                        globalLeaseActive = false
                    }
                }
            },
        )
    }

    @After
    fun cleanUp() {
        sources().deleteRecursively()
        receipts().deleteRecursively()
        shadowOf(context.packageManager).removePackage(PACKAGE)
    }

    @Test
    fun `busy package refuses before preflight invocation callback or install`() = runTest {
        var entered = false
        val staged = staged()
        val lease = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            runCatching {
                repository.installPackage(
                    staged, Uri.fromFile(staged.file), InstallMode.ROOT,
                    execution = PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE, packageName = PACKAGE),
                    onInvocationStarted = { entered = true },
                )
            }.exceptionOrNull()
        }

        val failure = (lease as PackageLeaseResult.Acquired).value
        assertTrue(failure is PackageOperationBusy)
        assertEquals(PackageOperationOwner.ARCHIVE_RESTORE, (failure as PackageOperationBusy).owner)
        assertFalse(entered)
        assertTrue(trace.toString(), trace.isEmpty())
        assertAvailable()
    }

    @Test
    fun `package lease spans install and OBB outcome observation while other packages proceed`() = runTest {
        val installing = CompletableDeferred<Unit>()
        val releaseInstall = CompletableDeferred<Unit>()
        val observing = CompletableDeferred<Unit>()
        val releaseObserver = CompletableDeferred<Unit>()
        installCommand = {
            installing.complete(Unit)
            releaseInstall.await()
            markInstalled()
            RootCommandResult(0, emptyList(), emptyList())
        }
        val observer = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) {
                if (trace.last() == "obb.copy") {
                    observing.complete(Unit)
                    releaseObserver.await()
                }
            }
        }
        val staged = staged()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.ROOT,
                execution = PrivilegeExecutionContext(rootExecutionObserver = observer))
        }
        try {
            installing.await()
            assertBusy(PackageOperationOwner.REINSTALL)
            assertAvailable("com.example.other")
            releaseInstall.complete(Unit)
            observing.await()
            assertBusy(PackageOperationOwner.REINSTALL)
            assertFalse(install.isCompleted)
        } finally {
            releaseInstall.complete(Unit)
            releaseObserver.complete(Unit)
            install.await()
        }
        assertEquals(listOf("preflight", "install", "obb.mkdir", "obb.copy"), trace)
        assertEquals(InstallState.Success, bus.latest)
        assertAvailable()
        assertTrue(sources().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive adapter reuses its caller package lease without reacquiring it`() = runTest {
        val archiveInstaller = AppArchiveInstallerImpl(
            context, repository, system, bus, obb,
            FakePrivilegeStateProvider(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)),
            Dispatchers.Unconfined,
        )
        val staged = staged()
        val lease = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            val result = archiveInstaller.installBundle(staged.file, PACKAGE, listOf("base.apk"))
            assertBusy(PackageOperationOwner.ARCHIVE_RESTORE)
            result
        }

        assertEquals(ArchiveInstallOutcome.Installed, (lease as PackageLeaseResult.Acquired).value.outcome)
        assertEquals(listOf("preflight", "install", "obb.mkdir", "obb.copy"), trace)
        assertAvailable()
    }

    @Test
    fun `borrowed lease for a different package refuses before preflight and install`() = runTest {
        var entered = false
        val staged = staged()
        coordinator.withPackageLease("com.example.other", PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.ROOT,
                packageLeaseHeldFor = "com.example.other", onInvocationStarted = { entered = true })
        }

        assertFalse(entered)
        assertTrue(trace.isEmpty())
        assertTrue(bus.latest is InstallState.Error)
        assertAvailable()
    }

    @Test
    fun `cancellation during install releases its package lease without OBB dispatch`() = runTest {
        val installing = CompletableDeferred<Unit>()
        installCommand = { installing.complete(Unit); awaitCancellation() }
        val staged = staged()
        var failure: Throwable? = null
        val install = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.ROOT)
            } catch (caught: Throwable) { failure = caught }
        }
        installing.await()
        assertBusy(PackageOperationOwner.REINSTALL)
        install.cancel(CancellationException("cancel install"))
        install.join()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("preflight", "install"), trace)
        assertAvailable()
    }

    @Test
    fun `ordinary and structured install failures both release package admission`() = runTest {
        for (failure in listOf(null, ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE))) {
            trace.clear()
            bus.reset()
            installCommand = {
                if (failure != null) throw failure
                RootCommandResult(1, emptyList(), listOf("install refused"))
            }
            val staged = staged()

            val caught = runCatching {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.ROOT)
            }.exceptionOrNull()

            assertSame(failure, caught)
            if (failure == null) assertTrue(bus.latest is InstallState.Error)
            assertEquals(listOf("preflight", "install"), trace)
            assertAvailable()
        }
    }

    @Test
    fun `known XAPK external chooser refuses a busy package before preflight or invocation`() = runTest {
        var entered = false
        val staged = staged()
        val lease = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            runCatching {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.EXTERNAL,
                    onInvocationStarted = { entered = true })
            }.exceptionOrNull()
        }

        val failure = (lease as PackageLeaseResult.Acquired).value
        assertTrue(failure is PackageOperationBusy)
        assertEquals(PackageOperationOwner.ARCHIVE_RESTORE, (failure as PackageOperationBusy).owner)
        assertFalse(entered)
        assertTrue(trace.toString(), trace.isEmpty())
        assertEquals(0, globalChecks)
        assertAvailable()
    }

    @Test
    fun `known XAPK external chooser still invokes when package admission is available`() = runTest {
        var entered = false
        val staged = staged()
        repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.EXTERNAL,
            onInvocationStarted = { entered = true })

        assertTrue(entered)
        assertEquals(listOf("preflight"), trace)
        assertEquals(0, globalChecks)
        assertAvailable()
    }

    @Test
    fun `unknown package refuses every install mode before invocation or privileged calls while globally blocked`() = runTest {
        globallyBlocked = true
        val file = temporaryFolder.newFile("unknown.apk").apply { writeText("apk bytes") }
        val staged = StagedPackage(file, file.name)
        for (mode in InstallMode.entries) {
            var entered = false
            val failure = runCatching {
                repository.installPackage(
                    staged, Uri.fromFile(file), mode,
                    onInvocationStarted = { entered = true },
                )
            }.exceptionOrNull()

            assertTrue("$mode must refuse before invoking its installer", failure is PackageOperationBusy)
            assertEquals(PackageOperationOwner.CLEAR_DATA, (failure as PackageOperationBusy).owner)
            assertFalse(entered)
            assertTrue(trace.toString(), trace.isEmpty())
        }
        assertEquals(InstallMode.entries.size, globalChecks)
        assertFalse(globalLeaseActive)
    }

    @Test
    fun `unknown root install holds the global lease through invocation and privileged work`() = runTest {
        val file = temporaryFolder.newFile("unknown.apk").apply { writeText("apk bytes") }
        val staged = StagedPackage(file, file.name)
        var entered = false
        installCommand = {
            assertTrue(globalLeaseActive)
            RootCommandResult(0, emptyList(), emptyList())
        }

        repository.installPackage(
            staged, Uri.fromFile(file), InstallMode.ROOT,
            onInvocationStarted = {
                assertTrue(globalLeaseActive)
                entered = true
            },
        )

        assertTrue(entered)
        assertEquals(1, globalChecks)
        assertEquals(listOf("install"), trace)
        assertEquals(InstallState.Success, bus.latest)
        assertFalse(globalLeaseActive)
    }

    @Test
    fun `unknown normal install holds global admission through matching terminal success or failure`() = runTest {
        for (terminal in listOf(InstallState.Success, installFailure())) {
            HeldSession.nextCommit = CompletableDeferred()
            var succeeded = false
            val staged = unknownApk()
            val install = async(start = CoroutineStart.UNDISPATCHED) {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL,
                    onInstallSucceeded = { succeeded = true })
            }
            val session = HeldSession.nextCommit.await()
            try {
                assertTrue(globalLeaseActive)
                assertFalse(install.isCompleted)
                assertFalse(succeeded)
                assertEquals(0, HeldSession.abandons)
            } finally {
                session.emit(terminal)
                install.await()
            }
            assertEquals(terminal == InstallState.Success, succeeded)
            assertEquals(terminal, bus.latest)
            assertFalse(globalLeaseActive)
        }
        assertEquals(2, globalChecks)
    }

    @Test
    fun `known normal install holds package admission through matching terminal success or failure`() = runTest {
        for (terminal in listOf(InstallState.Success, installFailure())) {
            HeldSession.nextCommit = CompletableDeferred()
            val staged = staged(includeObb = false)
            val install = async(start = CoroutineStart.UNDISPATCHED) {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL)
            }
            val session = HeldSession.nextCommit.await()
            try {
                assertBusy(PackageOperationOwner.REINSTALL)
                assertAvailable("com.example.other")
                assertFalse(install.isCompleted)
            } finally {
                session.emit(terminal)
                install.await()
            }
            assertAvailable()
            assertEquals(terminal, bus.latest)
        }
        assertEquals(0, globalChecks)
        assertTrue(trace.toString(), trace.isEmpty())
    }

    @Test
    fun `normal terminal success survives a failed success observer without abandoning or resubmitting`() = runTest {
        val staged = unknownApk()
        var observerCalls = 0
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL,
                onInstallSucceeded = {
                    assertTrue(globalLeaseActive)
                    observerCalls++
                    throw IOException("success observer could not persist its bookkeeping")
                })
        }
        val session = HeldSession.nextCommit.await()
        try {
            assertTrue(globalLeaseActive)
            assertFalse(install.isCompleted)
            assertEquals(0, observerCalls)
        } finally {
            session.emit(InstallState.Success)
            install.await()
        }

        assertEquals(InstallState.Success, bus.latest)
        assertEquals(1, observerCalls)
        assertEquals(1, HeldSession.commits)
        assertEquals(0, HeldSession.abandons)
        assertFalse(globalLeaseActive)
    }

    @Test
    fun `normal session ignores confirmation unrelated events and mismatched callback identities`() = runTest {
        val staged = staged(includeObb = false)
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL)
        }
        val session = HeldSession.nextCommit.await()
        try {
            val nonterminalEvents: List<suspend () -> Unit> = listOf(
                { session.emit(InstallState.UserConfirmationRequired) },
                { bus.emit(InstallState.Success) },
                { bus.emit(installFailure()) },
                { bus.reset() },
                { bus.emitSessionResult(session.id + 1, session.token, InstallState.Success) },
                { bus.emitSessionResult(session.id, UUID.randomUUID().toString(), InstallState.Success) },
                { bus.emitSessionResult(session.id, null, InstallState.Success) },
            )
            for (emit in nonterminalEvents) {
                emit()
                assertFalse(install.isCompleted)
                assertBusy(PackageOperationOwner.REINSTALL)
            }
        } finally {
            session.emit(InstallState.Success)
            install.await()
        }
        assertAvailable()
    }

    @Test
    fun `cancellation after normal submission retains global admission until the terminal result`() = runTest {
        val staged = unknownApk()
        var succeeded = false
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL,
                onInstallSucceeded = { succeeded = true })
        }
        val session = HeldSession.nextCommit.await()
        install.cancel(CancellationException("cancel submitted install"))
        try {
            assertFalse(install.isCompleted)
            assertTrue(globalLeaseActive)
            assertFalse(succeeded)
            assertEquals(0, HeldSession.abandons)
        } finally {
            session.emit(InstallState.Success)
            install.join()
        }
        assertTrue(runCatching { install.await() }.exceptionOrNull() is CancellationException)
        assertTrue(succeeded)
        assertFalse(globalLeaseActive)
        assertEquals(0, HeldSession.abandons)
    }

    @Test
    fun `cancellation after normal submission retains the borrowed package lease until terminal failure`() = runTest {
        val staged = staged(includeObb = false)
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
                repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL,
                    packageLeaseHeldFor = PACKAGE)
            }
        }
        val session = HeldSession.nextCommit.await()
        install.cancel(CancellationException("cancel borrowed install"))
        try {
            assertFalse(install.isCompleted)
            assertBusy(PackageOperationOwner.ARCHIVE_RESTORE)
            assertEquals(0, HeldSession.abandons)
        } finally {
            session.emit(installFailure())
            install.join()
        }
        assertTrue(runCatching { install.await() }.exceptionOrNull() is CancellationException)
        assertAvailable()
        assertEquals(0, HeldSession.abandons)
    }

    @Test
    fun `normal session failure ignores a changed install timestamp and does not place game data`() = runTest {
        val staged = staged()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.NORMAL)
        }
        val session = HeldSession.nextCommit.await()
        try {
            assertEquals(listOf("preflight"), trace)
            assertBusy(PackageOperationOwner.REINSTALL)
            // Another install's package metadata cannot override this session's failure.
            markInstalled()
        } finally {
            session.emit(installFailure())
            install.await()
        }
        assertEquals(listOf("preflight"), trace)
        assertTrue(bus.latest is InstallState.Error)
        assertAvailable()
    }

    @Test
    fun `external unknown install releases global admission after chooser handoff without a session callback`() = runTest {
        val staged = unknownApk()
        val install = async(start = CoroutineStart.UNDISPATCHED) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.EXTERNAL)
        }

        install.await()

        assertEquals(InstallState.Success, bus.latest)
        assertEquals(1, globalChecks)
        assertFalse(globalLeaseActive)
        assertFalse(HeldSession.nextCommit.isCompleted)
        assertEquals(android.content.Intent.ACTION_CHOOSER, shadowOf(context).nextStartedActivity.action)
    }

    private fun unknownApk(): StagedPackage {
        val file = temporaryFolder.newFile("${UUID.randomUUID()}.apk").apply { writeText("apk bytes") }
        return StagedPackage(file, file.name)
    }

    private fun installFailure() = InstallState.Error(UiText.DynamicString("platform refused session"))

    private suspend fun SessionIdentity.emit(state: InstallState) {
        bus.emitSessionResult(id, token, state)
    }

    private fun staged(includeObb: Boolean = true): StagedPackage {
        val file = temporaryFolder.newFile("${UUID.randomUUID()}.xapk")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mutableListOf(
                "manifest.json" to if (includeObb) {
                    """{"package_name":"$PACKAGE","expansions":[{"file":"main.obb","install_path":"Android/obb/$PACKAGE/main.obb"}]}"""
                } else {
                    """{"package_name":"$PACKAGE"}"""
                },
                "base.apk" to "apk bytes",
            )
            if (includeObb) entries += "main.obb" to "game data"
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        return StagedPackage(file, file.name, listOf("base.apk"))
    }

    internal fun markInstalled() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = PACKAGE
            lastUpdateTime = 5_000L
            applicationInfo = ApplicationInfo().apply { packageName = PACKAGE; flags = ApplicationInfo.FLAG_INSTALLED }
        })
    }

    private suspend fun assertAvailable(packageName: String = PACKAGE) {
        val result = coordinator.withPackageLease(packageName, PackageOperationOwner.OTHER_MUTATION, Duration.ZERO) { true }
        assertEquals(PackageLeaseResult.Acquired(true), result)
    }

    private suspend fun assertBusy(owner: PackageOperationOwner) {
        val result = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.OTHER_MUTATION, Duration.ZERO) { error("package was not held") }
        assertEquals(PackageLeaseResult.Busy(owner), result)
    }

    private fun sources() = File(requireNotNull(context.getExternalFilesDir(null)), "obb_placement")
    private fun receipts() = File(context.noBackupFilesDir, "obb_placement")

    data class SessionIdentity(val id: Int, val token: String)

    /** Keeps real session creation and writes, replacing only the platform's asynchronous reply. */
    @Implements(PackageInstaller.Session::class)
    class HeldSession : ShadowPackageInstaller.ShadowSession() {
        @Implementation
        override fun commit(statusReceiver: IntentSender) {
            commits++
            val pendingIntent = (statusReceiver as RoboIntentSender).pendingIntent
            val intent = shadowOf(pendingIntent).savedIntent
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            check(id >= 0 && id == shadowOf(pendingIntent).requestCode)
            val token = requireNotNull(intent.getStringExtra(InstallReceiver.EXTRA_INSTALL_TOKEN))
            check(nextCommit.complete(SessionIdentity(id, token)))
        }

        @Implementation
        override fun abandon() {
            abandons++
            super.abandon()
        }

        companion object {
            lateinit var nextCommit: CompletableDeferred<SessionIdentity>
            var commits: Int = 0
            var abandons: Int = 0
        }
    }

    companion object {
        private const val PACKAGE = "com.example.game"
        internal fun terminal() = RootJobOutcome(
            RootJobOutcomeKind.EXITED, 0, emptyList(), emptyList(), true, true, true, true, null,
        )
    }
}
