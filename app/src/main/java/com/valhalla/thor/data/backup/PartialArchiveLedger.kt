// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.util.Logger
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
                //
                // Reported, not swallowed: what is being deleted here is the record of every `.part`
                // container in flight, so the files those names pointed at are now unsweepable. The
                // failure is survivable, which is exactly why it must not also be silent.
                Logger.e(TAG, "the partial-archive ledger could not be read; removing it", it)
                file.delete()
                emptySet()
            }
    }

    private fun write(names: Set<String>) {
        runCatching {
            directory.mkdirs()
            if (names.isEmpty()) file.delete() else file.writeText(json.encodeToString(names))
        }.onFailure {
            // Same class of failure as `FileArchiveBreadcrumbStore.write`, and reported for the same
            // reason. A write that fails on `add` leaves a `.part` file nothing knows the name of, so
            // the launch sweep will never remove it; on `forget`, it leaves a name the sweep will
            // retry forever. This log is the only place either says so.
            Logger.e(TAG, "could not write the partial-archive ledger", it)
        }
    }

    companion object {
        const val FILE_NAME = "partial-archives.json"

        private const val TAG = "PartialArchiveLedger"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
