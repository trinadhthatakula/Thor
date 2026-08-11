// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.util.zip.ZipException

/**
 * Turns a `content://` URI into a randomly-accessible zip.
 *
 * `ZipFile` needs a path, and a `content://` URI is not one. The route is
 * `openFileDescriptor` → `ParcelFileDescriptor` → `/proc/self/fd/<n>`, which for a provider backed by
 * a regular file is a seekable path to the same inode. That is the cheap case and the common one: no
 * copy, no second disk cost on a file that may be tens of gigabytes.
 *
 * **A provider is not obliged to give a regular file.** `openFileDescriptor` may hand back a pipe —
 * some cloud and media providers do. Opening it succeeds and the first seek fails, so `ZipFile`
 * throws, and the only remaining option is to copy the whole thing into cache first. That costs the
 * archive's size in free space, so it is a fallback with a log line, never the default.
 *
 * A [ZipException] from the fd path means the bytes were readable but are not a zip; the copy
 * fallback would fail identically, so that case gives up immediately.
 */
@Single(binds = [ArchiveSourceFactory::class])
class UriArchiveSourceFactory(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ArchiveSourceFactory {

    override suspend fun open(uriString: String): ArchiveSource? = withContext(ioDispatcher) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext null
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "backup"

        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull()
            ?: return@withContext null

        var committed = false
        var source: ArchiveSource? = null
        try {
            source = runCatching {
                ZipArchiveSource(
                    file = File("/proc/self/fd/${descriptor.fd}"),
                    displayName = name,
                    onClose = { runCatching { descriptor.close() } },
                )
            }.getOrElse { direct ->
                // The fd path failed; the descriptor is no longer needed regardless of why.
                runCatching { descriptor.close() }
                if (direct is ZipException) {
                    // The bytes were readable but are not a zip — a copy fallback would fail
                    // identically. Give up immediately rather than spending disk space and time.
                    Logger.w(TAG, "$name is not a valid zip (${direct.message})")
                    null
                } else {
                    // ESPIPE (pipe fd), EACCES (another app's private file via SAF), etc. — a path
                    // problem rather than a format one. Copy the whole file into cache and try again.
                    // Two-argument `w`, the only overload this codebase uses; `e` takes a throwable.
                    Logger.w(TAG, "fd path unusable for $name (${direct.message}), copying to cache")
                    copyThenOpen(uri, name)
                }
            }
            committed = true
            source
        } finally {
            // If this coroutine was cancelled while the block was running, `source` may have been
            // created but the caller can no longer receive it. Clean up under NonCancellable so the
            // fd and cache copy are released even while cancellation is in progress.
            if (!committed) {
                withContext(NonCancellable) {
                    source?.close()
                    // Covers the case where source is null (fd-path failure) and the inline close
                    // above did not run — e.g. cancellation before `getOrElse` completed.
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    /**
     * The fallback. One fixed file name, in Thor's own cache, so the launch-time orphan sweep can
     * delete it by exact name if the process dies mid-read.
     */
    private fun copyThenOpen(uri: Uri, name: String): ArchiveSource? {
        val copy = File(context.cacheDir, COPY_FILE_NAME)
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                copy.outputStream().use(input::copyTo)
            }
            ZipArchiveSource(copy, name, onClose = { copy.delete() })
        }.getOrElse {
            Logger.e(TAG, "could not read $name", it)
            copy.delete()
            null
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val TAG = "UriArchiveSource"

        /** Also named in the orphan sweep (Task 15). Exact name, never a wildcard. */
        const val COPY_FILE_NAME = "thorbak_read_copy.zip"
    }
}
