package com.valhalla.thor.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.PreferenceRepository
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
            val file = bundleBuilder.build(appInfo).getOrElse { return@withContext Result.failure(it) }
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
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String {
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        return if (savedUri != null && isTreeWritable(savedUri)) {
            DocumentFile.fromTreeUri(context, savedUri.toUri())?.name ?: "Selected folder"
        } else "Downloads/Thor"
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

    private fun writeToDownloads(source: File, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Thor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
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
        return "Downloads/Thor"
    }

    private fun writeToDownloadsLegacy(source: File): String {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Thor"
        )
        if (!dir.exists()) dir.mkdirs()
        source.copyTo(File(dir, source.name), overwrite = true)
        return "Downloads/Thor"
    }

    private fun writeToTree(source: File, treeUri: Uri, mime: String): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
        tree.findFile(source.name)?.delete() // overwrite
        val doc = tree.createFile(mime, source.name) ?: throw IOException("Could not create file")
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("openOutputStream failed")
        return tree.name ?: "Selected folder"
    }
}
