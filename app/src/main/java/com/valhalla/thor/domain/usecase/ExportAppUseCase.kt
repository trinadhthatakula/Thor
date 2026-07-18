package com.valhalla.thor.domain.usecase

import android.content.ContentValues
import androidx.core.content.contentValuesOf
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import java.io.File
import java.io.IOException

@Factory
class ExportAppUseCase(
    private val context: Context,
    private val bundleBuilder: AppBundleBuilder,
    private val preferenceRepository: PreferenceRepository
) {
    /** Build the bundle and write it to the resolved target. Returns a location label. */
    suspend operator fun invoke(appInfo: AppInfo): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = bundleBuilder.build(appInfo, cacheSubDir = "export_temp").getOrElse { return@withContext Result.failure(it) }
            val mime = mimeFor(file)

            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            val location = when (val choice = resolution.choice) {
                is ExportTargetChoice.Custom -> writeToTree(file, choice.treeUri.toUri(), mime)
                ExportTargetChoice.Downloads ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeToDownloads(file, mime)
                    else writeToDownloadsLegacy(file)
            }
            Result.success(location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String = withContext(Dispatchers.IO) {
        // SAF validity checks hit the content resolver / disk — keep them off the main thread.
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        if (savedUri != null && isTreeWritable(savedUri)) {
            DocumentFile.fromTreeUri(context, savedUri.toUri())?.name
                ?: context.getString(R.string.export_dest_selected)
        } else context.getString(R.string.export_dest_downloads)
    }

    private fun mimeFor(file: File) =
        if (file.name.endsWith(".apk")) "application/vnd.android.package-archive"
        else "application/octet-stream"

    private fun isTreeWritable(uriStr: String?): Boolean {
        if (uriStr == null) return false
        return try {
            val doc = DocumentFile.fromTreeUri(context, uriStr.toUri())
            doc != null && doc.exists() && doc.canWrite()
        } catch (_: Exception) { false }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(source: File, mime: String): String {
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

    private fun writeToTree(source: File, treeUri: Uri, mime: String): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
        tree.findFile(source.name)?.delete() // overwrite
        val doc = tree.createFile(mime, source.name) ?: throw IOException("Could not create file")
        try {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("openOutputStream failed")
        } catch (e: Exception) {
            doc.delete() // don't leave a partial/corrupted file behind
            throw e
        }
        return tree.name ?: context.getString(R.string.export_dest_selected)
    }
}
