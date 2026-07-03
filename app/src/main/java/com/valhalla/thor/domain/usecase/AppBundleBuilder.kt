package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import android.content.Context
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.formattedAppName
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable/exportable app bundle in the cache dir: a single `.apk` for
 * apps with no splits, an `.apks` (base + splits + metadata.json + manifest.json)
 * for split apps. Copies with a root fallback for protected/system apps.
 */
@Single
class AppBundleBuilder(
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val apksMetadataGenerator: ApksMetadataGenerator
) {
    suspend fun build(appInfo: AppInfo, cacheSubDir: String = "share_temp"): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Per-package subdir. Bulk share builds each selected app sequentially into
            // the same cacheSubDir and hands all the resulting content:// URIs to
            // ACTION_SEND_MULTIPLE together AFTER the loop; wiping the whole dir on each
            // call would delete earlier apps' bundles before they are read. Distinct
            // packages (a multi-select can't pick the same app twice) never collide.
            val cacheDir = File(File(context.cacheDir, cacheSubDir), appInfo.packageName)
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            // Sanitize the output filename: appName/versionName are app-controlled and
            // formattedAppName() only strips spaces, so a "/" or ".." could escape
            // cacheDir once copyFileSafely() falls back to a root `cp`. Keep safe chars.
            val safeName = "${appInfo.formattedAppName()}_${appInfo.versionName}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")

            val finalFile: File
            if (appInfo.splitPublicSourceDirs.isEmpty()) {
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                    ?: return@withContext Result.failure(Exception("No source path found"))
                finalFile = File(cacheDir, "$safeName.apk")
                if (!copyFileSafely(sourcePath, finalFile)) {
                    return@withContext Result.failure(Exception("Failed to copy base APK"))
                }
            } else {
                finalFile = File(cacheDir, "$safeName.apks")
                val tempSplitDir = File(cacheDir, "splits_staging")
                tempSplitDir.mkdirs()

                val allPaths = mutableListOf<String>()
                appInfo.sourceDir?.let { allPaths.add(it) }
                allPaths.addAll(appInfo.splitPublicSourceDirs)

                val filesToZip = allPaths.mapNotNull { path ->
                    val destFile = File(tempSplitDir, path.substringAfterLast("/"))
                    if (copyFileSafely(path, destFile)) destFile else null
                }.toMutableList()
                if (filesToZip.isEmpty()) {
                    return@withContext Result.failure(Exception("Failed to copy any APK files"))
                }

                val metadataFile = File(tempSplitDir, "metadata.json")
                apksMetadataGenerator.generateJson(appInfo, metadataFile)
                filesToZip.add(metadataFile)

                val manifestFile = File(tempSplitDir, "manifest.json")
                apksMetadataGenerator.generateManifestJson(appInfo, manifestFile)
                filesToZip.add(manifestFile)

                zipFiles(filesToZip, finalFile)
                tempSplitDir.deleteRecursively()
            }
            Result.success(finalFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun copyFileSafely(sourcePath: String, destFile: File): Boolean {
        return try {
            File(sourcePath).copyTo(destFile, overwrite = true)
            true
        } catch (_: Exception) {
            systemRepository.copyFileWithRoot(sourcePath, destFile.absolutePath).isSuccess
        }
    }

    private fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            // 8 KB buffer — 1 KB is needlessly slow when zipping multi-MB APK splits.
            val data = ByteArray(8192)
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
