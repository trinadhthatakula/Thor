// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.valhalla.thor.data.backup.PartialArchiveLedger
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.THORBAK_EXTENSION
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
import kotlinx.coroutines.CancellationException
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

/**
 * True when [name] is a name the launch-time orphan sweep may delete.
 *
 * §10's rule is "exact names, never a wildcard", and the names come from [PartialArchiveLedger] — a
 * JSON file that a truncated write or a hand edit can turn into anything. Every one of them is then
 * fed to `File(dir, name)` or matched against a provider's display names **inside a folder the user
 * chose**, so two things have to hold:
 *
 * - no path component, or the delete escapes the directory the sweep was pointed at;
 * - carries [PARTIAL_SUFFIX] and is not itself a finished `.thorbak`, or a bad ledger entry turns the
 *   sweep into "delete the user's backup".
 *
 * `contains`, not `endsWith`: SAF providers may de-duplicate a colliding name, and the recorded name
 * is the one the provider actually assigned — which can be `foo.thorbak.part (1)`.
 *
 * That does make the suffix clause wider than it reads: `notes.part.txt` would satisfy it. **What
 * keeps that safe is the source of the names, not this rule** — every candidate comes from
 * [PartialArchiveLedger], written by Thor for a container Thor created, and is then matched for exact
 * equality against a display name. No directory is ever listed for things that look sweepable. Widen
 * the *input* to this function — a scan, a user-supplied name — and this clause is no longer enough on
 * its own.
 */
internal fun isSweepableOrphanName(name: String): Boolean =
    name.isNotBlank() &&
        !name.contains('/') &&
        !name.contains('\\') &&
        name != "." &&
        name != ".." &&
        name.contains(PARTIAL_SUFFIX) &&
        !name.endsWith(".$THORBAK_EXTENSION")

