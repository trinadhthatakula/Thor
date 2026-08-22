// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.util.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "ArchiveKeyHolder"

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
 *
 * **Every entry expires.** [ThorJobWorker]'s `finally` drops the key on every path `doWork` can reach
 * and [ThorJobLauncher.cancel] covers an explicit user cancel, but neither covers a job that never
 * reaches `doWork` at all. `beginUniqueWork(…, APPEND_OR_REPLACE, …)` appends a second request as a
 * **dependent** of the one already queued, and WorkManager cancels the dependents of a prerequisite
 * that returns `Result.failure()`. So a backup that fails — a wrong passphrase, a full disk — takes
 * the restore queued behind it with it: `doWork` is never called, no `finally` runs, and without the
 * timer below that job's derived key would sit in this map for the lifetime of the process.
 *
 * @param dispatcher used for nothing but the expiry timer. Injected rather than hardcoded so the
 *   expiry can be pinned on the JVM with a `TestDispatcher`'s virtual clock — which is why this
 *   mechanism was chosen over observing the terminal `WorkInfo`, a path that needs an instrumented
 *   WorkManager and so could not be pinned by a unit test at all.
 */
@Single
class ArchiveKeyHolder(
    @Named("default") dispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val keys = ConcurrentHashMap<String, Held>()

    /** A key and the timer that removes it if nothing else does. */
    private class Held(val key: SecretKey, val expiry: Job)

    fun put(jobId: String, key: SecretKey) {
        val expiry = scope.launch {
            delay(KEY_LIFETIME_MS)
            if (keys.remove(jobId) != null) {
                // Worth a line: the job this belonged to will now fail with "its key is no longer in
                // memory", and this is the only place that can say why.
                Logger.w(TAG, "dropped an unused key for $jobId after ${KEY_LIFETIME_MS}ms")
            }
        }
        // A second put under the same id replaces the entry, so the entry it replaced must take its
        // timer with it — otherwise the old timer fires later and removes the *new* key.
        keys.put(jobId, Held(key, expiry))?.expiry?.cancel()
    }

    /** Single-use: a key with no job to use it is key material held for nothing. */
    fun take(jobId: String): SecretKey? = keys.remove(jobId)?.let { held ->
        held.expiry.cancel()
        held.key
    }

    /** For an enqueue that failed after [put] — a rejected request, a dismissed sheet. */
    fun drop(jobId: String) {
        keys.remove(jobId)?.expiry?.cancel()
    }

    companion object {
        /**
         * How long a key may wait for the job that will use it.
         *
         * It has to cover **queue wait only**, never run time: the worker `take`s its key in the first
         * lines of `runJob`, so a job that has started has already emptied its entry. An hour is far
         * longer than a job spends waiting behind another on the single unique chain, and a job that
         * somehow waits longer fails with the message it already has — "start it again" — rather than
         * running. That is the safe direction for a wait nobody is watching.
         */
        const val KEY_LIFETIME_MS = 60L * 60L * 1000L
    }
}
