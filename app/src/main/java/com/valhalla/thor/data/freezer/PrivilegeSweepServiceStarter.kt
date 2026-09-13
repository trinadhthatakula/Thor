// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.valhalla.thor.data.service.ForegroundNotificationState
import com.valhalla.thor.data.service.ForegroundServiceNotificationCapability
import com.valhalla.thor.data.service.PRIVILEGED_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import java.util.UUID
import org.koin.core.annotation.Single

fun interface PrivilegeQueueWakeSignal {
    fun wake(requestId: UUID): ServiceStartResult
}

internal class PrivilegeSweepServiceStartRequester(
    private val isRunning: () -> Boolean,
    private val start: (UUID) -> Unit,
) {
    fun request(requestId: UUID): ServiceStartResult {
        val alreadyRunning = isRunning()
        start(requestId)
        return if (alreadyRunning) {
            ServiceStartResult.AlreadyRunning
        } else {
            ServiceStartResult.Requested
        }
    }
}

@Single(binds = [PrivilegeQueueWakeSignal::class])
internal class PrivilegeSweepServiceStarter(
    private val context: Context,
) : PrivilegeQueueWakeSignal {
    override fun wake(requestId: UUID): ServiceStartResult {
        val notification = PrivilegeSweepServiceNotification(context)
        val state = try {
            notification.ensureChannel()
            ForegroundServiceNotificationCapability(context)
                .currentState(PRIVILEGED_FOREGROUND_CHANNEL_ID)
        } catch (_: Exception) {
            ForegroundNotificationState.Invalid("notification capability unavailable")
        }
        return when (state) {
            ForegroundNotificationState.Blocked ->
                ServiceStartResult.Rejected(ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED)
            is ForegroundNotificationState.Invalid ->
                ServiceStartResult.Rejected(ServiceStartFailure.START_REQUEST_FAILED)
            ForegroundNotificationState.Available,
            ForegroundNotificationState.PostPermissionDenied,
                -> requestStart(requestId)
        }
    }

    private fun requestStart(requestId: UUID): ServiceStartResult = try {
        PrivilegeSweepServiceStartRequester(
            isRunning = { PrivilegeSweepService.isRunning },
            start = { id ->
                ContextCompat.startForegroundService(
                    context,
                    PrivilegeSweepService.intent(context, id),
                )
            },
        ).request(requestId)
    } catch (_: SecurityException) {
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
