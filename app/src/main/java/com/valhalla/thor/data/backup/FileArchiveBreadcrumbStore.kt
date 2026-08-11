// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.util.Logger
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
 */
class FileArchiveBreadcrumbStore(private val directory: File) : ArchiveBreadcrumbStore {

    private val file: File get() = File(directory, FILE_NAME)

    @Serializable
    private data class Stored(val packageName: String, val appLabel: String, val startedAt: Long)

    override suspend fun write(packageName: String, appLabel: String): Boolean {
        val temp = File(directory, TEMP_FILE_NAME)
        return runCatching {
            directory.mkdirs()
            temp.writeText(json.encodeToString(Stored(packageName, appLabel, System.currentTimeMillis())))
            // Renamed rather than written straight into `file`: see the class KDoc. A rename that
            // fails is a failed write, not a half-succeeded one.
            if (!temp.renameTo(file)) error("could not move $TEMP_FILE_NAME into place")
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

    override suspend fun read(): ArchiveBreadcrumb? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            // Deliberately **not** deleted. A read that failed is not a breadcrumb that is corrupt,
            // and erasing on a transient hiccup throws away the one record that this app's data may
            // be half-replaced.
            Logger.e(TAG, "could not read the restore breadcrumb", e)
            return null
        }
        return try {
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
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "restore-in-progress.json"

        /** The sibling a write lands on before it is renamed into place. */
        const val TEMP_FILE_NAME = "$FILE_NAME.tmp"

        private const val TAG = "ArchiveBreadcrumb"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
