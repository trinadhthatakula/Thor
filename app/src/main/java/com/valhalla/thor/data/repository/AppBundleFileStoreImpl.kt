// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationReconciliation
import com.valhalla.thor.domain.repository.AppExportPublicationStatus
import com.valhalla.thor.domain.repository.ArchiveDestination
import com.valhalla.thor.domain.repository.ArchivePublication
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "AppBundleFileStore"

/**
 * The subdirectory of public Downloads that every write Thor makes lands in.
 *
 * One name with four write sites across two files — this store's MediaStore and legacy paths, and
 * [AppArchiveStoreImpl]'s — so it is defined once. It had drifted: the two archive backends wrote to
 * Downloads **root** while `AppArchiveStore.currentTargetLabel()`, which resolves through this store,
 * told the user *"Downloads/Thor"*. The label was right about the convention and wrong about where the
 * file went, which is the worst way round for the one caption naming a folder the user then has to
 * find.
 *
 * A `const val` on purpose, so it is inlined and nothing has to load a file facade to read it: the
 * relative path is assembled inside the functions that need it, never in a top-level `val`. A
 * top-level `val` touching `Environment` would run on the JVM the moment a test called any other
 * top-level member of the same file, and `AppArchiveStoreImpl.kt` has three that are JVM-tested.
 */
internal const val THOR_DOWNLOADS_SUBDIR = "Thor"

internal data class ExportPublicationEntry(
    val id: String,
    val displayName: String,
    val isComplete: Boolean,
)

/** Return the exact completed task publication and remove only its exact incomplete state. */
internal fun reconcileExactExportPublication(
    identity: AppExportPublicationIdentity,
    entries: List<ExportPublicationEntry>,
    removeIncomplete: (String) -> Unit,
): String? {
    val partial = partialName(identity.fileName)
    entries.asSequence()
        .filter {
            !it.isComplete &&
                    (it.displayName == identity.fileName || it.displayName == partial)
        }
        .forEach { removeIncomplete(it.id) }
    return entries.firstOrNull {
        it.isComplete && it.displayName == identity.fileName
    }?.id
}

/**
 * Open a pending MediaStore row only when it still has the durable name replay can recognize.
 *
 * The name is checked once before an output stream is opened and again immediately before the row is
 * made visible. Any rejection leaves the row pending and removes it through [removePending].
 */
internal fun <T : Any> openExactPendingMediaStoreDestination(
    identity: AppExportPublicationIdentity,
    insertPending: () -> T?,
    assignedName: (T) -> String?,
    openOutput: (T) -> OutputStream?,
    makeVisible: (T) -> Boolean,
    removePending: (T) -> Unit,
): ArchiveDestination? {
    val pending = insertPending() ?: return null
    var destinationOpened = false
    try {
        if (assignedName(pending) != identity.fileName) return null
        val stream = openOutput(pending) ?: return null
        destinationOpened = true
        return object : BaseDestination(stream, onSettled = {}) {
            override fun onPublish(): ArchivePublication? {
                if (assignedName(pending) != identity.fileName || !makeVisible(pending)) return null
                return ArchivePublication(identity.fileName, writtenBytes)
            }

            override fun onDiscard() = removePending(pending)
        }
    } finally {
        if (!destinationOpened) {
            runCatching { removePending(pending) }
                .onFailure { Logger.e(TAG, "could not discard an unrecognized pending export", it) }
        }
    }
}

internal suspend fun copyWithVerifiedProgress(
    input: InputStream,
    output: OutputStream,
    progress: VerifiedProgress,
) {
    val buffer = ByteArray(EXPORT_PUBLICATION_COPY_BUFFER_BYTES)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        progress.onBytesWritten(read.toLong())
    }
    output.flush()
}

private const val EXPORT_PUBLICATION_COPY_BUFFER_BYTES = 8192

/**
 * Android-backed [AppBundleFileStore]: writes bundles to public Downloads
 * (MediaStore on Q+, legacy external storage otherwise) or a user-picked SAF
 * tree, and builds FileProvider content URIs for sharing. All the framework
 * file-I/O for export/share lives here so the domain use cases stay pure.
 */
