// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.util.Logger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
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
 * **Crash-consistent to the same rule as [FileArchiveBreadcrumbStore], and for a sharper reason.**
 * The write lands on [TEMP_FILE_NAME] and is renamed into place, because `writeText` truncates
 * before it writes and a kill inside that window leaves a file that parses as nothing — which throws
 * away the names of *every* `.part` container in flight at once, not one. `rename(2)` within a
 * directory is atomic, so a reader sees the previous ledger or the new one and never half of either.
 *
 * A read that fails is likewise **not** treated as a ledger that is corrupt: an [IOException] leaves
 * the file alone and refuses the write on top of it, because the alternative is a transient hiccup
 * causing a read of "no names", an `add` of one, and a rewritten ledger that has forgotten the rest.
 * Only a [SerializationException] — a file that is there and is not a ledger — is deleted.
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
    private val temp: File get() = File(directory, TEMP_FILE_NAME)

    /**
     * @return true when [name] is now recorded. **False means the sweep will never find this
     *   container** — the caller has just opened a `.part` whose name nothing remembers — so it is a
     *   return value and not a `Unit`, even though today's call sites only log it.
     */
    suspend fun add(name: String): Boolean = mutex.withLock {
        val current = read() ?: return@withLock refuse("record $name in")
        write(current + name)
    }

    /** @return true when [name] is no longer recorded; false leaves the sweep retrying it next launch. */
    suspend fun forget(name: String): Boolean = mutex.withLock {
        val current = read() ?: return@withLock refuse("remove $name from")
        write(current - name)
    }

    /**
     * The recorded names, or empty.
     *
     * Empty is also the answer for a ledger that could not be read, which is the safe direction here:
     * the sweep deletes files in a folder the *user* chose, so "offer nothing" costs a strand and
     * "guess" costs somebody else's file.
     */
    suspend fun names(): Set<String> = mutex.withLock { read().orEmpty() }

    /**
     * The recorded names, or **null when the file could not be read** — which is not the same as
     * "no names" and must not be written on top of.
     *
     * Callers hold [mutex]; nothing here is safe outside it.
     */
    private fun read(): Set<String>? {
        // Before the `exists` check, because "no ledger, one temp" is exactly what a kill between the
        // write and the rename leaves. Deleting it here is safe and deleting it anywhere else is not:
        // every path that touches this file holds `mutex`, so no write can be part-way through its
        // own temp while this runs.
        temp.delete()
        if (!file.exists()) return emptySet()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            // Deliberately **not** deleted, and deliberately not read as an empty ledger. Both of
            // those turn one unreadable moment into the permanent loss of every recorded name — the
            // next `add` would write a fresh ledger holding only its own.
            Logger.e(TAG, "the partial-archive ledger could not be read", e)
            return null
        }
        return try {
            json.decodeFromString<Set<String>>(text)
        } catch (e: SerializationException) {
            // The file is there and is not a ledger. Left in place, every launch would try to parse
            // it again.
            //
            // Reported, not swallowed: what is being deleted here is the record of every `.part`
            // container in flight, so the files those names pointed at are now unsweepable. The
            // failure is survivable, which is exactly why it must not also be silent.
            Logger.e(TAG, "the partial-archive ledger could not be decoded; removing it", e)
            file.delete()
            emptySet()
        }
    }

    private fun refuse(what: String): Boolean {
        Logger.e(TAG, "the partial-archive ledger could not be read, so Thor did not $what it")
        return false
    }

    /** Temp file first, then a rename — see the class KDoc. Callers hold [mutex]. */
    private fun write(names: Set<String>): Boolean = runCatching {
        directory.mkdirs()
        if (names.isEmpty()) {
            // Nothing to make atomic: an absent ledger and an empty one say the same thing, and a
            // delete is already all-or-nothing.
            file.delete()
        } else {
            temp.writeText(json.encodeToString(names))
            // A rename that fails is a failed write, not a half-succeeded one — the old ledger is
            // still whole and still the answer.
            if (!temp.renameTo(file)) error("could not move $TEMP_FILE_NAME into place")
        }
        true
    }.getOrElse {
        // Same class of failure as `FileArchiveBreadcrumbStore.write`, and reported for the same
        // reason. A write that fails on `add` leaves a `.part` file nothing knows the name of, so
        // the launch sweep will never remove it; on `forget`, it leaves a name the sweep will
        // retry forever. This log is the only place either says so.
        Logger.e(TAG, "could not write the partial-archive ledger", it)
        temp.delete()
        false
    }

    companion object {
        const val FILE_NAME = "partial-archives.json"

        /** The sibling a write lands on before it is renamed into place. */
        const val TEMP_FILE_NAME = "$FILE_NAME.tmp"

        private const val TAG = "PartialArchiveLedger"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
