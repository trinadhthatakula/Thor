// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Normal application runtime and authenticated root; all files belong to these disposable fixtures. */
@RunWith(AndroidJUnit4::class)
class RootExportStagingIntegrationTest {
    @Test
    fun rootOnlyApkUsesIsolatedExportStagingAndPreservesBytes() = runBlocking<Unit> {
        val fixture = readyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "root-export-test-${UUID.randomUUID()}"
        val files = File(context.cacheDir, scope).apply { assertTrue(mkdir()) }
        val source = File(files, "root-only.apk")
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val stagingRoot = File(context.noBackupFilesDir, "root_export_staging")
        val originalWorkspaces = stagingRoot.listFiles().orEmpty().map { it.name }.toSet()
        val workspaces = AtomicReference<List<File>>(emptyList())
        val submissions = AtomicInteger()
        val terminal = AtomicReference<RootJobOutcome?>()
        val payloadBytes = AtomicLong()
        val receiptBytes = AtomicLong()
        var sampleCleanupConfirmed = true
        try {
            rootCommand(fixture, "chown 0:0 ${source.absolutePath.escapeForShell()} && chmod 600 ${source.absolutePath.escapeForShell()}")
            val directRead = runCatching { source.inputStream().use { it.read() } }
            assertTrue("The application must fail to read the fixture so the real root fallback runs", directRead.exceptionOrNull() is IOException)

            val persistentNs = rootCommand(fixture, "readlink /proc/self/ns/mnt", archiveContext()).second
            val isolatedNs = rootCommand(fixture, "readlink /proc/self/ns/mnt",
                archiveContext().copy(rootExecutionPolicy = RootExecutionPolicy.ISOLATED)).second
            assertNotNull(persistentNs)
            assertEquals("Isolation retains the owned mount-master namespace", persistentNs, isolatedNs)
            assertOwnedArchive(fixture)

            // These paired samples measure aggregate isolation cost, including process setup and
            // drain. They do not attribute every nanosecond to control-shell acquisition.
            val persistentSamples = mutableListOf<Long>()
            val isolatedSamples = mutableListOf<Long>()
            for (index in 0 until 3) {
                for (policy in listOf(RootExecutionPolicy.PERSISTENT, RootExecutionPolicy.ISOLATED)) {
                    val target = File(files, "sample-$index-${policy.name}.apk").apply { writeBytes(byteArrayOf()) }
                    val sampleOutcome = AtomicReference<RootJobOutcome?>()
                    val submitted = AtomicInteger()
                    val execution = archiveContext().copy(
                        rootExecutionPolicy = policy,
                        rootExecutionObserver = object : RootExecutionObserver {
                            override suspend fun beforeSubmit() { submitted.incrementAndGet() }
                            override suspend fun onOutcome(outcome: RootJobOutcome) { sampleOutcome.set(outcome) }
                        },
                    )
                    sampleCleanupConfirmed = false
                    var returned = false
                    val started = SystemClock.elapsedRealtimeNanos()
                    try {
                        withTimeout(20_000) { fixture.gateway.copyFile(source.absolutePath, target.absolutePath, execution) }
                        returned = true
                    } finally {
                        sampleCleanupConfirmed = if (policy == RootExecutionPolicy.PERSISTENT) returned
                        else sampleOutcome.get()?.cleanupConfirmed == true || submitted.get() == 0
                    }
                    val elapsed = SystemClock.elapsedRealtimeNanos() - started
                    if (policy == RootExecutionPolicy.PERSISTENT) persistentSamples += elapsed
                    else {
                        isolatedSamples += elapsed
                        assertEquals(1, submitted.get())
                        assertCompleteCopy(requireNotNull(sampleOutcome.get()))
                    }
                    assertArrayEquals(bytes, target.readBytes())
                    assertTrue(target.delete())
                }
            }

            val observed = object : RootExecutionObserver {
                override suspend fun beforeSubmit() {
                    submissions.incrementAndGet()
                    assertEquals(FILE_COPY, fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)
                    assertOwnedArchive(fixture)
                    val created = stagingRoot.listFiles().orEmpty().filter { it.name !in originalWorkspaces }
                    assertEquals("One isolated root copy owns one unique workspace", 1, created.size)
                    workspaces.set(created)
                    assertTrue(File(created.single(), RootExportStaging.RECEIPT_NAME).isFile)
                }

                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    terminal.set(outcome)
                    val workspace = workspaces.get().single()
                    payloadBytes.set(File(workspace, RootExportStaging.PAYLOAD_NAME).length())
                    receiptBytes.set(File(workspace, RootExportStaging.RECEIPT_NAME).length())
                    assertTrue("Outcome observation precedes promotion and workspace removal", workspace.exists())
                }
            }
            val progress = AtomicLong()
            val boundaries = AtomicInteger()
            val started = SystemClock.elapsedRealtimeNanos()
            val built = withTimeout(30_000) {
                fixture.builder.buildExportWithProgress(
                    appInfo = AppInfo(packageName = TEST_PACKAGE, appName = "Disposable root export", publicSourceDir = source.absolutePath),
                    cacheSubDir = "$scope/built",
                    format = BundleFormat.APK,
                    fileName = "result.apk",
                    // An interactive caller still receives the export-only ARCHIVE adoption.
                    execution = PrivilegeExecutionContext(packageName = TEST_PACKAGE, workRequestId = UUID.randomUUID(), rootExecutionObserver = observed),
                    progress = VerifiedProgress { progress.addAndGet(it) },
                    operationBoundary = VerifiedOperationBoundary { boundaries.incrementAndGet() },
                ).getOrThrow()
            }
            val stagedElapsed = SystemClock.elapsedRealtimeNanos() - started
            assertEquals(1, submissions.get())
            assertCompleteCopy(requireNotNull(terminal.get()))
            assertArrayEquals(bytes, built.readBytes())
            assertEquals(bytes.size.toLong(), progress.get())
            assertEquals(bytes.size.toLong(), payloadBytes.get())
            assertEquals(1, boundaries.get())
            assertTrue(workspaces.get().all { !it.exists() })
            assertTrue("No new recovery workspace remains after confirmed success",
                stagingRoot.listFiles().orEmpty().none { it.name !in originalWorkspaces })
            assertTrue(receiptBytes.get() > 0)
            reportMetrics("regular", linkedMapOf(
                "persistent_copy_ns" to persistentSamples.joinToString(","),
                "isolated_copy_ns" to isolatedSamples.joinToString(","),
                "aggregate_isolation_delta_ns" to persistentSamples.indices.joinToString(",") { (isolatedSamples[it] - persistentSamples[it]).toString() },
                "full_export_stage_ns" to stagedElapsed,
                "source_bytes" to bytes.size,
                "peak_payload_bytes" to payloadBytes.get(),
                "peak_receipt_bytes" to receiptBytes.get(),
            ))
        } finally {
            withContext(NonCancellable) {
                if (sampleCleanupConfirmed && (submissions.get() == 0 || terminal.get()?.cleanupConfirmed == true)) {
                    rootCommand(fixture, "rm -f ${source.absolutePath.escapeForShell()}")
                    assertTrue(files.deleteRecursively())
                } else {
                    reportMetrics("retained", mapOf("fixture" to files.absolutePath, "recovery" to stagingRoot.absolutePath))
                }
            }
        }
    }

    @Test
    fun partialCopyCancellationRetainsResourcesUntilAcknowledgementAndKeepsInteractiveResponsive() = runBlocking<Unit> {
        val fixture = readyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID()
        val files = File(context.cacheDir, "root-export-fifo-test-$id").apply { assertTrue(mkdir()) }
        val recovery = File(context.noBackupFilesDir, "root-export-fifo-test-$id")
        val staging = RootExportStaging(recovery)
        val fifo = File(files, "source.pipe")
        val destination = File(files, "must-not-publish.apk")
        val nextMarker = File(files, "next")
        val prefix = ByteArray(1024) { (it % 127).toByte() }
        val payload = AtomicReference<File?>()
        val submissions = AtomicInteger()
        val operations = AtomicInteger()
        val terminal = AtomicReference<RootJobOutcome?>()
        val outcomeEntered = CompletableDeferred<RootJobOutcome>()
        val finishRecording = CompletableDeferred<Unit>()
        val cancellation = CompletableDeferred<CancellationException>()
        val workId = UUID.randomUUID()
        var descriptor: FileDescriptor? = null
        var pending: kotlinx.coroutines.Job? = null
        try {
            // App creation preserves its SELinux categories as well as UID ownership.
            Os.mkfifo(fifo.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            // Opening both ends never waits for a reader. The held write end keeps actual cp
            // blocked after this prefix, making partial-copy readiness independent of timing.
            descriptor = Os.open(fifo.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            assertEquals(prefix.size, Os.write(descriptor, prefix, 0, prefix.size))
            val shellPid = rootCommand(fixture, "printf '%s' \"\$\$\"", archiveContext()).second
            assertNotNull(shellPid?.toLongOrNull())
            assertOwnedArchive(fixture)
            val execution = PrivilegeExecutionContext(
                lane = PrivilegeExecutionLane.ARCHIVE,
                packageName = TEST_PACKAGE,
                workRequestId = workId,
                rootExecutionObserver = object : RootExecutionObserver {
                    override suspend fun beforeSubmit() {
                        submissions.incrementAndGet()
                        assertTrue(File(requireNotNull(payload.get()).parentFile, RootExportStaging.RECEIPT_NAME).isFile)
                    }

                    override suspend fun onOutcome(outcome: RootJobOutcome) {
                        terminal.set(outcome)
                        outcomeEntered.complete(outcome)
                        withTimeout(20_000) { finishRecording.await() }
                    }
                },
            )
            val running = launch(Dispatchers.IO) {
                try {
                    staging.copy(fifo.absolutePath, destination, execution) { sourcePath, targetPath, routed ->
                        operations.incrementAndGet()
                        payload.set(File(targetPath))
                        assertEquals(fifo.absolutePath, sourcePath)
                        assertEquals(PrivilegeExecutionLane.ARCHIVE, routed.lane)
                        assertEquals(RootExecutionPolicy.ISOLATED, routed.rootExecutionPolicy)
                        assertEquals(TEST_PACKAGE, routed.packageName)
                        assertEquals(workId, routed.workRequestId)
                        fixture.repository.copyFileWithRoot(sourcePath, targetPath, routed)
                    }
                } catch (cancelled: CancellationException) {
                    cancellation.complete(cancelled)
                    throw cancelled
                }
            }
            pending = running
            withTimeout(15_000) {
                while (payload.get()?.length() != prefix.size.toLong()) {
                    assertFalse("cp must copy the acknowledged prefix before finishing", running.isCompleted)
                    delay(10)
                }
            }
            val partial = requireNotNull(payload.get())
            assertArrayEquals(prefix, partial.readBytes())
            assertFalse(destination.exists())
            assertEquals(FILE_COPY, fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)
            assertOwnedArchive(fixture)
            val interactiveStarted = SystemClock.elapsedRealtimeNanos()
            val interactive = rootCommand(fixture, "printf independent")
            val interactiveElapsed = SystemClock.elapsedRealtimeNanos() - interactiveStarted
            assertEquals("independent", interactive.second)
            assertFalse("Independent work completed while the archive copy remained blocked", running.isCompleted)

            val requested = CancellationException("Cancel acknowledged partial export")
            val cancelledAt = SystemClock.elapsedRealtimeNanos()
            running.cancel(requested)
            val outcome = withTimeout(15_000) { outcomeEntered.await() }
            val acknowledgedAfter = SystemClock.elapsedRealtimeNanos() - cancelledAt
            assertEquals(RootJobOutcomeKind.CANCELLED, outcome.kind)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertTrue(outcome.stdout.isEmpty())
            // The shell may report a terminated child on stderr; drained output is retained.
            assertFalse("The caller waits for durable outcome observation", running.isCompleted)
            assertEquals(FILE_COPY, fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)
            assertTrue(partial.exists())
            assertFalse(destination.exists())
            val receipt = File(partial.parentFile, RootExportStaging.RECEIPT_NAME)
            val receiptSize = receipt.length()
            assertTrue(receiptSize > 0)
            assertEquals("Sweep cannot clean a workspace whose observer is still active", 0, staging.sweep())
            assertTrue(partial.exists())
            finishRecording.complete(Unit)
            withTimeout(15_000) { running.join() }
            val received = withTimeout(5_000) { cancellation.await() }
            assertTrue("The original cancellation remains in the cause chain",
                generateSequence<Throwable>(received) { it.cause }.any { it === requested })
            assertFalse(partial.exists())
            assertFalse(destination.exists())
            assertTrue(recovery.listFiles().orEmpty().isEmpty())
            assertNull(fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)
            val next = rootCommand(fixture,
                "printf 'next\\n' >> ${nextMarker.absolutePath.escapeForShell()}; printf 'next\\n'; printf '%s' \"\$\$\"", archiveContext())
            assertEquals(listOf("next", shellPid), next.second.orEmpty().lines())
            assertEquals(listOf("next"), nextMarker.readLines())
            assertEquals("The uncertain or cancelled operation is never replayed", 1, operations.get())
            assertEquals(1, submissions.get())
            reportMetrics("fifo", linkedMapOf(
                "interactive_while_copy_ns" to interactiveElapsed,
                "cancel_to_ack_ns" to acknowledgedAfter,
                "logical_source_bytes" to prefix.size,
                "fifo_file_bytes" to fifo.length(),
                "peak_partial_bytes" to prefix.size,
                "peak_receipt_bytes" to receiptSize,
                "cancellation_stderr_lines" to outcome.stderr.size,
            ))
        } finally {
            withContext(NonCancellable) {
                finishRecording.complete(Unit)
                pending?.cancel()
                try {
                    withTimeout(20_000) { pending?.join() }
                } finally {
                    descriptor?.let { Os.close(it) }
                    if (terminal.get()?.cleanupConfirmed == true || (submissions.get() == 0 && recovery.listFiles().orEmpty().isEmpty())) {
                        assertTrue(files.deleteRecursively())
                        assertTrue(recovery.deleteRecursively())
                    } else {
                        reportMetrics("retained", mapOf("fixture" to files.absolutePath, "recovery" to recovery.absolutePath))
                    }
                }
            }
        }
    }

    private suspend fun readyFixture(): Fixture {
        assumeTrue("Explicit root-device opt-in required", InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        withTimeout(30_000) {
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
            assertTrue(manager.refreshAndAwait().rootAvailability.canAdmitRoot)
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
        }
        return Fixture(
            gateway, statuses,
            requireNotNull(koin.getOrNull<SystemRepository>()),
            requireNotNull(koin.getOrNull<AppBundleBuilder>()),
        )
    }

    private suspend fun rootCommand(
        fixture: Fixture,
        command: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Pair<Int, String?> = withTimeout(15_000) {
        fixture.gateway.executeShellCommand(command, execution).getOrThrow().also { assertEquals(0, it.first) }
    }

    private fun archiveContext() = PrivilegeExecutionContext(
        lane = PrivilegeExecutionLane.ARCHIVE,
        commandClass = PrivilegeCommandClass("test.export-staging"),
        packageName = TEST_PACKAGE,
    )

    internal fun assertOwnedArchive(fixture: Fixture) {
        assertEquals("Acceptance requires the owned ARCHIVE session, not degraded fallback",
            RootLaneMode.ISOLATED, fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).mode)
    }

    private fun assertCompleteCopy(outcome: RootJobOutcome) {
        assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.started)
        assertTrue(outcome.cleanupConfirmed)
        assertTrue(outcome.shellReusable)
        assertTrue(outcome.stdout.isEmpty())
        assertTrue(outcome.stderr.isEmpty())
        assertNull(outcome.failure)
    }

    private fun reportMetrics(name: String, values: Map<String, Any>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nTHOR_ROOT_EXPORT_METRIC $name ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
        })
    }

    internal data class Fixture(
        val gateway: RootSystemGateway,
        val statuses: RootLaneStatusSource,
        val repository: SystemRepository,
        val builder: AppBundleBuilder,
    )

    private companion object {
        const val TEST_PACKAGE = "com.valhalla.thor.test.rootexport"
        val FILE_COPY = PrivilegeCommandClass("file.copy")
    }
}
