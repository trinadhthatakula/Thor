// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import android.os.PowerManager
import com.valhalla.thor.data.gateway.ReinstallPostconditionVerifier
import com.valhalla.thor.domain.model.ReinstallPostconditionFailed
import com.valhalla.thor.data.service.ForegroundTaskOwner
import com.valhalla.thor.data.service.ForegroundTaskWakeLock
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepRequest
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetResult
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetTerminalState
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val PRIVILEGE_CLAIM_LEASE_MILLIS = ForegroundTaskWakeLock.LEASE_MILLIS
private const val PRIVILEGE_SETTLEMENT_TIMEOUT_MILLIS = 3_000L

internal interface PrivilegeSweepDrainRuntime {
    suspend fun awaitCutover()
    suspend fun recoverClaims(sessionToken: String, localOwnerIsLive: (UUID, String) -> Boolean)
    suspend fun claimNext(sessionToken: String, claimToken: String): ClaimedPrivilegeSweepRequest?
    suspend fun hasPrivilege(): Boolean
    suspend fun blockMissingPrivilege(claim: ClaimedPrivilegeSweepRequest): Boolean
    suspend fun executeClaim(
        claim: ClaimedPrivilegeSweepRequest,
        onTargetClaimed: (String) -> Unit,
    )
    suspend fun settleClaim(claim: ClaimedPrivilegeSweepRequest): Boolean
    suspend fun abortDrain()
    suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit): Boolean
}

