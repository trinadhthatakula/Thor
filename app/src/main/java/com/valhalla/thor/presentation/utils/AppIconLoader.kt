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
import java.io.File
import java.io.FileOutputStream

/**
 * A lightweight data class to trigger our custom fetcher.
 */
data class AppIconModel(val packageName: String)

/**
 * The worker that loads the icon on the IO thread.
 */
class AppIconFetcher(
    private val model: AppIconModel,
    private val context: Context
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
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    return ImageFetchResult(
                        image = drawable.asImage(),
                        isSampled = false,
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

            // Save to cache on IO thread
            try {
                if (!iconDir.exists()) {
                    iconDir.mkdirs()
                }
                val bitmap = drawable.toBitmap()
                FileOutputStream(iconFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            ImageFetchResult(
                image = drawable.asImage(),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            null // Let Coil handle the error/fallback
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) return this.bitmap
        val bitmap = Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val oldBounds = bounds
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    class Factory(private val context: Context) : Fetcher.Factory<AppIconModel> {
        override fun create(
            data: AppIconModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return AppIconFetcher(data, context)
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