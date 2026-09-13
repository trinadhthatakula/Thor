// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.TaskAction
import java.util.UUID

sealed interface TaskUiRoute {
    val taskId: UUID

    data class AuthenticateArchive(
        override val taskId: UUID,
        val packageName: String,
        val kind: DataTaskKind,
    ) : TaskUiRoute

    data class PickRestoreSource(
        override val taskId: UUID,
        val expectedPackageName: String,
    ) : TaskUiRoute

    data class ReviewInterruptedRestore(
        override val taskId: UUID,
        val breadcrumb: RestoreMutationBreadcrumb,
    ) : TaskUiRoute

    data class AuthorizePrivilege(
        override val taskId: UUID,
    ) : TaskUiRoute

    data class ConfirmSweepRetry(
        override val taskId: UUID,
        val targetOrdinal: Int,
        val packageName: String,
        val operation: PrivilegeSweepOperation,
    ) : TaskUiRoute

    data class SharePreparedOutputs(
        override val taskId: UUID,
        val outputIds: List<UUID>,
    ) : TaskUiRoute

    data class OpenNotificationSettings(
        override val taskId: UUID,
        val channelId: String,
    ) : TaskUiRoute
}

enum class TaskActionRejection {
    NOT_FOUND,
    INVALID_STATE,
    STALE_PROJECTION,
    AUTHORIZATION_NOT_GRANTED,
    OUTPUT_EXPIRED,
    START_REJECTED,
    OPERATION_FAILED,
}

sealed interface TaskActionDispatch {
    data object Applied : TaskActionDispatch

    data class Route(val destination: TaskUiRoute) : TaskActionDispatch

    data class Rejected(val reason: TaskActionRejection) : TaskActionDispatch
}

interface TaskActionController {
    suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch

    suspend fun submitArchivePassphrase(taskId: UUID, passphrase: CharArray): TaskActionDispatch

    suspend fun submitRestoreSource(taskId: UUID, transientSourceToken: UUID): TaskActionDispatch

    suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch

    suspend fun authorizeSweepTargetRetry(taskId: UUID, targetOrdinal: Int): TaskActionDispatch
}
