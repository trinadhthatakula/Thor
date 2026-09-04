// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.valhalla.thor.ThorApplication
import com.valhalla.thor.data.backup.job.DataSyncCoordinator
import com.valhalla.thor.data.backup.job.evaluateDataNotificationCapability
import com.valhalla.thor.data.backup.job.DataTaskStore
import com.valhalla.thor.data.service.DATA_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.data.service.ForegroundNotificationState
import com.valhalla.thor.data.service.ForegroundServiceNotificationCapability
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.data.source.local.room.DataTaskDao
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Foreground shell for the serial Room-backed archive and single-app export lane. */
class DataSyncService : Service(), KoinComponent {
    private val coordinator: DataSyncCoordinator by inject()
    private val dataTaskDao: DataTaskDao by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))
    private var latestStartId: Int = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val notification = DataSyncServiceNotification(this)
        val promotion = evaluateDataNotificationCapability(
            ensureChannel = notification::ensureChannel,
            evaluate = {
                ForegroundServiceNotificationCapability(this).evaluateAndPromote(
                    DATA_FOREGROUND_CHANNEL_ID,
                ) {
                    ServiceCompat.startForeground(
                        this,
                        DataSyncServiceNotification.NOTIFICATION_ID,
                        notification.preparing(),
                        dataSyncForegroundServiceType(Build.VERSION.SDK_INT),
                    )
                }
            },
        )
        if (
            promotion !== ForegroundNotificationState.Available &&
            promotion !== ForegroundNotificationState.PostPermissionDenied
        ) {
            persistBlockedStartAndStop(intent.taskIdOrNull(), promotion, startId)
            return START_NOT_STICKY
        }

        running.set(true)
        coordinator.wake(
            onClaimed = { taskId, label ->
                ServiceCompat.startForeground(
                    this,
                    DataSyncServiceNotification.NOTIFICATION_ID,
                    notification.running(taskId, label),
                    dataSyncForegroundServiceType(Build.VERSION.SDK_INT),
                )
            },
            onDrained = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStartId)
            },
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        coordinator.stopClaimsAndInterrupt()
        super.onTimeout(startId, foregroundServiceType)
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    private fun persistBlockedStartAndStop(
        taskId: UUID?,
        state: ForegroundNotificationState,
        startId: Int,
    ) {
        val application = applicationContext as ThorApplication
        application.launchInApplicationScope(ioDispatcher) {
            if (taskId != null) {
                val store = DataTaskStore(dataTaskDao)
                val current = store.loadTask(taskId)?.state
                if (current == DataTaskState.QUEUED || current == DataTaskState.STAGING_SOURCE) {
                    store.compareAndSetStartBlocked(
                        taskId = taskId,
                        expectedState = current,
                        blockedState = if (state === ForegroundNotificationState.Blocked) {
                            DataTaskState.START_BLOCKED_NOTIFICATION
                        } else {
                            DataTaskState.START_BLOCKED
                        },
                        nowMs = System.currentTimeMillis(),
                    )
                }
            }
            stopSelfResult(startId)
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private val running = AtomicBoolean(false)

        val isRunning: Boolean
            get() = running.get()

        fun intent(context: Context, taskId: UUID): Intent =
            Intent(context, DataSyncService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId.toString())

        private fun Intent?.taskIdOrNull(): UUID? = this?.getStringExtra(EXTRA_TASK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

@SuppressLint("InlinedApi")
internal fun dataSyncForegroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }
