// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.data.freezer.PrivilegeSweepTargetResolver
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Like share submission, admission and navigation settlement outlive the presenting ViewModel. */
@Single
class ProfileSubmissionCoordinator(
    private val resolver: PrivilegeSweepTargetResolver,
    private val controller: PrivilegeSweepController,
    private val navigation: TaskNavigationTargets,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun submit(provisionalTaskId: UUID, request: BulkRequest) {
        scope.launch {
            val result = try {
                controller.launch(provisionalTaskId, resolver.resolve(request, PrivilegeSweepSource.PROFILE))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("ProfileSubmission", "profile sweep launch failed", failure)
                null
            }
            when (result) {
                is PrivilegeSweepLaunchResult.Accepted ->
                    navigation.requestAccepted(provisionalTaskId, result.requestId)
                is PrivilegeSweepLaunchResult.Rejected, null ->
                    navigation.requestRejected(provisionalTaskId)
            }
        }
    }
}
