// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.File

@Factory
class ExportAppUseCase(
    private val bundleBuilder: AppBundleBuilder,
    private val preferenceRepository: PreferenceRepository,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {
    /** Build the bundle in [format] and write it to the resolved target. Returns a location label. */
    suspend operator fun invoke(
        appInfo: AppInfo,
        format: BundleFormat = BundleFormat.autoFor(appInfo),
    ): Result<String> = withContext(ioDispatcher) {
        var staged: File? = null
        try {
            val file = bundleBuilder.build(appInfo, cacheSubDir = "export_temp", format = format)
                .getOrElse { return@withContext Result.failure(it) }
            staged = file

            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            val location = when (val choice = resolution.choice) {
                is ExportTargetChoice.Custom ->
                    fileStore.writeToTree(file, choice.treeUri, format.mime)

                ExportTargetChoice.Downloads -> fileStore.writeToDownloads(file, format.mime)
            }
            Result.success(location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        } finally {
            // Export is the only caller that can free the staged copy: writeToTree/writeToDownloads
            // stream every byte to the destination before returning, and the staged path never
            // leaves this function. Share cannot — it hands a FileProvider content:// URI to
            // another app that opens the file long after the use case returns, so its "share_temp"
            // copy has to outlive the call. Do not unify the two.
            // In the finally so cancellation frees it too — delete() does not suspend, so it
            // still runs on a cancelled coroutine. That covers the window from the build
            // returning to the write finishing; a cancel *during* the build never assigns
            // `staged`, and the builder clears its own staging dir on that path instead.
            // This one path only, never the directory — staging is per package, so nothing
            // else in flight is holding this exact file.
            staged?.delete()
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String = withContext(ioDispatcher) {
        // SAF validity checks hit the content resolver / disk — keep them off the main thread.
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        fileStore.currentTargetLabel(savedUri)
    }
}
