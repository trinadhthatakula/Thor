// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * One small JSON file in `filesDir`.
 *
 * `filesDir`, **not** `cacheDir`: the platform evicts cache under pressure, and a breadcrumb that can
 * vanish is a breadcrumb that lies. DataStore would also work and would be the house style for
 * preferences, but this is not a preference — it is a flag that has to survive a process kill during
 * a multi-gigabyte write, and one file write is easier to reason about than a coroutine flushing an
 * unrelated store.
 *
 * **The write lands on a sibling and is renamed into place.** `writeText` truncates before it writes,
 * so a kill inside the breadcrumb's *own* write would leave a partial file — which [read] then deletes,
 * and "no breadcrumb" is indistinguishable from "nothing was interrupted". `rename(2)` within one
 * directory is atomic, so a reader sees either the previous breadcrumb or the new one, never half of
 * either.
 *
 * **Not `@Single`-annotated**, deliberately: the Koin compiler plugin binds the primary constructor,
 * there is no `File` in the graph, and `compileSafety` turns that into a build failure rather than a
 * runtime one. The binding is a `@Single` function in `di/Modules.kt` — which is what `AppModule`
 * exists for — so this class keeps a single, JVM-constructible constructor.
 *
 * @param directory `filesDir` in production; a [File] parameter rather than the `Context` so the
 *   whole class is JVM-testable.
 * @param ioDispatcher every method here touches the disk and one of them is collected from a
 *   composition, so the class relocates its own work rather than trusting each caller to.
 */
class FileArchiveBreadcrumbStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
) : ArchiveBreadcrumbStore {

    private val file: File get() = File(directory, FILE_NAME)

    /**
     * Bumped by every call that can change what [read] answers.
     *
     * An `Int` and not the breadcrumb itself: the file is the single source of truth — the launch
     * sweep and `RestoreAppArchiveUseCase` both write through this instance, and a cached value would
     * be a second one to keep honest. This only says "look again".
     */
    private val revisions = MutableStateFlow(0)

    @Serializable
    private data class Stored(val packageName: String, val appLabel: String, val startedAt: Long)

    override suspend fun write(packageName: String, appLabel: String): Boolean = withContext(ioDispatcher) {
        val temp = File(directory, TEMP_FILE_NAME)
        runCatching {
            directory.mkdirs()
            temp.writeText(json.encodeToString(Stored(packageName, appLabel, System.currentTimeMillis())))
            // Renamed rather than written straight into `file`: see the class KDoc. A rename that
            // fails is a failed write, not a half-succeeded one.
            if (!temp.renameTo(file)) error("could not move $TEMP_FILE_NAME into place")
            // After the rename, never before it: a bump is a promise that a reader will now see this
            // breadcrumb, and until the rename lands the reader sees the previous one.
            revisions.update { it + 1 }
            true
        }.getOrElse {
            // Reported, never swallowed. A silent no-op here lets the destructive phase run with no
            // notice behind it, and the next launch says nothing at all — the exact silence §8.5
            // exists to prevent.
            Logger.e(TAG, "could not write the restore breadcrumb", it)
            temp.delete()
            false
        }
    }

    override suspend fun read(): ArchiveBreadcrumb? = withContext(ioDispatcher) {
        if (!file.exists()) return@withContext null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            // Deliberately **not** deleted. A read that failed is not a breadcrumb that is corrupt,
            // and erasing on a transient hiccup throws away the one record that this app's data may
            // be half-replaced.
            Logger.e(TAG, "could not read the restore breadcrumb", e)
            return@withContext null
        }
        try {
            json.decodeFromString<Stored>(text)
                .let { ArchiveBreadcrumb(it.packageName, it.appLabel, it.startedAt) }
        } catch (e: SerializationException) {
            // The file is there but is not a breadcrumb: a write truncated by a process death, from
            // before the rename above. Left in place it would report an interrupted restore of a
            // package Thor cannot name, on every launch, forever.
            Logger.e(TAG, "the restore breadcrumb could not be decoded; removing it", e)
            file.delete()
            null
        }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) { runCatching { file.delete() } }
        // Bumped whatever `delete` returned. It returns false for a file that was not there, which is
        // not a change — but a re-read that finds the same null costs one `exists()`, and gating the
        // bump on a boolean would make "clear a breadcrumb that is already gone" the one path that
        // silently leaves a stale banner up.
        revisions.update { it + 1 }
    }

    /**
     * Re-reads the file on every bump. Cold and unshared: the one collector is a Settings section that
     * is composed for as long as a user is looking at it, and a `stateIn` would need a scope this
     * class does not have and would hold the last breadcrumb after that collector had gone.
     */
    override fun observe(): Flow<ArchiveBreadcrumb?> = revisions.map { read() }

    companion object {
        const val FILE_NAME = "restore-in-progress.json"

        /** The sibling a write lands on before it is renamed into place. */
        const val TEMP_FILE_NAME = "$FILE_NAME.tmp"

        private const val TAG = "ArchiveBreadcrumb"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
