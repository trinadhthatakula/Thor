// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import com.valhalla.thor.domain.model.AppShareRequest
import com.valhalla.thor.domain.repository.ShareTaskLauncher
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Owns admission after the foreground selection or its ViewModel has been dismissed. */
@Single
class ShareSubmissionCoordinator(
    private val launcher: ShareTaskLauncher,
    private val taskNavigationTargets: TaskNavigationTargets,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun submit(taskId: UUID, request: AppShareRequest): Deferred<UUID?> {
        // Snapshot the selection before returning control to the sheet. The observer cannot cancel admission.
        val selection = request.copy(targets = request.targets.toList())
        val result = CompletableDeferred<UUID?>()
        scope.launch {
            val accepted = try {
                launcher.startShare(taskId, selection)
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (accepted == null) taskNavigationTargets.requestRejected(taskId)
            else taskNavigationTargets.requestAccepted(taskId, accepted)
            result.complete(accepted)
        }
        return result
    }
}
