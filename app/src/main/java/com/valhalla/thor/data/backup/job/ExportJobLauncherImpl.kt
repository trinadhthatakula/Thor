// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.domain.repository.ThorJobWatcher
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.ServiceQueueEvent
import com.valhalla.thor.util.ServiceQueueLatencyProbe
import com.valhalla.thor.util.ServiceQueueOperation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

/**
 * Starts a single-app export by accepting it into the Room-backed data queue.
 *
 * The watch half remains delegated to [ThorJobWatcher], which combines active Room tasks with
 * persisted legacy WorkManager work while the released data chain drains.
 */
@Single(binds = [ExportJobLauncher::class])
class ExportJobLauncherImpl(
    private val acceptance: DataTaskAcceptance,
    watcher: ThorJobWatcher,
) : ExportJobLauncher, ThorJobWatcher by watcher {

    /**
     * @param request already resolved — its destination came from `ExportAppUseCase.openSession` on
     *   the foreground, at tap time. Nothing here re-reads a preference, so a job enqueued now and run
     *   an hour later writes where the user was told it would.
     *
     * The task's durable kind and target key let the shared watcher suppress duplicate submissions
     * even after process recreation.
     */
    override suspend fun startExport(request: AppExportRequest): UUID? {
        val taskId = try {
            acceptance.acceptExport(request) {
                ServiceQueueLatencyProbe.mark(
                    ServiceQueueOperation.EXPORT,
                    ServiceQueueEvent.DURABLE_ACCEPTED,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.e(TAG, "export acceptance failed for ${request.packageName}", failure)
            return null
        }
        return taskId
    }

    private companion object {
        const val TAG = "ExportJobLauncher"
    }
}
