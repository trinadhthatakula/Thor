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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

        openOrRelease(
            build = {
                runCatching {
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
                        // ESPIPE (pipe fd), EACCES (another app's private file via SAF), etc. — a
                        // path problem rather than a format one. Copy the whole file into cache and
                        // try again. Two-argument `w`, the only overload this codebase uses; `e`
                        // takes a throwable.
                        Logger.w(TAG, "fd path unusable for $name (${direct.message}), copying to cache")
                        copyThenOpen(uri, name)
                    }
                }
            },
            release = { source ->
                // A source that reached here can no longer be handed to anyone: closing it closes
                // the zip, closes the fd via `onClose`, and deletes the cache copy on the fallback
                // path. The trailing `descriptor.close()` covers the case where `source` is null
                // (fd-path failure with no fallback) — it is idempotent-by-runCatching, so the
                // double close on the paths that already closed inline is harmless.
                source?.close()
                runCatching { descriptor.close() }
            },
        )
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

/**
 * Run [build] and hand its result to the caller — unless this coroutine was cancelled while [build]
 * was running, in which case [release] frees the result and the cancellation is rethrown.
 *
 * **This exists because [build] holds no suspension points.** Cancellation in Kotlin is cooperative:
 * it is observable only at a suspension point or at an explicit check, and constructing a
 * [ZipArchiveSource] or copying a file to cache is neither. A plain `try`/`finally` around such a
 * block therefore never enters its `finally` on the cancellation path — the block runs to
 * completion, commits, and `withContext`'s prompt-cancellation guarantee then discards the returned
 * value at the call site with nothing left holding a reference to close it. The result is a leaked
 * `ParcelFileDescriptor`, a leaked `ZipFile`, and a possibly multi-gigabyte
 * [UriArchiveSourceFactory.COPY_FILE_NAME] orphaned in cache.
 *
 * `ensureActive()` is the checkpoint that makes [release] reachable. It is not optional decoration:
 * `AppDataArchiveGatewayImpl` uses the same `committed`/[NonCancellable] shape *without* an explicit
 * check only because its `try` really does suspend. The idiom does not transfer on its own.
 *
 * [release] runs under [NonCancellable] because cleanup that is itself cancelled leaks exactly what
 * it was written to free.
 *
 * Top-level and `internal` so the cancellation contract is JVM-testable: the enclosing `open` needs
 * a `ContentResolver` and a real `ParcelFileDescriptor`, neither of which exists on the unit-test
 * classpath.
 */
internal suspend fun openOrRelease(
    build: () -> ArchiveSource?,
    release: (ArchiveSource?) -> Unit,
): ArchiveSource? {
    var committed = false
    var built: ArchiveSource? = null
    try {
        built = build()
        currentCoroutineContext().ensureActive()
        committed = true
        return built
    } finally {
        if (!committed) {
            withContext(NonCancellable) { release(built) }
        }
    }
}