@Single(binds = [AppArchiveStore::class])
class AppArchiveStoreImpl(
    private val context: Context,
    private val preferenceRepository: PreferenceRepository,
    private val fileStore: AppBundleFileStore,
    private val ledger: PartialArchiveLedger,
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
     * §10's half of the sweep that only this class can perform: the containers live at the *export*
     * destination, which is the user's folder and not Thor's.
     *
     * Deliberately **does not** clear a saved-but-unreadable export tree the way [openArchive] does.
     * This runs at launch, where a tree on a volume the platform has not mounted yet reads as
     * unwritable; forgetting the user's chosen folder because a boot-time probe failed is a worse
     * outcome than leaving one `.part` for the next launch.
     */
    override suspend fun discardOrphans(names: Set<String>): Set<String> = withContext(ioDispatcher) {
        if (names.isEmpty()) return@withContext emptySet()
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        val choice = resolveExportTarget(savedUri, fileStore.isTreeWritable(savedUri)).choice
        names.filterTo(mutableSetOf()) { name ->
            try {
                isSweepableOrphanName(name) && deleteByName(choice, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // False, not a throw: one unreachable name must not abandon the rest, and a name that
                // could not be deleted stays in the ledger rather than being forgotten with the file
                // still on disk.
                Logger.e(TAG, "could not delete the orphan $name", e)
                false
            }
        }
    }

    /**
     * @return true only when the file is **gone**. A destination Thor cannot reach yet is not an
     *   orphan that does not exist; returning true there would drop the name and abandon the file.
     */
    private fun deleteByName(choice: ExportTargetChoice, name: String): Boolean = when (choice) {
        is ExportTargetChoice.Custom -> deleteInTree(choice.treeUri, name)
        // MediaStore is absent on purpose, and it is the one branch that records nothing in the
        // ledger either — see openInMediaStore. Nothing Thor wrote through it ever carries a
        // PARTIAL_SUFFIX name, so there is nothing here to match; matching on the *published* name
        // instead would delete the user's finished backup.
        ExportTargetChoice.Downloads ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) false else deleteInLegacyDownloads(name)
    }

    /**
     * SAF has no "open by name", so the tree's children are listed and matched on
     * `COLUMN_DISPLAY_NAME`. Exact equality, never `startsWith` — the folder is the user's.
     */
    private fun deleteInTree(treeUri: String, name: String): Boolean {
        val resolver = context.contentResolver
        val tree = treeUri.toUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) != name) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                return DocumentsContract.deleteDocument(resolver, docUri)
            }
        }
        return false
    }

    @Suppress("DEPRECATION") // See openInLegacyDownloads: this branch only runs below API 29.
    private fun deleteInLegacyDownloads(name: String): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, name)
        return file.isFile && file.delete()
    }

    /**
     * Records [name] as an unpublished container and returns the callback that forgets it again.
     *
     * The callback fires from **both** `publish()` and `discard()`. Both, not just `discard()`: a
     * published archive's `.part` name no longer exists, and leaving it in the ledger makes the next
     * launch ask this store to delete a name that no file carries.
     */
    private suspend fun rememberPartial(name: String): suspend () -> Unit {
        ledger.add(name)
        return { forgetPartial(name) }
    }

    private suspend fun forgetPartial(name: String) {
        try {
            ledger.forget(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A ledger write that failed leaves a name behind. The next launch offers it for deletion
            // and gets false back, which is the harmless half of this function's two failure modes.
            Logger.e(TAG, "could not forget the partial $name", e)
        }
    }

    /**
     * MediaStore, API 29+. `IS_PENDING = 1` already means "not visible to other apps", so this
     * backend writes under the **final** name and publishes by clearing the flag — no rename, and no
     * window in which a complete archive carries a partial name.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun openInMediaStore(fileName: String): ArchiveDestination? {
        // Records nothing in the ledger, on purpose. A pending row carries the archive's FINAL display
        // name, so there is no `.part` name to record; recording the final one would point the launch
        // sweep at a name a user's completed backup also carries. The platform already expires a
        // pending row on its own (DATE_EXPIRES, seven days), and an invisible row is not an orphan a
        // user can trip over.
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
        return object : BaseDestination(stream, onSettled = {}) {
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
    private suspend fun openInTree(treeUri: String, fileName: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val tree = treeUri.toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val requestedName = partialName(fileName)
        val docUri = DocumentsContract.createDocument(
            resolver,
            parent,
            THORBAK_MIME,
            requestedName,
        ) ?: return null
        val stream = resolver.openOutputStream(docUri) ?: run {
            DocumentsContract.deleteDocument(resolver, docUri)
            return null
        }
        // The name the provider *assigned*, not the one Thor asked for. A provider is free to
        // de-duplicate a collision into `foo.thorbak.part (1)`, and the sweep deletes by exact display
        // name — recording the requested name would leave the real file behind forever.
        val recorded = displayNameOf(resolver, docUri) ?: requestedName
        val forget = rememberPartial(recorded)
        return object : BaseDestination(stream, onSettled = forget) {
            override fun onPublish(): Boolean =
                DocumentsContract.renameDocument(resolver, docUri, fileName) != null

            override fun onDiscard() {
                DocumentsContract.deleteDocument(resolver, docUri)
            }
        }
    }

    /**
     * Null when the provider will not say. The caller falls back to the requested name, which is the
     * right answer whenever the provider did not rename anything — the common case.
     *
     * `catch (CancellationException) { throw it }` before `catch (Exception)`, and not
     * `runCatching { }.getOrNull()`: the query is not suspending today, so nothing here can be
     * cancelled today, but `runCatching` swallows a `CancellationException` whole and this file's one
     * other guard already spells the pair out. Three of those swallows have already had to be fixed on
     * this branch.
     */
    private fun displayNameOf(resolver: ContentResolver, docUri: Uri): String? = try {
        resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "could not read the display name the provider assigned", e)
        null
    }

    /**
     * API 28's Downloads directory as a plain `File`. `renameTo` within one volume is atomic, which
     * is the same guarantee the other two backends reach by other means.
     */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: deprecated at 29, and this branch
    // only runs below 29. minSdk is 28, which is the whole reason the branch exists.
    private suspend fun openInLegacyDownloads(fileName: String): ArchiveDestination? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val partial = File(dir, partialName(fileName))
        val stream = FileOutputStream(partial)
        val forget = rememberPartial(partial.name)
        return object : BaseDestination(stream, onSettled = forget) {
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
 *
 * `internal` rather than `private`, so the two invariants above — settle exactly once, and run
 * [onSettled] on *both* settle paths — are reachable from a JVM test. Nothing outside this file
 * constructs one; the three backends are all anonymous subclasses in [AppArchiveStoreImpl].
 */
internal abstract class BaseDestination(
    override val output: OutputStream,
    /**
     * Runs once, on **both** settle paths.
     *
     * Both, not only [discard]: this is where the ledger entry is forgotten, and a published archive's
     * `.part` name no longer names anything. Left in the ledger, the next launch would ask the store
     * to delete a name no file carries — harmless, but it never leaves, so the ledger grows a
     * permanent tail of names the sweep retries forever.
     */
    private val onSettled: suspend () -> Unit,
) : ArchiveDestination {

    private var settled = false

    protected abstract fun onPublish(): Boolean

    protected abstract fun onDiscard()

    override suspend fun publish(): Boolean {
        if (settled) return false
        settled = true
        output.close()
        // `finally`, so a rename that throws still clears the ledger entry: at that point the partial
        // is settled either way, and the failure is reported by the caller, not by a stale name.
        return try {
            onPublish()
        } finally {
            onSettled()
        }
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
        onSettled()
    }
}
