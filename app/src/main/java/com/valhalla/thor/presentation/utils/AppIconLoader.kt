package com.valhalla.thor.presentation.utils

import android.content.Context
import android.content.pm.PackageManager
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options

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
            // RUTHLESS: This is where the heavy lifting happens, safely on the IO thread
            // because Coil calls fetch() on its own dispatcher.
            val drawable = context.packageManager.getApplicationIcon(model.packageName)

            // Return the result to Coil
            ImageFetchResult(
                image = drawable.asImage(),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null // Let Coil handle the error/fallback
        }
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