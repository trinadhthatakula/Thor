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
import com.valhalla.thor.data.repository.copyAtMostTo
import com.valhalla.thor.domain.model.THORBAK_EXTENSION
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.escapeShellArg
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Most of the user's disk one 44 dp icon may spend on a scratch copy of the archive it came from.
 *
 * There is no way to avoid the copy for the formats that reach it: the icon comes out of a zip,
 * `ZipFile` is the only reader that can be trusted with an installer bundle (BundleZip's header has
 * the APKPure case that rules `ZipInputStream` out), and `ZipFile` needs a real path. So the copy
 * is a given and its size is the lever. 256 MB clears the ordinary `.apk`/`.xapk` in a Downloads
 * folder by a wide margin and refuses the multi-gigabyte game bundle, whose icon is not worth
 * writing a gigabyte of cacheDir — and whose entry in the list still shows its name, size and date.
 */
private const val MAX_ICON_STAGE_BYTES = 256L * 1024 * 1024

/**
 * The most pixels `decodeSampled` will allocate, whatever the caller asked for — 4 MB of ARGB_8888.
 *
 * Target-size sampling cannot carry this on its own, and the hole is not an exotic one: the sample
 * step advances only while **both** axes are still at or above the target, so a source with one axis
 * already below it never samples at all. A 263 × 150000 `icon.png` — about 160 KB of PNG if its rows
 * are uniform, so well inside [com.valhalla.thor.data.repository.MAX_METADATA_ENTRY_BYTES] — decodes
 * whole against a 132 px request: 39 Mpx, 158 MB, from a file BundleZip's header already calls
 * untrusted. Bigger than that and the allocation throws `OutOfMemoryError`, which is an `Error` and
 * so escapes the `catch (Exception)` in [ArchiveIconFetcher.fetch]; smaller and it *succeeds*, gets
 * PNG-encoded into `cacheDir` at quality 100, and is then handed to a `RecordingCanvas` that refuses
 * any bitmap past `ro.hwui.max_texture_allocation_size` (floor 150 MB) with a UI-thread exception.
 *
 * A second, latent route reaches the same place: Coil's `Size` is two [coil3.size.Dimension]s and
 * either may be `Undefined`, in which case `pxOrElse` falls back to the *source* dimension and
 * nothing samples. Not reachable today — the one model site sizes the image at 44 dp, so both
 * dimensions are `Pixels` — but `contentScale = ContentScale.None` on that call would make it so.
 *
 * Deliberately absolute rather than `maxOf(this, requested area)`: raising the ceiling to match the
 * request would restore the full decode on precisely the `Undefined` path above, where the request
 * *is* the source. An entry named `icon.png` is drawn at 44 dp, and 1024×1024 is past every real
 * icon (a 108 dp adaptive icon at 4× density is 432 px), so the clamp costs nothing real.
 */
