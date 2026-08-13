// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.jobTag
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.ThorJobWatcher
import java.util.UUID
import org.koin.core.annotation.Single

/**
 * The one place a Thor export job is started.
 *
 * Almost nothing, which is the point of it being separate from [ThorJobLauncher]: the whole reason
 * that class is long is key derivation and the ordering rules around [ArchiveKeyHolder], and an
 * export has neither. What is left is the shared [enqueueUniqueJob] — which is `internal` and
 * top-level precisely so this could reuse it rather than grow a second, subtly different, awaited
 * `Operation`.
 *
 * `onAbandoned` is left at its default for the same reason: an export that never reaches the database
 * has nothing held in memory to release. The caller's null return is the entire cleanup.
 *
 * [THOR_JOB_CHAIN], not [com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN]: an export moves bytes, and
 * the chain's argument is about disk. Serialising it behind a running backup is the intended
 * behaviour — the two would otherwise stage a multi-gigabyte bundle each, at once, on the same
 * volume.
 *
 * The watch half is delegated rather than reimplemented. `status` and `runningJobFor` are written
 * against [ThorJobKind] and a job id and contain nothing archive-specific; a second copy here would
 * be a second place for `WorkInfo.State` to be mapped, and the mapping's subtle case — a null
 * `WorkInfo` meaning "pruned", not "failed" — is exactly the kind that gets copied wrong.
 */
@Single(binds = [ExportJobLauncher::class])
class ExportJobLauncherImpl(
    private val context: Context,
    watcher: ThorJobWatcher,
) : ExportJobLauncher, ThorJobWatcher by watcher {

    /**
     * @param request already resolved — its destination came from `ExportAppUseCase.openSession` on
     *   the foreground, at tap time. Nothing here re-reads a preference, so a job enqueued now and run
     *   an hour later writes where the user was told it would.
     *
     * Tagged with [jobTag] so a screen can ask "is this app already exporting?". The chain name cannot
     * answer that — every job shares it — and without the tag `APPEND_OR_REPLACE` would happily queue
     * a second identical export behind the first on a double tap.
     */
    override suspend fun startExport(request: AppExportRequest): UUID? {
        val work = OneTimeWorkRequestBuilder<AppExportWorker>()
            .setInputData(workDataOf(*request.toMap().toList().toTypedArray()))
            .addTag(jobTag(ThorJobKind.APP_EXPORT, request.packageName))
            .build()

        return enqueueUniqueJob(context, THOR_JOB_CHAIN, work)
    }
}
