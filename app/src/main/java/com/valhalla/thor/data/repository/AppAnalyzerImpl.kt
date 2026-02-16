package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.AppAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class AppAnalyzerImpl(private val context: Context) : AppAnalyzer {

    override suspend fun analyze(uri: Uri): Result<AppMetadata> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "analysis_${System.currentTimeMillis()}.apk")

        try {
            val contentResolver = context.contentResolver

            // Phase 1: Try to extract a nested APK (for XAPK/APKS)
            var isNestedBundle = false

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                FileOutputStream(tempFile).use { fos ->
                                    zipStream.copyTo(fos)
                                }
                                isNestedBundle = true
                                break
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {
                isNestedBundle = false
            }

            // Phase 2: Fallback (Standard APK)
            if (!isNestedBundle) {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Phase 3: Parsing
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS

            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    tempFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tempFile.absolutePath, flags)
            }

            if (archiveInfo == null) {
                return@withContext Result.failure(Exception("Failed to parse APK manifest. The file might be corrupted or encrypted."))
            }

            // Necessary to load resources properly from an external file
            archiveInfo.applicationInfo?.sourceDir = tempFile.absolutePath
            archiveInfo.applicationInfo?.publicSourceDir = tempFile.absolutePath

            val label = archiveInfo.applicationInfo?.loadLabel(pm).toString()
            val drawable = archiveInfo.applicationInfo?.loadIcon(pm)
            val version = archiveInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode.toLong()
            }
            val pkgName = archiveInfo.packageName
            val permissions = archiveInfo.requestedPermissions?.toList() ?: emptyList()

            Result.success(
                AppMetadata(
                    label = label,
                    packageName = pkgName,
                    version = version,
                    versionCode = versionCode,
                    icon = drawable?.toBitmap(),
                    permissions = permissions
                )
            )

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) return this.bitmap

        val bitmap = createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}