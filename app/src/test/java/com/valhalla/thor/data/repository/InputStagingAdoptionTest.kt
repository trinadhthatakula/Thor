// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InputStagingAdoptionTest {
    private lateinit var context: Application
    private lateinit var resolver: ShadowContentResolver
    private lateinit var analyzer: AppAnalyzerImpl
    private lateinit var archives: UriArchiveSourceFactory
    private val copies = mutableListOf<ReadCopy>()
    private var copy: suspend (ReadCopy) -> Result<Unit> = { error("unexpected privileged copy") }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resolver = shadowOf(context.contentResolver)
        val system = object : SystemRepository by FakeSystemRepository() {
            override suspend fun copyFileForRead(
                sourcePath: String,
                destination: File,
                maxBytes: Long?,
                execution: PrivilegeExecutionContext,
            ): Result<Unit> {
                val request = ReadCopy(sourcePath, destination, maxBytes, execution)
                copies += request
                return copy(request)
            }

            override suspend fun executeShellCommand(
                command: String,
                execution: PrivilegeExecutionContext,
            ): Result<Pair<Int, String?>> = error("callers must not bypass the read-copy owner")
        }
        analyzer = AppAnalyzerImpl(context, system, Dispatchers.Unconfined)
        archives = UriArchiveSourceFactory(context, system, Dispatchers.Unconfined)
    }

    @After
    fun cleanUp() {
        File(context.cacheDir, "staged_installs").deleteRecursively()
        readCopies().forEach(File::delete)
    }

    @Test
    fun `preview reads provider once and transfers those exact bytes until discarded`() = runBlocking {
        val uri = uri("preview.xapk")
        val bytes = bundle()
        val changedBytes = bundle("com.example.changed")
        var opens = 0
        var closed = false
        resolver.registerInputStreamSupplier(uri) {
            opens++
            object : ByteArrayInputStream(if (opens == 1) bytes else changedBytes) {
                override fun close() { closed = true; super.close() }
            }
        }

        val analyzed = analyzer.analyze(uri).getOrThrow()

        assertEquals(1, opens)
        assertTrue(closed)
        assertTrue(copies.isEmpty())
        assertEquals("com.example.preview", analyzed.metadata.packageName)
        assertArrayEquals(bytes, analyzed.staged.file.readBytes())
        analyzer.discard(analyzed)
        assertFalse(analyzed.staged.file.exists())
    }

    @Test
    fun `preview fallback forwards its budget and bounded archive context`() = runBlocking {
        val uri = inaccessible("preview.xapk")
        val bytes = bundle()
        copy = { request -> request.destination.writeBytes(bytes); Result.success(Unit) }

        val analyzed = analyzer.analyze(uri).getOrThrow()

        val request = copies.single()
        assertEquals(uri.path, request.source)
        assertEquals(MAX_EXTRACTED_TOTAL_BYTES, request.maxBytes)
        assertExecution(request, "input.preview")
        assertEquals(analyzed.staged.file, request.destination)
        assertArrayEquals(bytes, analyzed.staged.file.readBytes())
        analyzer.discard(analyzed)
    }

    @Test
    fun `preview rejects invalid completed bytes and preserves typed staging failure`() = runBlocking {
        val uri = inaccessible("invalid.apk")
        copy = { request -> request.destination.writeText("not an APK"); Result.success(Unit) }
        assertTrue(analyzer.analyze(uri).isFailure)
        assertFalse(copies.single().destination.exists())

        val failure = uncertain()
        copy = { request -> request.destination.writeBytes(bundle()); Result.failure(failure) }
        assertSame(failure, analyzer.analyze(uri).exceptionOrNull())
        assertFalse(copies.last().destination.exists())
        assertEquals(2, copies.size)
    }

    @Test
    fun `preview cancellation propagates unchanged and deletes untransferred bytes`() = runBlocking {
        val uri = inaccessible("cancel.xapk")
        val cancelled = CancellationException("cancel preview")
        copy = { request -> request.destination.writeBytes(bundle()); Result.failure(cancelled) }

        assertSame(cancelled, runCatching { analyzer.analyze(uri) }.exceptionOrNull())
        assertFalse(copies.single().destination.exists())
    }

    @Test
    fun `archive provider fallback opens without privilege and owns its cache until close`() = runBlocking {
        val uri = uri("provider.thorbak")
        var opens = 0
        resolver.registerInputStreamSupplier(uri) { opens++; ByteArrayInputStream(archive("provider")) }

        val source = (archives.open(uri.toString()) as ArchiveOpenOutcome.Opened).source
        val cache = readCopies().single()
        try {
            assertEquals("provider", source.openEntry("payload")!!.bufferedReader().use { it.readText() })
            assertTrue(cache.exists())
            assertEquals(1, opens)
            assertTrue(copies.isEmpty())
        } finally {
            source.close()
        }
        assertFalse(cache.exists())
    }

    @Test
    fun `archive fallback forwards bounded context without imposing a new size cap`() = runBlocking {
        val uri = inaccessible("archive.thorbak")
        copy = { request -> request.destination.writeBytes(archive("complete")); Result.success(Unit) }

        val source = (archives.open(uri.toString()) as ArchiveOpenOutcome.Opened).source
        val request = copies.single()
        try {
            assertEquals(uri.path, request.source)
            assertNull(request.maxBytes)
            assertExecution(request, "input.archive")
            assertEquals("complete", source.openEntry("payload")!!.bufferedReader().use { it.readText() })
            assertTrue(request.destination.exists())
        } finally {
            source.close()
        }
        assertFalse(request.destination.exists())
    }

    @Test
    fun `invalid provider bytes remain not an archive without privileged retry`() = runBlocking {
        val uri = uri("invalid.thorbak")
        resolver.registerInputStream(uri, ByteArrayInputStream("not a ZIP".toByteArray()))

        assertSame(ArchiveOpenOutcome.NotAnArchive, archives.open(uri.toString()))
        assertTrue(copies.isEmpty())
        assertTrue(readCopies().isEmpty())
    }

    @Test
    fun `uncertain archive copy cannot publish readable bytes or retry`() = runBlocking {
        val uri = inaccessible("uncertain.thorbak")
        copy = { request -> request.destination.writeBytes(archive("partial")); Result.failure(uncertain()) }

        assertSame(ArchiveOpenOutcome.Unreadable, archives.open(uri.toString()))
        assertEquals(1, copies.size)
        assertFalse(copies.single().destination.exists())
    }

    @Test
    fun `archive cancellation propagates unchanged and removes untransferred cache`() = runBlocking {
        val uri = inaccessible("cancel.thorbak")
        val cancelled = CancellationException("cancel archive")
        copy = { request -> request.destination.writeBytes(archive("complete")); Result.failure(cancelled) }

        assertSame(cancelled, runCatching { archives.open(uri.toString()) }.exceptionOrNull())
        assertFalse(copies.single().destination.exists())
    }

    @Test
    fun `provider cancellation does not trigger privileged fallback in either caller`() = runBlocking {
        val uri = uri("cancel-open.xapk")
        val cancelled = CancellationException("provider open cancelled")
        resolver.registerInputStreamSupplier(uri) { throw cancelled }

        assertSame(cancelled, runCatching { analyzer.analyze(uri) }.exceptionOrNull())
        assertSame(cancelled, runCatching { archives.open(uri.toString()) }.exceptionOrNull())
        assertTrue(copies.isEmpty())
        assertTrue(readCopies().isEmpty())
    }

    @Test
    fun `descriptor-null copy cancelled during provider read never leaks its opened archive`() = runBlocking {
        val uri = uri("cancel-provider.thorbak")
        val owner = Job()
        var closed = false
        var staged: File? = null
        val bytes = archive("complete")
        resolver.registerInputStream(uri, object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { count ->
                    if (count == -1) {
                        staged = readCopies().single()
                        owner.cancel(CancellationException("cancel while reading"))
                    }
                }
            override fun close() { closed = true; super.close() }
        })

        val result = async(owner) { archives.open(uri.toString()) }

        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        assertTrue(closed)
        assertTrue(copies.isEmpty())
        assertFalse(requireNotNull(staged).exists())
        assertTrue(readCopies().isEmpty())
    }

    @Test
    fun `overlapping archive fallback opens retain independent copies through close`() = runBlocking {
        val firstUri = inaccessible("first.thorbak")
        val secondUri = inaccessible("second.thorbak")
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        copy = { request ->
            if (request.source == firstUri.path) {
                firstStarted.complete(Unit)
                finishFirst.await()
            }
            request.destination.writeBytes(archive(request.source))
            Result.success(Unit)
        }
        val firstOpen = async { archives.open(firstUri.toString()) }
        firstStarted.await()
        val second = (archives.open(secondUri.toString()) as ArchiveOpenOutcome.Opened).source
        finishFirst.complete(Unit)
        val first = (firstOpen.await() as ArchiveOpenOutcome.Opened).source
        try {
            val firstFile = copies.first().destination
            val secondFile = copies.last().destination
            assertNotEquals(firstFile, secondFile)
            assertTrue(firstFile.exists())
            assertTrue(secondFile.exists())
            first.close()
            assertFalse(firstFile.exists())
            assertTrue(secondFile.exists())
            assertEquals(secondUri.path, second.openEntry("payload")!!.bufferedReader().use { it.readText() })
        } finally {
            first.close()
            second.close()
        }
        assertTrue(readCopies().isEmpty())
    }

    private fun uri(name: String): Uri = "content://input-staging.test/$name".toUri()

    private fun inaccessible(name: String): Uri = uri(name).also { uri ->
        resolver.registerInputStreamSupplier(uri) { throw FileNotFoundException("provider refuses read") }
    }

    internal fun readCopies(): List<File> = context.cacheDir.listFiles().orEmpty()
        .filter { UriArchiveSourceFactory.isReadCopyName(it.name) }

    private fun assertExecution(request: ReadCopy, commandClass: String) {
        assertEquals(PrivilegeExecutionLane.ARCHIVE, request.execution.lane)
        assertEquals(commandClass, request.execution.commandClass.value)
        assertEquals(9.minutes, request.execution.commandTimeout)
    }

    private fun archive(payload: String): ByteArray = zip("payload", payload)

    private fun bundle(packageName: String = "com.example.preview"): ByteArray = zip(
        "manifest.json",
        """{"package_name":"$packageName","name":"Preview","version_name":"1"}""",
    )

    private fun zip(name: String, text: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        bytes.toByteArray()
    }

    private fun uncertain() = IsolatedRootExecutionException(
        RootJobOutcome(
            kind = RootJobOutcomeKind.TERMINATION_UNCONFIRMED,
            exitCode = null,
            stdout = emptyList(),
            stderr = emptyList(),
            started = true,
            terminationConfirmed = false,
            outputDrained = false,
            shellReusable = false,
            failure = null,
        ),
    )

    private data class ReadCopy(
        val source: String,
        val destination: File,
        val maxBytes: Long?,
        val execution: PrivilegeExecutionContext,
    )
}
