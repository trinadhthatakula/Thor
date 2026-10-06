// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.ObbExportStagingDir
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import com.valhalla.thor.presentation.FakeAppBundleFileStore
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExportRootCopyAdoptionTest {
    private lateinit var context: Application
    private lateinit var source: File
    private lateinit var rootStaging: File
    private lateinit var recording: FakeSystemRepository
    private lateinit var builder: AppBundleBuilderImpl
    private val stagingScope = "export-adoption-${UUID.randomUUID()}"
    private val payload = "complete protected APK bytes"
    private val copiedDestinations = mutableListOf<File>()
    private var rootCopy: suspend (File, PrivilegeExecutionContext) -> Result<Unit> = { _, _ ->
        error("unexpected privileged APK copy")
    }
    private var shellCommand: suspend (PrivilegeExecutionContext) -> Unit = {
        error("unexpected shell command")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File(context.filesDir, "unreadable-source-${UUID.randomUUID()}.apk")
        rootStaging = File(context.noBackupFilesDir, "root_export_staging")
        rootStaging.deleteRecursively()
        recording = FakeSystemRepository()
        val system = object : SystemRepository by recording {
            override suspend fun copyFileWithRoot(
                sourcePath: String,
                destinationPath: String,
                execution: PrivilegeExecutionContext,
            ): Result<Unit> {
                recording.copyFileWithRoot(sourcePath, destinationPath, execution)
                val destination = File(destinationPath)
                copiedDestinations += destination
                return rootCopy(destination, execution)
            }

            override suspend fun executeShellCommand(
                command: String,
                execution: PrivilegeExecutionContext,
            ): Result<Pair<Int, String?>> {
                recording.executeShellCommand(command, execution)
                shellCommand(execution)
                return Result.success(0 to null)
            }
        }
        builder = AppBundleBuilderImpl(context, system, ApksMetadataGenerator(), Dispatchers.Unconfined)
    }

    @After
    fun cleanUp() {
        source.delete()
        File(context.cacheDir, stagingScope).deleteRecursively()
        rootStaging.deleteRecursively()
    }

    @Test
    fun `root export forces archive isolation while preserving caller context and observer`() = runBlocking {
        val events = mutableListOf<String>()
        val expectedOutcome = exited()
        val observer = object : RootExecutionObserver {
            override suspend fun beforeSubmit() { events += "caller-before" }
            override suspend fun onOutcome(outcome: RootJobOutcome) {
                assertSame(expectedOutcome, outcome)
                assertFalse(finalFile().exists())
                events += "caller-outcome"
            }
        }
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandClass = PrivilegeCommandClass("custom.export"),
            packageName = "com.example.caller",
            workRequestId = UUID.randomUUID(),
            sweepRequestId = UUID.randomUUID(),
            commandTimeout = 17.seconds,
            rootExecutionObserver = observer,
        )
        rootCopy = { destination, routed ->
            assertEquals(PrivilegeExecutionLane.ARCHIVE, routed.lane)
            assertEquals(RootExecutionPolicy.ISOLATED, routed.rootExecutionPolicy)
            assertEquals(execution.packageName, routed.packageName)
            assertEquals(execution.commandClass, routed.commandClass)
            assertEquals(execution.workRequestId, routed.workRequestId)
            assertEquals(execution.sweepRequestId, routed.sweepRequestId)
            assertEquals(execution.commandTimeout, routed.commandTimeout)
            assertSame(execution.provenance, routed.provenance)
            assertTrue(destination.canonicalFile.toPath().startsWith(rootStaging.canonicalFile.toPath()))
            assertFalse(finalFile().exists())
            val observed = requireNotNull(routed.rootExecutionObserver)
            observed.beforeSubmit()
            events += "copy"
            destination.writeText(payload)
            observed.onOutcome(expectedOutcome)
            Result.success(Unit)
        }
        val reportedBytes = mutableListOf<Long>()
        var boundaries = 0

        val result = builder.buildExportWithProgress(
            app(), stagingScope, BundleFormat.APK, "export.apk", execution,
            progress = VerifiedProgress { reportedBytes += it },
            operationBoundary = VerifiedOperationBoundary {
                assertEquals(payload, finalFile().readText())
                events += "boundary"
                boundaries++
            },
        ).getOrThrow()

        assertEquals(payload, result.readText())
        assertEquals(1, copiedDestinations.size)
        assertFalse(copiedDestinations.single().exists())
        assertEquals(1, boundaries)
        // The inaccessible source has no known length, so its byte count is not invented.
        assertTrue(reportedBytes.isEmpty())
        assertEquals(listOf("caller-before", "copy", "caller-outcome", "boundary"), events)
    }

    @Test
    fun `export supplies package and bounded deadline when caller has neither`() = runBlocking {
        rootCopy = { destination, execution ->
            assertEquals(app().packageName, execution.packageName)
            assertEquals(EXPORT_ROOT_COMMAND_TIMEOUT, execution.commandTimeout)
            acknowledgeCopy(destination, execution, exited())
        }

        assertTrue(buildExport().isSuccess)
        assertEquals(1, copiedDestinations.size)
    }

    @Test
    fun `readable APK export keeps direct copy without privileged admission`() = runBlocking {
        source.writeText(payload)
        var bytes = 0L
        var boundaries = 0
        val execution = PrivilegeExecutionContext(rootExecutionObserver = object : RootExecutionObserver {
            override suspend fun beforeSubmit() = error("direct copy must not submit root work")
            override suspend fun onOutcome(outcome: RootJobOutcome) = error("no root outcome")
        })

        val result = builder.buildExportWithProgress(
            app(), stagingScope, BundleFormat.APK, "export.apk", execution,
            progress = VerifiedProgress { bytes += it },
            operationBoundary = VerifiedOperationBoundary { boundaries++ },
        ).getOrThrow()

        assertEquals(payload, result.readText())
        assertEquals(source.length(), bytes)
        assertEquals(0, boundaries)
        assertTrue(recording.calls.isEmpty())
        assertTrue(copiedDestinations.isEmpty())
    }

    @Test
    fun `nonexport root copy retains persistent caller policy and ordinary destination`() = runBlocking {
        val observer = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) = Unit
        }
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandTimeout = 12.seconds,
            rootExecutionObserver = observer,
        )
        rootCopy = { destination, routed ->
            assertEquals(execution, routed)
            assertSame(observer, routed.rootExecutionObserver)
            assertEquals(RootExecutionPolicy.PERSISTENT, routed.rootExecutionPolicy)
            assertEquals(finalFile(), destination)
            destination.writeText(payload)
            Result.success(Unit)
        }

        val result = builder.buildWithProgress(
            app(), stagingScope, BundleFormat.APK, "export.apk", execution,
        ).getOrThrow()

        assertEquals(payload, result.readText())
        assertEquals(1, copiedDestinations.size)
        assertFalse(rootStaging.exists())
    }

    @Test
    fun `xapk export keeps OBB probing and copying on the original persistent policy`() = runBlocking {
        source.writeText(payload)
        val obb = ObbFile("main.1.${app().packageName}.obb", 4L)
        recording.obbProbe = ObbProbe.Present(listOf(obb), otherEntryCount = 0)
        val observer = object : RootExecutionObserver {
            override suspend fun onOutcome(outcome: RootJobOutcome) = Unit
        }
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandTimeout = 9.seconds,
            rootExecutionObserver = observer,
        )
        val scope = UUID.nameUUIDFromBytes(stagingScope.toByteArray(Charsets.UTF_8))
        val obbDirectory = File(
            context.externalCacheDir,
            "${ObbExportStagingDir.NAME}/scoped/$scope/${app().packageName}",
        )
        shellCommand = { routed ->
            assertEquals(execution.lane, routed.lane)
            assertEquals(RootExecutionPolicy.PERSISTENT, routed.rootExecutionPolicy)
            assertSame(observer, routed.rootExecutionObserver)
            assertEquals(execution.commandTimeout, routed.commandTimeout)
            File(obbDirectory, obb.name).writeText("game")
        }

        val result = builder.buildExportWithProgress(
            app(), stagingScope, BundleFormat.XAPK, "export.xapk", execution,
        ).getOrThrow()

        assertEquals(2, recording.executions.size)
        assertEquals(execution, recording.executions.first().second)
        ZipFile(result).use { zip ->
            val entry = zip.getEntry("Android/obb/${app().packageName}/${obb.name}")
            assertEquals("game", zip.getInputStream(entry).reader().use { it.readText() })
        }
        assertFalse(obbDirectory.exists())
        assertTrue(copiedDestinations.isEmpty())
    }

    @Test
    fun `unconfirmed partial root copy is not published or deleted by outer export cleanup`() = runBlocking {
        val outcome = exited().copy(
            kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            terminationConfirmed = false,
        )
        rootCopy = { destination, execution ->
            acknowledgeCopy(destination, execution, outcome)
            Result.failure(IsolatedRootExecutionException(outcome))
        }
        val fileStore = FakeAppBundleFileStore()

        val result = exporter(fileStore).exportInto(
            app(), BundleFormat.APK, ExportSession(ExportTargetChoice.Downloads, stagingScope),
        )

        assertTrue(result.isFailure)
        assertTrue(fileStore.written.isEmpty())
        assertFalse(File(context.cacheDir, "$stagingScope/${app().packageName}").exists())
        assertEquals(payload, copiedDestinations.single().readText())
        assertTrue(copiedDestinations.single().canonicalFile.toPath().startsWith(rootStaging.canonicalFile.toPath()))
    }

    @Test
    fun `cancellation with unconfirmed cleanup preserves private payload without publishing`() = runBlocking {
        val cancelled = TestCancellation("cancel export")
        val outcome = exited().copy(
            kind = RootJobOutcomeKind.CANCELLED,
            exitCode = null,
            outputDrained = false,
        )
        rootCopy = { destination, execution ->
            acknowledgeCopy(destination, execution, outcome)
            cancelled.addSuppressed(IsolatedRootExecutionException(outcome))
            throw cancelled
        }
        val fileStore = FakeAppBundleFileStore()

        val caught = try {
            exporter(fileStore).exportInto(
                app(), BundleFormat.APK, ExportSession(ExportTargetChoice.Downloads, stagingScope),
            )
            null
        } catch (error: CancellationException) { error }

        assertSame(cancelled, caught)
        assertTrue(fileStore.written.isEmpty())
        assertFalse(File(context.cacheDir, "$stagingScope/${app().packageName}").exists())
        assertEquals(payload, copiedDestinations.single().readText())
        assertNotNull(caught?.suppressed?.filterIsInstance<IsolatedRootExecutionException>()?.single())
    }

    private suspend fun acknowledgeCopy(
        destination: File,
        execution: PrivilegeExecutionContext,
        outcome: RootJobOutcome,
    ): Result<Unit> {
        val observer = requireNotNull(execution.rootExecutionObserver)
        observer.beforeSubmit()
        destination.writeText(payload)
        observer.onOutcome(outcome)
        return Result.success(Unit)
    }

    private suspend fun buildExport() = builder.buildExportWithProgress(
        app(), stagingScope, BundleFormat.APK, "export.apk", PrivilegeExecutionContext(),
    )

    private fun exporter(store: FakeAppBundleFileStore) = ExportAppUseCase(
        builder, FakePreferenceRepository(), store, Dispatchers.Unconfined,
    )

    private fun app() = AppInfo(
        packageName = "com.example.export", appName = "Export", versionName = "1.0",
        publicSourceDir = source.absolutePath,
    )

    internal fun finalFile() = File(context.cacheDir, "$stagingScope/${app().packageName}/export.apk")

    private fun exited() = RootJobOutcome(
        kind = RootJobOutcomeKind.EXITED, exitCode = 0, stdout = emptyList(), stderr = emptyList(),
        started = true, terminationConfirmed = true, outputDrained = true, shellReusable = true,
        failure = null,
    )

    private class TestCancellation(message: String) : CancellationException(message) {
        // An extra field prevents coroutine stacktrace recovery from copying this test exception.
        @Suppress("unused") private val identity = Any()
    }
}
