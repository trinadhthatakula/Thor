// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.size.pxOrElse
import com.valhalla.thor.data.repository.BundleZip
import com.valhalla.thor.domain.model.escapeShellArg
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ArchiveIconModel(
    val uriString: String,
    val packageName: String?,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val lastModifiedEpochSec: Long = 0L,
)

class ArchiveIconFetcher(
    private val model: ArchiveIconModel,
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return try {
            val pm = context.packageManager

            // 1. If packageName is known, try loading installed app icon first
            val pkg = model.packageName
            if (!pkg.isNullOrBlank()) {
                try {
                    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                    val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags))
                    } else {
                        pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    }
                    val drawable = appInfo.loadIcon(pm)
                    val bitmap = drawable.toBitmap(options)
                    return ImageFetchResult(
                        image = bitmap.toDrawable(context.resources).asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                } catch (_: Exception) {
                    // Not installed or cannot load from PM, proceed to extract from archive
                }
            }

            // 2. Check disk cache in cacheDir/archive_icons
            val iconCacheDir = File(context.cacheDir, "archive_icons")
            val cacheKey = "icon_${model.uriString.hashCode()}_${model.sizeBytes}_${model.lastModifiedEpochSec}"
            val cachedFile = File(iconCacheDir, "$cacheKey.png")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    return ImageFetchResult(
                        image = bitmap.toDrawable(context.resources).asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                }
            }

            // 3. Extract icon from archive (APK, XAPK, APKS, THORBAK)
            val extractedBitmap = extractIcon(model) ?: return null

            // Cache extracted bitmap
            try {
                if (!iconCacheDir.exists()) {
                    iconCacheDir.mkdirs()
                } else {
                    cleanStaleCacheIfNeeded(iconCacheDir)
                }
                FileOutputStream(cachedFile).use { out ->
                    extractedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Cache writing failure is non-fatal
            }

            ImageFetchResult(
                image = extractedBitmap.toDrawable(context.resources).asImage(),
                isSampled = false,
                dataSource = DataSource.DISK,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("ArchiveIconFetcher", "Failed to fetch icon for ${model.displayName}: ${e.message}")
            null
        }
    }

    private fun cleanStaleCacheIfNeeded(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size > 100) {
            files.sortedBy { it.lastModified() }
                .take(files.size - 50)
                .forEach { it.delete() }
        }
    }

    private suspend fun extractIcon(model: ArchiveIconModel): Bitmap? {
        val uri = runCatching { model.uriString.toUri() }.getOrNull() ?: return null
        val token = UUID.randomUUID().toString()
        val tempStaging = File(context.cacheDir, "temp_ico_$token")
        var staged = false

        val sourceFile: File = try {
            if (uri.scheme == "file" && uri.path != null && File(uri.path!!).canRead()) {
                File(uri.path!!)
            } else {
                val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                if (input != null) {
                    try {
                        input.use { src ->
                            FileOutputStream(tempStaging).use { dst -> src.copyTo(dst) }
                        }
                        staged = true
                        tempStaging
                    } catch (e: Throwable) {
                        tempStaging.delete()
                        throw e
                    }
                } else {
                    val path = uri.path
                    if (path.isNullOrBlank()) return null
                    val tmpPath = "/data/local/tmp/thor_ico_$token"
                    val src = path.escapeShellArg()
                    val dst = tmpPath.escapeShellArg()
                    val cmd = "cat $src > $dst 2>/dev/null && chmod 666 $dst 2>/dev/null"
                    // One `finally` for every exit, and an uncancellable one. The `cat` can have
                    // produced the file even when this coroutine is cancelled before the call
                    // returns, so a removal placed on the success paths only — as this was — never
                    // runs on the path that matters. Coil cancels these fetches routinely as the
                    // archive list scrolls, and `ArchiveOrphanSweeper` sweeps `cacheDir`,
                    // `externalCacheDir/obb_out` and the SAF ledger but *not* `/data/local/tmp`, so
                    // a skipped `rm -f` strands a full-size, `chmod 666` copy of the user's archive
                    // where nothing in the app can ever reclaim it. `NonCancellable` alone, without
                    // a dispatcher: `executeShellCommand` makes its own `ioDispatcher` hop.
                    try {
                        val res = systemRepository.executeShellCommand(cmd).getOrNull()
                        if (res == null || res.first != 0) return null
                        val tmpFile = File(tmpPath)
                        if (!tmpFile.exists() || tmpFile.length() <= 0) return null
                        try {
                            tmpFile.inputStream().use { inputStream ->
                                FileOutputStream(tempStaging).use { outputStream -> inputStream.copyTo(outputStream) }
                            }
                        } catch (e: Throwable) {
                            tempStaging.delete()
                            throw e
                        }
                        staged = true
                        tempStaging
                    } finally {
                        withContext(NonCancellable) {
                            systemRepository.executeShellCommand("rm -f $dst")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // Above the rethrow, not below it: a cancellation arriving here leaves the same staged
            // copy behind as any other failure, and `File.delete()` is a blocking call that still
            // completes while cancellation is in progress.
            tempStaging.delete()
            throw e
        } catch (_: Exception) {
            tempStaging.delete()
            return null
        }

        try {
            val lower = model.displayName.lowercase()
            return when {
                lower.endsWith(".apk") -> parseApkIcon(sourceFile)
                lower.endsWith(".xapk") || lower.endsWith(".apks") -> parseBundleIcon(sourceFile)
                lower.endsWith(".thorbak") -> parseThorbakIcon(sourceFile)
                else -> parseApkIcon(sourceFile) ?: parseBundleIcon(sourceFile)
            }
        } finally {
            if (staged) {
                tempStaging.delete()
            }
        }
    }

    private fun parseApkIcon(file: File): Bitmap? {
        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
        val appInfo = archiveInfo?.applicationInfo
        if (appInfo != null) {
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            return appInfo.loadIcon(pm).toBitmap(options)
        }
        return null
    }

    private fun parseBundleIcon(file: File): Bitmap? {
        val contents = runCatching {
            BundleZip.read(
                file,
                setOf("manifest.json", "info.json", "icon.png", "icon.jpg", "icon.webp")
            )
        }.getOrNull()

        if (contents != null) {
            val iconBytes = contents.bytes["icon.png"]
                ?: contents.bytes["icon.jpg"]
                ?: contents.bytes["icon.webp"]
            if (iconBytes != null && iconBytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                if (bitmap != null) return bitmap
            }

            // Fallback: extract base.apk or first apk candidate
            val candidate = contents.entryNames.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            if (candidate != null) {
                val tmpApk = File(context.cacheDir, "tmp_bundle_apk_${UUID.randomUUID()}.apk")
                try {
                    val extracted = BundleZip.extractEntryTo(file, candidate.substringAfterLast('/'), tmpApk)
                    if (extracted) {
                        return parseApkIcon(tmpApk)
                    }
                } finally {
                    tmpApk.delete()
                }
            }
        }
        return null
    }

    private fun parseThorbakIcon(file: File): Bitmap? {
        try {
            ZipFile(file).use { zip ->
                val headerEntry = zip.getEntry("header.json")
                if (headerEntry != null) {
                    val jsonStr = zip.getInputStream(headerEntry).bufferedReader().readText()
                    val pkg = runCatching { JSONObject(jsonStr).optString("packageName") }.getOrNull()
                    if (!pkg.isNullOrBlank()) {
                        val pm = context.packageManager
                        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags))
                        } else {
                            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        }
                        return appInfo.loadIcon(pm).toBitmap(options)
                    }
                }
                val iconEntry = zip.getEntry("icon.png")
                if (iconEntry != null) {
                    val bytes = zip.getInputStream(iconEntry).readBytes()
                    if (bytes.isNotEmpty()) {
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun Drawable.toBitmap(options: Options): Bitmap {
        val intrinsicW = intrinsicWidth.takeIf { it > 0 } ?: 96
        val intrinsicH = intrinsicHeight.takeIf { it > 0 } ?: 96
        val targetWidth = options.size.width.pxOrElse { intrinsicW }.coerceAtLeast(1)
        val targetHeight = options.size.height.pxOrElse { intrinsicH }.coerceAtLeast(1)

        if (this is BitmapDrawable && bitmap.width == targetWidth && bitmap.height == targetHeight) {
            return this.bitmap
        }
        val config = if (options.bitmapConfig == Bitmap.Config.HARDWARE) Bitmap.Config.ARGB_8888 else options.bitmapConfig
        val bitmap = createBitmap(targetWidth, targetHeight, config)
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    class Factory(
        private val context: Context,
        private val systemRepository: SystemRepository,
    ) : Fetcher.Factory<ArchiveIconModel> {
        override fun create(
            data: ArchiveIconModel,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ArchiveIconFetcher(data, context, systemRepository, options)
        }
    }
}

class ArchiveIconKeyer : Keyer<ArchiveIconModel> {
    override fun key(data: ArchiveIconModel, options: Options): String {
        return "archive_icon:${data.uriString}:${data.packageName}:${data.sizeBytes}:${data.lastModifiedEpochSec}"
    }
}
