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
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.ArchiveDestination
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
    override suspend fun writeToDownloads(file: File, mime: String): String =
        withContext(ioDispatcher) {
            val destination = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openInDownloads(file, mime)
                else openInLegacyDownloads(file)
                ) ?: throw IOException("Could not create file")
            destination.write(file)
            context.getString(R.string.export_dest_downloads)
        }

    override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
        withContext(ioDispatcher) {
            val treeUri = treeUriStr.toUri()
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
            val destination = openInTree(treeUri, tree, file.name, mime)
                ?: throw IOException("Could not create file")
            destination.write(file)
            tree.name ?: context.getString(R.string.export_dest_selected)
        }

    override suspend fun isTreeWritable(treeUriStr: String?): Boolean =
        withContext(ioDispatcher) {
            if (treeUriStr == null) return@withContext false
            try {
                val doc = DocumentFile.fromTreeUri(context, treeUriStr.toUri())
                doc != null && doc.exists() && doc.canWrite()
            } catch (_: Exception) { false }
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
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file).toString()

    override suspend fun stageText(fileName: String, content: String): File =
        withContext(ioDispatcher) {
            // Under cacheDir, so `provider_paths.xml`'s cache-path makes it shareable, and so the
            // system can reclaim it if it is never collected here.
            val dir = File(context.cacheDir, TEXT_STAGING_DIR)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            File(dir, fileName).apply { writeText(content) }
        }

    /**
     * Copy [source] into this destination and settle it exactly once.
     *
     * The calling shape [BaseDestination] documents — `try { … publish() } finally { discard() }` —
     * where the trailing `discard()` is a no-op after any settle and the cleanup after a throw. A
     * `CancellationException` is an `Exception`, so a cancelled export lands in that `finally` too and
     * the partial goes with it; nothing is caught, so the cancellation stays a cancellation.
     *
     * A false [ArchiveDestination.publish] becomes an [IOException] because that is what every caller
     * up the chain already handles: `writeStaged` maps a throw to a worded failure, and there is no
     * "wrote the bytes but could not name them" outcome for it to report.
     */
    private suspend fun ArchiveDestination.write(source: File) {
        var published = false
        try {
            source.inputStream().use { it.copyCancellableTo(output) }
            published = publish()
        } finally {
            discard()
        }
        if (!published) throw IOException("Could not publish ${source.name}")
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
            Logger.e(TAG, "the provider for $treeUri cannot rename, so it can never publish a partial")
            DocumentsContract.deleteDocument(resolver, partUri)
            return null
        }
        val stream = resolver.openOutputStream(partUri) ?: run {
            DocumentsContract.deleteDocument(resolver, partUri)
            return null
        }
        return object : BaseDestination(stream, onSettled = {}) {
            override fun onPublish(): Boolean {
                // Now, with the bytes written: a rename onto a name the folder still holds would be
                // de-duplicated or refused, so the file being replaced goes first — and it goes at the
                // last possible moment rather than the first.
                tree.findFile(fileName)?.delete()
                return DocumentsContract.renameDocument(resolver, partUri, fileName) != null
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
            override fun onPublish(): Boolean {
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
                if (!cleared) return false
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
                return true
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
            override fun onPublish(): Boolean = partial.renameTo(published)

            override fun onDiscard() {
                partial.delete()
            }
        }
    }

    /**
     * `InputStream.copyTo` in chunks, checking for cancellation between them.
     *
     * Exports are cancellable from the UI and a bundle is routinely hundreds of megabytes. The
     * stock `copyTo` is one uninterruptible call, so cancelling mid-write did nothing until the
     * whole file had been pushed through SAF or MediaStore — the progress UI would sit on a
     * cancelled export for as long as the copy took. The check is a volatile read per 8 KB against
     * an IO-bound loop, which costs nothing next to the write it guards. Mirrors
     * `AppBundleBuilderImpl.copyCancellable`, which does the same for the staging copies.
     */
    private suspend fun InputStream.copyCancellableTo(out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        out.flush()
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 8192

        /** Cache subdirectory for [stageText]; kept apart from the bundle builder's staging. */
        const val TEXT_STAGING_DIR = "list_export"
    }
}
