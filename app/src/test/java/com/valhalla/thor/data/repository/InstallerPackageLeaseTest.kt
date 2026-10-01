// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
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
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InstallerPackageLeaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Application
    private lateinit var coordinator: DefaultPackageOperationCoordinator
    private lateinit var repository: InstallerRepositoryImpl
    private lateinit var obb: ObbInstaller
    private lateinit var system: SystemRepository
    private lateinit var bus: InstallerEventBus
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
    fun `external chooser handoff does not acquire the package lease`() = runTest {
        var entered = false
        val staged = staged()
        coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            repository.installPackage(staged, Uri.fromFile(staged.file), InstallMode.EXTERNAL,
                onInvocationStarted = { entered = true })
        }

        assertTrue(entered)
        assertEquals(listOf("preflight"), trace)
        assertAvailable()
    }

    private fun staged(): StagedPackage {
        val file = temporaryFolder.newFile("${UUID.randomUUID()}.xapk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf(
                "manifest.json" to """{"package_name":"$PACKAGE","expansions":[{"file":"main.obb","install_path":"Android/obb/$PACKAGE/main.obb"}]}""",
                "base.apk" to "apk bytes", "main.obb" to "game data",
            )) {
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

    companion object {
        private const val PACKAGE = "com.example.game"
        internal fun terminal() = RootJobOutcome(
            RootJobOutcomeKind.EXITED, 0, emptyList(), emptyList(), true, true, true, true, null,
        )
    }
}
