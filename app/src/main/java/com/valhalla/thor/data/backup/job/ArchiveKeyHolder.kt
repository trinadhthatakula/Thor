// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import org.koin.core.annotation.Single

/**
 * Hands a derived key from the confirm sheet to the worker, in memory only.
 *
 * **Never put a passphrase or a derived key in a `WorkRequest`'s input `Data`.** WorkManager persists
 * `Data` to its SQLite database, so that writes key material to disk in the clear and leaves it there
 * after the job is pruned.
 *
 * The consequence is deliberate: a job whose process died has no key, so it **fails** rather than
 * retrying. `Result.retry()` is forbidden in every archive worker — WorkManager would re-run in a
 * fresh process where [take] returns null, and the user would be told much later that a backup they
 * watched start had failed.
 */
@Single
class ArchiveKeyHolder {

    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun put(jobId: String, key: SecretKey) {
        keys[jobId] = key
    }

    /** Single-use: a key with no job to use it is key material held for nothing. */
    fun take(jobId: String): SecretKey? = keys.remove(jobId)

    /** For an enqueue that failed after [put] — a rejected request, a dismissed sheet. */
    fun drop(jobId: String) {
        keys.remove(jobId)
    }
}
