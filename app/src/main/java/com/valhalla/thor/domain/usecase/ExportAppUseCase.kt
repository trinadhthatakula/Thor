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

/**
 * Where a run writes, and where it stages, fixed for the whole run.
 *
 * Both halves are here because both are things a *batch* must not re-decide per app:
 *
 * - [target] because the export sheet is not modal. The user can change the destination — or
 *   revoke the tree, or pull the SD card — while a 200-app run is in flight, and a run that
 *   re-reads the preference per app would put the first eighty bundles in one folder, the rest in
 *   another, and a single manifest in whichever one the last write happened to resolve. That
 *   manifest then describes files that are not there and omits files that are.
 * - [stagingSubDir] because the builder wipes its per-package staging directory on entry. A single
 *   export of a package a batch is also exporting shares that directory, so one deletes the other's
 *   half-written copy — and the zip that was mid-stream finishes "successfully" and is truncated.
 */
data class ExportSession(
    val target: ExportTargetChoice,
    val stagingSubDir: String,
)

@Factory
class ExportAppUseCase(
    private val bundleBuilder: AppBundleBuilder,
    private val preferenceRepository: PreferenceRepository,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {
    /**
     * Build the bundle in [format] and write it to the resolved target. Returns a location label.
     *
     * A one-app export resolves its own session, which is what makes a destination change between
     * two taps take effect on the second one.
     */
    suspend operator fun invoke(
        appInfo: AppInfo,
        format: BundleFormat = BundleFormat.autoFor(appInfo),
    ): Result<String> = withContext(ioDispatcher) {
        exportInto(appInfo, format, openSession(SINGLE_STAGING_DIR))
    }

    /**
     * Resolve the export destination once.
     *
     * The saved-tree validity check and the stale-preference clear both live here rather than in
     * the caller, so a batch cannot accidentally skip them by holding a `treeUri` string of its own.
     */
    suspend fun openSession(stagingSubDir: String): ExportSession = withContext(ioDispatcher) {
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        val resolution = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri))
        if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)
        ExportSession(resolution.choice, stagingSubDir)
    }

    /**
     * Build [appInfo] into [session]'s staging scope and write it to [session]'s destination.
     *
     * @param fileName the name the bundle must take, or null to let the builder derive one. A batch
     *   supplies it because two selected apps can share a label and version, and both file-store
     *   paths write by name — the second bundle would replace the first while both were counted as
     *   written.
     */
    suspend fun exportInto(
        appInfo: AppInfo,
        format: BundleFormat,
        session: ExportSession,
        fileName: String? = null,
    ): Result<String> = withContext(ioDispatcher) {
        var staged: File? = null
        try {
            val file = bundleBuilder.build(
                appInfo,
                cacheSubDir = session.stagingSubDir,
                format = format,
                fileName = fileName,
            ).getOrElse { return@withContext Result.failure(it) }
            staged = file

            Result.success(writeStaged(file, session, format.mime))
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
            // This one path only, never the directory — staging is per package within a session,
            // so nothing else in flight is holding this exact file.
            staged?.delete()
        }
    }

    /**
     * Write an already-staged file to [session]'s destination, returning the location label.
     *
     * Exposed for the one file a batch stages itself rather than through the builder: its manifest.
     * Routing it through here is what keeps "where does this run write" a single answer — the
     * manifest cannot land in a folder none of the bundles went to.
     */
    suspend fun writeStaged(
        file: File,
        session: ExportSession,
        mime: String,
    ): String = withContext(ioDispatcher) {
        when (val choice = session.target) {
            is ExportTargetChoice.Custom -> fileStore.writeToTree(file, choice.treeUri, mime)
            ExportTargetChoice.Downloads -> fileStore.writeToDownloads(file, mime)
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String = withContext(ioDispatcher) {
        // SAF validity checks hit the content resolver / disk — keep them off the main thread.
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        fileStore.currentTargetLabel(savedUri)
    }

    companion object {
        /** Staging scope for one-app exports; a batch takes a scope of its own. */
        const val SINGLE_STAGING_DIR = "export_temp"
    }
}
