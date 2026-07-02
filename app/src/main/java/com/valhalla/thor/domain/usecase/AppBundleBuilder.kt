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
            val cacheDir = File(context.cacheDir, cacheSubDir)
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            val finalFile: File
            if (appInfo.splitPublicSourceDirs.isEmpty()) {
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                    ?: return@withContext Result.failure(Exception("No source path found"))
                finalFile = File(cacheDir, "${appInfo.formattedAppName()}_${appInfo.versionName}.apk")
                if (!copyFileSafely(sourcePath, finalFile)) {
                    return@withContext Result.failure(Exception("Failed to copy base APK"))
                }
            } else {
                finalFile = File(cacheDir, "${appInfo.formattedAppName()}_${appInfo.versionName}.apks")
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
