// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ThorJobKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Where a job got to, as a screen needs to see it. WorkManager's `WorkInfo.State` narrowed to this. */
sealed interface ThorJobStatus {
    data object Pending : ThorJobStatus
    data object Running : ThorJobStatus

    /**
     * @param warnings the sentences the worker put in `JOB_WARNINGS_KEY`, in order. Empty is the
     *   ordinary case and means "nothing to add", never "unknown".
     *
     * A class rather than an object because a job can succeed *and* have something the user has to
     * be told — a restore that placed the data but not the game data is the case this exists for.
     * WorkManager keeps output `Data` only on the terminal result, which is why this rides the
     * status rather than the progress registry.
     */
    data class Succeeded(val warnings: List<String> = emptyList()) : ThorJobStatus

    /**
     * @param reason the sentence the worker put in `JOB_ERROR_KEY`, or null.
     *
     * **Null is still a failure.** WorkManager produces a bare `Result.failure()` on some of its own
     * paths, and a UI that keys "did it fail?" off a non-null reason reports those as nothing at all.
     */
    data class Failed(val reason: String?) : ThorJobStatus

    /**
     * **Carries nothing, and cannot be made to.** The obvious want for a bulk sweep is
     * `Cancelled(done, total)` — "stopped after 7 of 20" is the only useful thing to say about a
     * stopped batch. It is not reachable from here: WorkManager's `WorkerWrapper` records CANCELLED
     * and discards the worker's returned `Result`, so there is no output `Data` on this state to read
     * counts out of. A worker that carefully assembled them would be handing them to something that
     * throws them away.
     *
     * The route that works is `ThorJobWorker.noteResult`, which posts from the worker's own `finally`
     * and so runs on the cancellation path. Partial counts are reported *beside* this status, never
     * through it.
     */
    data object Cancelled : ThorJobStatus

    /** No such job — WorkManager prunes finished work, so this is the normal answer for an old id. */
    data object Gone : ThorJobStatus
}

/**
 * Watch a Thor job. Says nothing about what kind of job it is or how it was started.
 *
 * Split out of [ArchiveJobLauncher] because *watching* is the half with no archive in it: both members
 * take a [UUID] or a [ThorJobKind] and neither mentions a passphrase, a salt or a request. A bulk
 * sweep launcher implements this and its own `start…`, and every screen that only needs to follow a
 * job — a progress row, a reattach after rotation — depends on this rather than on a launcher whose
 * other half it cannot use.
 *
 * A port for the same reason [ArchiveJobLauncher] is: the implementation calls
 * `WorkManager.getInstance(context)`, which would put every consumer beyond the reach of a JVM test.
 */
interface ThorJobWatcher {

    fun status(jobId: UUID): Flow<ThorJobStatus>

    /**
     * The id of an unfinished job for this kind and target, or null.
     *
     * How a screen reattaches after a rotation and how a second tap is refused. Backed by
     * `jobTag(kind, target)`, since a chain name carries no target.
     */
    fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?>
}

/**
 * Start an archive job and watch it.
 *
 * A port because the implementation calls `WorkManager.getInstance(context)`, which would put every
 * consumer beyond the reach of a JVM test — the same reason `AppShortcutController` exists next to
 * `FreezerShortcutManager`. The whole surface is "enqueue this, and tell me how it goes"; the second
 * half of that sentence is [ThorJobWatcher] and is not archive-specific.
 */
interface ArchiveJobLauncher : ThorJobWatcher {

    /** @param passphrase not cleared here; the caller owns it. @return the job id, or null if it could not be enqueued. */
    suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID?

    /**
     * @param salt the archive's own, from its header — never a freshly generated one.
     * @param iterations the archive's own KDF round count, from the same header as [salt]. **Not a
     *   convenience parameter and not one to default.** `AppArchiveCipher.deriveKey` defaults to this
     *   build's constant, and taking that default here derives a different key for every archive a
     *   different Thor wrote — from the correct passphrase. The failure surfaces at the first GCM tag,
     *   which reads as "this backup is damaged" and sends the user to check their file rather than
     *   their Thor version. Salt and rounds travel together because a key is only ever right for both.
     */
    suspend fun startRestore(
        request: ArchiveRestoreRequest,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): UUID?
}
