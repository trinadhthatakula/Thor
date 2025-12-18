package com.valhalla.thor.domain.usecase

import android.content.Context
import androidx.core.content.FileProvider
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.formattedAppName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShareAppUseCase(
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val apksMetadataGenerator: ApksMetadataGenerator
) {

    suspend operator fun invoke(appInfo: AppInfo): Result<android.net.Uri> = withContext(Dispatchers.IO) {
        try {
            // 1. Prepare Cache Directory
            val cacheDir = File(context.cacheDir, "share_temp")
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            val finalFile: File

            // 2. Check for Splits
            if (appInfo.splitPublicSourceDirs.isNullOrEmpty()) {
                // --- Single APK Mode ---
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                ?: return@withContext Result.failure(Exception("No source path found"))

                val fileName = "${appInfo.formattedAppName()}_${appInfo.versionName}.apk"
                finalFile = File(cacheDir, fileName)

                if (!copyFileSafely(sourcePath, finalFile)) {
                    return@withContext Result.failure(Exception("Failed to copy base APK"))
                }

            } else {
                // --- Split APK Mode (Zip/APKS) ---
                val fileName = "${appInfo.formattedAppName()}_${appInfo.versionName}.apks"
                finalFile = File(cacheDir, fileName)

                // Temp staging folder for individual parts
                val tempSplitDir = File(cacheDir, "splits_staging")
                tempSplitDir.mkdirs()

                // A. Gather APK Paths
                val allPaths = mutableListOf<String>()
                appInfo.sourceDir?.let { allPaths.add(it) }
                allPaths.addAll(appInfo.splitPublicSourceDirs)

                // B. Copy APKs to staging
                val filesToZip = allPaths.mapNotNull { path ->
                    val name = path.substringAfterLast("/")
                    val destFile = File(tempSplitDir, name)
                    if (copyFileSafely(path, destFile)) destFile else null
                }.toMutableList()

                if (filesToZip.isEmpty()) {
                    return@withContext Result.failure(Exception("Failed to copy any APK files"))
                }

                // C. GENERATE METADATA (The Missing Piece)
                // We generate "metadata.json" so installers know what this bundle is.
                val metadataFile = File(tempSplitDir, "metadata.json")
                apksMetadataGenerator.generateJson(appInfo, metadataFile)
                filesToZip.add(metadataFile)

                // D. Zip Everything (APKs + JSON)
                zipFiles(filesToZip, finalFile)
            }

            // 3. Generate URI
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.provider",
                finalFile
            )
            Result.success(uri)

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Tries standard copy, falls back to Root if permission denied.
     */
    private suspend fun copyFileSafely(sourcePath: String, destFile: File): Boolean {
        return try {
            File(sourcePath).copyTo(destFile, overwrite = true)
            true
        } catch (_: SecurityException) {
            systemRepository.copyFileWithRoot(sourcePath, destFile.absolutePath).isSuccess
        } catch (_: Exception) {
            systemRepository.copyFileWithRoot(sourcePath, destFile.absolutePath).isSuccess
        }
    }

    private fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            val data = ByteArray(1024)
            files.forEach { file ->
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        while (true) {
                            val readBytes = origin.read(data)
                            if (readBytes == -1) break
                            out.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }
}