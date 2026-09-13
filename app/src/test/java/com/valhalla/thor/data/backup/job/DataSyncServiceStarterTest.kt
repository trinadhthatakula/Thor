// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import com.valhalla.thor.data.backup.service.dataSyncForegroundServiceType
import com.valhalla.thor.data.service.ForegroundNotificationState
import com.valhalla.thor.data.service.ServiceStartResult
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

@SuppressLint("InlinedApi")
class DataSyncServiceStarterTest {

    @Test
    fun `data sync foreground type is omitted below API 29`() {
        assertEquals(0, dataSyncForegroundServiceType(28))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            dataSyncForegroundServiceType(29),
        )
    }

    @Test
    fun `notification channel construction failure becomes an invalid start state`() {
        var evaluated = false

        val state = evaluateDataNotificationCapability(
            ensureChannel = { throw SecurityException("channel rejected") },
            evaluate = {
                evaluated = true
                ForegroundNotificationState.Available
            },
        )

        assertEquals(ForegroundNotificationState.Invalid("channel rejected"), state)
        assertEquals(false, evaluated)
    }

    @Test
    fun `running service still receives wake before already running is returned`() {
        val starts = mutableListOf<UUID>()
        val requester = DataSyncServiceStartRequester(
            isRunning = { true },
            start = { starts += it },
        )

        val result = requester.request(TASK_ID)

        assertEquals(listOf(TASK_ID), starts)
        assertEquals(ServiceStartResult.AlreadyRunning, result)
    }

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")
    }
}
