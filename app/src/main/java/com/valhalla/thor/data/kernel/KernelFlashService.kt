// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.valhalla.thor.HomeActivity
import com.valhalla.thor.R
import com.valhalla.thor.data.service.ForegroundServiceNotificationCapability
import com.valhalla.thor.data.service.permitsExecution
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** User-started, non-restarting service. There is deliberately no stop/cancel action while writing. */
class KernelFlashService : Service(), KoinComponent {
    private val controller: KernelFlashController by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(REQUEST_ID)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.kernel_flasher), NotificationManager.IMPORTANCE_LOW,
        ))
        val result = ForegroundServiceNotificationCapability(this).evaluateAndPromote(CHANNEL) {
            val content = PendingIntent.getActivity(
                this, NOTIFICATION_ID, Intent(this, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.list_alt)
                .setContentTitle(getString(R.string.kernel_flasher))
                .setContentText(getString(R.string.kernel_running))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build()
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }
        if (!result.permitsExecution) {
            controller.startFailed(token, getString(R.string.kernel_service_blocked))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        controller.startFromService(token) {
            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val REQUEST_ID = "kernel_flash_request"
        private const val CHANNEL = "thor.kernel.flash"
        private const val NOTIFICATION_ID = 7301
    }
}
