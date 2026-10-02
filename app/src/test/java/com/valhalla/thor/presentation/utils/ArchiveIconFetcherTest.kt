// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Size
import com.valhalla.thor.data.repository.MAX_METADATA_ENTRY_BYTES
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveIconFetcherTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resolver: ShadowContentResolver
    private lateinit var system: SystemRepository
    private lateinit var png: ByteArray
    private lateinit var bundle: ByteArray
    private val copies = mutableListOf<ReadCopy>()
    private var providerOpens = 0
    private var copy: suspend (ReadCopy) -> Result<Unit> = { error("unexpected privileged staging") }

    @Before
    fun setUp() {
        val base: Application = ApplicationProvider.getApplicationContext()
        val cache = temporary.newFolder("cache").canonicalFile
        val noBackup = temporary.newFolder("no_backup").canonicalFile
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = noBackup
        }
        resolver = shadowOf(context.contentResolver)
        png = png()
        bundle = zip("icon.png" to png)
        system = object : SystemRepository by FakeSystemRepository() {
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
            ): Result<Pair<Int, String?>> = error("icon fetches must use the read-copy owner")
        }
    }

    @Test fun `privileged fallback forwards its byte budget and archive deadline then removes scratch`() = runBlocking {
        val model = inaccessible("bounded.xapk")
        copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }

        assertImage(fetch(model))

        val request = copies.single()
        assertEquals(model.uriString.toUri().path, request.source)
        assertEquals(256L * 1024 * 1024, request.maxBytes)
        assertEquals(PrivilegeExecutionLane.ARCHIVE, request.execution.lane)
        assertEquals("input.archive-icon", request.execution.commandClass.value)
        assertEquals(30.seconds, request.execution.commandTimeout)
        assertEquals(context.cacheDir, request.destination.parentFile)
        assertFalse(request.destination.exists())
        assertValidPng(cachedIcon(model))
        assertFalse(missMarker(model).exists())
        assertTrue(scratchFiles().isEmpty())
    }

    @Test fun `cancelled staging preserves unrelated ownership and same key succeeds on retry`() = runBlocking {
        val model = inaccessible("cancelled.xapk")
        val unrelated = File(context.cacheDir, "temp_ico_another-owner").apply { writeText("other fetch") }
        val rootOwned = File(context.noBackupFilesDir, "retained-root-payload").apply { writeText("root receipt owner") }
        val cancelled = CancellationException("cancel archive icon")
        copy = { request -> request.destination.writeBytes(bundle); Result.failure(cancelled) }

        assertSame(cancelled, runCatching { fetch(model) }.exceptionOrNull())
        assertFalse(copies.single().destination.exists())
        assertFalse(missMarker(model).exists())
        assertFalse(cachedIcon(model).exists())
        assertEquals("other fetch", unrelated.readText())
        assertEquals("root receipt owner", rootOwned.readText())

        copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }
        assertImage(fetch(model))
        assertEquals(2, copies.size)
        assertValidPng(cachedIcon(model))
        assertFalse(missMarker(model).exists())
        assertEquals(listOf(unrelated), scratchFiles())
    }

    @Test fun `ordinary and uncertain staging failures never become cached icon misses`() = runBlocking {
        for ((index, failure) in listOf(IOException("temporary provider failure"), uncertain()).withIndex()) {
            val model = inaccessible("retry-$index.xapk")
            val previousCalls = copies.size
            copy = { request -> request.destination.writeBytes(bundle); Result.failure(failure) }

            assertNull(fetch(model))
            assertEquals(previousCalls + 1, copies.size)
            assertFalse(copies.last().destination.exists())
            assertFalse(missMarker(model).exists())
            assertFalse(cachedIcon(model).exists())

            copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }
            assertImage(fetch(model))
            assertEquals(previousCalls + 2, copies.size)
            assertValidPng(cachedIcon(model))
            assertFalse(missMarker(model).exists())
        }
        assertTrue(scratchFiles().isEmpty())
    }

    @Test fun `successfully inspected bundle without an icon caches a known miss`() = runBlocking {
        val model = inaccessible("no-icon.xapk")
        copy = { request ->
            request.destination.writeBytes(zip("manifest.json" to "{}".toByteArray()))
            Result.success(Unit)
        }

        assertNull(fetch(model))
        assertTrue(missMarker(model).isFile)
        assertFalse(copies.single().destination.exists())
        assertNull(fetch(model))
        assertEquals(1, copies.size)
        assertEquals(1, providerOpens)
        assertTrue(scratchFiles().isEmpty())
    }

    @Test fun `unreadable bundle and invalid APK candidate do not cache a miss`() = runBlocking {
        val payloads = listOf("not a ZIP".toByteArray(), zip("base.apk" to "not an APK".toByteArray()))
        for ((index, bytes) in payloads.withIndex()) {
            val model = inaccessible("invalid-$index.xapk")
            copy = { request -> request.destination.writeBytes(bytes); Result.success(Unit) }
            assertNull(fetch(model))
            assertFalse(missMarker(model).exists())
            assertTrue(scratchFiles().isEmpty())
            copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }
            assertImage(fetch(model))
        }
    }

    @Test fun `present but unreadable or oversized bundle icons do not cache a miss`() = runBlocking {
        val payloads = listOf(ByteArray(0), "not an image".toByteArray(), ByteArray(MAX_METADATA_ENTRY_BYTES.toInt() + 1))
        for ((index, bytes) in payloads.withIndex()) {
            val model = inaccessible("invalid-icon-$index.xapk")
            val entry = if (index == 1) "ICON.PNG" else "icon.png"
            copy = { request -> request.destination.writeBytes(zip(entry to bytes)); Result.success(Unit) }
            assertNull(fetch(model))
            assertFalse(missMarker(model).exists())
            assertTrue(scratchFiles().isEmpty())
            copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }
            assertImage(fetch(model))
        }
    }

    @Test fun `thorbak header package absence is not cached as a permanent icon miss`() = runBlocking {
        val source = temporary.newFile("local.thorbak").apply {
            writeBytes(zip("thorbak.json" to "{\"packageName\":\"com.example.not.installed\"}".toByteArray()))
        }
        val model = ArchiveIconModel(Uri.fromFile(source).toString(), null, source.name)
        assertNull(fetch(model))
        assertFalse(missMarker(model).exists())
        assertTrue(copies.isEmpty())
        assertTrue(source.isFile)
    }

    @Test fun `cheap policy skips do not open providers stage bytes or write miss markers`() = runBlocking {
        val models = listOf(
            model("backup.thorbak"),
            model("too-large.xapk", sizeBytes = 256L * 1024 * 1024 + 1),
        )
        for (model in models) {
            resolver.registerInputStreamSupplier(model.uriString.toUri()) {
                providerOpens++
                ByteArrayInputStream(bundle)
            }
            assertNull(fetch(model))
            assertNull(fetch(model))
            assertFalse(missMarker(model).exists())
        }
        assertEquals(0, providerOpens)
        assertTrue(copies.isEmpty())
        assertTrue(scratchFiles().isEmpty())
    }

    @Test fun `legacy miss markers cannot suppress a successful read with the same key`() = runBlocking {
        val model = inaccessible("legacy-miss.xapk")
        File(iconCache(), "${cacheKey(model)}.none").apply { parentFile!!.mkdirs(); createNewFile() }
        copy = { request -> request.destination.writeBytes(bundle); Result.success(Unit) }

        assertImage(fetch(model))

        assertEquals(1, copies.size)
        assertValidPng(cachedIcon(model))
        assertFalse(missMarker(model).exists())
    }

    @Test fun `existing PNG cache key remains readable without provider or privileged access`() = runBlocking {
        val model = inaccessible("existing-cache.xapk")
        cachedIcon(model).apply { parentFile!!.mkdirs(); writeBytes(png) }

        assertImage(fetch(model))

        assertEquals(0, providerOpens)
        assertTrue(copies.isEmpty())
        assertArrayEquals(png, cachedIcon(model).readBytes())
    }

    @Test fun `overlapping same key reads own unique scratch and cancellation preserves sibling publication`() = runBlocking {
        withTimeout(5_000) {
            val model = inaccessible("overlap.xapk")
            val firstStarted = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            copy = { request ->
                request.destination.writeBytes(bundle)
                if (copies.size == 1) {
                    firstStarted.complete(Unit)
                    finishFirst.await()
                }
                Result.success(Unit)
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { fetch(model) }
            try {
                firstStarted.await()
                assertImage(fetch(model))
                assertEquals(2, copies.size)
                val firstScratch = copies[0].destination
                val secondScratch = copies[1].destination
                assertNotEquals(firstScratch, secondScratch)
                assertTrue(firstScratch.exists())
                assertFalse(secondScratch.exists())
                val published = cachedIcon(model).readBytes()
                assertValidPng(cachedIcon(model))

                first.cancel(CancellationException("first row left the viewport"))
                assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)

                assertFalse(firstScratch.exists())
                assertArrayEquals(published, cachedIcon(model).readBytes())
                assertFalse(missMarker(model).exists())
                assertTrue(scratchFiles().isEmpty())
                assertEquals(listOf(cachedIcon(model).name), iconCache().listFiles()!!.map { it.name })
                assertImage(fetch(model))
                assertEquals(2, copies.size)
            } finally {
                finishFirst.complete(Unit)
                first.cancelAndJoin()
            }
        }
    }

    @Test fun `provider bytes are read once and closed without privileged staging`() = runBlocking {
        val model = model("provider.xapk")
        var closed = false
        resolver.registerInputStreamSupplier(model.uriString.toUri()) {
            providerOpens++
            object : ByteArrayInputStream(bundle) {
                override fun close() { closed = true; super.close() }
            }
        }

        assertImage(fetch(model))

        assertEquals(1, providerOpens)
        assertTrue(closed)
        assertTrue(copies.isEmpty())
        assertTrue(scratchFiles().isEmpty())
        assertValidPng(cachedIcon(model))
    }

    @Test fun `provider open and read cancellations propagate without fallback or a miss marker`() = runBlocking {
        for (cancelOnOpen in listOf(true, false)) {
            val model = model("provider-cancel-$cancelOnOpen.xapk")
            val cancelled = CancellationException("provider cancelled")
            var closed = false
            resolver.registerInputStreamSupplier(model.uriString.toUri()) {
                if (cancelOnOpen) throw cancelled
                object : ByteArrayInputStream(bundle) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw cancelled
                    override fun close() { closed = true; super.close() }
                }
            }

            assertSame(cancelled, runCatching { fetch(model) }.exceptionOrNull())
            assertEquals(!cancelOnOpen, closed)
            assertTrue(copies.isEmpty())
            assertFalse(missMarker(model).exists())
            assertFalse(cachedIcon(model).exists())
            assertTrue(scratchFiles().isEmpty())
        }
    }

    @Test fun `readable local bundle is inspected in place without provider or staging`() = runBlocking {
        val source = temporary.newFile("local.xapk").apply { writeBytes(bundle) }
        val model = ArchiveIconModel(Uri.fromFile(source).toString(), null, source.name)
        resolver.registerInputStreamSupplier(Uri.fromFile(source)) {
            providerOpens++
            throw FileNotFoundException("the direct file must not use a provider")
        }

        assertImage(fetch(model))

        assertEquals(0, providerOpens)
        assertTrue(copies.isEmpty())
        assertArrayEquals(bundle, source.readBytes())
        assertTrue(scratchFiles().isEmpty())
    }

    @Test fun `installed app icon is returned before provider or staging work`() = runBlocking {
        val packageName = "com.example.archiveicon.installed"
        val packages = shadowOf(context.packageManager)
        packages.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                flags = ApplicationInfo.FLAG_INSTALLED
                icon = 1
            }
        })
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        packages.addDrawableResolution(packageName, 1, BitmapDrawable(context.resources, bitmap))
        val model = inaccessible("installed.thorbak").copy(packageName = packageName)
        try {
            assertImage(fetch(model))
            assertEquals(0, providerOpens)
            assertTrue(copies.isEmpty())
            assertFalse(missMarker(model).exists())
            assertTrue(scratchFiles().isEmpty())
        } finally {
            packages.removePackage(packageName)
        }
    }

    private suspend fun fetch(model: ArchiveIconModel): FetchResult? =
        ArchiveIconFetcher(model, context, system, Options(context, size = Size(24, 24))).fetch()

    private fun model(name: String, sizeBytes: Long = 0L) = ArchiveIconModel(
        uriString = "content://archive-icon.test/$name",
        packageName = null,
        displayName = name,
        sizeBytes = sizeBytes,
        lastModifiedEpochSec = 7L,
    )

    private fun inaccessible(name: String): ArchiveIconModel = model(name).also { model ->
        resolver.registerInputStreamSupplier(model.uriString.toUri()) {
            providerOpens++
            throw FileNotFoundException("provider refuses direct read")
        }
    }

    private fun iconCache() = File(context.cacheDir, "archive_icons")
    private fun cacheKey(model: ArchiveIconModel) =
        "icon_${model.uriString.hashCode()}_${model.sizeBytes}_${model.lastModifiedEpochSec}"
    private fun cachedIcon(model: ArchiveIconModel) = File(iconCache(), "${cacheKey(model)}.png")
    private fun missMarker(model: ArchiveIconModel) = File(iconCache(), "${cacheKey(model)}.none-v2")
    private fun scratchFiles() = context.cacheDir.listFiles().orEmpty().filter {
        it.name.startsWith("temp_ico_") || it.name.startsWith("tmp_bundle_apk_")
    }

    private fun assertImage(result: FetchResult?) { assertTrue(result is ImageFetchResult) }

    private fun assertValidPng(file: File) {
        assertTrue(file.isFile)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(decoded)
        assertEquals(8, decoded!!.width)
        assertEquals(8, decoded.height)
        decoded.recycle()
    }

    private fun png(): ByteArray {
        val bitmap = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        return try {
            ByteArrayOutputStream().use { bytes ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
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
