// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.toBitmap
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.utils.ArchiveIconFetcher
import com.valhalla.thor.presentation.utils.ArchiveIconModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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

/** Public Coil fetcher, real decoder and app-authenticated root; only UUID fixture leaves mutate. */
@RunWith(AndroidJUnit4::class)
class ArchiveIconStagingIntegrationTest {
    @Test
    fun protectedIconIgnoresLegacyMissAndCachesDecodedPixelsAfterAcknowledgedRead() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "icon")
        val reads = ObservedReads(fixture, files)
        try {
            writeArchive(files.input, withIcon = true)
            val expectedBytes = files.input.readBytes()
            protect(files.input)
            assertTrue(files.iconDirectory.mkdir())
            assertTrue(files.legacyMiss.createNewFile())

            assertImage(fetch(files, reads.repository))
            assertCachedImage(files)
            val copy = reads.records.single()
            assertCompleted(copy, expectedBytes.size.toLong())
            assertFalse(copy.destination.exists())
            assertFalse(files.miss.exists())
            assertNoScratch(files)
            assertTrue(files.input.exists())
            // A second instance exercises the fetcher's disk cache, not Coil's memory cache.
            assertImage(fetch(files, reads.repository))
            assertEquals(1, reads.records.size)
            report("icon", mapOf("source_bytes" to expectedBytes.size,
                "peak_payload_bytes" to copy.payloadBytes.get(), "peak_receipt_bytes" to copy.receiptBytes.get()))
        } finally {
            files.cleanupIfAcknowledged(reads)
        }
    }

    @Test
    fun protectedValidArchiveWithoutIconCachesOnlyAConfirmedMiss() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "miss")
        val reads = ObservedReads(fixture, files)
        try {
            writeArchive(files.input, withIcon = false)
            val expectedSize = files.input.length()
            protect(files.input)
            assertNull(fetch(files, reads.repository))
            assertCompleted(reads.records.single(), expectedSize)
            assertTrue("A successfully parsed archive without an icon may be remembered", files.miss.isFile)
            assertFalse(files.cachedImage.exists())
            assertNoScratch(files)
            assertNull(fetch(files, reads.repository))
            assertEquals("The confirmed miss prevents another privileged copy", 1, reads.records.size)
            report("miss", mapOf("root_copies" to reads.records.size, "miss_bytes" to files.miss.length()))
        } finally {
            files.cleanupIfAcknowledged(reads)
        }
    }

    @Test
    fun partialRootCancellationRetainsReceiptUntilAcknowledgedAndDoesNotCacheAMiss() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "fifo")
        val prefix = ByteArray(1024) { (it % 127).toByte() }
        val outcomeEntered = CompletableDeferred<RootJobOutcome>()
        val acknowledge = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<CancellationException>()
        val returned = AtomicInteger()
        val reads = ObservedReads(fixture, files, onOutcome = { record, outcome ->
            if (record.index == 1) {
                outcomeEntered.complete(outcome)
                withTimeout(20_000) { acknowledge.await() }
            }
        })
        var descriptor: FileDescriptor? = null
        var pending: Job? = null
        try {
            Os.mkfifo(files.input.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            // The retained write end keeps the actual root head blocked after the known prefix.
            val writer = Os.open(files.input.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            descriptor = writer
            val sourceInode = Os.fstat(writer).st_ino
            assertEquals(prefix.size, Os.write(writer, prefix, 0, prefix.size))
            protect(files.input)
            val running = launch(Dispatchers.IO) {
                try {
                    fetch(files, reads.repository)
                    returned.incrementAndGet()
                } catch (failure: CancellationException) {
                    cancelled.complete(failure)
                    throw failure
                }
            }
            pending = running
            withTimeout(15_000) {
                while (reads.records.firstOrNull()?.payload()?.length() != prefix.size.toLong()) {
                    assertFalse("The copy must remain blocked after writing the prefix", running.isCompleted)
                    delay(10)
                }
            }
            val copy = reads.records.single()
            val partial = requireNotNull(copy.payload())
            val receipt = requireNotNull(copy.receipt())
            assertArrayEquals(prefix, partial.readBytes())
            assertFalse(copy.destination.exists())
            assertFalse(files.cachedImage.exists())
            assertFalse(files.miss.exists())
            assertNull(readReceipt(receipt).kind)
            assertOwnedArchive(fixture)
            val interactiveStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals("independent", rootCommand(fixture, "printf independent").second)
            val interactiveElapsed = SystemClock.elapsedRealtimeNanos() - interactiveStarted
            assertFalse(running.isCompleted)

            val requested = CancellationException("Cancel acknowledged partial archive icon")
            val cancellationStarted = SystemClock.elapsedRealtimeNanos()
            running.cancel(requested)
            val outcome = withTimeout(15_000) { outcomeEntered.await() }
            val acknowledgedAfter = SystemClock.elapsedRealtimeNanos() - cancellationStarted
            assertEquals(RootJobOutcomeKind.CANCELLED, outcome.kind)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertFalse("The fetcher waits for its observer acknowledgement", running.isCompleted)
            assertOwnedArchive(fixture)
            assertArrayEquals(prefix, partial.readBytes())
            assertTrue(readReceipt(receipt).cleanupConfirmed)
            assertEquals(RootJobOutcomeKind.CANCELLED.name, readReceipt(receipt).kind)
            assertEquals(sourceInode, Os.stat(files.input.absolutePath).st_ino)
            assertTrue(writer.valid())
            assertFalse(copy.destination.exists())
            assertFalse(files.cachedImage.exists())
            assertFalse(files.miss.exists())
            RootExportStaging(files.stagingRoot).sweep()
            assertTrue("Recovery must retain an active observer's workspace", partial.exists())
            assertTrue(receipt.exists())

            acknowledge.complete(Unit)
            withTimeout(15_000) { running.join() }
            assertCancellation(requested, withTimeout(5_000) { cancelled.await() })
            assertEquals(0, returned.get())
            assertFalse(partial.exists())
            assertFalse(receipt.exists())
            assertFalse(copy.destination.exists())
            assertFalse(files.miss.exists())
            assertFalse(files.cachedImage.exists())
            assertNoScratch(files)
            assertEquals(sourceInode, Os.stat(files.input.absolutePath).st_ino)
            assertEquals("The cancelled request is not replayed", 1, reads.records.size)
            assertNull(fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE).activeCommandClass)

            // Only after the old reader is acknowledged and joined may this caller replace its
            // own FIFO. The immutable model/key stays identical, proving cancellation is no miss.
            Os.close(writer)
            descriptor = null
            assertTrue(files.input.delete())
            writeArchive(files.input, withIcon = true)
            val retryBytes = files.input.length()
            protect(files.input)
            assertImage(fetch(files, reads.repository))
            assertCachedImage(files)
            assertFalse(files.miss.exists())
            assertEquals("A fresh caller may retry the exact same cache key", 2, reads.records.size)
            assertCompleted(reads.records.last(), retryBytes)
            assertNoScratch(files)
            report("fifo", mapOf("interactive_while_read_ns" to interactiveElapsed,
                "cancel_to_ack_ns" to acknowledgedAfter, "peak_payload_bytes" to copy.payloadBytes.get(),
                "peak_receipt_bytes" to copy.receiptBytes.get(), "same_key_retry" to true))
        } finally {
            withContext(NonCancellable) {
                acknowledge.complete(Unit)
                pending?.cancel()
                try {
                    withTimeout(20_000) { pending?.join() }
                } finally {
                    descriptor?.let { Os.close(it) }
                    files.cleanupIfAcknowledged(reads)
                }
            }
        }
    }

    @Test
    fun cancellingAnOverlappingFetchPreservesItsPeersScratchAndPublishedIcon() = runBlocking<Unit> {
        val fixture = readyFixture()
        val files = TestFiles(fixture, "overlap")
        val ready = List(2) { CompletableDeferred<CopyRecord>() }
        val release = List(2) { CompletableDeferred<Unit>() }
        val reads = ObservedReads(fixture, files, afterCopy = { record ->
            // Suspend outside the real repository, after root releases ARCHIVE admission. Both
            // callers then own complete, separate scratch files before either begins decoding.
            ready[record.index - 1].complete(record)
            withTimeout(20_000) { release[record.index - 1].await() }
        })
        val pending = mutableListOf<Deferred<FetchResult?>>()
        try {
            writeArchive(files.input, withIcon = true)
            val expectedBytes = files.input.readBytes()
            protect(files.input)
            val first = async(Dispatchers.IO) { fetch(files, reads.repository) }.also { pending += it }
            val firstCopy = withTimeout(15_000) { ready[0].await() }
            val second = async(Dispatchers.IO) { fetch(files, reads.repository) }.also { pending += it }
            val secondCopy = withTimeout(15_000) { ready[1].await() }
            assertFalse(firstCopy.destination == secondCopy.destination)
            assertFalse(firstCopy.workspace.get() == secondCopy.workspace.get())
            for (copy in listOf(firstCopy, secondCopy)) {
                assertCompleted(copy, expectedBytes.size.toLong())
                assertArrayEquals(expectedBytes, copy.destination.readBytes())
            }
            assertFalse(files.cachedImage.exists())
            assertFalse(files.miss.exists())

            release[1].complete(Unit)
            assertImage(withTimeout(15_000) { second.await() })
            assertCachedImage(files)
            assertFalse(secondCopy.destination.exists())
            assertArrayEquals("A completing fetch deletes only its own scratch", expectedBytes, firstCopy.destination.readBytes())
            val publishedBytes = files.cachedImage.readBytes()
            val requested = CancellationException("Cancel peer after another same-key icon was published")
            first.cancel(requested)
            val failure = runCatching { withTimeout(15_000) { first.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertCancellation(requested, requireNotNull(failure))
            assertFalse(firstCopy.destination.exists())
            assertArrayEquals(publishedBytes, files.cachedImage.readBytes())
            assertCachedImage(files)
            assertFalse(files.miss.exists())
            assertNoScratch(files)
            assertImage(fetch(files, reads.repository))
            assertEquals(2, reads.records.size)
            assertEquals(listOf(files.cachedImage.name), files.iconDirectory.list().orEmpty().toList())
            report("overlap", mapOf("root_copies" to reads.records.size,
                "distinct_scratch_files" to true, "cancelled_peer_preserved_cache" to true))
        } finally {
            withContext(NonCancellable) {
                release.forEach { it.complete(Unit) }
                pending.forEach { it.cancel() }
                withTimeout(20_000) { pending.forEach { it.join() } }
                files.cleanupIfAcknowledged(reads)
            }
        }
    }

    /** Observation and completion gates only: the selected provider and all generated reads are real. */
    private class ObservedReads(
        val fixture: Fixture,
        val files: TestFiles,
        val onOutcome: suspend (CopyRecord, RootJobOutcome) -> Unit = { _, _ -> },
        val afterCopy: suspend (CopyRecord) -> Unit = {},
    ) {
        val records = ConcurrentLinkedQueue<CopyRecord>()
        private val sequence = AtomicInteger()
        val repository = object : SystemRepository by fixture.repository {
            override suspend fun copyFileForRead(
                sourcePath: String,
                destination: File,
                maxBytes: Long?,
                execution: PrivilegeExecutionContext,
            ): Result<Unit> {
                assertEquals(files.input.absolutePath, sourcePath)
                assertEquals(256L * 1024 * 1024, maxBytes)
                assertEquals(PrivilegeExecutionLane.ARCHIVE, execution.lane)
                assertEquals(ICON_READ, execution.commandClass)
                assertEquals(30.seconds, execution.commandTimeout)
                assertTrue(destination.canonicalPath.startsWith(files.cache.canonicalPath + File.separator))
                val record = CopyRecord(sequence.incrementAndGet(), destination)
                records += record
                val observer = object : RootExecutionObserver {
                    override suspend fun beforeSubmit() {
                        assertEquals(1, record.submissions.incrementAndGet())
                        assertOwnedArchive(fixture)
                        // Test correlation only: it identifies this call even when another caller
                        // has allocated its own workspace while waiting for ARCHIVE admission.
                        val workspace = files.stagingRoot.listFiles().orEmpty().single { directory ->
                            runCatching {
                                readReceipt(File(directory, RootExportStaging.RECEIPT_NAME)).workRequestId == record.id.toString()
                            }.getOrDefault(false)
                        }
                        record.workspace.set(workspace)
                        val receipt = requireNotNull(record.receipt())
                        assertNull(readReceipt(receipt).kind)
                        assertEquals(workspace.name, readReceipt(receipt).id)
                        assertTrue(requireNotNull(record.payload()).isFile)
                        assertEquals(0L, requireNotNull(record.payload()).length())
                        assertFalse(destination.exists())
                    }

                    override suspend fun onOutcome(outcome: RootJobOutcome) {
                        record.terminal.set(outcome)
                        val payload = requireNotNull(record.payload())
                        val receipt = requireNotNull(record.receipt())
                        record.payloadBytes.set(payload.length())
                        record.receiptBytes.set(receipt.length())
                        assertTrue("The receipt and payload precede promotion", payload.exists())
                        assertEquals(outcome.kind.name, readReceipt(receipt).kind)
                        assertEquals(outcome.cleanupConfirmed, readReceipt(receipt).cleanupConfirmed)
                        assertFalse(destination.exists())
                        onOutcome(record, outcome)
                    }
                }
                val decorated = execution.copy(workRequestId = record.id, rootExecutionObserver = observer)
                    .also { it.provenance = execution.provenance }
                val result = fixture.repository.copyFileForRead(sourcePath, destination, maxBytes, decorated)
                if (result.isSuccess) afterCopy(record)
                return result
            }
        }
    }

    private class CopyRecord(val index: Int, val destination: File) {
        val id: UUID = UUID.randomUUID()
        val submissions = AtomicInteger()
        val workspace = AtomicReference<File?>()
        val terminal = AtomicReference<RootJobOutcome?>()
        val payloadBytes = AtomicLong()
        val receiptBytes = AtomicLong()
        fun payload(): File? = workspace.get()?.let { File(it, RootExportStaging.PAYLOAD_NAME) }
        fun receipt(): File? = workspace.get()?.let { File(it, RootExportStaging.RECEIPT_NAME) }
    }

    private class TestFiles(fixture: Fixture, name: String) {
        val directory = File(fixture.context.cacheDir, "archive-icon-$name-${UUID.randomUUID()}")
            .apply { assertTrue(mkdir()) }
        val cache = File(directory, "consumer-cache").apply { assertTrue(mkdir()) }
        val context = object : ContextWrapper(fixture.context) {
            override fun getCacheDir(): File = cache
        }
        val input = File(directory, "protected.xapk")
        // Unknown size and stable metadata also let the cancellation test retry the exact key.
        val model = ArchiveIconModel(Uri.fromFile(input).toString(), null, input.name)
        val iconDirectory = File(cache, "archive_icons")
        private val key = "icon_${model.uriString.hashCode()}_${model.sizeBytes}_${model.lastModifiedEpochSec}"
        val cachedImage = File(iconDirectory, "$key.png")
        val legacyMiss = File(iconDirectory, "$key.none")
        val miss = File(iconDirectory, "$key.none-v2")
        val stagingRoot = File(fixture.context.noBackupFilesDir, PrivilegedReadStaging.DIRECTORY_NAME)

        fun cleanupIfAcknowledged(reads: ObservedReads) {
            if (reads.records.all { it.submissions.get() == 0 || it.terminal.get()?.cleanupConfirmed == true }) {
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

    private suspend fun fetch(files: TestFiles, repository: SystemRepository): FetchResult? =
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                ArchiveIconFetcher(files.model, files.context, repository, Options(files.context, size = Size(32, 32))).fetch()
            }
        }

    private suspend fun rootCommand(fixture: Fixture, command: String): Pair<Int, String?> = withTimeout(15_000) {
        fixture.gateway.executeShellCommand(command, PrivilegeExecutionContext()).getOrThrow()
            .also { assertEquals(0, it.first) }
    }

    private data class Fixture(
        val context: Context,
        val gateway: RootSystemGateway,
        val statuses: RootLaneStatusSource,
        val repository: SystemRepository,
    )

    private companion object {
        val ICON_READ = PrivilegeCommandClass("input.archive-icon")
        val ICON_COLOR = Color.rgb(37, 173, 91)

        fun protect(input: File) {
            // The app owns this fixture. Keep setup independent of root lane admission.
            Os.chmod(input.absolutePath, 0)
            assertFalse(input.canRead())
            val direct = runCatching { input.inputStream().use { it.read() } }
            assertTrue("Resolver fallback must be backed by real app read denial", direct.exceptionOrNull() is IOException)
        }

        fun writeArchive(file: File, withIcon: Boolean) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/readme.txt"))
                zip.write("Disposable archive icon fixture".toByteArray())
                zip.closeEntry()
                if (withIcon) {
                    val bitmap = createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    val encoded = try {
                        bitmap.eraseColor(ICON_COLOR)
                        ByteArrayOutputStream().use { bytes ->
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                            bytes.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    zip.putNextEntry(ZipEntry("icon.png"))
                    zip.write(encoded)
                    zip.closeEntry()
                }
            }
        }

        fun assertImage(result: FetchResult?) {
            assertTrue(result is ImageFetchResult)
            val fetched = result as ImageFetchResult
            assertEquals(DataSource.DISK, fetched.dataSource)
            val bitmap = fetched.image.toBitmap()
            assertEquals(32, bitmap.width)
            assertEquals(32, bitmap.height)
            assertEquals(ICON_COLOR, bitmap[0, 0])
            assertEquals(ICON_COLOR, bitmap[31, 31])
        }

        fun assertCachedImage(files: TestFiles) {
            assertTrue(files.cachedImage.isFile)
            val bitmap = BitmapFactory.decodeFile(files.cachedImage.absolutePath)
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            try {
                assertEquals(32, bitmap.width)
                assertEquals(32, bitmap.height)
                assertEquals(ICON_COLOR, bitmap[16, 16])
            } finally {
                bitmap.recycle()
            }
        }

        fun assertCompleted(record: CopyRecord, expectedSize: Long) {
            assertEquals(1, record.submissions.get())
            val outcome = requireNotNull(record.terminal.get())
            assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
            assertEquals(0, outcome.exitCode)
            assertTrue(outcome.started)
            assertTrue(outcome.cleanupConfirmed)
            assertTrue(outcome.shellReusable)
            assertTrue(outcome.stdout.isEmpty())
            assertTrue(outcome.stderr.isEmpty())
            assertNull(outcome.failure)
            assertEquals(expectedSize, record.payloadBytes.get())
            assertTrue(record.receiptBytes.get() > 0)
            assertFalse(requireNotNull(record.workspace.get()).exists())
        }

        fun assertNoScratch(files: TestFiles) {
            assertTrue("Every per-fetch archive and extracted APK is released",
                files.cache.listFiles().orEmpty().none { it.name.startsWith("temp_ico_") || it.name.startsWith("tmp_bundle_apk_") })
            assertTrue("Icon publication releases its private encoding file",
                files.iconDirectory.listFiles().orEmpty().none { it.name.startsWith(".icon_write_") })
        }

        fun assertOwnedArchive(fixture: Fixture) {
            val status = fixture.statuses.statuses.value.getValue(PrivilegeExecutionLane.ARCHIVE)
            assertEquals(RootLaneMode.ISOLATED, status.mode)
            assertEquals(ICON_READ, status.activeCommandClass)
        }

        fun assertCancellation(requested: CancellationException, received: Throwable) {
            assertTrue("The caller's cancellation is preserved",
                generateSequence(received) { it.cause }.any { it === requested })
        }

        fun readReceipt(receipt: File): RootExportStagingRecord =
            RootExportStaging.json.decodeFromString(receipt.readText())

        fun report(name: String, values: Map<String, Any>) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nTHOR_ARCHIVE_ICON_METRIC $name ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
            })
        }
    }
}
