// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The names of `.part` containers Thor has opened in the user's folder and not yet published.
 *
 * §10 requires the launch sweep to match **exact names, never a wildcard** — Thor writes into a
 * directory the user chose, which may hold anything. `ArchiveDestination.discard()` covers every
 * failure Thor survives; this exists for the one it does not, a process killed mid-write, where
 * nothing runs to clean up and nothing else remembers the name.
 *
 * `Mutex` rather than `synchronized`: `add` and `forget` are called from the worker's coroutine and
 * from `AppArchiveStoreImpl`'s `withContext(ioDispatcher)`, and a read-modify-write across a file
 * needs the whole pair held, not each half.
 *
 * **Not `@Single`-annotated**, for the same reason as `FileArchiveBreadcrumbStore`: the Koin compiler
 * plugin binds the primary constructor, there is no `File` in the graph, and `compileSafety` turns
 * that into a build failure. The binding is a `@Single` function in `di/Modules.kt`.
 *
 * @param directory `filesDir`, for the same reason [FileArchiveBreadcrumbStore] uses it: a record the
 *   platform may evict is a record that lies.
 */
class PartialArchiveLedger(private val directory: File) {

    private val mutex = Mutex()
    private val file: File get() = File(directory, FILE_NAME)

    suspend fun add(name: String) = mutex.withLock {
        write(read() + name)
    }

    suspend fun forget(name: String) = mutex.withLock {
        write(read() - name)
    }

    suspend fun names(): Set<String> = mutex.withLock { read() }

    private fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(file.readText()) }
            .getOrElse {
                // A truncated write. Left in place, every launch would try to parse it again.
                file.delete()
                emptySet()
            }
    }

    private fun write(names: Set<String>) {
        runCatching {
            directory.mkdirs()
            if (names.isEmpty()) file.delete() else file.writeText(json.encodeToString(names))
        }
    }

    companion object {
        const val FILE_NAME = "partial-archives.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
