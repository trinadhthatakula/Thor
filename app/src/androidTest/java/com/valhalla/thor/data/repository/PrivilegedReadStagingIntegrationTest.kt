// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.AnalyzedPackage
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.SystemRepository
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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

/** Real entry points and the application's authenticated root runtime; only UUID fixtures mutate. */
@RunWith(AndroidJUnit4::class)
class PrivilegedReadStagingIntegrationTest {
    @Test
    fun protectedApkPreviewPreservesMetadataAndBytesUntilDiscard() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "preview")
        val input = File(files.directory, "protected.apk")
        val observed = ReadObservation(fixture, files, input, PREVIEW)
        val analyzer = AppAnalyzerImpl(files.context, observed.repository, Dispatchers.IO)
        var analyzed: AnalyzedPackage? = null
        try {
            // Use an installed, parseable APK rather than a mocked PackageManager or sidecar.
            val testContext = InstrumentationRegistry.getInstrumentation().context
            File(testContext.applicationInfo.sourceDir).copyTo(input)
            val expectedDigest = digest(input)
            val expectedSize = input.length()
            @Suppress("DEPRECATION")
            val expected = fixture.context.packageManager.getPackageInfo(testContext.packageName, 0)
            protect(input)

            val result = withTimeout(60_000) { analyzer.analyze(Uri.fromFile(input)).getOrThrow() }
            analyzed = result
            assertEquals(expected.packageName, result.metadata.packageName)
            assertEquals(expected.longVersionCode, result.metadata.versionCode)
            assertEquals(testContext.applicationInfo.targetSdkVersion, result.metadata.targetSdk)
            assertEquals(input.name, result.staged.displayName)
            assertEquals(observed.destination.get(), result.staged.file)
            assertEquals(expectedSize, result.staged.file.length())
            assertArrayEquals(expectedDigest, digest(result.staged.file))
            assertTrue(input.exists())
            observed.assertCompleted(expectedSize)
            analyzer.discard(result)
            assertFalse(result.staged.file.exists())
            report("preview", mapOf("source_bytes" to expectedSize,
                "peak_payload_bytes" to observed.payloadBytes.get(), "peak_receipt_bytes" to observed.receiptBytes.get()))
        } finally {
            analyzer.discard(analyzed)
            files.cleanupIfAcknowledged(observed)
        }
    }

    @Test
    fun protectedArchiveUsesPrivateRootReadAndDeletesItsCopyOnClose() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "archive")
        val input = File(files.directory, "protected.thorbak")
        val observed = ReadObservation(fixture, files, input, ARCHIVE)
        val factory = UriArchiveSourceFactory(files.context, observed.repository, Dispatchers.IO)
        var opened: ArchiveSource? = null
        try {
            writeArchive(input)
            val expectedDigest = digest(input)
            val expectedSize = input.length()
            protect(input)
            val outcome = withTimeout(30_000) { factory.open(Uri.fromFile(input).toString()) }
            assertTrue(outcome is ArchiveOpenOutcome.Opened)
            val source = (outcome as ArchiveOpenOutcome.Opened).source
            opened = source
            assertEquals(input.name, source.displayName)
            assertArchiveEntries(source)
            val copy = requireNotNull(observed.destination.get())
            assertTrue(UriArchiveSourceFactory.isReadCopyName(copy.name))
            assertArrayEquals(expectedDigest, digest(copy))
            observed.assertCompleted(expectedSize)
            source.close()
            assertFalse(copy.exists())
            assertTrue(input.exists())
            assertTrue(runCatching { source.openEntry(ENTRY_NAME) }.isFailure)
            report("archive", mapOf("source_bytes" to expectedSize,
                "peak_payload_bytes" to observed.payloadBytes.get(), "peak_receipt_bytes" to observed.receiptBytes.get()))
        } finally {
            opened?.close()
            files.cleanupIfAcknowledged(observed)
        }
    }

    @Test
    fun readableArchiveUsesRealDescriptorWithoutStagingAndClosesIt() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "descriptor")
        val input = File(files.directory, "readable.thorbak")
        val observed = ReadObservation(fixture, files, input, ARCHIVE)
        val factory = UriArchiveSourceFactory(files.context, observed.repository, Dispatchers.IO)
        var opened: ArchiveSource? = null
        try {
            writeArchive(input)
            assertTrue(descriptorsFor(input).isEmpty())
            val outcome = withTimeout(15_000) { factory.open(Uri.fromFile(input).toString()) }
            assertTrue(outcome is ArchiveOpenOutcome.Opened)
            val source = (outcome as ArchiveOpenOutcome.Opened).source
            opened = source
            assertArchiveEntries(source)
            assertEquals(0, observed.calls.get())
            assertTrue("The actual file descriptor remains open while the archive is owned",
                descriptorsFor(input).isNotEmpty())
            assertTrue(files.cache.listFiles().orEmpty().isEmpty())
            source.close()
            assertTrue("Closing the source releases its ParcelFileDescriptor and ZipFile",
                descriptorsFor(input).isEmpty())
            assertTrue(runCatching { source.openEntry(ENTRY_NAME) }.isFailure)
            assertTrue(input.isFile)
            assertEquals(0, observed.submissions.get())
        } finally {
            opened?.close()
            files.cleanupIfAcknowledged(observed)
        }
    }

    @Test
    fun previewCancellationWaitsForRootAcknowledgementAndKeepsInteractiveResponsive() = runBlocking<Unit> {
        assertPartialReadCancellation(PREVIEW)
    }

    @Test
    fun archiveCancellationWaitsForRootAcknowledgementAndKeepsInteractiveResponsive() = runBlocking<Unit> {
        assertPartialReadCancellation(ARCHIVE)
    }

    private suspend fun assertPartialReadCancellation(commandClass: PrivilegeCommandClass) = coroutineScope {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "fifo-${commandClass.value.substringAfter('.')}")
        val input = File(files.directory, "protected.pipe")
        val prefix = ByteArray(1024) { (it % 127).toByte() }
        val outcomeEntered = CompletableDeferred<RootJobOutcome>()
        val acknowledge = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<CancellationException>()
        val returned = AtomicInteger()
        val observed = ReadObservation(fixture, files, input, commandClass) { outcome ->
            outcomeEntered.complete(outcome)
            withTimeout(20_000) { acknowledge.await() }
        }
        var descriptor: FileDescriptor? = null
        var pending: Job? = null
        try {
            // Keep a real writer open before denying app access. Both resolver open calls now
            // fail with EACCES immediately; the unmodified root head/cat command reads this FIFO.
            Os.mkfifo(input.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            val writer = Os.open(input.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            descriptor = writer
            assertEquals(prefix.size, Os.write(writer, prefix, 0, prefix.size))
            val sourceInode = Os.fstat(writer).st_ino
            protect(input)
            val archivePid = rootCommand(fixture, "printf '%s' \"\$\$\"", archiveContext()).second
            assertNotNull(archivePid?.toLongOrNull())

            val running = launch(Dispatchers.IO) {
                try {
                    if (commandClass == PREVIEW) {
                        val analyzer = AppAnalyzerImpl(files.context, observed.repository, Dispatchers.IO)
                        val result = analyzer.analyze(Uri.fromFile(input))
                        returned.incrementAndGet()
                        result.getOrNull()?.let(analyzer::discard)
                    } else {
                        val factory = UriArchiveSourceFactory(files.context, observed.repository, Dispatchers.IO)
                        val result = factory.open(Uri.fromFile(input).toString())
                        returned.incrementAndGet()
                        (result as? ArchiveOpenOutcome.Opened)?.source?.close()
                    }
                } catch (failure: CancellationException) {
                    cancelled.complete(failure)
                    throw failure
                }
            }
            pending = running
            withTimeout(15_000) {
                while (observed.payload.get()?.length() != prefix.size.toLong()) {
                    assertFalse("The root reader must remain blocked after copying the prefix", running.isCompleted)
                    delay(10)
                }
            }
            val partial = requireNotNull(observed.payload.get())
            val receipt = File(partial.parentFile, RootExportStaging.RECEIPT_NAME)
            assertArrayEquals(prefix, partial.readBytes())
            assertUnpublished(requireNotNull(observed.destination.get()))
            assertNull(readReceipt(receipt).kind)
            assertOwnedArchive(fixture, commandClass)
            val interactiveStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals("independent", rootCommand(fixture, "printf independent").second)
            val interactiveElapsed = SystemClock.elapsedRealtimeNanos() - interactiveStarted
            assertFalse(running.isCompleted)

            val requested = CancellationException("Cancel acknowledged partial ${commandClass.value}")
            val cancellationStarted = SystemClock.elapsedRealtimeNanos()
            running.cancel(requested)
            val outcome = withTimeout(15_000) { outcomeEntered.await() }
            val acknowledgedAfter = SystemClock.elapsedRealtimeNanos() - cancellationStarted
            assertEquals(RootJobOutcomeKind.CANCELLED, outcome.kind)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertTrue(outcome.stdout.isEmpty())
            assertFalse("The consumer must wait for durable outcome observation", running.isCompleted)
            assertOwnedArchive(fixture, commandClass)
            assertArrayEquals(prefix, partial.readBytes())
            assertUnpublished(requireNotNull(observed.destination.get()))
            val recorded = readReceipt(receipt)
            assertEquals(RootJobOutcomeKind.CANCELLED.name, recorded.kind)
            assertTrue(recorded.cleanupConfirmed)
            assertEquals(sourceInode, Os.stat(input.absolutePath).st_ino)
            assertTrue(writer.valid())
            RootExportStaging(files.stagingRoot).sweep()
            assertTrue("An active observer prevents recovery from deleting its payload", partial.exists())
            assertTrue(receipt.exists())

            acknowledge.complete(Unit)
            withTimeout(15_000) { running.join() }
            val received = withTimeout(5_000) { cancelled.await() }
            assertTrue("Cancellation is propagated rather than mapped to a failed result",
                generateSequence<Throwable>(received) { it.cause }.any { it === requested })
            assertEquals(0, returned.get())
            assertFalse(partial.exists())
            assertFalse(receipt.exists())
            assertFalse(requireNotNull(observed.destination.get()).exists())
            assertTrue(files.cache.walkTopDown().none { it.isFile })
            assertEquals(sourceInode, Os.stat(input.absolutePath).st_ino)
            assertTrue("Input ownership remains with the caller", writer.valid())
            assertNull(fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)

            val nextMarker = File(files.directory, "next")
            val next = rootCommand(fixture,
                "printf 'next\\n' >> ${nextMarker.absolutePath.escapeForShell()}; printf 'next\\n'; printf '%s' \"\$\$\"",
                archiveContext())
            assertEquals(listOf("next", archivePid), next.second.orEmpty().lines())
            assertEquals(listOf("next"), nextMarker.readLines())
            assertEquals("Cancelled reads are never replayed", 1, observed.calls.get())
            assertEquals(1, observed.submissions.get())
            report("fifo-${commandClass.value}", mapOf("interactive_while_read_ns" to interactiveElapsed,
                "cancel_to_ack_ns" to acknowledgedAfter, "peak_payload_bytes" to observed.payloadBytes.get(),
                "peak_receipt_bytes" to observed.receiptBytes.get(), "source_prefix_bytes" to prefix.size))
        } finally {
            withContext(NonCancellable) {
                acknowledge.complete(Unit)
                pending?.cancel()
                try {
                    withTimeout(20_000) { pending?.join() }
                } finally {
                    descriptor?.let { Os.close(it) }
                    files.cleanupIfAcknowledged(observed)
                }
            }
        }
    }

    /** Decorates only observation; provider selection, commands, root execution and cleanup are real. */
    private class ReadObservation(
        val fixture: Fixture,
        val files: TestFiles,
        val input: File,
        val commandClass: PrivilegeCommandClass,
        val observeOutcome: suspend (RootJobOutcome) -> Unit = {},
    ) {
        val calls = AtomicInteger()
        val submissions = AtomicInteger()
        val destination = AtomicReference<File?>()
        val payload = AtomicReference<File?>()
        val terminal = AtomicReference<RootJobOutcome?>()
        val payloadBytes = AtomicLong()
        val receiptBytes = AtomicLong()

        val repository = object : SystemRepository by fixture.repository {
            override suspend fun copyFileForRead(
                sourcePath: String,
                destination: File,
                maxBytes: Long?,
                execution: PrivilegeExecutionContext,
            ): Result<Unit> {
                assertEquals(1, calls.incrementAndGet())
                assertEquals(input.absolutePath, sourcePath)
                assertEquals(commandClass, execution.commandClass)
                assertEquals(PrivilegeExecutionLane.ARCHIVE, execution.lane)
                assertEquals(9.minutes, execution.commandTimeout)
                assertEquals(if (commandClass == PREVIEW) MAX_EXTRACTED_TOTAL_BYTES else null, maxBytes)
                assertTrue(destination.canonicalPath.startsWith(files.cache.canonicalPath + File.separator))
                this@ReadObservation.destination.set(destination)
                val observer = object : RootExecutionObserver {
                    override suspend fun beforeSubmit() {
                        submissions.incrementAndGet()
                        assertOwnedArchive(fixture, commandClass)
                        val created = files.stagingRoot.listFiles().orEmpty()
                            .filter { it.name !in files.originalWorkspaces }
                        assertEquals("One real root read owns one unique private workspace", 1, created.size)
                        val workspace = created.single()
                        assertEquals(workspace.name, UUID.fromString(workspace.name).toString())
                        val staged = File(workspace, RootExportStaging.PAYLOAD_NAME)
                        payload.set(staged)
                        assertTrue(staged.isFile)
                        assertEquals(0L, staged.length())
                        val receipt = File(workspace, RootExportStaging.RECEIPT_NAME)
                        assertEquals(workspace.name, readReceipt(receipt).id)
                        assertNull(readReceipt(receipt).kind)
                        assertUnpublished(destination)
                    }

                    override suspend fun onOutcome(outcome: RootJobOutcome) {
                        terminal.set(outcome)
                        val staged = requireNotNull(payload.get())
                        val receipt = File(staged.parentFile, RootExportStaging.RECEIPT_NAME)
                        payloadBytes.set(staged.length())
                        receiptBytes.set(receipt.length())
                        assertTrue("The payload survives until acknowledgement returns", staged.exists())
                        assertEquals(outcome.kind.name, readReceipt(receipt).kind)
                        assertEquals(outcome.cleanupConfirmed, readReceipt(receipt).cleanupConfirmed)
                        assertUnpublished(destination)
                        observeOutcome(outcome)
                    }
                }
                val decorated = execution.copy(rootExecutionObserver = observer)
                    .also { it.provenance = execution.provenance }
                return fixture.repository.copyFileForRead(sourcePath, destination, maxBytes, decorated)
            }
        }

        fun assertCompleted(expectedSize: Long) {
            assertEquals(1, calls.get())
            assertEquals(1, submissions.get())
            val outcome = requireNotNull(terminal.get())
            assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
            assertEquals(0, outcome.exitCode)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertTrue(outcome.stdout.isEmpty())
            assertTrue(outcome.stderr.isEmpty())
            assertNull(outcome.failure)
            assertEquals(expectedSize, payloadBytes.get())
            assertTrue(receiptBytes.get() > 0)
            assertFalse(requireNotNull(payload.get()).parentFile!!.exists())
        }
    }

    private class TestFiles(fixture: Fixture, name: String) {
        val directory = File(fixture.context.cacheDir, "root-read-$name-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val cache = File(directory, "consumer-cache").apply { assertTrue(mkdir()) }
        // Keep the real resolver and PackageManager. Only consumer-owned outputs are scoped.
        val context = object : ContextWrapper(fixture.context) {
            override fun getCacheDir(): File = cache
        }
        val stagingRoot = File(fixture.context.noBackupFilesDir, PrivilegedReadStaging.DIRECTORY_NAME)
        val originalWorkspaces = stagingRoot.listFiles().orEmpty().map { it.name }.toSet()

        fun cleanupIfAcknowledged(observed: ReadObservation) {
            if (observed.submissions.get() == 0 || observed.terminal.get()?.cleanupConfirmed == true) {
                assertTrue(directory.deleteRecursively())
            } else {
                report("retained", mapOf("fixture" to directory.absolutePath,
                    "recovery" to stagingRoot.absolutePath))
            }
        }
    }

    private suspend fun readyFixture(): Fixture {
        assumeTrue("Explicit root-device opt-in required", InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Run as the installed application UID", Os.getuid() != 0)
        val koin = GlobalContext.get()
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        withTimeout(30_000) {
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
            val state = manager.refreshAndAwait()
            assertTrue(state.rootAvailability.canAdmitRoot)
            assertEquals("The app must already select root for these acceptance tests", PrivilegeMode.ROOT, state.active)
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
        }
        return Fixture(context, requireNotNull(koin.getOrNull<RootSystemGateway>()), statuses,
            requireNotNull(koin.getOrNull<SystemRepository>()))
    }

    private fun protect(input: File) {
        // Retain the app's SELinux categories and inode ownership; the directory remains deletable.
        Os.chmod(input.absolutePath, 0)
        val direct = runCatching { input.inputStream().use { it.read() } }
        assertTrue("Direct app reads must fail so the real privileged fallback runs", direct.exceptionOrNull() is IOException)
    }

    private suspend fun rootCommand(
        fixture: Fixture,
        command: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Pair<Int, String?> = withTimeout(15_000) {
        fixture.gateway.executeShellCommand(command, execution).getOrThrow().also { assertEquals(0, it.first) }
    }

    private data class Fixture(
        val context: Context,
        val gateway: RootSystemGateway,
        val statuses: RootLaneStatusSource,
        val repository: SystemRepository,
    )

    private companion object {
        val PREVIEW = PrivilegeCommandClass("input.preview")
        val ARCHIVE = PrivilegeCommandClass("input.archive")
        const val ENTRY_NAME = "payload/data.bin"
        val ENTRY_BYTES = ByteArray(32 * 1024) { (it % 251).toByte() }
        val HEADER_BYTES = "{\"version\":1}".toByteArray()

        fun archiveContext() = PrivilegeExecutionContext(lane = PrivilegeExecutionLane.ARCHIVE,
            commandClass = PrivilegeCommandClass("test.read-staging"))

        fun assertOwnedArchive(fixture: Fixture, commandClass: PrivilegeCommandClass) {
            val status = fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE)
            assertEquals("Acceptance requires the owned ARCHIVE session", RootLaneMode.ISOLATED, status.mode)
            assertEquals(commandClass, status.activeCommandClass)
        }

        fun assertUnpublished(destination: File) {
            // Archive open reserves an empty cache leaf before asking for a privileged copy.
            assertEquals("Root may not publish bytes before acknowledgement", 0L, destination.length())
        }

        fun readReceipt(receipt: File): RootExportStagingRecord =
            RootExportStaging.json.decodeFromString(receipt.readText())

        fun writeArchive(file: File) {
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in listOf(ENTRY_NAME to ENTRY_BYTES, "header.json" to HEADER_BYTES)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        fun assertArchiveEntries(source: ArchiveSource) {
            assertEquals(listOf(ENTRY_NAME, "header.json"), source.entryNames())
            assertArrayEquals(ENTRY_BYTES, requireNotNull(source.openEntry(ENTRY_NAME)).use { it.readBytes() })
            assertArrayEquals(HEADER_BYTES, requireNotNull(source.openEntry("header.json")).use { it.readBytes() })
            assertNull(source.openEntry("missing"))
        }

        fun digest(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }

        fun descriptorsFor(file: File): List<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull { descriptor ->
            runCatching {
                val target = Os.readlink(descriptor.absolutePath)
                descriptor.name.takeIf { target.startsWith('/') && File(target).canonicalFile == file.canonicalFile }
            }.getOrNull()
        }

        fun report(name: String, values: Map<String, Any>) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nTHOR_PRIVILEGED_READ_METRIC $name ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
            })
        }
    }
}