@Single(binds = [AppBundleFileStore::class])
class AppBundleFileStoreImpl(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppBundleFileStore {

    // All suspend members are main-safe: the blocking MediaStore/SAF/disk I/O runs on the
    // injected IO dispatcher so callers can invoke them from any context without risking an ANR.
    override suspend fun reconcilePublicExport(
        target: ExportTargetChoice,
        identity: AppExportPublicationIdentity,
    ): AppExportPublicationReconciliation = withContext(ioDispatcher) {
        val complete = when (target) {
            ExportTargetChoice.Downloads -> reconcileDownloads(identity)
            is ExportTargetChoice.Custom -> reconcileTree(target.treeUri, identity)
        }
        if (complete) {
            AppExportPublicationReconciliation.Complete(
                AppExportPublication(
                    destinationLabel = publicationLabel(target),
                    status = AppExportPublicationStatus.RECONCILED,
                )
            )
        } else {
            AppExportPublicationReconciliation.Absent
        }
    }

    override suspend fun publishPublicExport(
        file: File,
        target: ExportTargetChoice,
        mime: String,
        identity: AppExportPublicationIdentity,
        progress: VerifiedProgress,
    ): AppExportPublication = withContext(ioDispatcher) {
        require(file.name == identity.fileName)
        val destination = when (target) {
            ExportTargetChoice.Downloads ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    openExactInDownloads(identity, mime)
                } else {
                    openInLegacyDownloads(file)
                }

            is ExportTargetChoice.Custom -> {
                val treeUri = target.treeUri.toUri()
                openExactInTree(treeUri, identity.fileName, mime)
            }
        } ?: throw IOException("Could not create file")
        destination.write(file, progress)
        AppExportPublication(
            destinationLabel = publicationLabel(target),
            status = AppExportPublicationStatus.PUBLISHED,
        )
    }

    override suspend fun writeToDownloads(file: File, mime: String): String =
        writeToDownloads(file, mime, VerifiedProgress.NONE)

    override suspend fun writeToDownloads(
        file: File,
        mime: String,
        progress: VerifiedProgress,
    ): String = withContext(ioDispatcher) {
        val destination = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openInDownloads(file, mime)
                else openInLegacyDownloads(file)
                ) ?: throw IOException("Could not create file")
        destination.write(file, progress)
        context.getString(R.string.export_dest_downloads)
    }

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        writeToTree(file, treeUriStr, mime, VerifiedProgress.NONE)

    override suspend fun writeToTree(
        file: File,
        treeUriStr: String,
        mime: String,
        progress: VerifiedProgress,
    ): String = withContext(ioDispatcher) {
        val treeUri = treeUriStr.toUri()
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
        val destination = openInTree(treeUri, tree, file.name, mime)
            ?: throw IOException("Could not create file")
        destination.write(file, progress)
        tree.name ?: context.getString(R.string.export_dest_selected)
    }

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean =
        withContext(ioDispatcher) {
            if (treeUriStr == null) return@withContext false
            try {
                val doc = DocumentFile.fromTreeUri(context, treeUriStr.toUri())
                doc != null && doc.exists() && doc.canWrite()
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun currentTargetLabel(savedTreeUriStr: String?): String =
        withContext(ioDispatcher) {
            // SAF validity checks hit the content resolver / disk — keep them off the main thread.
            if (savedTreeUriStr != null && isTreeWritable(savedTreeUriStr)) {
                DocumentFile.fromTreeUri(context, savedTreeUriStr.toUri())?.name
                    ?: context.getString(R.string.export_dest_selected)
            } else context.getString(R.string.export_dest_downloads)
        }

    override fun shareUri(file: File): String =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
            .toString()

    override suspend fun stageText(fileName: String, content: String): File =
        withContext(ioDispatcher) {
            // Under cacheDir, so `provider_paths.xml`'s cache-path makes it shareable, and so the
            // system can reclaim it if it is never collected here.
            val dir = File(context.cacheDir, TEXT_STAGING_DIR)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            File(dir, fileName).apply { writeText(content) }
        }

    private fun publicationLabel(target: ExportTargetChoice): String = when (target) {
        ExportTargetChoice.Downloads -> context.getString(R.string.export_dest_downloads)
        is ExportTargetChoice.Custom ->
            DocumentFile.fromTreeUri(context, target.treeUri.toUri())?.name
                ?: context.getString(R.string.export_dest_selected)
    }

    private fun reconcileDownloads(identity: AppExportPublicationIdentity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                THOR_DOWNLOADS_SUBDIR,
            )
            val partial = File(dir, partialName(identity.fileName))
            if (partial.exists() && !partial.delete()) {
                throw IOException("Could not remove incomplete ${identity.fileName}")
            }
            return File(dir, identity.fileName).isFile
        }
        return reconcileMediaStoreDownloads(identity)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun reconcileMediaStoreDownloads(identity: AppExportPublicationIdentity): Boolean {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$THOR_DOWNLOADS_SUBDIR/"
        val entries = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.IS_PENDING,
            ),
            "(${MediaStore.Downloads.DISPLAY_NAME} = ? OR " +
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?) AND " +
                    "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(identity.fileName, partialName(identity.fileName), relativePath),
            null,
        )?.use { cursor ->
            buildList<ExportPublicationEntry> {
                while (cursor.moveToNext()) {
                    add(
                        ExportPublicationEntry(
                            id = cursor.getLong(0).toString(),
                            displayName = cursor.getString(1),
                            isComplete = cursor.getInt(2) == 0,
                        )
                    )
                }
            }
        } ?: throw IOException("Could not inspect export destination")
        return reconcileExactExportPublication(identity, entries) { id ->
            val deleted = resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads._ID} = ?",
                arrayOf(id),
            )
            if (deleted != 1) throw IOException("Could not remove incomplete ${identity.fileName}")
        } != null
    }

    private fun reconcileTree(
        treeUriString: String,
        identity: AppExportPublicationIdentity,
    ): Boolean {
        // SAF has no pending bit or durable returned-URI receipt. Exact final names are the only
        // publication proof under the strict writer's ordering: no bytes before an exact partial
        // name, and no rename until copying and stream close finish. Positive size is only a guard
        // against create normalizing straight to final before that name check, not proof by itself.
        // Providers must truthfully create a new empty document and report metadata; concurrent
        // external replacement is not attributable without a durable receipt (which we do not have).
        // Do not delete even a partial here: reconciliation is inspection, not guessed rollback.
        return treeEntries(treeUriString.toUri()).count {
            it.displayName == identity.fileName && it.isComplete
        } == 1
    }

    /**
     * Copy [source] into this destination and settle it exactly once.
     *
     * The calling shape [BaseDestination] documents — `try { … publish() } finally { discard() }` —
     * where the trailing `discard()` is a no-op after any settle and the cleanup after a throw. A
     * `CancellationException` is an `Exception`, so a cancelled export lands in that `finally` too and
     * the partial goes with it; nothing is caught, so the cancellation stays a cancellation.
     *
     * A null [ArchiveDestination.publish] becomes an [IOException] because that is what every caller
     * up the chain already handles: `writeStaged` maps a throw to a worded failure, and there is no
     * "wrote the bytes but could not name them" outcome for it to report.
     */
    private suspend fun ArchiveDestination.write(
        source: File,
        progress: VerifiedProgress = VerifiedProgress.NONE,
    ) {
        var publication: ArchivePublication? = null
        try {
            source.inputStream().use { copyWithVerifiedProgress(it, output, progress) }
            publication = publish()
        } finally {
            discard()
        }
        if (publication == null) throw IOException("Could not publish ${source.name}")
    }

    /** Unlike DocumentFile.listFiles(), an unreadable listing must not mean an empty folder. */
    private fun treeEntries(treeUri: Uri): List<ExportPublicationEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                        ?: throw IOException("Could not inspect export destination")
                    add(ExportPublicationEntry(
                        id = cursor.getString(0),
                        displayName = name,
                        isComplete = cursor.getString(2)?.let {
                            it.isNotEmpty() && it != DocumentsContract.Document.MIME_TYPE_DIR
                        } == true && !cursor.isNull(3) && cursor.getLong(3) > 0L,
                    ))
                }
            }
        } ?: throw IOException("Could not inspect export destination")
    }

    /**
     * Durable SAF publication never replaces an existing document. The caller must persist its
     * PUBLISHING fence before entering here: a process death can lose either returned URI, so name
     * enforcement alone cannot authorize replay. Cleanup uses only a positively returned identity.
     */
    private fun openExactInTree(treeUri: Uri, fileName: String, mime: String): ArchiveDestination? {
        if (treeEntries(treeUri).any { it.displayName == fileName }) return null
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val partial = DocumentsContract.createDocument(resolver, parent, mime, partialName(fileName))
            ?: return null
        var ownedUri: Uri? = partial
        var opened = false
        fun discardOwned() {
            ownedUri?.let { uri ->
                if (!DocumentsContract.deleteDocument(resolver, uri)) {
                    throw IOException("Could not discard rejected export")
                }
            }
        }
        try {
            if (displayNameOf(resolver, partial) != partialName(fileName) ||
                renameKnownUnsupported(resolver, partial)) return null
            val stream = resolver.openOutputStream(partial) ?: return null
            opened = true
            return object : BaseDestination(stream, onSettled = {}) {
                override fun onPublish(): ArchivePublication? {
                    if (displayNameOf(resolver, partial) != partialName(fileName) ||
                        treeEntries(treeUri).any { it.displayName == fileName }) return null
                    // If rename throws or returns no URI, its side effect is unknown. Do not guess
                    // that the old URI still identifies our document after a provider-side rename.
                    ownedUri = null
                    val renamed = DocumentsContract.renameDocument(resolver, partial, fileName)
                        ?: return null
                    ownedUri = renamed
                    if (displayNameOf(resolver, renamed) != fileName) return null
                    return ArchivePublication(fileName, writtenBytes)
                }

                override fun onDiscard() = discardOwned()
            }
        } finally {
            if (!opened) runCatching { discardOwned() }
                .onFailure { Logger.e(TAG, "could not discard rejected export", it) }
        }
    }

    /**
     * SAF, any API: write `<name>.part`, then rename it over the file being replaced.
     *
     * The order is the whole point. This used to delete the existing file **before** creating the new
     * document, so every byte of a multi-gigabyte copy was a window in which the user had neither the
     * old export nor the new one — and a `Worker` widens that window from "a rare foreground cancel"
     * to routine (foreground-service time cap, Task Manager Stop, low-memory kill, and WorkManager's
     * free re-run of an interrupted worker). Now the replace is a delete plus a rename with every byte
     * already on disk.
     *
     * A provider that cannot rename is refused **before** anything is deleted. That matches
     * [AppArchiveStoreImpl]'s answer for the same provider — a rename that publishes nothing is a
     * failure, not a reason to fall back to a copy — because a fallback that copies into the final name
     * after deleting the old file re-creates exactly the window this function exists to close.
     *
     * `onSettled = {}`: [PartialArchiveLedger] exists so the launch sweep can delete a `.thorbak.part`
     * an archive left behind, and export partials are deliberately not in it. A killed export leaves
     * `<name>.part` in the user's folder — visible, obviously incomplete, and never mistaken for a
     * finished export, which is the trade this reordering buys.
     */
    private fun openInTree(
        treeUri: Uri,
        tree: DocumentFile,
        fileName: String,
        mime: String,
    ): ArchiveDestination? {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val partUri = DocumentsContract.createDocument(
            resolver,
            parent,
            mime,
            partialName(fileName),
        ) ?: return null
        if (renameKnownUnsupported(resolver, partUri)) {
            Logger.e(
                TAG,
                "the provider for $treeUri cannot rename, so it can never publish a partial"
            )
            DocumentsContract.deleteDocument(resolver, partUri)
            return null
        }
        val stream = resolver.openOutputStream(partUri) ?: run {
            DocumentsContract.deleteDocument(resolver, partUri)
            return null
        }
        return object : BaseDestination(stream, onSettled = {}) {
            override fun onPublish(): ArchivePublication? {
                // Now, with the bytes written: a rename onto a name the folder still holds would be
                // de-duplicated or refused, so the file being replaced goes first — and it goes at the
                // last possible moment rather than the first.
                tree.findFile(fileName)?.delete()
                val publishedUri = DocumentsContract.renameDocument(resolver, partUri, fileName)
                    ?: return null
                return ArchivePublication(
                    displayName = displayNameOf(resolver, publishedUri) ?: fileName,
                    byteSize = writtenBytes,
                )
            }

            override fun onDiscard() {
                DocumentsContract.deleteDocument(resolver, partUri)
            }
        }
    }

    /**
     * True only when the provider **said** it cannot rename.
     *
     * Deliberately not "supportsRename": an unreadable or absent flags column answers "unknown", and
     * unknown proceeds. Refusing on unknown would turn an exotic provider that renames perfectly well
     * into an export that cannot be written at all, which is a worse regression than the one this
     * guard prevents — and on unknown the publish still fails safely, having lost only the file it was
     * asked to overwrite, which is what today's code loses unconditionally at the *start*.
     */
    private fun renameKnownUnsupported(resolver: ContentResolver, docUri: Uri): Boolean = try {
        resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            // `isNull` before `getInt`, because `getInt` on a null column answers 0 — indistinguishable
            // from "the provider supports nothing", which is exactly the wrong way to read silence.
            cursor.moveToFirst() && !cursor.isNull(0) &&
                    (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0
        } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "could not read the document flags; assuming the provider can rename", e)
        false
    }

    /**
     * Q+ durable Downloads: reject a collision-assigned row while it is still pending and empty.
     *
     * Reconciliation recognizes only [identity]'s exact name. MediaStore may assign a suffix between
     * that check and this insert, so both name checks happen before visibility and a mismatch is
     * deleted rather than becoming an orphan replay cannot identify.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openExactInDownloads(
        identity: AppExportPublicationIdentity,
        mime: String,
    ): ArchiveDestination? {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$THOR_DOWNLOADS_SUBDIR/"
        return openExactPendingMediaStoreDestination(
            identity = identity,
            insertPending = {
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValuesOf(
                        MediaStore.Downloads.DISPLAY_NAME to identity.fileName,
                        MediaStore.Downloads.MIME_TYPE to mime,
                        MediaStore.Downloads.RELATIVE_PATH to relativePath,
                        MediaStore.Downloads.IS_PENDING to 1,
                    ),
                )
            },
            assignedName = { uri -> displayNameOf(resolver, uri) },
            openOutput = resolver::openOutputStream,
            makeVisible = { uri ->
                resolver.update(
                    uri,
                    contentValuesOf(MediaStore.Downloads.IS_PENDING to 0),
                    null,
                    null,
                ) == 1
            },
            removePending = { uri -> resolver.delete(uri, null, null) },
        )
    }

    /**
     * Q+ Downloads: write a pending row, then clear the pending flag once every byte has landed.
     *
     * `IS_PENDING` is MediaStore's own settle-once, so no partial name is needed — a pending row is
     * invisible to other apps. What was wrong was the *replace*: the same-named row was deleted before
     * the insert, so the whole copy ran with the old export already gone.
     *
     * The row being replaced is resolved to an `_ID` **before** the insert, and deleted by that id.
     * Re-running the original `DISPLAY_NAME = ? AND RELATIVE_PATH = ?` delete after the insert would
     * match the row just written — it carries that display name in that folder — and so would delete
     * the export it had only just finished.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openInDownloads(source: File, mime: String): ArchiveDestination? {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$THOR_DOWNLOADS_SUBDIR/"
        // RELATIVE_PATH must match exactly, including the trailing slash.
        val replacedIds = idsAt(resolver, source.name, relativePath)
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            contentValuesOf(
                MediaStore.Downloads.DISPLAY_NAME to source.name,
                MediaStore.Downloads.MIME_TYPE to mime,
                MediaStore.Downloads.RELATIVE_PATH to relativePath,
                MediaStore.Downloads.IS_PENDING to 1,
            ),
        ) ?: return null
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        return object : BaseDestination(stream, onSettled = {}) {
            override fun onPublish(): ArchivePublication? {
                // Clearing IS_PENDING first is what makes the bytes real, and it is the one step that
                // must not be traded for a tidier name: a crash between here and the delete below
                // leaves the user with two complete files, where the other order would leave them with
                // neither the old file nor a visible new one.
                val cleared = resolver.update(
                    uri,
                    contentValuesOf(MediaStore.Downloads.IS_PENDING to 0),
                    null,
                    null,
                ) == 1
                if (!cleared) return null
                replacedIds.forEach { id ->
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads._ID} = ?",
                        arrayOf(id.toString()),
                    )
                }
                // MediaStore de-duplicates a colliding display name at insert, so while the old row
                // still existed this one may have become `Foo (1).apk`. With it gone, ask for the name
                // the user was promised — but do not fail the export over it: the bytes are already
                // published and complete, and a wrong word beats a lost file.
                val assigned = displayNameOf(resolver, uri)
                if (assigned != null && assigned != source.name) {
                    runCatching {
                        resolver.update(
                            uri,
                            contentValuesOf(MediaStore.Downloads.DISPLAY_NAME to source.name),
                            null,
                            null,
                        )
                    }.onFailure {
                        Logger.w(TAG, "exported as $assigned, not ${source.name}: $it")
                    }
                }
                return ArchivePublication(
                    displayName = displayNameOf(resolver, uri) ?: assigned ?: source.name,
                    byteSize = writtenBytes,
                )
            }

            override fun onDiscard() {
                // An IS_PENDING row that is never cleared is invisible to other apps and never
                // collected, so leaving one behind would be a permanent phantom in Downloads.
                resolver.delete(uri, null, null)
            }
        }
    }

    /** The ids of every row already carrying [displayName] in [relativePath]. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun idsAt(
        resolver: ContentResolver,
        displayName: String,
        relativePath: String,
    ): List<Long> = try {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Best-effort replace: an unreadable query leaves the old row in place, so the export lands
        // beside it under a de-duplicated name. A visible duplicate, not a lost file.
        Logger.e(TAG, "could not look up the row being replaced", e)
        emptyList()
    }

    /**
     * Null when the provider will not say; the caller then leaves the assigned name alone.
     *
     * `MediaColumns.DISPLAY_NAME`, not `Downloads.DISPLAY_NAME` — the same string, from a class that has
     * existed since API 1, so this helper needs no API gate of its own.
     */
    // internal, not private — reached from a lambda's own class; see SyntheticAccessor in lint.xml.
    internal fun displayNameOf(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "could not read the display name MediaStore assigned", e)
        null
    }

    /**
     * API 28 Downloads: write `<name>.part`, then `renameTo` over the file being replaced.
     *
     * `rename(2)` within one volume is atomic and overwrites, so this is the one backend that needs no
     * delete at all — and it is where the old code was worst. It wrote straight into the final name, so
     * a cancelled or killed export left a **truncated `.apk` under the right name**: the one failure
     * shape a user cannot tell from a good export.
     */
    private fun openInLegacyDownloads(source: File): ArchiveDestination? {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            THOR_DOWNLOADS_SUBDIR,
        )
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val partial = File(dir, partialName(source.name))
        val published = File(dir, source.name)
        val stream = FileOutputStream(partial)
        return object : BaseDestination(stream, onSettled = {}) {
            override fun onPublish(): ArchivePublication? =
                if (partial.renameTo(published)) {
                    ArchivePublication(published.name, writtenBytes)
                } else {
                    null
                }

            override fun onDiscard() {
                partial.delete()
            }
        }
    }

    private companion object {
        /** Cache subdirectory for [stageText]; kept apart from the bundle builder's staging. */
        const val TEXT_STAGING_DIR = "list_export"
    }
}
