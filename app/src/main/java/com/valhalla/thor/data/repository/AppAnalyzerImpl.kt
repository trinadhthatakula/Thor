package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageInfo
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
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

@Single(binds = [AppAnalyzer::class])
class AppAnalyzerImpl(private val context: Context) : AppAnalyzer {

    override suspend fun analyze(uri: Uri): Result<AppMetadata> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "analysis_${System.currentTimeMillis()}.apk")

        try {
            val contentResolver = context.contentResolver

            // Phase 1: Single pass — collect the zip's entry names plus the XAPK
            // manifest.json and icon bytes (if any). The entry names let us decide
            // monolithic-vs-bundle without trusting the mere presence of a .apk
            // entry (every APK is itself a zip that may embed nested .apk assets).
            val entryNames = mutableListOf<String>()
            var manifestBytes: ByteArray? = null
            var iconBytes: ByteArray? = null

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) entryNames += entry.name
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
                            entry = zipStream.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {
                // Not a readable zip (or truncated). We'll still try the monolithic
                // whole-file parse below.
            }

            // Phase 2: Monolithic-APK gate (GH#207). A file that carries its own
            // top-level AndroidManifest.xml is a single installable APK — parse the
            // whole file and never scan inner .apk assets.
            if (isMonolithicApk(entryNames)) {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                val archiveInfo = parseArchive(tempFile)
                if (archiveInfo != null) {
                    return@withContext Result.success(metadataFrom(archiveInfo, tempFile, iconBytes))
                }
                // Fall through to bundle/base handling only if the whole-file parse
                // unexpectedly failed.
            }

            // Phase 3: XAPK manifest path — build metadata from the authoritative
            // base APK. Tolerant deserialization (GH#159) so numeric version_code /
            // missing name don't nuke this path.
            val manifest = manifestBytes?.let { bytes -> parseXapkManifest(String(bytes)) }

            val manifestBaseFile = manifest?.baseApkFile()
            if (manifestBaseFile != null) {
                val archiveInfo = extractAndParse(uri, manifestBaseFile, tempFile)
                if (archiveInfo != null) {
                    return@withContext Result.success(metadataFrom(archiveInfo, tempFile, iconBytes))
                }
                // else fall through to candidate scan below
            }

            // Phase 4: Generic bundle base selection (GH#159). Order candidates so
            // splits/config APKs are tried last, and pick the first that parses as a
            // non-split base.
            val candidates = selectBaseApkCandidates(entryNames, manifest?.packageName)
            for (candidate in candidates) {
                val archiveInfo = extractAndParse(uri, candidate, tempFile)
                if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                    return@withContext Result.success(metadataFrom(archiveInfo, tempFile, iconBytes))
                }
            }

            // TODO(#159): richer .apks (bundletool/SAI toc.pb, splits/…) and .apkm
            // (APKMirror info.json) base-layout detection + OBB extraction — tracked
            // as a separate follow-up.

            // Phase 5: Last resort — treat the whole file as a monolithic APK even
            // without a detectable top-level manifest (e.g. an entry-listing failure).
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            val archiveInfo = parseArchive(tempFile)
                ?: return@withContext Result.failure(
                    Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
                )

            Result.success(metadataFrom(archiveInfo, tempFile, iconBytes))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    /** Extract a single zip entry into [tempFile] and parse it via PackageManager. */
    private fun extractAndParse(uri: Uri, entryName: String, tempFile: File): PackageInfo? {
        return try {
            var extracted = false
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (entry.name.equals(entryName, ignoreCase = true)) {
                            FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }
                            extracted = true
                            break
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
            if (!extracted || !tempFile.exists()) null else parseArchive(tempFile)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse an on-disk APK via getPackageArchiveInfo across API levels. */
    private fun parseArchive(tempFile: File): PackageInfo? {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                tempFile.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(tempFile.absolutePath, flags)
        }
    }

    /** Build [AppMetadata] from a parsed [PackageInfo], preferring the XAPK icon. */
    private fun metadataFrom(
        archiveInfo: PackageInfo,
        tempFile: File,
        iconBytes: ByteArray?
    ): AppMetadata {
        val pm = context.packageManager
        archiveInfo.applicationInfo?.sourceDir = tempFile.absolutePath
        archiveInfo.applicationInfo?.publicSourceDir = tempFile.absolutePath

        val iconBitmap: Bitmap? = when {
            iconBytes != null -> BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
            else -> archiveInfo.applicationInfo?.loadIcon(pm)?.toBitmap()
        }

        return AppMetadata(
            label = archiveInfo.applicationInfo?.loadLabel(pm)?.toString() ?: "Unknown",
            packageName = archiveInfo.packageName,
            version = archiveInfo.versionName ?: "Unknown",
            versionCode = archiveInfo.longVersionCode,
            icon = iconBitmap,
            permissions = archiveInfo.requestedPermissions?.toList() ?: emptyList()
        )
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
