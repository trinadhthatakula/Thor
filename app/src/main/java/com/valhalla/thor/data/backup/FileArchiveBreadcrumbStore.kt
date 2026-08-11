// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One small JSON file in `filesDir`.
 *
 * `filesDir`, **not** `cacheDir`: the platform evicts cache under pressure, and a breadcrumb that can
 * vanish is a breadcrumb that lies. DataStore would also work and would be the house style for
 * preferences, but this is not a preference — it is a flag that has to survive a process kill during
 * a multi-gigabyte write, and one atomic-ish file write is easier to reason about than a coroutine
 * flushing an unrelated store.
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

    override suspend fun write(packageName: String, appLabel: String) {
        runCatching {
            directory.mkdirs()
            file.writeText(json.encodeToString(Stored(packageName, appLabel, System.currentTimeMillis())))
        }
    }

    override suspend fun read(): ArchiveBreadcrumb? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<Stored>(file.readText())
        }.fold(
            onSuccess = { ArchiveBreadcrumb(it.packageName, it.appLabel, it.startedAt) },
            onFailure = {
                // A truncated write — the process died mid-`write`. Left in place it would report an
                // interrupted restore of a package Thor cannot name, on every launch, forever.
                file.delete()
                null
            },
        )
    }

    override suspend fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "restore-in-progress.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
