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
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

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

        runCatching {
            ZipArchiveSource(
                file = File("/proc/self/fd/${descriptor.fd}"),
                displayName = name,
                onClose = { runCatching { descriptor.close() } },
            )
        }.getOrElse { direct ->
            // Two-argument `w`, because that is the only overload this codebase uses. `Logger` is a
            // typealias onto `thor-extension-api`'s; every `w` call site in `app/` passes tag and
            // message only, and `e` is the one that takes a throwable.
            Logger.w(TAG, "fd path unusable for $name (${direct.message}), copying to cache")
            copyThenOpen(uri, name).also { runCatching { descriptor.close() } }
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
