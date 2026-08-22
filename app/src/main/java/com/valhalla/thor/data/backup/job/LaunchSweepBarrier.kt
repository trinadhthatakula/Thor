// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

/**
 * "The launch sweep has finished" — the one thing a worker started at process start has to know
 * before it stages anything.
 *
 * `ArchiveOrphanSweeper.sweep()` runs once per process, from `ThorApplication.onCreate`, and it
 * `deleteRecursively()`s `externalCacheDir/obb_out` **wholesale**. Its stated justification is that
 * "any export — archive or share — that was still using it died with the process", and that was true
 * of every writer of that tree while every writer was a foreground operation. It stopped being true
 * the moment an export became a `CoroutineWorker`: WorkManager re-runs an interrupted worker *in the
 * next process*, and the next process is the one running the sweep. The two then start together and
 * the sweep deletes the live build's staging directory out from under it — for an OBB game, gigabytes
 * of it, halfway through the copy that took the user ten minutes the first time.
 *
 * The archive workers do not need this and are not wired to it. Both hold their key in
 * [ArchiveKeyHolder], which is process memory, so a re-run has no key and fails before it reaches the
 * bundle builder — the same property that makes them safe to re-run makes them incapable of hitting
 * this race. `AppExportWorker` is the first job on the seam that genuinely runs to completion after a
 * process death, so it is the first that can lose to the sweep.
 *
 * **Not a lock.** It is completed once, by the launch, and every later `await` returns immediately —
 * so the ordinary case (a user taps export minutes into a session) pays a resumption and nothing
 * else. Nothing re-arms it; there is exactly one launch sweep per process.
 *
 * Pure `kotlinx.coroutines` on purpose: this holds a startup-ordering rule that is worth pinning, and
 * a JVM test can reach a `CompletableDeferred` where it can never reach a `Worker` or an
 * `Application`.
 */
@Single
class LaunchSweepBarrier {

    private val swept = CompletableDeferred<Unit>()

    /**
     * The sweep is over — released or not, successfully or not.
     *
     * Called from a `finally`, which is the only placement that works. A sweep that *throws* has
     * still stopped deleting, so a worker held back from a tree nobody is touching any more is held
     * back for nothing; and the `runCatching` around the sweep swallows everything except
     * cancellation, so "it failed" is not a signal that reaches here at all.
     *
     * Idempotent — `CompletableDeferred.complete` returns false on an already-completed one and does
     * nothing else.
     */
    fun markSwept() {
        swept.complete(Unit)
    }

    /**
     * Wait for the launch sweep, and say whether it actually arrived.
     *
     * @return false on timeout, and a caller must treat that as "do not stage", not as "close
     *   enough". The window this guards is a wholesale `deleteRecursively()` on a tree the caller is
     *   about to write gigabytes into; proceeding on a timeout is the exact race the class exists to
     *   close, just with a log line in front of it.
     *
     * The timeout is not there because the sweep is slow — it is a handful of `delete()` calls and an
     * empty-ledger short-circuit, and it is normally over before the first frame. It is there because
     * the coroutine that runs it is launched on the application scope *after* a preference read, and
     * a `Flow.first()` that never emits would otherwise leave a foreground service pinned on
     * "Preparing" with no timeout anywhere in the system to end it. Two minutes is far past anything
     * a working sweep needs and far short of a user's patience for a notification that never moves.
     */
    suspend fun awaitSwept(timeoutMs: Long = SWEEP_WAIT_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { swept.await() } != null

    companion object {
        const val SWEEP_WAIT_TIMEOUT_MS = 120_000L
    }
}