@Single(binds = [PrivilegeSweepReinstallPostconditionVerifier::class])
internal class DefaultPrivilegeSweepReinstallPostconditionVerifier(
    private val verifier: ReinstallPostconditionVerifier,
) : PrivilegeSweepReinstallPostconditionVerifier {
    override suspend fun verify(
        packageName: String,
        userId: Int,
        executionId: UUID,
        requestId: UUID,
    ): ReinstallPostcondition = try {
        val result = verifier.verify(packageName, userId)
        when {
            result.isSuccess -> ReinstallPostcondition.SATISFIED
            result.exceptionOrNull() is ReinstallPostconditionFailed -> ReinstallPostcondition.NOT_SATISFIED
            else -> ReinstallPostcondition.UNKNOWN
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReinstallPostcondition.UNKNOWN
    }
}

@Single(binds = [PrivilegeSweepDrainRuntime::class])
internal class RoomPrivilegeSweepDrainRuntime(
    context: Context,
    private val cutover: PrivilegeSweepWorkManagerCutover,
    private val reconciler: PrivilegeSweepReconciler,
    private val verifier: PrivilegeSweepReinstallPostconditionVerifier,
    private val store: PrivilegeSweepStore,
    private val privilegeState: PrivilegeStateProvider,
    private val executor: PrivilegeSweepItemExecutor,
    private val clock: PrivilegeSweepClock,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    private val wakeLockFactory: () -> ForegroundTaskWakeLock = {
        ForegroundTaskWakeLock(
            requireNotNull(context.applicationContext.getSystemService(PowerManager::class.java)),
            ForegroundTaskOwner.PRIVILEGE_SWEEP,
        )
    },
) : PrivilegeSweepDrainRuntime {

    override suspend fun awaitCutover() = cutover.awaitCompleted()

    override suspend fun recoverClaims(
        sessionToken: String,
        localOwnerIsLive: (UUID, String) -> Boolean,
    ) {
        reconciler.reconcileInterruptedClaims(sessionToken, localOwnerIsLive, reinstallVerifier = verifier)
    }

    override suspend fun claimNext(
        sessionToken: String,
        claimToken: String,
    ): ClaimedPrivilegeSweepRequest? {
        val nowMs = clock.nowMs()
        return store.claimOldestRunnableRequest(
            sessionToken = sessionToken,
            claimToken = claimToken,
            nowMs = nowMs,
            leaseUntilMs = nowMs + PRIVILEGE_CLAIM_LEASE_MILLIS,
        )
    }

    override suspend fun hasPrivilege(): Boolean =
        privilegeState.state.first { it.isReady }.hasAnyPrivilege

    override suspend fun blockMissingPrivilege(claim: ClaimedPrivilegeSweepRequest): Boolean =
        store.blockClaimedRequestForMissingPrivilege(
            requestId = claim.requestId,
            requestClaimToken = claim.claimToken,
            nowMs = clock.nowMs(),
        )

    override suspend fun executeClaim(
        claim: ClaimedPrivilegeSweepRequest,
        onTargetClaimed: (String) -> Unit,
    ) {
        val snapshot = requireNotNull(store.load(claim.requestId))
        while (true) {
            currentCoroutineContext().ensureActive()
            // Authorization is checked only between targets, never used to reinterpret a result.
            if (!hasPrivilege()) {
                check(blockMissingPrivilege(claim)) { "Privilege block did not commit" }
                return
            }
            val target = claimNextTarget(claim) ?: break
            // All setup and claim-return cancellation windows are covered by the coordinator's
            // exact-token exit settlement, which reloads the target rather than guessing its ordinal.
            val wakeLock = wakeLockFactory()
            wakeLock.withClaimedExecution {
                currentCoroutineContext().ensureActive()
                onTargetClaimed(target.packageName)
                currentCoroutineContext().ensureActive()
                val outcome = executor.execute(snapshot, target.packageName)
                withContext(NonCancellable + ioDispatcher) {
                    check(store.completeClaimedTarget(
                        claim.requestId, target.ordinal, claim.claimToken, target.claimToken,
                        outcome.toTargetResult(clock.nowMs()),
                    )) { "Privilege target completion lost ownership" }
                    check(store.renewRequestClaim(
                        claim.requestId, claim.claimToken, clock.nowMs() + PRIVILEGE_CLAIM_LEASE_MILLIS,
                    )) { "Privilege request lease renewal lost ownership" }
                    renewAfterPersistedCheckpoint()
                }
            }
        }
        check(store.finishClaimedRequestIfDrained(claim.requestId, claim.claimToken, clock.nowMs())) {
            "Privilege request completion lost ownership"
        }
    }

    override suspend fun settleClaim(claim: ClaimedPrivilegeSweepRequest): Boolean =
        store.settleClaimedRequestAfterExit(claim.requestId, claim.claimToken, clock.nowMs())

    override suspend fun abortDrain() {
        // Bounded by the caller. Preserve accepted work for an explicit retry, never claim it here.
        store.observeRetained().first().filter {
            it.terminalState == null && it.requestState == PrivilegeSweepRequestState.QUEUED
        }.forEach {
            store.markUnclaimedStartBlocked(it.requestId, PrivilegeSweepBlockReason.START_BLOCKED, clock.nowMs())
        }
    }

    private suspend fun claimNextTarget(
        claim: ClaimedPrivilegeSweepRequest,
    ): ClaimedPrivilegeSweepTarget? {
        val nowMs = clock.nowMs()
        return store.claimNextPendingTarget(
            requestId = claim.requestId,
            requestClaimToken = claim.claimToken,
            targetClaimToken = UUID.randomUUID().toString(),
            nowMs = nowMs,
            leaseUntilMs = nowMs + PRIVILEGE_CLAIM_LEASE_MILLIS,
        )
    }

    override suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit): Boolean =
        store.finishDrainIfQueueEmpty(onQueueEmpty)

    private fun PrivilegeSweepItemExecutionResult.toTargetResult(
        nowMs: Long,
    ): PrivilegeSweepTargetResult = when (outcome) {
        SweepAttemptOutcome.SUCCEEDED -> PrivilegeSweepTargetResult(
            PrivilegeSweepTargetTerminalState.SUCCEEDED,
            PrivilegeSweepResultCode("SUCCEEDED"),
            rootLaneDegraded = rootLaneDegraded,
            finishedAtEpochMs = nowMs,
        )
        SweepAttemptOutcome.FAILED -> PrivilegeSweepTargetResult(
            PrivilegeSweepTargetTerminalState.FAILED,
            PrivilegeSweepResultCode("FAILED"),
            rootLaneDegraded = rootLaneDegraded,
            finishedAtEpochMs = nowMs,
        )
        SweepAttemptOutcome.BUSY -> PrivilegeSweepTargetResult(
            PrivilegeSweepTargetTerminalState.BUSY,
            PrivilegeSweepResultCode("BUSY"),
            rootLaneDegraded = rootLaneDegraded,
            finishedAtEpochMs = nowMs,
        )
    }
}

