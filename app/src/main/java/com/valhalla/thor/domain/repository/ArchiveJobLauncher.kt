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
    data object Cancelled : ThorJobStatus

    /** No such job — WorkManager prunes finished work, so this is the normal answer for an old id. */
    data object Gone : ThorJobStatus
}

/**
 * Start an archive job and watch it.
 *
 * A port because the implementation calls `WorkManager.getInstance(context)`, which would put every
 * consumer beyond the reach of a JVM test — the same reason `AppShortcutController` exists next to
 * `FreezerShortcutManager`. The whole surface is "enqueue this, and tell me how it goes".
 */
interface ArchiveJobLauncher {

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

    fun status(jobId: UUID): Flow<ThorJobStatus>

    /**
     * The id of an unfinished job for this kind and target, or null.
     *
     * How a screen reattaches after a rotation and how a second tap is refused. Backed by
     * `jobTag(kind, target)`, since every job shares one chain name.
     */
    fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?>
}