private const val MAX_DECODED_ICON_PIXELS = 1024L * 1024

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
            // 1. If packageName is known, try loading installed app icon first
            val pkg = model.packageName
            if (!pkg.isNullOrBlank()) {
                val installed = installedIcon(pkg)
                if (installed != null) {
                    return ImageFetchResult(
                        image = installed.toDrawable(context.resources).asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                }
            }

            // 2. Check disk cache in cacheDir/archive_icons
            val iconCacheDir = File(context.cacheDir, "archive_icons")
            val cacheKey = "icon_${model.uriString.hashCode()}_${model.sizeBytes}_${model.lastModifiedEpochSec}"
            val cachedFile = File(iconCacheDir, "$cacheKey.png")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                // Sampled on the way out as well as in. `decodeFile` takes no options, so it has no
                // ceiling: an entry written by a build that decoded a crafted strip whole is still
                // on disk, keyed by size and mtime, and would be re-decoded at full size on every
                // launch until the pruner reaches it — the ceiling would then protect only the
                // devices that never hit the bug.
                val bitmap = runCatching { cachedFile.readBytes() }
                    .getOrNull()
                    ?.let { decodeSampled(it) }
                if (bitmap != null) {
                    return ImageFetchResult(
                        image = bitmap.toDrawable(context.resources).asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                }
            }

            // 2b. A remembered miss. Coil caches an image, not the absence of one, so without this
            // a fetch that ends in null is redone on every pass over the list — and for an archive
            // that has to be staged first, "redone" means copying it again. The key carries the
            // size and the mtime, so a file that changes is re-examined, and the marker is evicted
            // on the same schedule as an icon. A transient failure is therefore remembered as a
            // permanent one until one of those happens: that is the trade, and it is the right way
            // round for a list whose entries can be gigabytes each.
            val missMarker = File(iconCacheDir, "$cacheKey.none")
            if (missMarker.exists()) return null

            // 3. Extract icon from archive (APK, XAPK, APKS, THORBAK)
            val extractedBitmap = extractIcon(model)
            if (extractedBitmap == null) {
                runCatching {
                    prepareCacheDir(iconCacheDir)
                    missMarker.createNewFile()
                }
                return null
            }

            // Cache extracted bitmap
            try {
                prepareCacheDir(iconCacheDir)
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

    /** Create the icon cache directory, or prune it if it is already over its ceiling. */
    private fun prepareCacheDir(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        } else {
            cleanStaleCacheIfNeeded(dir)
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
        val lower = model.displayName.lowercase()
        val localFile = uri.path
            ?.let(::File)
            ?.takeIf { uri.scheme == "file" && it.canRead() }

        // Two refusals before anything is staged, because staging is where the cost of this fetcher
        // lives — it writes a whole second copy of the archive into cacheDir.
        if (localFile == null) {
            // A `.thorbak` never gets one. The format carries no icon entry at all; the only thing
            // a reader can take from it is the `packageName` in `thorbak.json`, and that entry is
            // written LAST (chunk counts are unknown until the members exist), so reaching it means
            // reading a whole multi-gigabyte archive to arrive at the identity the scanner already
            // parsed out of the `<pkg>-<versionCode>.thorbak` file name and step 1 above has
            // already tried against the package manager.
            if (lower.endsWith(".$THORBAK_EXTENSION")) return null
            if (model.sizeBytes > MAX_ICON_STAGE_BYTES) return null
        }

        val token = UUID.randomUUID().toString()
        val tempStaging = File(context.cacheDir, "temp_ico_$token")

        val sourceFile: File = localFile ?: stageArchive(uri, tempStaging, token) ?: return null
        val staged = sourceFile === tempStaging

        try {
            return when {
                lower.endsWith(".apk") -> parseApkIcon(sourceFile)
                lower.endsWith(".xapk") || lower.endsWith(".apks") -> parseBundleIcon(sourceFile)
                lower.endsWith(".$THORBAK_EXTENSION") -> parseThorbakIcon(sourceFile)
                else -> parseApkIcon(sourceFile) ?: parseBundleIcon(sourceFile)
            }
        } finally {
            if (staged) {
                tempStaging.delete()
            }
        }
    }

    /**
     * Copy the archive at [uri] into [tempStaging] so a `ZipFile` reader can open it, and return
     * that file — or null when it cannot be staged, or should not be.
     *
     * Owns the cleanup of everything it writes, on every exit including a cancellation, so a caller
     * holding a null has nothing left to undo.
     */
    private suspend fun stageArchive(uri: Uri, tempStaging: File, token: String): File? {
        return try {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (input != null) {
                try {
                    // Bounded, not merely size-checked by the caller: `sizeBytes` reaches it from
                    // the same provider as the bytes, and a provider that reports 0 for a length it
                    // does not know — plenty do — sails straight through that check.
                    val copied = input.use { src ->
                        FileOutputStream(tempStaging).use { dst ->
                            src.copyAtMostTo(dst, MAX_ICON_STAGE_BYTES)
                        }
                    }
                    if (copied == null) {
                        tempStaging.delete()
                        null
                    } else {
                        tempStaging
                    }
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
                    val copied = try {
                        tmpFile.inputStream().use { inputStream ->
                            FileOutputStream(tempStaging).use { outputStream ->
                                inputStream.copyAtMostTo(outputStream, MAX_ICON_STAGE_BYTES)
                            }
                        }
                    } catch (e: Throwable) {
                        tempStaging.delete()
                        throw e
                    }
                    if (copied == null) {
                        tempStaging.delete()
                        null
                    } else {
                        tempStaging
                    }
                } finally {
                    withContext(NonCancellable) {
                        systemRepository.executeShellCommand("rm -f $dst")
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
            null
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
                val bitmap = decodeSampled(iconBytes)
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

    /**
     * The icon for a `.thorbak`, which is the *installed* app's icon or nothing: the format carries
     * no icon of its own, only the identity in `thorbak.json`.
     *
     * Reached only for an archive Thor can open in place — [extractIcon] refuses to stage one, since
     * the header is the last entry written and a stream would have to be read to its end to reach
     * it. Here [BundleZip.read] takes it from the central directory, so its position costs nothing.
     *
     * The entry this looked for was `header.json`, a name no `.thorbak` has ever carried
     * ([THORBAK_HEADER_ENTRY] is `thorbak.json`). Every read missed, fell through to an `icon.png`
     * the writer does not produce either, and returned null — after the caller had copied the whole
     * archive to get here, and with nothing recording the miss, so the next scroll did it again.
     */
    private fun parseThorbakIcon(file: File): Bitmap? {
        val contents = runCatching {
            BundleZip.read(file, setOf(THORBAK_HEADER_ENTRY, "icon.png"))
        }.getOrNull() ?: return null

        val header = contents.bytes[THORBAK_HEADER_ENTRY]
        if (header != null) {
            val pkg = runCatching {
                JSONObject(header.toString(Charsets.UTF_8)).optString("packageName")
            }.getOrNull()
            if (!pkg.isNullOrBlank()) installedIcon(pkg)?.let { return it }
        }
        // Thor writes no such entry, but honouring a foreign writer's costs nothing now that both
        // names come out of one pass.
        val iconBytes = contents.bytes["icon.png"]
        if (iconBytes != null && iconBytes.isNotEmpty()) return decodeSampled(iconBytes)
        return null
    }

    /** The installed icon for [pkg] at the requested size, or null if the device has no such app. */
    private fun installedIcon(pkg: String): Bitmap? = try {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }
        appInfo.loadIcon(pm).toBitmap(options)
    } catch (_: Exception) {
        null
    }

    /**
     * Decode [bytes] no larger than the icon has to be.
     *
     * `decodeByteArray` with no options allocates the source's full pixel buffer. A bundle icon is
     * bounded at 8 MB of *file* ([com.valhalla.thor.data.repository.MAX_METADATA_ENTRY_BYTES]),
     * which a PNG turns into 10000×10000 pixels without difficulty — 400 MB of ARGB_8888 for
     * something drawn at 44 dp, and OutOfMemoryError is an `Error`, so the `catch (Exception)` in
     * [fetch] would not contain it either.
     *
     * The sample step stops at the last power of two still at or above the requested size, so the
     * result is never smaller than what was asked for; Coil scales it the rest of the way. The one
     * exception is [MAX_DECODED_ICON_PIXELS], which overrides that property rather than extending
     * it — the alternative to an icon smaller than requested there is no icon and a dead list row.
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val targetWidth = options.size.width.pxOrElse { sourceWidth }.coerceAtLeast(1)
        val targetHeight = options.size.height.pxOrElse { sourceHeight }.coerceAtLeast(1)
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        // The absolute ceiling, applied after the target-size step and independent of it. The `&&`
        // above is why it is needed: the loop stops the moment *either* axis drops below the target,
        // so an asymmetric source pins sample at 1 however many pixels the other axis carries, and
        // both axes come out of the archive. [MAX_DECODED_ICON_PIXELS] has the numbers. Doubling
        // from whatever the first loop chose keeps the "never smaller than requested" property for
        // every icon inside the budget and breaks it only where the alternative is an allocation
        // that fails or a bitmap the canvas refuses to draw.
        //
        // `maxOf(1, …)`, because that is what the decoder does: AOSP's `get_scaled_dimension` is
        // `max(1, floor(dim / sample))`, so a sampled axis floors at 1 px and never at 0. Estimating
        // with a bare division read 1 / 2 as 0, so a one-pixel axis made the product 0 and needed a
        // `> 1 && > 1` guard to look sensible — and that guard exempted the most extreme aspect
        // ratio from the ceiling that exists for exactly that shape. Nothing decodable reached the
        // exemption, so this replaces a wrong reason rather than a shipped over-allocation: libpng
        // caps an axis at `PNG_USER_{WIDTH,HEIGHT}_MAX` = 1000000, itself under the ceiling, and
        // every other format Skia sniffs caps lower, so a strip long enough to matter fails the
        // bounds pass and returns null above. The guard is gone because the clamped product implies
        // it — a product over the ceiling needs an axis over 1 — and that is also why this
        // terminates: doubling drives both divisions to 0, where the clamped product is 1.
        while (
            maxOf(1, sourceWidth / sample).toLong() * maxOf(1, sourceHeight / sample) >
                MAX_DECODED_ICON_PIXELS
        ) {
            sample *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
                Bitmap.Config.ARGB_8888
            } else {
                options.bitmapConfig
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
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