/** One service-generation drain loop over the process-wide serial privilege lane. */
@Factory
internal class PrivilegeSweepDrainCoordinator(
    @Named("io") dispatcher: CoroutineDispatcher,
    private val owners: PrivilegeSweepOwnerRegistry,
    private val runtime: PrivilegeSweepDrainRuntime,
    private val sessionToken: String = owners.sessionToken,
    private val claimTokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Wake(
        val number: Long,
        val onClaimed: (UUID, String) -> Unit,
        val onDrained: () -> Unit,
        val onAborted: () -> Unit,
    )

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + dispatcher)
    private val lock = Any()
    private val generationToken = UUID.randomUUID().toString()
    private var drain: Job? = null
    private var closed = false
    private var wakeNumber = 0L
    private var latestWake: Wake? = null
    private val drainLaunches = AtomicInteger()

    fun wake(
        onClaimed: (UUID, String) -> Unit = { _, _ -> },
        onAborted: () -> Unit = {},
        onDrained: () -> Unit,
    ): Long = synchronized(lock) {
        if (closed) return@synchronized wakeNumber
        val wake = Wake(++wakeNumber, onClaimed, onDrained, onAborted)
        latestWake = wake
        if (drain?.isActive != true) launchDrainLocked(wake.number)
        wake.number
    }

    internal val drainLaunchCountForTest: Int
        get() = drainLaunches.get()

    private fun launchDrainLocked(observedWake: Long) {
        drainLaunches.incrementAndGet()
        drain = scope.launch { drainQueue(observedWake) }
    }

    /** Close admission immediately; registered ownership survives until child cleanup and settlement exit. */
    fun shutdown() {
        synchronized(lock) {
            closed = true
            latestWake = null
            parentJob.cancel()
        }
    }

    private suspend fun drainQueue(initialWake: Long) {
        var observedWake = initialWake
        var laneAcquired = false
        var aborted = false
        try {
            owners.acquireLane(generationToken)
            laneAcquired = true
            runtime.awaitCutover()
            runtime.recoverClaims(sessionToken, owners::isLive)
            while (true) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) { observedWake = wakeNumber }
                val claimToken = claimTokenFactory()
                check(owners.registerProvisional(claimToken))
                var claim: ClaimedPrivilegeSweepRequest? = null
                try {
                    // Assign and bind inside the non-cancellable context, not on its cancellable return.
                    withContext(NonCancellable) {
                        claim = runtime.claimNext(sessionToken, claimToken)
                        claim?.let { check(owners.bindRequest(it.requestId, claimToken)) }
                    }
                    val ownedClaim = claim
                    if (ownedClaim == null) {
                        if (runtime.finishDrainIfEmpty {
                                synchronized(lock) {
                                    observedWake = wakeNumber
                                    if (!closed) latestWake?.onDrained?.invoke()
                                }
                            }) break
                        continue
                    }
                    try {
                        executeOwnedClaim(ownedClaim)
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        // Continue only if the exact-token exit settlement below succeeds.
                    }
                } finally {
                    val ownedClaim = claim
                    if (ownedClaim == null) {
                        owners.unregisterProvisional(claimToken)
                    } else {
                        try {
                            val settled = withContext(NonCancellable) {
                                withTimeoutOrNull(PRIVILEGE_SETTLEMENT_TIMEOUT_MILLIS) {
                                    runtime.settleClaim(ownedClaim)
                                }
                            }
                            check(settled == true) { "Privilege exit settlement did not commit" }
                        } finally {
                            owners.unregister(ownedClaim.requestId, claimToken)
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // shutdown suppresses callbacks and further claims; the finally above owns settlement.
        } catch (_: Exception) {
            aborted = true
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(PRIVILEGE_SETTLEMENT_TIMEOUT_MILLIS) { runtime.abortDrain() }
                } catch (_: Exception) {
                    // Storage is unavailable. Retain durable state for a later explicit wake/recovery.
                }
            }
        } finally {
            if (laneAcquired) owners.releaseLane(generationToken)
            synchronized(lock) {
                drain = null
                if (!closed) {
                    if (wakeNumber > observedWake) launchDrainLocked(wakeNumber)
                    else if (aborted) latestWake?.onAborted?.invoke()
                }
            }
        }
    }

    private suspend fun executeOwnedClaim(claim: ClaimedPrivilegeSweepRequest) {
        supervisorScope {
            val child = async(start = CoroutineStart.LAZY) {
                if (!runtime.hasPrivilege()) {
                    check(runtime.blockMissingPrivilege(claim)) { "Privilege block did not commit" }
                    return@async
                }
                runtime.executeClaim(claim) { packageName ->
                    synchronized(lock) {
                        if (!closed) latestWake?.onClaimed?.invoke(claim.requestId, packageName)
                    }
                }
            }
            check(owners.attachChild(claim.requestId, claim.claimToken, child))
            child.start()
            try {
                child.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }
}
