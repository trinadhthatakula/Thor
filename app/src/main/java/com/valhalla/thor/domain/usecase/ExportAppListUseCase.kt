// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.APP_LIST_MIME
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.appListCsv
import com.valhalla.thor.domain.model.appListFileName
import com.valhalla.thor.domain.repository.AppBundleFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.File

/**
 * Write or share the visible app list as CSV.
 *
 * Saving goes through [ExportAppUseCase]'s session rather than the file store directly, so a list
 * lands in exactly the folder a bundle export would — including the stale-SAF-tree clear, which is
 * the case where "it saved fine" and "the folder is gone" are the same tap.
 *
 * Sharing does not go near that destination at all. A share is not a save: the user picked a
 * messaging app, not a folder, and writing a copy to Downloads on the way there would leave a file
 * behind that nobody asked for.
 */
@Factory
class ExportAppListUseCase(
    private val exportAppUseCase: ExportAppUseCase,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Save the list to the user's export destination; returns the location label to show.
     *
     * @param apps the list exactly as displayed — already filtered, searched and sorted.
     * @param createdAt stamps the file name; injectable so a test can name the file it asserts on.
     */
    suspend operator fun invoke(
        apps: List<AppInfo>,
        createdAt: Long = System.currentTimeMillis(),
    ): Result<String> = withContext(ioDispatcher) {
        runCatchingExport {
            val staged = stage(apps, createdAt)
            val session = exportAppUseCase.openSession(LIST_STAGING_DIR)
            exportAppUseCase.writeStaged(staged, session, APP_LIST_MIME)
        }
    }

    /**
     * Stage the list and return a `content://` URI another app can read.
     *
     * The staged copy is deliberately left on disk. The receiving app opens the URI whenever it gets
     * round to it — long after this returns — so deleting on the way out would hand over a URI that
     * resolves to nothing. [AppBundleFileStore.stageText] clears the directory on the next export
     * instead, which is the only moment nobody can still be reading the last one.
     */
    suspend fun shareUri(
        apps: List<AppInfo>,
        createdAt: Long = System.currentTimeMillis(),
    ): Result<String> = withContext(ioDispatcher) {
        runCatchingExport { fileStore.shareUri(stage(apps, createdAt)) }
    }

    private suspend fun stage(apps: List<AppInfo>, createdAt: Long): File =
        fileStore.stageText(appListFileName(createdAt), appListCsv(apps))

    private inline fun <T> runCatchingExport(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) e.printStackTrace()
        Result.failure(e)
    }

    private companion object {
        /**
         * Staging scope for the session, distinct from the bundle scopes.
         *
         * Nothing is built into it — the CSV is already staged by the time the session opens — but
         * `openSession` requires one, and naming it after this feature keeps a future writer from
         * assuming a list export shares the single-export scope and wiping a bundle mid-stream.
         */
        const val LIST_STAGING_DIR = "list_export"
    }
}
