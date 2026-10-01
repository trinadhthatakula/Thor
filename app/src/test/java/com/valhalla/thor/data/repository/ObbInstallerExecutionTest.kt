// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.IsolatedRootJob
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.executeIsolatedRootCommand
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.ObbPlacementUnresolved
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.ArchiveRollbackOutcome
import com.valhalla.thor.domain.repository.ArchiveRollbackReceipt
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** Real ZIP extraction and placement ownership, with the privileged command boundary controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ObbInstallerExecutionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Application
    private lateinit var sourceRoot: File
    private lateinit var receiptRoot: File
    private lateinit var recording: FakeSystemRepository
    private lateinit var system: SystemRepository
    private lateinit var installer: ObbInstaller
    private val commands = mutableListOf<Command>()
    private var execute: suspend (Command) -> Result<Pair<Int, String?>> = { acknowledge(it) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Environment.getExternalStorageDirectory().mkdirs()
        sourceRoot = File(requireNotNull(context.getExternalFilesDir(null)), "obb_placement")
        receiptRoot = File(context.noBackupFilesDir, "obb_placement")
        sourceRoot.deleteRecursively()
        receiptRoot.deleteRecursively()
        recording = FakeSystemRepository()
        system = object : SystemRepository by recording {
            override suspend fun executeShellCommand(
                command: String,
                execution: PrivilegeExecutionContext,
            ): Result<Pair<Int, String?>> {
                val call = Command(command, execution)
                commands += call
                return execute(call)
            }
        }
        installer = ObbInstaller(context, system, Dispatchers.Unconfined)
    }

    @After
    fun cleanUp() {
        sourceRoot.deleteRecursively()
        receiptRoot.deleteRecursively()
    }

    @Test
    fun `both entry points route commands and retain complete caller context and observers`() = runTest {
        for (entry in Entry.entries) {
            commands.clear()
            val events = mutableListOf<String>()
            val caller = PrivilegeExecutionContext(
                lane = PrivilegeExecutionLane.SWEEP,
                commandClass = PrivilegeCommandClass("custom.restore"),
                packageName = "com.example.caller",
                workRequestId = UUID.randomUUID(),
                sweepRequestId = UUID.randomUUID(),
                commandTimeout = 17.seconds,
                rootExecutionObserver = object : RootExecutionObserver {
                    override suspend fun beforeSubmit() {
                        assertTrue(receipt(PACKAGE).readText().contains("\"submitted\":true"))
                        events += "before"
                    }

                    override suspend fun onOutcome(outcome: RootJobOutcome) {
                        assertTrue(receipt(PACKAGE).readText().contains("\"kind\":\"EXITED\""))
                        events += "outcome"
                    }
                },
            )
            caller.provenance.recordDegradedRootFallback()
            execute = { command ->
                with(command.execution) {
                    assertEquals(PrivilegeExecutionLane.ARCHIVE, lane)
                    assertEquals(RootExecutionPolicy.ISOLATED, rootExecutionPolicy)
                    assertEquals(caller.packageName, packageName)
                    assertEquals(caller.workRequestId, workRequestId)
                    assertEquals(caller.sweepRequestId, sweepRequestId)
                    assertEquals(caller.commandTimeout, commandTimeout)
                    assertSame(caller.provenance, provenance)
                    assertTrue(provenance.usedDegradedRootFallback)
                }
                command.source?.let { source ->
                    assertTrue(source.canonicalFile.toPath().startsWith(sourceRoot.canonicalFile.toPath()))
                    assertEquals(PAYLOAD, source.readText())
                }
                acknowledge(command)
            }

            assertEquals(ObbPlacement.Placed(2), place(entry, bundle(), caller))

            assertEquals(listOf("obb.mkdir", "obb.copy", "obb.copy"), commands.map { it.execution.commandClass.value })
            assertEquals(List(3) { listOf("before", "outcome") }.flatten(), events)
            assertClean()
        }
    }

    @Test
    fun `placement supplies package identity and caps each command deadline`() = runTest {
        for (entry in Entry.entries) {
            for (timeout in listOf(null, 20.minutes, 2.minutes)) {
                commands.clear()
                assertEquals(ObbPlacement.Placed(2), place(entry, bundle(), PrivilegeExecutionContext(commandTimeout = timeout)))
                for (command in commands) {
                    assertEquals(PACKAGE, command.execution.packageName)
                    assertEquals(timeout?.coerceAtMost(9.minutes) ?: 9.minutes, command.execution.commandTimeout)
                }
                assertClean()
            }
        }
    }

    @Test
    fun `streaming holds one extracted source while legacy retains its existing extraction strategy`() = runTest {
        for (entry in Entry.entries) {
            val counts = mutableListOf<Int>()
            val progress = mutableListOf<Triple<String, Int, Int>>()
            execute = { command ->
                if (command.source != null) counts += sourceFiles().size
                acknowledge(command)
            }

            assertEquals(ObbPlacement.Placed(2), place(entry, bundle(), onFile = { name, index, total ->
                progress += Triple(name, index, total)
            }))

            assertEquals(if (entry == Entry.STREAMING) listOf(1, 1) else listOf(2, 2), counts)
            if (entry == Entry.STREAMING) {
                assertEquals(listOf(Triple("main.obb", 1, 2), Triple("patch.obb", 2, 2)), progress)
            }
            assertClean()
        }
    }

    @Test
    fun `cancellation retains sources and admission until terminal observer returns`() = runTest {
        for (entry in Entry.entries) {
            val submitted = CompletableDeferred<Unit>()
            val observing = CompletableDeferred<Unit>()
            val releaseObserver = CompletableDeferred<Unit>()
            lateinit var source: File
            val cancellation = TestCancellation("cancel OBB placement")
            var caught: Throwable? = null
            val caller = PrivilegeExecutionContext(rootExecutionObserver = object : RootExecutionObserver {
                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    if (outcome.kind == RootJobOutcomeKind.CANCELLED) {
                        assertTrue(source.exists())
                        observing.complete(Unit)
                        releaseObserver.await()
                        assertTrue(source.exists())
                    }
                }
            })
            execute = { command ->
                if (command.source == null) {
                    acknowledge(command)
                } else {
                    source = requireNotNull(command.source)
                    val observer = requireNotNull(command.execution.rootExecutionObserver)
                    observer.beforeSubmit()
                    submitted.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (failure: CancellationException) {
                        withContext(NonCancellable) { observer.onOutcome(terminal(RootJobOutcomeKind.CANCELLED)) }
                        throw failure
                    }
                }
            }
            val archive = bundle()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { place(entry, archive, caller) } catch (failure: Throwable) { caught = failure }
            }
            try {
                submitted.await()
                job.cancel(cancellation)
                observing.await()
                assertFalse(job.isCompleted)
                assertTrue(source.exists())
                val before = commands.size
                assertTrue(runCatching { place(entry, archive) }.exceptionOrNull() is ObbPlacementUnresolved)
                assertEquals(before, commands.size)
            } finally {
                releaseObserver.complete(Unit)
                job.join()
            }
            assertSame(cancellation, caught)
            assertFalse(source.exists())
            assertClean()
        }
    }

    @Test
    fun `uncertain or missing completion retains sources and blocks another placement before dispatch`() = runTest {
        for (entry in Entry.entries) {
            for (missing in listOf(false, true)) {
                val packageName = "com.example.game${entry.ordinal}${if (missing) 1 else 0}"
                execute = { command ->
                    if (command.source == null) acknowledge(command) else {
                        val observer = requireNotNull(command.execution.rootExecutionObserver)
                        observer.beforeSubmit()
                        if (!missing) observer.onOutcome(terminal(RootJobOutcomeKind.TERMINATION_UNCONFIRMED))
                        Result.success(0 to null)
                    }
                }
                val archive = bundle(packageName)
                assertTrue(runCatching { place(entry, archive, packageName = packageName) }.exceptionOrNull() is ObbPlacementUnresolved)
                val retained = sourceFiles().associateWith { it.readText() }
                assertTrue(retained.isNotEmpty())
                assertTrue(receipt(packageName).isFile)
                assertTrue(installer.hasUnresolvedPlacement(packageName))
                val before = commands.size

                assertTrue(runCatching { place(entry, archive, packageName = packageName) }.exceptionOrNull() is ObbPlacementUnresolved)

                assertEquals(before, commands.size)
                retained.forEach { (source, bytes) -> assertEquals(bytes, source.readText()) }
            }
        }
    }

    @Test
    fun `confirmed nonzero copy stops without placing the next file and cleans owned sources`() = runTest {
        for (entry in Entry.entries) {
            commands.clear()
            execute = { command -> acknowledge(command, if (command.source == null) 0 else 1) }

            assertTrue(place(entry, bundle()) is ObbPlacement.Failed)

            assertEquals(listOf("obb.mkdir", "obb.copy"), commands.map { it.execution.commandClass.value })
            assertClean()
        }
    }

    @Test
    fun `successful Shizuku commands do not require root observer callbacks`() = runTest {
        execute = { Result.success(0 to null) }
        val caller = PrivilegeExecutionContext(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun beforeSubmit() = error("Shizuku must not report a root submission")
            override suspend fun onOutcome(outcome: RootJobOutcome) = error("Shizuku must not report a root outcome")
        })
        for (entry in Entry.entries) {
            assertEquals(ObbPlacement.Placed(2), place(entry, bundle(), caller))
            assertClean()
        }
    }

    @Test
    fun `archives without expansions return without commands or staging`() = runTest {
        execute = { error("No expansions must not dispatch a command") }
        for (entry in Entry.entries) assertEquals(ObbPlacement.NotNeeded, place(entry, bundle(leaves = emptyList())))
        assertTrue(commands.isEmpty())
        assertClean()
    }

    @Test
    fun `automatic rollback refuses an installed package with unresolved OBB placement`() = runTest {
        execute = { command ->
            val observer = requireNotNull(command.execution.rootExecutionObserver)
            observer.beforeSubmit()
            if (command.source == null) observer.onOutcome(terminal())
            Result.success(0 to null)
        }
        assertTrue(runCatching { place(Entry.STREAMING, bundle()) }.exceptionOrNull() is ObbPlacementUnresolved)
        val packages = shadowOf(context.packageManager)
        packages.installPackage(PackageInfo().apply {
            packageName = PACKAGE
            lastUpdateTime = 5_000L
            applicationInfo = ApplicationInfo().apply { packageName = PACKAGE; flags = ApplicationInfo.FLAG_INSTALLED }
        })
        val unusedInstaller = object : InstallerRepository {
            override suspend fun installPackage(
                staged: StagedPackage, uri: Uri, mode: InstallMode, canDowngrade: Boolean,
                grantAllPermissions: Boolean?, execution: PrivilegeExecutionContext,
                onInvocationStarted: () -> Unit, onInstallSucceeded: () -> Unit, bypassLowTargetSdkBlock: Boolean,
            ) = error("rollback must not start an install")
        }
        val archiveInstaller = AppArchiveInstallerImpl(
            context, unusedInstaller, system, InstallerEventBus(), installer,
            FakePrivilegeStateProvider(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)),
            Dispatchers.Unconfined,
        )
        try {
            assertTrue(archiveInstaller.hasUnresolvedObbPlacement(PACKAGE))
            assertFalse(archiveInstaller.hasUnresolvedObbPlacement("com.example.other"))
            assertEquals(ArchiveRollbackOutcome.REFUSED, archiveInstaller.rollbackNewInstall(ArchiveRollbackReceipt(PACKAGE, 5_000L)))
            assertTrue(recording.calls.isEmpty())
            assertEquals(5_000L, context.packageManager.getPackageInfo(PACKAGE, 0).lastUpdateTime)
            assertTrue(sourceFiles().isNotEmpty())
        } finally {
            packages.removePackage(PACKAGE)
        }
    }

    @Test
    fun `outer install timeout preserves an unconfirmed OBB copy instead of returning ordinary unconfirmed install`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        installer = ObbInstaller(context, system, dispatcher)
        val obb = installer
        var submitted = 0
        var cancellations = 0
        execute = { command ->
            if (command.source == null) {
                acknowledge(command)
            } else {
                val completion = CompletableDeferred<RootJobOutcome>()
                val job = object : IsolatedRootJob {
                    override fun submit() { submitted++ }
                    override fun cancel() {
                        cancellations++
                        completion.complete(terminal(RootJobOutcomeKind.TERMINATION_UNCONFIRMED))
                    }
                    override suspend fun await() = completion.await()
                }
                val result = withTimeout(9.minutes) {
                    executeIsolatedRootCommand(RootCommand(command.text, command.execution), job)
                }
                Result.success(result.exitCode to null)
            }
        }
        val archiveInstaller = archiveInstallerForTimeout(dispatcher) { file, execution ->
            delay(2.minutes) // The ten-minute install budget wins before this copy's nine-minute budget.
            obb.place(file, PACKAGE, execution)
        }

        val failure = runCatching { archiveInstaller.installBundle(bundle(), PACKAGE, listOf("base.apk")) }
            .exceptionOrNull()

        assertTrue(failure is ObbPlacementUnresolved)
        assertEquals(1, submitted)
        assertEquals(1, cancellations)
        assertEquals(listOf("obb.mkdir", "obb.copy"), commands.map { it.execution.commandClass.value })
        assertTrue(sourceFiles().isNotEmpty())
        assertTrue(receipt(PACKAGE).readText().contains("TERMINATION_UNCONFIRMED"))
        assertTrue(obb.hasUnresolvedPlacement(PACKAGE))
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun `outer install timeout with acknowledged OBB cancellation keeps the ordinary timeout result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        installer = ObbInstaller(context, system, dispatcher)
        val obb = installer
        var cancellations = 0
        execute = { command ->
            if (command.source == null) {
                acknowledge(command)
            } else {
                val completion = CompletableDeferred<RootJobOutcome>()
                val job = object : IsolatedRootJob {
                    override fun submit() = Unit
                    override fun cancel() {
                        cancellations++
                        completion.complete(terminal(RootJobOutcomeKind.CANCELLED))
                    }
                    override suspend fun await() = completion.await()
                }
                val result = withTimeout(9.minutes) {
                    executeIsolatedRootCommand(RootCommand(command.text, command.execution), job)
                }
                Result.success(result.exitCode to null)
            }
        }
        val archiveInstaller = archiveInstallerForTimeout(dispatcher) { file, execution ->
            delay(2.minutes)
            obb.place(file, PACKAGE, execution)
        }

        val result = archiveInstaller.installBundle(bundle(), PACKAGE, listOf("base.apk"))

        assertEquals(ArchiveInstallOutcome.Unconfirmed, result.outcome)
        assertEquals(1, cancellations)
        assertClean()
        assertFalse(obb.hasUnresolvedPlacement(PACKAGE))
    }

    @Test
    fun `outer install timeout without OBB ownership keeps the ordinary timeout result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val archiveInstaller = archiveInstallerForTimeout(dispatcher) { _, _ -> awaitCancellation() }

        val result = archiveInstaller.installBundle(bundle(leaves = emptyList()), PACKAGE, listOf("base.apk"))

        assertEquals(ArchiveInstallOutcome.Unconfirmed, result.outcome)
        assertTrue(commands.isEmpty())
        assertClean()
    }

    private fun archiveInstallerForTimeout(
        dispatcher: CoroutineDispatcher,
        install: suspend (File, PrivilegeExecutionContext) -> Unit,
    ): AppArchiveInstallerImpl {
        val repository = object : InstallerRepository {
            override suspend fun installPackage(
                staged: StagedPackage, uri: Uri, mode: InstallMode, canDowngrade: Boolean,
                grantAllPermissions: Boolean?, execution: PrivilegeExecutionContext,
                onInvocationStarted: () -> Unit, onInstallSucceeded: () -> Unit, bypassLowTargetSdkBlock: Boolean,
            ) {
                onInvocationStarted()
                install(staged.file, execution)
            }
        }
        return AppArchiveInstallerImpl(
            context, repository, system, InstallerEventBus(), installer,
            FakePrivilegeStateProvider(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)),
            dispatcher,
        )
    }

    private suspend fun place(
        entry: Entry,
        bundle: File,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
        packageName: String = PACKAGE,
        onFile: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): ObbPlacement = when (entry) {
        Entry.LEGACY -> installer.place(bundle, packageName, execution)
        Entry.STREAMING -> installer.placeStreaming(bundle, packageName, onFile, execution)
    }

    private fun bundle(packageName: String = PACKAGE, leaves: List<String> = listOf("main.obb", "patch.obb")): File =
        temporaryFolder.newFile("${UUID.randomUUID()}.xapk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in listOf("base.apk" to "apk") + leaves.map { "Android/obb/$packageName/$it" to PAYLOAD }) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    internal fun receipt(packageName: String) = File(receiptRoot, "$packageName/receipt.json")
    private fun sourceFiles() = sourceRoot.walkTopDown().filter { it.isFile }.toList()
    private fun assertClean() {
        assertTrue(sourceRoot.listFiles().orEmpty().isEmpty())
        assertTrue(receiptRoot.listFiles().orEmpty().isEmpty())
    }

    private data class Command(val text: String, val execution: PrivilegeExecutionContext) {
        val source: File? get() = Regex("cp -f '([^']+)'").find(text)?.groupValues?.get(1)?.let(::File)
    }

    private enum class Entry { LEGACY, STREAMING }

    private class TestCancellation(message: String) : CancellationException(message) {
        // An extra field prevents coroutine stacktrace recovery from copying this test exception.
        @Suppress("unused") private val identity = Any()
    }

    companion object {
        private const val PACKAGE = "com.example.game"
        private const val PAYLOAD = "complete expansion bytes"

        private suspend fun acknowledge(command: Command, exitCode: Int = 0): Result<Pair<Int, String?>> {
            val observer = requireNotNull(command.execution.rootExecutionObserver)
            observer.beforeSubmit()
            observer.onOutcome(terminal(exitCode = exitCode))
            return Result.success(exitCode to null)
        }

        internal fun terminal(kind: RootJobOutcomeKind = RootJobOutcomeKind.EXITED, exitCode: Int = 0) = RootJobOutcome(
            kind = kind,
            exitCode = if (kind == RootJobOutcomeKind.EXITED) exitCode else null,
            stdout = emptyList(),
            stderr = emptyList(),
            started = true,
            terminationConfirmed = kind != RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            outputDrained = true,
            shellReusable = kind != RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            failure = null,
        )
    }
}
