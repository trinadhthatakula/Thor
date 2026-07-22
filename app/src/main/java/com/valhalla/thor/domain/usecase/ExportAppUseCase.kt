// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
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
    /** Build the bundle and write it to the resolved target. Returns a location label. */
    suspend operator fun invoke(appInfo: AppInfo): Result<String> = withContext(ioDispatcher) {
        try {
            val file = bundleBuilder.build(appInfo, cacheSubDir = "export_temp").getOrElse { return@withContext Result.failure(it) }
            val mime = mimeFor(file)

            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            val location = when (val choice = resolution.choice) {
                is ExportTargetChoice.Custom -> fileStore.writeToTree(file, choice.treeUri, mime)
                ExportTargetChoice.Downloads -> fileStore.writeToDownloads(file, mime)
            }
            Result.success(location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String = withContext(ioDispatcher) {
        // SAF validity checks hit the content resolver / disk — keep them off the main thread.
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        fileStore.currentTargetLabel(savedUri)
    }

    private fun mimeFor(file: File) =
        if (file.name.endsWith(".apk")) "application/vnd.android.package-archive"
        else "application/octet-stream"
}
