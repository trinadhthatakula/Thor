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
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException

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
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("openOutputStream failed")
            } catch (e: Exception) {
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloadsMediaStore(source: File, mime: String): String {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Thor/"
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
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a dangling pending entry
            throw e
        }
        return context.getString(R.string.export_dest_downloads)
    }

    private fun writeToDownloadsLegacy(source: File): String {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Thor"
        )
        if (!dir.exists()) dir.mkdirs()
        source.copyTo(File(dir, source.name), overwrite = true)
        return context.getString(R.string.export_dest_downloads)
    }
}
