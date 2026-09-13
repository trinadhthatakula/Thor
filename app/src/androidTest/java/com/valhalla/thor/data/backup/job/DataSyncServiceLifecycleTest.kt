// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.data.backup.service.DataSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataSyncServiceLifecycleTest {

    @Test
    @SuppressLint("InlinedApi", "NewApi")
    fun serviceIsFrameworkConstructibleNonBindingAndDataSyncTyped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = DataSyncService()

        assertNull(service.onBind(Intent()))
        service.onTaskRemoved(Intent())

        val info = context.packageManager.getServiceInfo(
            ComponentName(context, DataSyncService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals(false, info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }
}
