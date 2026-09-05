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
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.milliseconds

/** Foreground shell for the serial Room-backed archive and single-app export lane. */
class DataSyncService : Service(), KoinComponent {
    private val coordinator: DataSyncCoordinator by inject()
    private val dataTaskDao: DataTaskDao by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))
    private val generationFence = DataSyncServiceGenerationFence()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            val mayDiscoverCurrentTask = generationFence.onBlockedStart(startId)
            persistBlockedStartAndStop(
                taskId = intent.taskIdOrNull(),
                state = promotion,
                startId = startId,
                mayDiscoverCurrentTask = mayDiscoverCurrentTask,
            )
            return START_NOT_STICKY
        }
        if (!generationFence.onPromotedStart(startId)) {
            persistBlockedStartAndStop(
                taskId = intent.taskIdOrNull(),
                state = ForegroundNotificationState.Invalid("previous generation is timing out"),
                startId = startId,
                mayDiscoverCurrentTask = false,
            )
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
                generationFence.onDrained(startId)?.let(::finishGeneration)
            },
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        if (generationFence.onTimeoutStarted(startId)) {
            val application = applicationContext as ThorApplication
            application.launchInApplicationScope(ioDispatcher) {
                boundedDataSyncTimeoutUnwind(
                    timeoutMillis = TIMEOUT_UNWIND_MILLIS,
                    settle = coordinator::stopClaimsAndInterrupt,
                    finish = {
                        val stopped = finishGeneration(startId)
                        val followUpStartId = generationFence.onTimeoutFinished(startId)
                        if (!stopped && followUpStartId != null && followUpStartId != startId) {
                            finishGeneration(followUpStartId)
                        }
                    },
                )
            }
        }
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
        mayDiscoverCurrentTask: Boolean,
    ) {
        val application = applicationContext as ThorApplication
        application.launchInApplicationScope(ioDispatcher) {
            boundedDataSyncPromotionFailure(
                timeoutMillis = PROMOTION_FAILURE_UNWIND_MILLIS,
                persist = {
                    if (taskId != null || mayDiscoverCurrentTask) {
                        DataTaskStore(dataTaskDao).blockCurrentStart(
                            taskId = taskId,
                            blockedState = if (state === ForegroundNotificationState.Blocked) {
                                DataTaskState.START_BLOCKED_NOTIFICATION
                            } else {
                                DataTaskState.START_BLOCKED
                            },
                            nowMs = System.currentTimeMillis(),
                        )
                    }
                },
                finish = {
                    generationFence.onBlockedPersistenceFinished(startId)?.let(::finishGeneration)
                },
            )
        }
    }

    private fun finishGeneration(startId: Int): Boolean = finishDataSyncServiceGeneration(
        startId = startId,
        stopSelfResult = ::stopSelfResult,
        removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
    )

    companion object {
        private const val EXTRA_TASK_ID = "task_id"
        private const val TIMEOUT_UNWIND_MILLIS = 3_000L
        private const val PROMOTION_FAILURE_UNWIND_MILLIS = 2_000L
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

internal class DataSyncServiceGenerationFence {
    private val lock = Any()
    private var latestStartId = 0
    private var activeStartId: Int? = null
    private var timeoutStartId: Int? = null
    private var finishIssuedThrough = 0
    private val blockedPersistence = mutableSetOf<Int>()

    /** Returns false when a timeout generation already owns teardown. */
    fun onPromotedStart(startId: Int): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        if (timeoutStartId != null) {
            blockedPersistence += startId
            false
        } else {
            activeStartId = startId
            true
        }
    }

    /** Returns whether a sticky null intent may safely discover the current actionable row. */
    fun onBlockedStart(startId: Int): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        blockedPersistence += startId
        activeStartId == null && timeoutStartId == null
    }

    fun onTimeoutStarted(startId: Int): Boolean = synchronized(lock) {
        if (timeoutStartId != null || activeStartId != startId) return@synchronized false
        timeoutStartId = startId
        true
    }

    fun onDrained(startId: Int): Int? = synchronized(lock) {
        if (activeStartId != startId || timeoutStartId != null) return@synchronized null
        activeStartId = null
        stopCandidateLocked()
    }

    fun onTimeoutFinished(startId: Int): Int? = synchronized(lock) {
        if (timeoutStartId != startId) return@synchronized null
        timeoutStartId = null
        if (activeStartId == startId) activeStartId = null
        stopCandidateLocked()
    }

    fun onBlockedPersistenceFinished(startId: Int): Int? = synchronized(lock) {
        if (!blockedPersistence.remove(startId)) return@synchronized null
        stopCandidateLocked()
    }

    private fun stopCandidateLocked(): Int? {
        if (
            activeStartId != null ||
            timeoutStartId != null ||
            blockedPersistence.isNotEmpty() ||
            latestStartId <= finishIssuedThrough
        ) {
            return null
        }
        finishIssuedThrough = latestStartId
        return latestStartId
    }
}

internal suspend fun boundedDataSyncPromotionFailure(
    timeoutMillis: Long,
    persist: suspend () -> Unit,
    finish: () -> Unit,
) {
    try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            try {
                persist()
            } catch (_: Exception) {
                // Promotion already failed; shutdown must not depend on Room availability.
            }
        }
    } finally {
        finish()
    }
}

internal suspend fun boundedDataSyncTimeoutUnwind(
    timeoutMillis: Long,
    settle: suspend () -> Unit,
    finish: () -> Unit,
) {
    try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            try {
                settle()
            } catch (_: Exception) {
                // Shutdown must proceed when Room or cleanup infrastructure is unavailable.
            }
        }
    } finally {
        finish()
    }
}

internal fun finishDataSyncServiceGeneration(
    startId: Int,
    stopSelfResult: (Int) -> Boolean,
    removeForeground: () -> Unit,
): Boolean {
    val stopped = stopSelfResult(startId)
    if (stopped) removeForeground()
    return stopped
}

@SuppressLint("InlinedApi")
internal fun dataSyncForegroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }
