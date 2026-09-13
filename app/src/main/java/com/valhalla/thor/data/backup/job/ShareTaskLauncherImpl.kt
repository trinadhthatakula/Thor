// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppShareRequest
import com.valhalla.thor.domain.repository.ShareTaskLauncher
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

@Single(binds = [ShareTaskLauncher::class])
class ShareTaskLauncherImpl(private val acceptance: DataTaskAcceptance) : ShareTaskLauncher {
    override suspend fun startShare(taskId: UUID, request: AppShareRequest): UUID? {
        var durablyAccepted = false
        return try {
            acceptance.acceptShare(taskId, request) { durablyAccepted = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.e(TAG, "share acceptance failed for $taskId", failure)
            if (durablyAccepted) taskId else null
        }
    }

    private companion object {
        const val TAG = "ShareTaskLauncher"
    }
}
