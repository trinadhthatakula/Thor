// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.io.OutputStream

/**
 * One archive being written, at its destination.
 *
 * Not visible under its final name until [publish]. Use it in a `try`/`finally` and call [discard]
 * on any path that is not a successful publish — an interrupted backup that leaves a plausible
 * `.thorbak` behind is worse than one that leaves nothing.
 */
interface ArchiveDestination {

    /** Where the zip goes. Closed by [publish] or [discard]; do not close it directly. */
    val output: OutputStream

    /**
     * Make the archive visible under its final name. False when it could not be promoted.
     *
     * Deliberately **not** returning the published `Uri`. Nothing downstream needs one — the
     * completion message names the destination label, not a path — and a port returning `Uri` cannot
     * be faked in a JVM test, because `android.net.Uri` throws "not mocked". That would leave the
     * backup use case's whole success path untestable in exchange for a value no caller reads.
     */
    suspend fun publish(): Boolean

    /** Delete the partial archive. Safe to call after [publish]; then it does nothing. */
    suspend fun discard()
}

/**
 * Opens a stream *at* the export destination.
 *
 * A separate port from `AppBundleFileStore`, whose every method takes an already-written `File` and
 * copies it. A `.thorbak` is as large as the app's data, so staging one in Thor's cache first would
 * double this feature's peak disk cost. The destination itself is the same one exports use —
 * `ExportTargetChoice`, the saved SAF tree or Downloads.
 */
interface AppArchiveStore {

    /**
     * @return a destination, or null when there is nowhere to write — no SAF tree and no writable
     *   Downloads. Callers surface that as "choose a folder", never as a failed backup.
     */
    suspend fun openArchive(fileName: String): ArchiveDestination?

    /** Human-readable destination, for the confirm sheet. Mirrors `AppBundleFileStore`'s. */
    suspend fun currentTargetLabel(): String

    /**
     * Delete the named `.part` containers from wherever this store writes.
     *
     * @param names exact file names, from `PartialArchiveLedger`. Never a pattern: this store writes
     *   into a folder the user chose, and §10 is explicit that the sweep must not guess.
     * @return the subset actually removed. A name that could not be deleted — an unmounted volume, a
     *   revoked SAF grant — stays in the ledger for the next launch rather than being forgotten with
     *   the file still there.
     */
    suspend fun discardOrphans(names: Set<String>): Set<String>
}
