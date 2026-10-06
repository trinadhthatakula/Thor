// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.util.DefaultRootLifecycleDiagnostics
import com.valhalla.thor.util.RootLifecycleDiagnostics
import com.valhalla.thor.util.RootLifecycleEvent
import com.valhalla.thor.util.RootRefreshPhase
import com.valhalla.thor.util.recordSafely
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import com.valhalla.thor.domain.repository.RootRefreshController
import com.valhalla.thor.domain.repository.RootRefreshRequest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

enum class RootProbeOutcome { ROOT, NON_ROOT, BUSY, TIMED_OUT, FAILED }

data class RootProbeResult(val outcome: RootProbeOutcome, val failure: String? = null)

fun interface RootAvailabilityProbe {
    suspend fun observeFreshRoot(): RootProbeResult
}

/** Owns root observation and short admission decisions, without depending on any gateway. */
@Single(binds = [RootAvailabilityProvider::class, RootRefreshController::class, RootAdmissionController::class])
class RootAvailabilityCoordinator(
    private val probe: RootAvailabilityProbe,
    @Named("io") ioDispatcher: CoroutineDispatcher,
    private val diagnostics: RootLifecycleDiagnostics = DefaultRootLifecycleDiagnostics,
) : RootRefreshController, RootAdmissionController {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val mutableState = MutableStateFlow(RootAvailabilityState())
    override val state: StateFlow<RootAvailabilityState> = mutableState.asStateFlow()

    // Every field below is accessed under lock. Accepted work runs without holding this lock.
    private var inFlight: PendingRefresh? = null
    private var activeAdmissions = 0
    private var idleRetryPending = false
    private var busyResult: RootRefreshRequest? = null
    private var idleSequence = 0L

    override suspend fun awaitInitialObservation(): RootAvailabilityState {
        val attempt = synchronized(lock) {
            if (mutableState.value.hasCompletedRefresh) return mutableState.value
            inFlight ?: startRefreshLocked(allowIdleRetry = true)
        }
        return attempt.await()
    }

    override fun requestRefresh(): RootRefreshRequest = synchronized(lock) {
        inFlight ?: if (activeAdmissions > 0 && idleRetryPending) {
            requireNotNull(busyResult)
        } else {
            startRefreshLocked(allowIdleRetry = true)
        }
    }

    private fun startRefreshLocked(allowIdleRetry: Boolean): PendingRefresh {
        val completion = PendingRefresh()
        val idleSequenceAtStart = idleSequence
        idleRetryPending = false
        busyResult = null
        if (activeAdmissions > 0) {
            // Refresh must not retire a shell between an accepted operation's command/Binder steps.
            val busy = mutableState.value.copy(
                refreshStatus = RootRefreshStatus.BUSY,
                revision = mutableState.value.revision + 1,
                hasCompletedRefresh = true,
                failure = "Accepted root work is still running",
            )
            mutableState.value = busy
            recordRefresh(RootRefreshPhase.DEFERRED_ACTIVE_WORK)
            idleRetryPending = allowIdleRetry
            busyResult = completion
            completion.complete(busy)
            return completion
        }

        mutableState.value = mutableState.value.copy(
            refreshStatus = RootRefreshStatus.CHECKING,
            revision = mutableState.value.revision + 1,
            failure = null,
        )
        inFlight = completion
        recordRefresh(RootRefreshPhase.STARTED)
        scope.launch {
            val result = try {
                probe.observeFreshRoot()
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (inFlight === completion) {
                        publishResultLocked(RootProbeResult(RootProbeOutcome.FAILED, "Root refresh cancelled"), RootRefreshPhase.CANCELLED)
                        inFlight = null
                    }
                    completion.cancel(cancelled)
                }
                throw cancelled
            } catch (failure: Exception) {
                RootProbeResult(RootProbeOutcome.FAILED, failure.message)
            }
            synchronized(lock) {
                val published = publishResultLocked(result)
                inFlight = null
                idleRetryPending = allowIdleRetry && result.outcome == RootProbeOutcome.BUSY
                if (idleRetryPending) busyResult = completion
                completion.complete(published)
                // Lane idle can precede Odin's BUSY callback; retain that event until the result.
                if (idleSequence > idleSequenceAtStart) retryWhenIdleLocked()
            }
        }
        return completion
    }

    private fun publishResultLocked(
        result: RootProbeResult,
        phase: RootRefreshPhase = RootRefreshPhase.COMPLETED,
    ): RootAvailabilityState {
        val previous = mutableState.value
        val revision = previous.revision + 1
        val confirmed = when (result.outcome) {
            RootProbeOutcome.ROOT -> RootConfirmation.ROOT
            RootProbeOutcome.NON_ROOT -> RootConfirmation.NON_ROOT
            else -> null
        }
        return previous.copy(
            confirmation = confirmed ?: previous.confirmation,
            refreshStatus = when (result.outcome) {
                RootProbeOutcome.ROOT, RootProbeOutcome.NON_ROOT -> RootRefreshStatus.IDLE
                RootProbeOutcome.BUSY -> RootRefreshStatus.BUSY
                RootProbeOutcome.TIMED_OUT -> RootRefreshStatus.TIMED_OUT
                RootProbeOutcome.FAILED -> RootRefreshStatus.FAILED
            },
            revision = revision,
            confirmedRevision = if (confirmed != null) revision else previous.confirmedRevision,
            hasCompletedRefresh = true,
            failure = result.failure,
        ).also {
            mutableState.value = it
            recordRefresh(phase)
        }
    }

    override fun onRootWorkIdle() {
        synchronized(lock) {
            idleSequence++
            retryWhenIdleLocked()
        }
    }

    private fun retryWhenIdleLocked() {
        if (activeAdmissions == 0 && idleRetryPending && inFlight == null) {
            recordRefresh(RootRefreshPhase.IDLE_RETRY)
            startRefreshLocked(allowIdleRetry = false)
        }
    }

    override suspend fun <T> withRootAdmission(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val inherited = currentCoroutineContext()[Admission]
        if (inherited != null && inherited.owner === this) {
            synchronized(lock) { check(inherited.active) { "Root operation has already completed" } }
            return block()
        }
        val admission = synchronized(lock) {
            if (!mutableState.value.canAdmitRoot) {
                recordRefresh(RootRefreshPhase.ADMISSION_REFUSED)
                throw RootAdmissionUnavailable(mutableState.value)
            }
            activeAdmissions++
            Admission(this)
        }
        try {
            return withContext(admission) { block() }
        } finally {
            // Synchronous cleanup still runs in a cancelled coroutine; no suspending lock is held.
            synchronized(lock) {
                admission.active = false
                activeAdmissions--
                retryWhenIdleLocked()
            }
        }
    }

    private fun recordRefresh(phase: RootRefreshPhase) {
        val observation = mutableState.value
        diagnostics.recordSafely(RootLifecycleEvent.Refresh(
            phase, observation.revision, observation.confirmedRevision,
            observation.confirmation, observation.refreshStatus,
        ))
    }

    private class Admission(val owner: RootAvailabilityCoordinator) : AbstractCoroutineContextElement(Key) {
        var active = true
        companion object Key : CoroutineContext.Key<Admission>
    }

    private class PendingRefresh : RootRefreshRequest {
        private val result = CompletableDeferred<RootAvailabilityState>()
        override suspend fun await(): RootAvailabilityState = result.await()
        fun complete(state: RootAvailabilityState) { result.complete(state) }
        fun cancel(cause: CancellationException) { result.cancel(cause) }
    }
}
