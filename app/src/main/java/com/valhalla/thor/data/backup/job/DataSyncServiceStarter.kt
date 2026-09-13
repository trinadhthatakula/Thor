// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.valhalla.thor.data.backup.service.DataSyncService
import com.valhalla.thor.data.backup.service.DataSyncServiceNotification
import com.valhalla.thor.data.service.DATA_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.ForegroundNotificationState
import com.valhalla.thor.data.service.ForegroundServiceNotificationCapability
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import java.util.UUID
import org.koin.core.annotation.Single

internal class DataSyncServiceStartRequester(
    private val isRunning: () -> Boolean,
    private val start: (UUID) -> Unit,
) {
    fun request(taskId: UUID): ServiceStartResult {
        val alreadyRunning = isRunning()
        start(taskId)
        return if (alreadyRunning) {
            ServiceStartResult.AlreadyRunning
        } else {
            ServiceStartResult.Requested
        }
    }
}

internal fun evaluateDataNotificationCapability(
    ensureChannel: () -> Unit,
    evaluate: () -> ForegroundNotificationState,
): ForegroundNotificationState = try {
    ensureChannel()
    evaluate()
} catch (failure: Exception) {
    ForegroundNotificationState.Invalid(failure.message ?: failure.javaClass.simpleName)
}

/** Requests a service wake without treating a successful start request as successful promotion. */
@Single
class DataSyncServiceStarter(
    private val context: Context,
) : DataQueueWakeSignal {
    override fun wake(taskId: UUID): ServiceStartResult {
        val notifications = DataSyncServiceNotification(context)
        val notificationState = evaluateDataNotificationCapability(
            ensureChannel = notifications::ensureChannel,
            evaluate = {
                ForegroundServiceNotificationCapability(context)
                    .currentState(DATA_FOREGROUND_CHANNEL_ID)
            },
        )
        return when (notificationState) {
            ForegroundNotificationState.Blocked ->
                ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)

            is ForegroundNotificationState.Invalid ->
                ServiceStartResult.Rejected(ServiceStartFailure.START_REQUEST_FAILED)

            ForegroundNotificationState.Available,
            ForegroundNotificationState.PostPermissionDenied,
                -> requestStart(taskId)
        }
    }

    private fun requestStart(taskId: UUID): ServiceStartResult = try {
        DataSyncServiceStartRequester(
            isRunning = { DataSyncService.isRunning },
            start = { requestedTaskId ->
                ContextCompat.startForegroundService(
                    context,
                    DataSyncService.intent(context, requestedTaskId),
                )
            },
        ).request(taskId)
    } catch (failure: SecurityException) {
        ServiceStartResult.Rejected(ServiceStartFailure.SECURITY_EXCEPTION)
    } catch (failure: IllegalStateException) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            failure is ForegroundServiceStartNotAllowedException
        ) {
            ServiceStartResult.Rejected(ServiceStartFailure.BACKGROUND_START_NOT_ALLOWED)
        } else {
            ServiceStartResult.Rejected(ServiceStartFailure.START_REQUEST_FAILED)
        }
    }
}
