// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
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
import com.valhalla.thor.domain.repository.SystemRepository

@Single(binds = [ArchiveSourceFactory::class])
class UriArchiveSourceFactory(
    private val context: Context,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ArchiveSourceFactory {

    override suspend fun open(uriString: String): ArchiveOpenOutcome = withContext(ioDispatcher) {
        val uri = runCatching { uriString.toUri() }.getOrNull()
            ?: return@withContext ArchiveOpenOutcome.Unreadable
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "backup"

        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull()

        if (descriptor == null) {
            return@withContext copyThenOpen(uri, name)
        }

        openOrRelease(
            build = {
                runCatching {
                    ArchiveOpenOutcome.Opened(
                        ZipArchiveSource(
                            file = File("/proc/self/fd/${descriptor.fd}"),
                            displayName = name,
                            onClose = { runCatching { descriptor.close() } },
                        )
                    )
                }.getOrElse { direct ->
                    // The fd path failed; the descriptor is no longer needed regardless of why.
                    runCatching { descriptor.close() }
                    if (direct is ZipException) {
                        // The bytes were readable but are not a zip — a copy fallback would fail
                        // identically. Give up immediately rather than spending disk space and time,
                        // and say *which* failure this was: the user picked the wrong file, and the
                        // one thing that will not help them is trying the same one again.
                        Logger.w(TAG, "$name is not a valid zip (${direct.message})")
                        ArchiveOpenOutcome.NotAnArchive
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
            release = { outcome ->
                // An outcome that reached here can no longer be handed to anyone: closing the source
                // closes the zip, closes the fd via `onClose`, and deletes the cache copy on the
                // fallback path. The trailing `descriptor.close()` covers the two failure outcomes,
                // which hold nothing to close — it is idempotent-by-runCatching, so the double close
                // on the paths that already closed inline is harmless.
                (outcome as? ArchiveOpenOutcome.Opened)?.source?.close()
                runCatching { descriptor.close() }
            },
        )
    }

    /**
     * The fallback: copy the archive into Thor's own cache and open *that*.
     *
     * **One file per open, not one file for the app.** The fixed name this replaced was chosen so the
     * launch-time orphan sweep could delete it by exact equality, which is a real requirement — but it
     * was pinned on one side only, and said nothing about two opens overlapping. They do overlap:
     * `ArchiveRestoreViewModel` opens a source to read a header while `AppArchiveWorker` can be holding
     * one open for the whole of a running restore, and the fallback is provider-decided, so if one open
     * takes it the other almost certainly does too. With a shared name the second `outputStream()`
     * truncates the inode the first's `ZipFile` is mid-read on, and the first `onClose` then deletes the
     * second's copy. The reader that loses can be a *destructive* restore that has already swapped a
     * class in.
     *
     * `createTempFile` gives the uniqueness, and [isReadCopyName] gives the sweep back its side of the
     * bargain: prefix-and-suffix instead of equality, which the staging sweep already does for `.part`
     * names. The name is only ever produced and consumed here, so nothing has to parse it.
     */
    private suspend fun copyThenOpen(uri: Uri, name: String): ArchiveOpenOutcome {
        val copy = runCatching {
            File.createTempFile(COPY_FILE_PREFIX, COPY_FILE_SUFFIX, context.cacheDir)
        }.getOrElse {
            Logger.e(TAG, "could not create a cache copy for $name", it)
            return ArchiveOpenOutcome.Unreadable
        }
        return runCatching {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (input != null) {
                input.use { src -> copy.outputStream().use(src::copyTo) }
            } else {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val tempToken = java.util.UUID.randomUUID().toString()
                    val tmpPath = "/data/local/tmp/thor_read_$tempToken"
                    val cmd = "cat '$path' > '$tmpPath' 2>/dev/null && chmod 666 '$tmpPath' 2>/dev/null"
                    val res = systemRepository.executeShellCommand(cmd).getOrNull()
                    if (res != null && res.first == 0) {
                        val tmpFile = File(tmpPath)
                        if (tmpFile.exists() && tmpFile.length() > 0) {
                            try {
                                tmpFile.inputStream().use { input ->
                                    copy.outputStream().use(input::copyTo)
                                }
                            } finally {
                                systemRepository.executeShellCommand("rm -f '$tmpPath'")
                            }
                        } else {
                            systemRepository.executeShellCommand("rm -f '$tmpPath'")
                        }
                    }
                }
            }
            if (!copy.exists() || copy.length() == 0L) {
                copy.delete()
                return ArchiveOpenOutcome.Unreadable
            }
            // Deletes *this* copy, by identity — the whole point of the unique name.
            ArchiveOpenOutcome.Opened(ZipArchiveSource(copy, name, onClose = { copy.delete() }))
        }.getOrElse {
            Logger.e(TAG, "could not read $name", it)
            copy.delete()
            // A `ZipException` here is the same statement the fd path makes with
            // `ArchiveOpenOutcome.NotAnArchive`: the copy completed and the bytes are not a zip. The
            // rest — the stream that would not open, the volume that filled up — is access.
            if (it is ZipException) ArchiveOpenOutcome.NotAnArchive else ArchiveOpenOutcome.Unreadable
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val TAG = "UriArchiveSource"

        /**
         * The name every fallback copy used to share.
         *
         * Kept, and still swept, because it is what an orphan left by an *older build* of Thor is
         * called: the sweep runs at launch, and the launch after an update is exactly when such a file
         * is found. Nothing writes it any more — see [copyThenOpen].
         */
        const val COPY_FILE_NAME = "thorbak_read_copy.zip"

        private const val COPY_FILE_PREFIX = "thorbak_read_copy_"
        private const val COPY_FILE_SUFFIX = ".zip"

        /**
         * True for a cache entry the orphan sweep owns: a current per-open copy, or the legacy
         * [COPY_FILE_NAME].
         *
         * Prefix-and-suffix rather than equality, now that the name is unique per open. The two
         * `thorbak_read_copy` spellings are Thor's alone — `createTempFile` inserts digits between the
         * prefix and the suffix, so no other cache entry can collide with the shape by accident.
         */
        fun isReadCopyName(name: String): Boolean =
            name == COPY_FILE_NAME ||
                (name.startsWith(COPY_FILE_PREFIX) && name.endsWith(COPY_FILE_SUFFIX))
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
 * `ParcelFileDescriptor`, a leaked `ZipFile`, and a possibly multi-gigabyte fallback copy orphaned in
 * cache (one the launch-time sweep will only find if it matches
 * [UriArchiveSourceFactory.Companion.isReadCopyName]).
 *
 * `ensureActive()` is the checkpoint that makes [release] reachable. It is not optional decoration:
 * `AppDataArchiveGatewayImpl` uses the same `committed`/[NonCancellable] shape *without* an explicit
 * check only because its `try` really does suspend. The idiom does not transfer on its own.
 *
 * Be exact about what the [NonCancellable] at the cleanup site buys, because the obvious reading is
 * wrong: [release] is a plain `(T?) -> Unit`, so it has no suspension point and cannot
 * itself be cancelled — `release(built)` on its own line would behave identically. What
 * [NonCancellable] is load-bearing *for* is the `withContext` around it: on the cancellation path
 * this coroutine is already cancelled, and any other `withContext` would throw before running its
 * block at all. So the pairing is all-or-nothing — keep both or drop both; dropping only
 * [NonCancellable] reintroduces the leak.
 *
 * Top-level and `internal` so the cancellation contract is JVM-testable: the enclosing `open` needs
 * a `ContentResolver` and a real `ParcelFileDescriptor`, neither of which exists on the unit-test
 * classpath.
 */
internal suspend fun <T> openOrRelease(
    build: suspend () -> T,
    release: (T?) -> Unit,
): T {
    var committed = false
    // Nullable independently of [T], because "build has not run yet" is a third state the result type
    // does not have to be able to express: [release] is reachable from the cancellation path before
    // `build` returns, and on that path there is nothing to free.
    var built: T? = null
    try {
        val result = build()
        built = result
        currentCoroutineContext().ensureActive()
        committed = true
        return result
    } finally {
        if (!committed) {
            withContext(NonCancellable) { release(built) }
        }
    }
}
