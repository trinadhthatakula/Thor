// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeStorageStatsProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PrivilegeExecutionProductionPathTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `SystemRepositoryImpl OBB probe keeps every execution failure identity`() = runTest {
        allExecutionFailures().forEach { failure ->
            val repository = systemRepository(failure)

            val caught = caught { repository.probeObb("com.example.game") }

            assertSame(failure, caught)
        }
    }

    @Test
    fun `SystemRepositoryImpl OBB probe keeps ordinary undetermined fallback`() = runTest {
        val repository = systemRepository(IllegalStateException("ordinary probe failure"))

        assertEquals(
            ObbProbe.Undetermined("ordinary probe failure"),
            repository.probeObb("com.example.game"),
        )
    }

    @Test
    fun `SystemRepositoryImpl measures private data through the archive lane`() = runTest {
        val executor = RecordingMeasureExecutor()
        val repository = systemRepository(executor)

        val size = repository.measureDataClass("com.example.private", DataClass.CE)

        assertEquals(DataClassSize.Empty, size)
        assertEquals(2, executor.commands.size)
        assertEquals(PrivilegeExecutionLane.ARCHIVE, executor.commands[0].execution.lane)
        with(executor.commands[1].execution) {
            assertEquals(PrivilegeExecutionLane.ARCHIVE, lane)
            assertEquals(PrivilegeCommandClass("archive.measure"), commandClass)
            assertEquals("com.example.private", packageName)
            assertEquals(null, commandTimeout)
        }
    }

    @Test
    fun `AppBundleBuilderImpl OBB copy keeps every execution failure identity`() = runTest {
        allExecutionFailures().forEach { failure ->
            val system = FakeSystemRepository().apply {
                obbProbe = ObbProbe.Present(
                    listOf(ObbFile("main.1.com.example.game.obb", 1L)),
                    otherEntryCount = 0
                )
                shellCommandFailure = failure
            }
            val builder = bundleBuilder(system)

            val outcome = runCatching {
                builder.build(bundleApp(), "obb-copy", BundleFormat.XAPK).getOrThrow()
            }

            assertSame(failure, outcome.exceptionOrNull())
            assertEquals(1, system.calls.count { it.startsWith("executeShellCommand:") })
        }
    }

    @Test
    fun `AppBundleBuilderImpl OBB copy keeps ordinary null-copy fallback`() = runTest {
        val ordinary = IllegalStateException("ordinary OBB copy failure")
        val system = FakeSystemRepository().apply {
            obbProbe = ObbProbe.Present(
                listOf(ObbFile("main.1.com.example.game.obb", 1L)),
                otherEntryCount = 0
            )
            shellCommandFailure = ordinary
        }

        val outcome = bundleBuilder(system)
            .build(bundleApp(), "obb-copy-generic", BundleFormat.XAPK)

        assertFalse(outcome.isSuccess)
        assertNotSame(ordinary, outcome.exceptionOrNull())
        assertEquals(
            "this app's game data could not be read, so the .xapk would be incomplete",
            outcome.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `AppBundleBuilderImpl root-copy fallback keeps every execution failure identity`() =
        runTest {
            allExecutionFailures().forEach { failure ->
                val system = FakeSystemRepository().apply { rootCopyFailure = failure }
                val builder = bundleBuilder(system)

                val outcome = runCatching {
                    builder.build(missingSourceApp(), "root-copy", BundleFormat.APK).getOrThrow()
                }

                assertSame(failure, outcome.exceptionOrNull())
                assertEquals(1, system.calls.count { it.startsWith("copyFileWithRoot:") })
            }
        }

    @Test
    fun `AppBundleBuilderImpl root-copy keeps ordinary false fallback`() = runTest {
        val ordinary = IllegalStateException("ordinary root copy failure")
        val system = FakeSystemRepository().apply { rootCopyFailure = ordinary }

        val outcome = bundleBuilder(system)
            .build(missingSourceApp(), "root-copy-generic", BundleFormat.APK)

        assertFalse(outcome.isSuccess)
        assertNotSame(ordinary, outcome.exceptionOrNull())
        assertEquals("Failed to copy base APK", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `InstallerRepositoryImpl root install keeps every execution failure identity`() = runTest {
        allExecutionFailures().forEach { failure ->
            val fixture = installerRepository(failure)

            val caught = caught {
                fixture.repository.installPackage(
                    staged = fixture.staged,
                    uri = Uri.fromFile(fixture.staged.file),
                    mode = InstallMode.ROOT,
                )
            }

            assertSame(failure, caught)
            assertEquals(1, fixture.executor.installCalls)
        }
    }

    @Test
    fun `InstallerRepositoryImpl root install keeps ordinary error state`() = runTest {
        val ordinary = IllegalStateException("ordinary root install failure")
        val fixture = installerRepository(ordinary)

        fixture.repository.installPackage(
            staged = fixture.staged,
            uri = Uri.fromFile(fixture.staged.file),
            mode = InstallMode.ROOT,
        )

        assertEquals(
            InstallState.Error(UiText.DynamicString("ordinary root install failure")),
            fixture.bus.latest,
        )
        assertEquals(1, fixture.executor.installCalls)
    }

    @Test
    fun `AppArchiveInstallerImpl keeps every execution failure identity`() = runTest {
        allExecutionFailures().forEach { failure ->
            val fixture = archiveInstaller(failure)

            val caught = caught {
                fixture.installer.installBundle(
                    fixture.bundle,
                    "com.example.game",
                    listOf("base.apk"),
                    com.valhalla.thor.domain.model.PrivilegeExecutionContext(),
                )
            }

            assertSame(failure, caught)
            assertEquals(1, fixture.repository.calls)
        }
    }

    @Test
    fun `AppArchiveInstallerImpl diagnostics omit throwable and outcome details`() {
        val source = archiveInstallerSource()

        assertFalse(
            "archive installer diagnostics pass throwable details to Logger",
            Regex("""Logger\.e\(TAG,\s*"[^"]*",\s*e\)""").containsMatchIn(source),
        )
        assertFalse(
            "archive installer logs a failure reason through the full outcome",
            source.contains("outcome=\$outcome"),
        )
    }

    @Test
    fun `AppArchiveInstallerImpl keeps ordinary failed outcome`() = runTest {
        val ordinary = IllegalStateException("ordinary archive install failure")
        val fixture = archiveInstaller(ordinary)

        val outcome = fixture.installer.installBundle(
            fixture.bundle,
            "com.example.game",
            listOf("base.apk"),
            com.valhalla.thor.domain.model.PrivilegeExecutionContext(),
        )

        assertEquals(ArchiveInstallOutcome.Failed("ordinary archive install failure"), outcome.outcome)
        assertEquals(1, fixture.repository.calls)
    }

    private fun systemRepository(failure: Throwable): SystemRepositoryImpl =
        systemRepository(ProbeThenFailExecutor(failure))

    private fun systemRepository(executor: RootCommandExecutor): SystemRepositoryImpl {
        val preferences = FakePreferenceRepository(
            UserPreferences(preferredPrivilegeMode = PrivilegeMode.ROOT)
        )
        val root = RootSystemGateway(context, executor, preferences, Dispatchers.Unconfined).also {
            it.userIdProvider = { 0 }
        }
        return SystemRepositoryImpl(
            rootGateway = root,
            shizukuGateway = ShizukuSystemGateway(
                context,
                ShizukuReflector(context),
                preferences,
                Dispatchers.Unconfined,
            ),
            dhizukuGateway = DhizukuSystemGateway(
                context,
                DhizukuReflector(context),
                preferences,
                Dispatchers.Unconfined,
            ),
            preferenceRepository = preferences,
            storageStats = FakeStorageStatsProvider(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun bundleBuilder(system: FakeSystemRepository) = AppBundleBuilderImpl(
        context = context,
        systemRepository = system,
        apksMetadataGenerator = ApksMetadataGenerator(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun bundleApp() = userApp("com.example.game", appName = "Game").copy(
        sourceDir = temporaryFolder.newFile("base-${System.nanoTime()}.apk").apply {
            writeText("apk")
        }.absolutePath,
        versionName = "1.0",
        versionCode = 1L,
    )

    private fun missingSourceApp() = userApp("com.example.missing", appName = "Missing").copy(
        sourceDir = File(temporaryFolder.root, "missing-${System.nanoTime()}.apk").absolutePath,
    )

    private fun installerRepository(failure: Throwable): InstallerFixture {
        val preferences = FakePreferenceRepository()
        val executor = AlwaysFailExecutor(failure)
        val root = RootSystemGateway(context, executor, preferences, Dispatchers.Unconfined).also {
            it.userIdProvider = { 0 }
        }
        val system = FakeSystemRepository()
        val bus = InstallerEventBus()
        val repository = InstallerRepositoryImpl(
            context = context,
            eventBus = bus,
            rootGateway = root,
            shizukuReflector = ShizukuReflector(context),
            preferenceRepository = preferences,
            obbInstaller = ObbInstaller(context, system, Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        val staged = StagedPackage(
            temporaryFolder.newFile("install-${System.nanoTime()}.apk").apply { writeText("apk") },
            "sample.apk",
        )
        return InstallerFixture(repository, executor, bus, staged)
    }

    private fun archiveInstaller(failure: Throwable): ArchiveFixture {
        val repository = ThrowingInstallerRepository(failure)
        val system = FakeSystemRepository()
        val installer = AppArchiveInstallerImpl(
            context = context,
            installerRepository = repository,
            systemRepository = system,
            eventBus = InstallerEventBus(),
            obbInstaller = ObbInstaller(context, system, Dispatchers.Unconfined),
            privilegeState = FakePrivilegeStateProvider(
                PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val bundle = temporaryFolder.newFile("archive-${System.nanoTime()}.apk").apply {
            writeText("apk")
        }
        return ArchiveFixture(installer, repository, bundle)
    }

    private class ProbeThenFailExecutor(private val failure: Throwable) : RootCommandExecutor {
        private var calls = 0

        override suspend fun execute(command: RootCommand): RootCommandResult {
            calls++
            if (calls == 1) return RootCommandResult(0, listOf("0"), emptyList())
            throw failure
        }
    }

    private class RecordingMeasureExecutor : RootCommandExecutor {
        val commands = mutableListOf<RootCommand>()

        override suspend fun execute(command: RootCommand): RootCommandResult {
            commands += command
            return if (commands.size == 1) {
                RootCommandResult(0, listOf("0"), emptyList())
            } else {
                RootCommandResult(44, emptyList(), emptyList())
            }
        }
    }

    private class AlwaysFailExecutor(private val failure: Throwable) : RootCommandExecutor {
        var installCalls = 0

        override suspend fun execute(command: RootCommand): RootCommandResult {
            installCalls++
            throw failure
        }
    }

    private class ThrowingInstallerRepository(
        private val failure: Throwable,
    ) : InstallerRepository {
        var calls = 0

        override suspend fun installPackage(
            staged: StagedPackage,
            uri: Uri,
            mode: InstallMode,
            canDowngrade: Boolean,
            grantAllPermissions: Boolean?,
            execution: com.valhalla.thor.domain.model.PrivilegeExecutionContext,
        ) {
            calls++
            throw failure
        }
    }

    private data class InstallerFixture(
        val repository: InstallerRepositoryImpl,
        val executor: AlwaysFailExecutor,
        val bus: InstallerEventBus,
        val staged: StagedPackage,
    )

    private data class ArchiveFixture(
        val installer: AppArchiveInstallerImpl,
        val repository: ThrowingInstallerRepository,
        val bundle: File,
    )

    private fun archiveInstallerSource(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val source = File(
                directory,
                "app/src/main/java/com/valhalla/thor/data/repository/AppArchiveInstallerImpl.kt",
            )
            if (source.isFile) return source.readText()
            directory = directory.parentFile
        }
        error("could not locate AppArchiveInstallerImpl.kt")
    }

    private suspend fun caught(block: suspend () -> Unit): Throwable =
        runCatching { block() }.exceptionOrNull() ?: error("expected an exception")

    private fun allExecutionFailures(): List<Throwable> = listOf(
        ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE),
        ShellLaneDegraded(PrivilegeExecutionLane.ARCHIVE),
        ShellTransportDied(PrivilegeExecutionLane.ARCHIVE),
        ShellCommandTimedOut(PrivilegeCommandClass("archive.copy")),
        ShellCommandCancelled(
            PrivilegeCommandClass("archive.copy"),
            CancellationException("cancelled"),
        ),
    )
}
