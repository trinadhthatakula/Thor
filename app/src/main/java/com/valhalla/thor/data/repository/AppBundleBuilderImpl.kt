// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.formattedAppName
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable/exportable app bundle in the cache dir in the requested [BundleFormat]:
 * a monolithic `.apk` copy, or a zip of base + splits + sidecars as `.apks` (metadata.json +
 * manifest.json) or `.xapk` (the same, plus a root icon.png). Copies with a root fallback for
 * protected/system apps.
 */
@Single(binds = [AppBundleBuilder::class])
class AppBundleBuilderImpl(
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val apksMetadataGenerator: ApksMetadataGenerator,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppBundleBuilder {
    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
    ): Result<File> = withContext(ioDispatcher) {
        // Per-package subdir. Bulk share builds each selected app sequentially into
        // the same cacheSubDir and hands all the resulting content:// URIs to
        // ACTION_SEND_MULTIPLE together AFTER the loop; wiping the whole dir on each
        // call would delete earlier apps' bundles before they are read. Distinct
        // packages (a multi-select can't pick the same app twice) never collide.
        val cacheDir = File(File(context.cacheDir, cacheSubDir), appInfo.packageName)
        try {
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            // Sanitize the output filename: appName/versionName are app-controlled and
            // formattedAppName() only strips spaces, so a "/" or ".." could escape
            // cacheDir once copyFileSafely() falls back to a root `cp`. Keep safe chars.
            val safeName = "${appInfo.formattedAppName()}_${appInfo.versionName}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")

            val finalFile = File(cacheDir, "$safeName.${format.extension}")
            if (format == BundleFormat.APK) {
                // Base only, even for a split app: that is what a monolithic .apk means, and
                // autoFor() never picks this format for one.
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                    ?: throw IllegalStateException("No source path found")
                if (!copyFileSafely(sourcePath, finalFile)) {
                    throw IllegalStateException("Failed to copy base APK")
                }
            } else {
                // The zip branch serves .apks and .xapk alike. XAPK takes it even when the app
                // has no splits — a base-only .xapk is a legal artifact, and an explicit format
                // request must never silently hand back a different container.
                val tempSplitDir = File(cacheDir, "splits_staging")
                tempSplitDir.mkdirs()

                val allPaths = mutableListOf<String>()
                // publicSourceDir first, exactly as the APK branch above resolves it, so a
                // single-APK app exports the same base whichever format was asked for.
                (appInfo.publicSourceDir ?: appInfo.sourceDir)?.let { allPaths.add(it) }
                allPaths.addAll(appInfo.splitPublicSourceDirs)

                val apkFiles = allPaths.mapNotNull { path ->
                    val destFile = File(tempSplitDir, path.substringAfterLast("/"))
                    if (copyFileSafely(path, destFile)) destFile else null
                }
                if (apkFiles.isEmpty()) {
                    throw IllegalStateException("Failed to copy any APK files")
                }
                // The APKs that actually make it into the zip, summed before the sidecars are
                // staged because the XAPK manifest has to carry this number.
                val totalApkSize = apkFiles.sumOf { it.length() }
                // ...and mapNotNull above drops any file that would not copy, so the manifest
                // is told what is really in the zip rather than what the app claims to have.
                val apkNames = apkFiles.map { it.name }

                // metadata.json is written into the .xapk too. It is the SAI/.apks descriptor,
                // so SAI and APKPure ignore it as an unknown root entry, and carrying it means
                // both containers stage identically and an .apks-oriented reader can still make
                // sense of a .xapk. Thor's own installer reads manifest.json, not this file —
                // dropping it from .apks would break third-party readers, not Thor.
                val metadataFile = File(tempSplitDir, "metadata.json")
                apksMetadataGenerator.generateJson(appInfo, metadataFile)

                val iconFile = if (format == BundleFormat.XAPK) {
                    stageIcon(appInfo, tempSplitDir)
                } else {
                    null
                }

                val manifestFile = File(tempSplitDir, "manifest.json")
                if (format == BundleFormat.XAPK) {
                    // A null icon name leaves the field out altogether. A .xapk that names an
                    // icon.png it does not contain is worse than one that names nothing.
                    manifestFile.writeText(
                        apksMetadataGenerator.generateManifestJson(
                            appInfo,
                            totalApkSize,
                            iconFile?.name,
                            apkNames
                        )
                    )
                } else {
                    apksMetadataGenerator.generateManifestJson(appInfo, manifestFile, apkNames)
                }

                val sidecars = listOfNotNull(metadataFile, manifestFile, iconFile)
                // A .xapk gets its sidecars first: SAI reads manifest.json + icon.png and a
                // streaming reader would otherwise scan past every APK to reach them. .apks
                // keeps the historical order, which readers using the central directory
                // (Thor's BundleZip included) are indifferent to either way.
                val filesToZip = if (format == BundleFormat.XAPK) {
                    sidecars + apkFiles
                } else {
                    apkFiles + sidecars
                }

                zipFiles(filesToZip, finalFile)
                tempSplitDir.deleteRecursively()
            }
            Result.success(finalFile)
        } catch (e: CancellationException) {
            // A cancel lands mid-copy, so staging holds however much of a multi-GB app got
            // written. Nothing has been handed out yet — no content:// URI, no returned File —
            // so this copy has no reader, and here is the only chance to free it: the dir is
            // otherwise wiped by the *next* build of this same package, which may never come.
            cacheDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            cacheDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Stages the `icon.png` root entry for a `.xapk`, preferring the full-resolution PNG
     * AppIconLoader already persisted and rendering from the PackageManager otherwise.
     * Returns null when neither works — SAI treats the icon as optional, so a missing one
     * costs the user a picture, never the export.
     */
    private fun stageIcon(appInfo: AppInfo, stagingDir: File): File? {
        return try {
            val destFile = File(stagingDir, "icon.png")
            val cachedIcon = File(File(context.filesDir, "app_icons"), "${appInfo.packageName}.png")
            // Same freshness rule AppIconFetcher applies: an app update invalidates the cached
            // PNG, so a cache written before lastUpdateTime would ship the previous release's
            // icon inside the .xapk.
            val cacheIsFresh = cachedIcon.exists() &&
                    cachedIcon.length() > 0 &&
                    cachedIcon.lastModified() >= appInfo.lastUpdateTime
            if (cacheIsFresh) {
                cachedIcon.copyTo(destFile, overwrite = true)
            } else {
                val bitmap =
                    context.packageManager.getApplicationIcon(appInfo.packageName).toIconBitmap()
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            destFile.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            null
        }
    }

    /** Renders a launcher icon at its intrinsic size — adaptive icons are the common case now
     *  and are not [BitmapDrawable]s, so this draws through a canvas rather than casting. */
    private fun Drawable.toIconBitmap(): Bitmap {
        (this as? BitmapDrawable)?.bitmap?.let { return it }
        // A drawable with no intrinsic size (a plain ColorDrawable icon, say) would render 1x1
        // and put a useless entry in the zip; give it a launcher-sized square instead.
        val bitmap = createBitmap(
            intrinsicWidth.takeIf { it > 0 } ?: FALLBACK_ICON_PX,
            intrinsicHeight.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        )
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    private suspend fun copyFileSafely(sourcePath: String, destFile: File): Boolean {
        return try {
            copyCancellable(File(sourcePath), destFile)
            true
        } catch (e: CancellationException) {
            // Ahead of the broad catch, or a cancelled copy falls through to the root fallback and
            // starts the whole multi-gigabyte copy again as a shell command — which no longer
            // observes cancellation at all.
            throw e
        } catch (_: Exception) {
            systemRepository.copyFileWithRoot(sourcePath, destFile.absolutePath).isSuccess
        }
    }

    /**
     * `File.copyTo` in chunks, so a cancel does not have to wait out a whole APK.
     *
     * A bulk export cancels by cancelling the coroutine, and the replacement run waits for this
     * one to unwind before it starts staging. A single uninterruptible `copyTo` of a 2 GB app
     * therefore becomes a 2 GB stall with the UI showing "0 of N" — the check per 8 KB chunk is a
     * volatile read against an IO-bound loop, which is free by comparison.
     */
    private suspend fun copyCancellable(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private suspend fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            // APK entries are already DEFLATEd, so re-compressing them is pure CPU for ~0%
            // saving. Measurable when a bulk share zips dozens of apps in a row.
            // This is level-0 DEFLATE, not ZipEntry.STORED as real .xapk files use: STORED
            // needs a CRC32 and size per entry up front, i.e. a second full read of every APK
            // — more IO than it saves. Every ZipFile-based reader (SAI, Thor's own BundleZip)
            // is indifferent; only a reader that fast-paths STORED loses the shortcut.
            out.setLevel(Deflater.NO_COMPRESSION)
            // 8 KB buffer — 1 KB is needlessly slow when zipping multi-MB APK splits.
            val data = ByteArray(COPY_BUFFER_BYTES)
            files.forEach { file ->
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        while (true) {
                            // Same reason as copyCancellable: zipping a split app is the other
                            // multi-gigabyte loop a cancel would otherwise have to sit through.
                            currentCoroutineContext().ensureActive()
                            val readBytes = origin.read(data)
                            if (readBytes == -1) break
                            out.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val FALLBACK_ICON_PX = 192
        const val COPY_BUFFER_BYTES = 8192
    }
}
