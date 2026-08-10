// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.THORBAK_MIME
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "AppArchiveStore"

/**
 * Suffix for an archive that is still being written.
 *
 * Deliberately does not end in `.thorbak`: the restore picker filters on that extension and the
 * launch-time orphan sweep deletes by this one. If a partial were `foo.thorbak.part.thorbak`, the
 * picker would offer a half-written archive and the sweep would delete a finished one.
 */
const val PARTIAL_SUFFIX = ".part"

fun partialName(fileName: String): String = fileName + PARTIAL_SUFFIX

fun publishedName(fileName: String): String = fileName.removeSuffix(PARTIAL_SUFFIX)

@Single(binds = [AppArchiveStore::class])
class AppArchiveStoreImpl(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val fileStore: AppBundleFileStore,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppArchiveStore {

    override suspend fun currentTargetLabel(): String = withContext(ioDispatcher) {
        fileStore.currentTargetLabel(preferenceRepository.userPreferences.first().exportDirUri)
    }

    override suspend fun openArchive(fileName: String): ArchiveDestination? =
        withContext(ioDispatcher) {
            // The same resolution `ExportAppUseCase.openSession` performs, including the stale-tree
            // clear: an export destination the user revoked must not silently become Downloads for
            // exports and stay broken for archives.
            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            try {
                when (val choice = resolution.choice) {
                    is ExportTargetChoice.Custom -> openInTree(choice.treeUri, fileName)
                    ExportTargetChoice.Downloads ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            openInMediaStore(fileName)
                        } else {
                            openInLegacyDownloads(fileName)
                        }
                }
            } catch (e: Exception) {
                // "Nowhere to write" is a real state — a revoked tree, a denied legacy permission, a
                // full volume. The caller turns null into "choose a folder", never into a failure
                // that implies the backup itself went wrong.
                Logger.e(TAG, "could not open \"$fileName\" at the export destination", e)
                null
            }
        }

    /**
     * MediaStore, API 29+. `IS_PENDING = 1` already means "not visible to other apps", so this
     * backend writes under the **final** name and publishes by clearing the flag — no rename, and no
     * window in which a complete archive carries a partial name.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun openInMediaStore(fileName: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, THORBAK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                // `update` returns the number of rows changed. Zero means the row went away — a user
                // who deleted the pending entry from a file manager mid-backup — and reporting that as
                // a success would tell them a backup exists when nothing does.
                return resolver.update(uri, values, null, null) > 0
            }

            override fun onDiscard() {
                resolver.delete(uri, null, null)
            }
        }
    }

    /**
     * SAF, any API. `createDocument` has no pending concept, so the partial name is real: create
     * `<name>.part`, then rename on publish.
     *
     * `renameDocument` may return null on failure and a provider may de-duplicate a colliding name
     * (`foo (1).thorbak`) instead of failing — so success is "it returned something", not "the name is
     * the one Thor asked for".
     *
     * [treeUri] is a **String**: `ExportTargetChoice.Custom.treeUri` is a persisted string, and
     * `Uri.parse` takes a string. Typing this parameter as `Uri` and then calling `Uri.parse` on it
     * does not compile.
     */
    private fun openInTree(treeUri: String, fileName: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val tree = treeUri.toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val docUri = DocumentsContract.createDocument(
            resolver,
            parent,
            THORBAK_MIME,
            partialName(fileName),
        ) ?: return null
        val stream = resolver.openOutputStream(docUri) ?: run {
            DocumentsContract.deleteDocument(resolver, docUri)
            return null
        }
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean =
                DocumentsContract.renameDocument(resolver, docUri, fileName) != null

            override fun onDiscard() {
                DocumentsContract.deleteDocument(resolver, docUri)
            }
        }
    }

    /**
     * API 28's Downloads directory as a plain `File`. `renameTo` within one volume is atomic, which
     * is the same guarantee the other two backends reach by other means.
     */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: deprecated at 29, and this branch
    // only runs below 29. minSdk is 28, which is the whole reason the branch exists.
    private fun openInLegacyDownloads(fileName: String): ArchiveDestination? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val partial = File(dir, partialName(fileName))
        val stream = FileOutputStream(partial)
        return object : BaseDestination(stream) {
            override fun onPublish(): Boolean = partial.renameTo(File(dir, fileName))

            override fun onDiscard() {
                partial.delete()
            }
        }
    }
}

/**
 * The half of [ArchiveDestination] that is identical across all three backends: close exactly once,
 * publish or discard exactly once, and never do both.
 *
 * Closing before publishing is not tidiness — it is what flushes the stream. Publishing while a
 * buffered chunk is still in memory produces an archive that passes every check except its own chunk
 * count.
 */
private abstract class BaseDestination(override val output: OutputStream) : ArchiveDestination {

    private var settled = false

    protected abstract fun onPublish(): Boolean

    protected abstract fun onDiscard()

    override suspend fun publish(): Boolean {
        if (settled) return false
        settled = true
        output.close()
        return onPublish()
    }

    /**
     * Idempotent, because the calling shape is `try { … publish() } finally { discard() }` — a
     * discard after a successful publish is the *normal* path and must do nothing.
     *
     * Both the stream close and [onDiscard] are guarded: this runs when something has already gone
     * wrong, and a cleanup failure must never replace the original error.
     */
    override suspend fun discard() {
        if (settled) return
        settled = true
        runCatching { output.close() }
        runCatching { onDiscard() }.onFailure { Logger.e(TAG, "discard failed", it) }
    }
}
