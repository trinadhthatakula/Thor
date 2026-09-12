// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.valhalla.thor.ThorApplication
import com.valhalla.thor.data.service.ForegroundNotificationState
import com.valhalla.thor.data.service.observeQueueNotifications
import com.valhalla.thor.data.repository.toQueuedSummary
import com.valhalla.thor.data.service.ForegroundServiceNotificationCapability
import com.valhalla.thor.data.service.PRIVILEGED_FOREGROUND_CHANNEL_ID
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Foreground shell for the serial Room-backed privilege queue. */
class PrivilegeSweepService : Service(), KoinComponent {
    private val coordinatorDelegate = inject<PrivilegeSweepDrainCoordinator>()
    private val coordinator by coordinatorDelegate
    private val notificationLock = Any()
    private var activeNotification: Notification? = null
    @Volatile private var destroyed = false
    private val store: PrivilegeSweepStore by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))
    private val mainDispatcher: CoroutineDispatcher by inject(named("main"))
    private val generationFence = PrivilegeSweepServiceGenerationFence()
    private val activeNotificationTask = MutableStateFlow<UUID?>(null)
    private val notificationScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private var notificationObserver: Job? = null

    private fun observeNotifications(notification: PrivilegeSweepServiceNotification) {
        if (notificationObserver != null) return
        notificationObserver = notificationScope.observeQueueNotifications(
            activeNotificationTask,
            store.observeRetained().map { rows -> rows.map { it.toQueuedSummary() } },
        ) { snapshot ->
            synchronized(notificationLock) {
                if (!destroyed && activeNotificationTask.value == snapshot.task.taskId) {
                    ForegroundServiceNotificationCapability(this).evaluateAndPromote(PRIVILEGED_FOREGROUND_CHANNEL_ID) {
                        val updated = notification.running(snapshot)
                        ServiceCompat.startForeground(this, PrivilegeSweepServiceNotification.NOTIFICATION_ID,
                            updated, privilegeForegroundServiceType(Build.VERSION.SDK_INT))
                        activeNotification = updated
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = PrivilegeSweepServiceNotification(this)
        val promotion = synchronized(notificationLock) { try {
            notification.ensureChannel()
            ForegroundServiceNotificationCapability(this).evaluateAndPromote(
                PRIVILEGED_FOREGROUND_CHANNEL_ID,
            ) {
                ServiceCompat.startForeground(
                    this,
                    PrivilegeSweepServiceNotification.NOTIFICATION_ID,
                    activeNotification ?: notification.preparing(),
                    privilegeForegroundServiceType(Build.VERSION.SDK_INT),
                )
            }
        } catch (failure: Exception) {
            ForegroundNotificationState.Invalid(
                failure.message ?: failure.javaClass.simpleName
            )
        } }
        if (
            promotion !== ForegroundNotificationState.Available &&
            promotion !== ForegroundNotificationState.PostPermissionDenied
        ) {
            val mayDiscoverCurrentRequest = generationFence.onBlockedStart(startId)
            persistBlockedStartAndStop(
                requestId = intent.requestIdOrNull(),
                state = promotion,
                startId = startId,
                mayDiscoverCurrentRequest = mayDiscoverCurrentRequest,
            )
            return START_NOT_STICKY
        }
        if (!generationFence.onPromotedStart(startId)) {
            persistBlockedStartAndStop(
                requestId = intent.requestIdOrNull(),
                state = ForegroundNotificationState.Invalid("blocked start is settling"),
                startId = startId,
                mayDiscoverCurrentRequest = false,
            )
            return START_NOT_STICKY
        }

        running.set(true)
        observeNotifications(notification)
        coordinator.wake(
            onClaimed = { requestId, packageName ->
                synchronized(notificationLock) {
                    if (!destroyed) {
                        val runningNotification = notification.running(requestId, packageName)
                        ServiceCompat.startForeground(
                            this, PrivilegeSweepServiceNotification.NOTIFICATION_ID,
                            runningNotification, privilegeForegroundServiceType(Build.VERSION.SDK_INT),
                        )
                        activeNotificationTask.value = requestId
                        activeNotification = runningNotification
                    }
                }
            },
            onDrained = { finishDrainedGeneration(startId) },
            onAborted = { finishAbortedGeneration(startId) },
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(notificationLock) {
            destroyed = true
            activeNotificationTask.value = null
            activeNotification = null
            notificationObserver?.cancel()
        }
        if (notificationObserver != null) notificationScope.cancel()
        if (coordinatorDelegate.isInitialized()) coordinator.shutdown()
        running.set(false)
        super.onDestroy()
    }

    private fun persistBlockedStartAndStop(
        requestId: UUID?,
        state: ForegroundNotificationState,
        startId: Int,
        mayDiscoverCurrentRequest: Boolean,
    ) {
        val application = applicationContext as ThorApplication
        application.launchInApplicationScope(ioDispatcher) {
            try {
                withTimeoutOrNull(PROMOTION_FAILURE_UNWIND_MILLIS) {
                    if (generationFence.beginBlockedPersistence(startId)) {
                        val selectedRequestId = requestId ?: if (mayDiscoverCurrentRequest) {
                            store.observeRetained().first()
                                .firstOrNull {
                                    it.terminalState == null &&
                                            it.requestState == PrivilegeSweepRequestState.QUEUED
                                }
                                ?.requestId
                        } else {
                            null
                        }
                        selectedRequestId?.let { id ->
                            store.markUnclaimedStartBlocked(
                                requestId = id,
                                reason = if (state === ForegroundNotificationState.Blocked) {
                                    PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION
                                } else {
                                    PrivilegeSweepBlockReason.START_BLOCKED
                                },
                                nowMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Promotion already failed; shutdown cannot depend on Room availability.
            } finally {
                withContext(mainDispatcher) {
                    generationFence.onBlockedPersistenceFinished(startId)
                        ?.let(::finishGeneration)
                }
            }
        }
    }

    private fun finishDrainedGeneration(startId: Int) {
        val application = applicationContext as ThorApplication
        application.launchInApplicationScope(mainDispatcher) {
            if (!destroyed) generationFence.onDrained(startId)?.let(::finishGeneration)
        }
    }

    private fun finishAbortedGeneration(startId: Int) {
        val application = applicationContext as ThorApplication
        application.launchInApplicationScope(mainDispatcher) {
            if (!destroyed) generationFence.onAborted(startId)?.let(::finishGeneration)
        }
    }

    private fun finishGeneration(startId: Int): Boolean {
        val stopped = stopSelfResult(startId)
        if (stopped) synchronized(notificationLock) {
            destroyed = true
            activeNotificationTask.value = null
            activeNotification = null
            notificationObserver?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        return stopped
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val PROMOTION_FAILURE_UNWIND_MILLIS = 2_000L
        private val running = AtomicBoolean(false)

        val isRunning: Boolean
            get() = running.get()

        fun intent(context: Context, requestId: UUID): Intent =
            Intent(context, PrivilegeSweepService::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId.toString())

        internal fun Intent?.requestIdOrNull(): UUID? = this?.getStringExtra(EXTRA_REQUEST_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

internal class PrivilegeSweepServiceGenerationFence {
    private val lock = Any()
    private var latestStartId = 0
    private var activeStartId: Int? = null
    private var finishIssuedThrough = 0
    private val blockedStarts = mutableSetOf<Int>()
    private val blockedReservations = mutableSetOf<Int>()

    fun onPromotedStart(startId: Int): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        if (blockedReservations.isNotEmpty()) {
            blockedStarts += startId
            false
        } else {
            activeStartId = startId
            true
        }
    }

    fun onBlockedStart(startId: Int): Boolean = synchronized(lock) {
        latestStartId = maxOf(latestStartId, startId)
        blockedStarts += startId
        activeStartId == null && blockedReservations.isEmpty()
    }

    fun beginBlockedPersistence(startId: Int): Boolean = synchronized(lock) {
        if (startId !in blockedStarts || latestStartId != startId || activeStartId != null) {
            return@synchronized false
        }
        blockedReservations += startId
        true
    }

    fun onDrained(startId: Int): Int? = synchronized(lock) {
        if (activeStartId != startId) return@synchronized null
        activeStartId = null
        stopCandidateLocked()
    }

    fun onAborted(startId: Int): Int? = synchronized(lock) {
        if (activeStartId != startId) return@synchronized null
        activeStartId = null
        stopCandidateLocked()
    }

    fun onBlockedPersistenceFinished(startId: Int): Int? = synchronized(lock) {
        blockedReservations.remove(startId)
        if (!blockedStarts.remove(startId)) return@synchronized null
        stopCandidateLocked()
    }

    private fun stopCandidateLocked(): Int? {
        if (
            activeStartId != null || blockedStarts.isNotEmpty() ||
            latestStartId <= finishIssuedThrough
        ) {
            return null
        }
        finishIssuedThrough = latestStartId
        return latestStartId
    }
}

@SuppressLint("InlinedApi")
internal fun privilegeForegroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        0
    }
