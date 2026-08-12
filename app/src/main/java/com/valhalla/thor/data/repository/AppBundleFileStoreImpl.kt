// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.AppBundleFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The subdirectory of public Downloads that every write Thor makes lands in.
 *
 * One name with four write sites across two files — this store's MediaStore and legacy paths, and
 * [AppArchiveStoreImpl]'s — so it is defined once. It had drifted: the two archive backends wrote to
 * Downloads **root** while `AppArchiveStore.currentTargetLabel()`, which resolves through this store,
 * told the user *"Downloads/Thor"*. The label was right about the convention and wrong about where the
 * file went, which is the worst way round for the one caption naming a folder the user then has to
 * find.
 *
 * A `const val` on purpose, so it is inlined and nothing has to load a file facade to read it: the
 * relative path is assembled inside the functions that need it, never in a top-level `val`. A
 * top-level `val` touching `Environment` would run on the JVM the moment a test called any other
 * top-level member of the same file, and `AppArchiveStoreImpl.kt` has three that are JVM-tested.
 */
internal const val THOR_DOWNLOADS_SUBDIR = "Thor"

/**
 * Android-backed [AppBundleFileStore]: writes bundles to public Downloads
 * (MediaStore on Q+, legacy external storage otherwise) or a user-picked SAF
 * tree, and builds FileProvider content URIs for sharing. All the framework
 * file-I/O for export/share lives here so the domain use cases stay pure.
 */
@Single(binds = [AppBundleFileStore::class])
class AppBundleFileStoreImpl(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppBundleFileStore {

    // All suspend members are main-safe: the blocking MediaStore/SAF/disk I/O runs on the
    // injected IO dispatcher so callers can invoke them from any context without risking an ANR.
    override suspend fun writeToDownloads(file: File, mime: String): String =
        withContext(ioDispatcher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeToDownloadsMediaStore(file, mime)
            else writeToDownloadsLegacy(file)
        }

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        withContext(ioDispatcher) {
            val treeUri = treeUriStr.toUri()
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
            tree.findFile(file.name)?.delete() // overwrite
            val doc = tree.createFile(mime, file.name) ?: throw IOException("Could not create file")
            try {
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    file.inputStream().use { it.copyCancellableTo(out) }
                } ?: throw IOException("openOutputStream failed")
            } catch (e: Exception) {
                // CancellationException is an Exception, so a cancelled export lands here too and
                // that is the point: the half-written document is deleted before the throw
                // propagates. Rethrowing unchanged keeps the cancellation a cancellation.
                doc.delete() // don't leave a partial/corrupted file behind
                throw e
            }
            tree.name ?: context.getString(R.string.export_dest_selected)
        }

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean =
        withContext(ioDispatcher) {
            if (treeUriStr == null) return@withContext false
            try {
                val doc = DocumentFile.fromTreeUri(context, treeUriStr.toUri())
                doc != null && doc.exists() && doc.canWrite()
            } catch (_: Exception) { false }
        }

    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String =
        withContext(ioDispatcher) {
            // SAF validity checks hit the content resolver / disk — keep them off the main thread.
            if (savedTreeUriStr != null && isTreeWritable(savedTreeUriStr)) {
                DocumentFile.fromTreeUri(context, savedTreeUriStr.toUri())?.name
                    ?: context.getString(R.string.export_dest_selected)
            } else context.getString(R.string.export_dest_downloads)
        }

    override fun shareUri(file: File): String =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file).toString()

    override suspend fun stageText(fileName: String, content: String): File =
        withContext(ioDispatcher) {
            // Under cacheDir, so `provider_paths.xml`'s cache-path makes it shareable, and so the
            // system can reclaim it if it is never collected here.
            val dir = File(context.cacheDir, TEXT_STAGING_DIR)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            File(dir, fileName).apply { writeText(content) }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun writeToDownloadsMediaStore(source: File, mime: String): String {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$THOR_DOWNLOADS_SUBDIR/"
        // MediaStore.insert appends " (1)" instead of overwriting, so delete any same-named
        // entry first. RELATIVE_PATH must match exactly, including the trailing slash.
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(source.name, relativePath)
        try {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, selectionArgs)
        } catch (_: Exception) { /* best-effort overwrite; fall through to insert */ }
        val values = contentValuesOf(
            MediaStore.Downloads.DISPLAY_NAME to source.name,
            MediaStore.Downloads.MIME_TYPE to mime,
            MediaStore.Downloads.RELATIVE_PATH to relativePath,
            MediaStore.Downloads.IS_PENDING to 1
        )
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyCancellableTo(out) }
            } ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Cancellation included — see writeToTree. An IS_PENDING entry that is never cleared
            // is invisible to other apps and never collected, so leaving one behind on cancel
            // would be a permanent phantom row in the user's Downloads.
            resolver.delete(uri, null, null) // don't leave a dangling pending entry
            throw e
        }
        return context.getString(R.string.export_dest_downloads)
    }

    private suspend fun writeToDownloadsLegacy(source: File): String {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            THOR_DOWNLOADS_SUBDIR
        )
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, source.name)
        try {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyCancellableTo(output) }
            }
        } catch (e: Exception) {
            // The SAF and MediaStore paths have always cleaned up after themselves; this one had
            // nothing to clean up because `File.copyTo` could not be interrupted. Now that it can,
            // a cancelled export would otherwise leave a truncated .apk sitting in Downloads under
            // the right name — the one failure shape a user cannot tell from a good export.
            dest.delete()
            throw e
        }
        return context.getString(R.string.export_dest_downloads)
    }

    /**
     * `InputStream.copyTo` in chunks, checking for cancellation between them.
     *
     * Exports are cancellable from the UI and a bundle is routinely hundreds of megabytes. The
     * stock `copyTo` is one uninterruptible call, so cancelling mid-write did nothing until the
     * whole file had been pushed through SAF or MediaStore — the progress UI would sit on a
     * cancelled export for as long as the copy took. The check is a volatile read per 8 KB against
     * an IO-bound loop, which costs nothing next to the write it guards. Mirrors
     * `AppBundleBuilderImpl.copyCancellable`, which does the same for the staging copies.
     */
    private suspend fun InputStream.copyCancellableTo(out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        out.flush()
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 8192

        /** Cache subdirectory for [stageText]; kept apart from the bundle builder's staging. */
        const val TEXT_STAGING_DIR = "list_export"
    }
}
