package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class AppAnalyzerImpl(private val context: Context) : AppAnalyzer {

    @Serializable
    private data class XapkManifest(
        @SerialName("package_name") val packageName: String,
        @SerialName("name") val name: String,
        @SerialName("version_code") val versionCode: String,
        @SerialName("version_name") val versionName: String,
        @SerialName("permissions") val permissions: List<String> = emptyList(),
        @SerialName("icon") val iconFile: String? = null,
        @SerialName("split_apks") val splitApks: List<XapkSplitApk> = emptyList()
    )

    @Serializable
    private data class XapkSplitApk(
        @SerialName("file") val file: String,
        @SerialName("id") val id: String
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun analyze(uri: Uri): Result<AppMetadata> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "analysis_${System.currentTimeMillis()}.apk")

        try {
            val contentResolver = context.contentResolver

            // Phase 1: Single pass — collect manifest.json and icon bytes from XAPK zip
            var manifestBytes: ByteArray? = null
            var iconBytes: ByteArray? = null

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            when {
                                entry.name.equals("manifest.json", ignoreCase = true) -> {
                                    manifestBytes = zipStream.readBytes()
                                    zipStream.closeEntry()
                                }
                                entry.name.equals("icon.png", ignoreCase = true) ||
                                entry.name.equals("icon.jpg", ignoreCase = true) ||
                                entry.name.equals("icon.webp", ignoreCase = true) -> {
                                    iconBytes = zipStream.readBytes()
                                    zipStream.closeEntry()
                                }
                                else -> zipStream.closeEntry()
                            }
                            if (manifestBytes != null && iconBytes != null) break
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {}

            // Phase 2: If we have manifest.json, build metadata directly from it
            val manifest = manifestBytes?.let { bytes ->
                try { json.decodeFromString<XapkManifest>(String(bytes)) } catch (_: Exception) { null }
            }

            if (manifest != null) {
                // Decode icon — prefer icon.png from zip, fall back to extracting base APK
                val iconBitmap: Bitmap? = when {
                    iconBytes != null -> BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes!!.size)
                    else -> {
                        val baseApkFile = manifest.splitApks.firstOrNull { it.id == "base" }?.file
                        if (baseApkFile != null) extractBaseApkIcon(uri, baseApkFile, tempFile) else null
                    }
                }

                return@withContext Result.success(
                    AppMetadata(
                        label = manifest.name,
                        packageName = manifest.packageName,
                        version = manifest.versionName,
                        versionCode = manifest.versionCode.toLongOrNull() ?: 0L,
                        icon = iconBitmap,
                        permissions = manifest.permissions
                    )
                )
            }

            // Phase 3: No XAPK manifest — fall back to APK extraction from zip
            // First scan: look specifically for base.apk; second scan: first .apk found
            var isNestedBundle = false
            try {
                var foundBase = false

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name.equals("base.apk", ignoreCase = true) ||
                                name.endsWith("/base.apk", ignoreCase = true)) {
                                FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }
                                isNestedBundle = true
                                foundBase = true
                                break
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                }

                if (!foundBase) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipStream ->
                            var entry = zipStream.nextEntry
                            while (entry != null) {
                                if (entry.name.endsWith(".apk", ignoreCase = true)) {
                                    FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }
                                    isNestedBundle = true
                                    break
                                }
                                zipStream.closeEntry()
                                entry = zipStream.nextEntry
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                isNestedBundle = false
            }

            // Phase 4: Treat as monolithic APK if nothing was extracted from a zip
            if (!isNestedBundle) {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            }

            // Phase 5: Parse extracted/monolithic APK
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
                return@withContext Result.failure(
                    Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
                )
            }

            archiveInfo.applicationInfo?.sourceDir = tempFile.absolutePath
            archiveInfo.applicationInfo?.publicSourceDir = tempFile.absolutePath

            Result.success(
                AppMetadata(
                    label = archiveInfo.applicationInfo?.loadLabel(pm).toString(),
                    packageName = archiveInfo.packageName,
                    version = archiveInfo.versionName ?: "Unknown",
                    versionCode = archiveInfo.longVersionCode,
                    icon = archiveInfo.applicationInfo?.loadIcon(pm)?.toBitmap(),
                    permissions = archiveInfo.requestedPermissions?.toList() ?: emptyList()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    private fun extractBaseApkIcon(uri: Uri, baseApkFile: String, tempFile: File): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (entry.name.equals(baseApkFile, ignoreCase = true)) {
                            FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }
                            break
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            if (!tempFile.exists()) return null

            val pm = context.packageManager
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    tempFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tempFile.absolutePath, PackageManager.GET_META_DATA)
            }

            archiveInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = tempFile.absolutePath
                appInfo.publicSourceDir = tempFile.absolutePath
                appInfo.loadIcon(pm)?.toBitmap()
            }
        } catch (_: Exception) {
            null
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
