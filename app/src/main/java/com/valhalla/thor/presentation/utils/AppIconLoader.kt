package com.valhalla.thor.presentation.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
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
import java.io.File
import java.io.FileOutputStream
import com.valhalla.thor.extension.api.AppIconModel


/**
 * The worker that loads the icon on the IO thread.
 */
class AppIconFetcher(
    private val model: AppIconModel,
    private val context: Context,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return try {
            val pm = context.packageManager
            val iconDir = File(context.filesDir, "app_icons")
            val iconFile = File(iconDir, "${model.packageName}.png")

            var lastUpdateTime = 0L
            try {
                val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                val packInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(model.packageName, PackageManager.PackageInfoFlags.of(flags))
                } else {
                    pm.getPackageInfo(model.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                lastUpdateTime = packInfo.lastUpdateTime
            } catch (_: Exception) {}

            if (iconFile.exists() && iconFile.lastModified() >= lastUpdateTime) {
                // Two-pass decode: read the bounds first, then compute an inSampleSize from the
                // Coil target size so adaptive/high-res icons don't bloat the memory cache.
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(iconFile.absolutePath, boundsOptions)

                val sampleSize = computeInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = options.bitmapConfig
                }
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath, decodeOptions)
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    // Report sampled only when actually smaller than the source, so a full-size
                    // decode stays reusable for larger targets.
                    val sampled = bitmap.width < boundsOptions.outWidth ||
                        bitmap.height < boundsOptions.outHeight
                    return ImageFetchResult(
                        image = drawable.asImage(),
                        isSampled = sampled,
                        dataSource = DataSource.DISK
                    )
                }
            }

            // Fetch from package manager with uninstalled fallback
            val drawable = try {
                pm.getApplicationIcon(model.packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(model.packageName, PackageManager.ApplicationInfoFlags.of(flags))
                } else {
                    pm.getApplicationInfo(model.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                appInfo.loadIcon(pm)
            }

            // Persist the full-resolution icon so later requests at larger sizes (details
            // screens render up to ~120dp) stay crisp via the disk two-pass decode. The memory
            // cache is fed the downscaled bitmap below instead.
            try {
                if (!iconDir.exists()) {
                    iconDir.mkdirs()
                }
                val fullBitmap = drawable.toFullBitmap()
                FileOutputStream(iconFile).use { out ->
                    fullBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Return a bitmap sized to the Coil target (coerced to the intrinsic size) rather
            // than the full adaptive-icon resolution, keeping the memory cache small.
            val bitmap = drawable.toBitmap()

            // Report sampled only when the rendered bitmap is smaller than the drawable's
            // intrinsic size, so a full-size render can be reused for larger targets.
            val sampled = bitmap.width < drawable.intrinsicWidth ||
                bitmap.height < drawable.intrinsicHeight
            ImageFetchResult(
                image = BitmapDrawable(context.resources, bitmap).asImage(),
                isSampled = sampled,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            null // Let Coil handle the error/fallback
        }
    }

    /** Renders the drawable at its full intrinsic size, for persisting to the disk cache. */
    private fun Drawable.toFullBitmap(): Bitmap {
        if (this is BitmapDrawable) return this.bitmap
        val bitmap = Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    private fun Drawable.toBitmap(): Bitmap {
        val intrinsicWidth = intrinsicWidth.coerceAtLeast(1)
        val intrinsicHeight = intrinsicHeight.coerceAtLeast(1)

        // Target the Coil-requested px, never upscaling past the drawable's intrinsic size.
        val targetWidth = options.size.width
            .pxOrElse { intrinsicWidth }
            .coerceIn(1, intrinsicWidth)
        val targetHeight = options.size.height
            .pxOrElse { intrinsicHeight }
            .coerceIn(1, intrinsicHeight)

        if (this is BitmapDrawable && targetWidth >= intrinsicWidth && targetHeight >= intrinsicHeight) {
            return this.bitmap
        }

        // Honor Coil's requested config, but a created (mutable) bitmap can't be HARDWARE.
        val config = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
            Bitmap.Config.ARGB_8888
        } else {
            options.bitmapConfig
        }
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, config)
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    /**
     * Computes a power-of-two [BitmapFactory.Options.inSampleSize] that downsamples a source of
     * [sourceWidth] x [sourceHeight] to at least the Coil target size. Falls back to no
     * downsampling (`1`) for [Size.ORIGINAL] or when the bounds could not be read.
     */
    private fun computeInSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1

        val reqWidth = options.size.width.pxOrElse { return 1 }
        val reqHeight = options.size.height.pxOrElse { return 1 }
        if (reqWidth <= 0 || reqHeight <= 0) return 1

        var inSampleSize = 1
        while (sourceWidth / (inSampleSize * 2) >= reqWidth &&
            sourceHeight / (inSampleSize * 2) >= reqHeight
        ) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    class Factory(private val context: Context) : Fetcher.Factory<AppIconModel> {
        override fun create(
            data: AppIconModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return AppIconFetcher(data, context, options)
        }
    }
}

/**
 * Tells Coil how to cache this image in memory.
 * Using just the packageName is usually enough, but technically if the app updates,
 * the icon might change. For a system manager, packageName is sufficient for session cache.
 */
class AppIconKeyer : Keyer<AppIconModel> {
    override fun key(data: AppIconModel, options: Options): String {
        return "app_icon:${data.packageName}"
    }
}

typealias AppIconModel = com.valhalla.thor.extension.api.AppIconModel