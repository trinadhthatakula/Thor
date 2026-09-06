// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.PrivilegeSweepRecovery
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetResult
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetTerminalState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** One process-wide gate shared by every sweep launch and reconciliation entry point. */
@Single
internal class PrivilegeSweepProcessGate {
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}

internal fun interface PrivilegeSweepClock {
    fun nowMs(): Long
}

@Single(binds = [PrivilegeSweepClock::class])
internal class WallPrivilegeSweepClock : PrivilegeSweepClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

internal enum class ReinstallPostcondition {
    SATISFIED,
    NOT_SATISFIED,
    UNKNOWN,
}

internal fun interface PrivilegeSweepReinstallPostconditionVerifier {
    suspend fun verify(
        packageName: String,
        userId: Int,
        executionId: UUID,
        requestId: UUID,
    ): ReinstallPostcondition
}

/** Repairs durable sweep state without replaying an operation whose outcome is ambiguous. */
@Single
internal class PrivilegeSweepReconciler(
    private val store: PrivilegeSweepStore,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
    private val stateReader: PrivilegeSweepPackageStateReader? = null,
    private val packageOperationCoordinator: PackageOperationCoordinator? = null,
) {
    /** Startup only prunes. Cutover owns every nonterminal legacy row, including cancelled WorkInfo. */
    suspend fun pruneRetained() {
        gate.serialized { store.deleteExpired(clock.nowMs()) }
    }

    internal suspend fun reconcileInterruptedClaims(
        currentSessionToken: String,
        localOwnerIsLive: (requestId: UUID, requestClaimToken: String) -> Boolean,
        requestId: UUID? = null,
        reinstallVerifier: PrivilegeSweepReinstallPostconditionVerifier,
    ) {
        gate.serialized {
            val nowMs = clock.nowMs()
            store.recoverRequestClaims(
                sessionToken = currentSessionToken,
                nowMs = nowMs,
                localOwnerIsLive = { id, token ->
                    (requestId != null && id != requestId) || localOwnerIsLive(id, token)
                },
            ).forEach { candidate ->
                val snapshot = store.load(candidate.requestId) ?: return@forEach
                val target = snapshot.targetSnapshots.singleOrNull {
                    it.ordinal == candidate.activeTargetOrdinal
                } ?: return@forEach
                if (
                    snapshot.terminalState != null ||
                    target.state != PrivilegeSweepTargetState.RUNNING ||
                    target.packageName != candidate.packageName
                ) {
                    return@forEach
                }
                val recovery = recoveryFor(
                    snapshot = snapshot,
                    packageName = candidate.packageName,
                    nowMs = nowMs,
                    reinstallVerifier = reinstallVerifier,
                )
                store.recoverInterruptedTarget(candidate, recovery)
            }
        }
    }

    internal suspend fun reconcileUnknownTarget(
        requestId: UUID,
        ordinal: Int,
        reinstallVerifier: PrivilegeSweepReinstallPostconditionVerifier,
    ): Boolean = gate.serialized {
        val snapshot = store.load(requestId) ?: return@serialized false
        if (snapshot.terminalState != null) return@serialized false
        val target = snapshot.targetSnapshots.singleOrNull { it.ordinal == ordinal }
            ?: return@serialized false
        if (
            (target.state != PrivilegeSweepTargetState.UNKNOWN &&
                    target.state != PrivilegeSweepTargetState.LEGACY_UNKNOWN) ||
            target.claimToken != null ||
            target.claimLeaseExpiresAtEpochMs != null
        ) {
            return@serialized false
        }
        val nowMs = clock.nowMs()
        val recovery = recoveryFor(
            snapshot = snapshot,
            packageName = target.packageName,
            nowMs = nowMs,
            reinstallVerifier = reinstallVerifier,
        )
        store.recoverInterruptedTarget(requestId, ordinal, recovery)
    }

    private suspend fun recoveryFor(
        snapshot: StoredPrivilegeSweep,
        packageName: String,
        nowMs: Long,
        reinstallVerifier: PrivilegeSweepReinstallPostconditionVerifier,
    ): PrivilegeSweepRecovery {
        if (snapshot.operation == PrivilegeSweepOperation.CLEAR_CACHE) {
            return unknown(CLEAR_CACHE_OUTCOME_UNKNOWN, nowMs)
        }
        val coordinator = packageOperationCoordinator
            ?: return unknown(snapshot.operation.unknownRecoveryCode(), nowMs)
        return try {
            when (
                val lease = coordinator.withPackageLease(
                    packageName = packageName,
                    owner = snapshot.operation.owner(),
                    admissionTimeout = PrivilegeExecutionTimeouts.SWEEP_ADMISSION,
                ) {
                    when (snapshot.operation) {
                        PrivilegeSweepOperation.FREEZE,
                        PrivilegeSweepOperation.UNFREEZE,
                            -> recoveryFromFreezeState(
                            operation = snapshot.operation,
                            state = requireNotNull(stateReader).stateOf(packageName),
                            nowMs = nowMs,
                        )

                        PrivilegeSweepOperation.REINSTALL -> recoveryFromReinstallPostcondition(
                            postcondition = reinstallVerifier.verify(
                                packageName = packageName,
                                userId = snapshot.userId,
                                executionId = snapshot.executionId,
                                requestId = snapshot.requestId,
                            ),
                            nowMs = nowMs,
                        )

                        PrivilegeSweepOperation.CLEAR_CACHE -> error("Clear-cache recovery is never inspected")
                    }
                }
            ) {
                is PackageLeaseResult.Acquired -> lease.value
                is PackageLeaseResult.Busy -> unknown(
                    snapshot.operation.unknownRecoveryCode(),
                    nowMs
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unknown(snapshot.operation.unknownRecoveryCode(), nowMs)
        }
    }

    private fun recoveryFromFreezeState(
        operation: PrivilegeSweepOperation,
        state: FreezeState,
        nowMs: Long,
    ): PrivilegeSweepRecovery = when (operation) {
        PrivilegeSweepOperation.FREEZE -> when (state) {
            FreezeState.FROZEN -> completed(
                PrivilegeSweepTargetTerminalState.SUCCEEDED,
                RECOVERED_ALREADY_FROZEN,
                nowMs,
            )

            FreezeState.ACTIVE -> requeue(RECOVERY_RETRY_FREEZE, nowMs)
            FreezeState.ABSENT -> completed(
                PrivilegeSweepTargetTerminalState.FAILED,
                PACKAGE_ABSENT,
                nowMs,
            )
        }

        PrivilegeSweepOperation.UNFREEZE -> when (state) {
            FreezeState.ACTIVE -> completed(
                PrivilegeSweepTargetTerminalState.SUCCEEDED,
                RECOVERED_ALREADY_ACTIVE,
                nowMs,
            )

            FreezeState.FROZEN -> requeue(RECOVERY_RETRY_UNFREEZE, nowMs)
            FreezeState.ABSENT -> completed(
                PrivilegeSweepTargetTerminalState.FAILED,
                PACKAGE_ABSENT,
                nowMs,
            )
        }

        PrivilegeSweepOperation.CLEAR_CACHE,
        PrivilegeSweepOperation.REINSTALL,
            -> error("Freeze-state recovery requires a freeze operation")
    }

    private fun recoveryFromReinstallPostcondition(
        postcondition: ReinstallPostcondition,
        nowMs: Long,
    ): PrivilegeSweepRecovery = when (postcondition) {
        ReinstallPostcondition.SATISFIED -> completed(
            PrivilegeSweepTargetTerminalState.SUCCEEDED,
            REINSTALL_POSTCONDITION_VERIFIED,
            nowMs,
        )

        ReinstallPostcondition.NOT_SATISFIED -> requeue(RECOVERY_RETRY_REINSTALL, nowMs)
        ReinstallPostcondition.UNKNOWN -> unknown(REINSTALL_POSTCONDITION_UNKNOWN, nowMs)
    }

    private fun completed(
        terminalState: PrivilegeSweepTargetTerminalState,
        resultCode: String,
        nowMs: Long,
    ) = PrivilegeSweepRecovery.Completed(
        result = PrivilegeSweepTargetResult(
            terminalState = terminalState,
            resultCode = PrivilegeSweepResultCode(resultCode),
            rootLaneDegraded = false,
            finishedAtEpochMs = nowMs,
        ),
        recoveredAtEpochMs = nowMs,
    )

    private fun requeue(resultCode: String, nowMs: Long) = PrivilegeSweepRecovery.Requeue(
        resultCode = PrivilegeSweepResultCode(resultCode),
        recoveredAtEpochMs = nowMs,
    )

    private fun unknown(resultCode: String, nowMs: Long) = PrivilegeSweepRecovery.MarkUnknown(
        resultCode = PrivilegeSweepResultCode(resultCode),
        recoveredAtEpochMs = nowMs,
    )

    private fun PrivilegeSweepOperation.unknownRecoveryCode(): String = when (this) {
        PrivilegeSweepOperation.FREEZE,
        PrivilegeSweepOperation.UNFREEZE,
            -> RECOVERY_INSPECTION_UNAVAILABLE

        PrivilegeSweepOperation.REINSTALL -> REINSTALL_POSTCONDITION_UNKNOWN
        PrivilegeSweepOperation.CLEAR_CACHE -> CLEAR_CACHE_OUTCOME_UNKNOWN
    }

    private fun PrivilegeSweepOperation.owner(): PackageOperationOwner = when (this) {
        PrivilegeSweepOperation.FREEZE -> PackageOperationOwner.FREEZE
        PrivilegeSweepOperation.UNFREEZE -> PackageOperationOwner.UNFREEZE
        PrivilegeSweepOperation.CLEAR_CACHE -> PackageOperationOwner.CLEAR_CACHE
        PrivilegeSweepOperation.REINSTALL -> PackageOperationOwner.REINSTALL
    }

    private companion object {
        const val RECOVERED_ALREADY_FROZEN = "RECOVERED_ALREADY_FROZEN"
        const val RECOVERED_ALREADY_ACTIVE = "RECOVERED_ALREADY_ACTIVE"
        const val RECOVERY_RETRY_FREEZE = "RECOVERY_RETRY_FREEZE"
        const val RECOVERY_RETRY_UNFREEZE = "RECOVERY_RETRY_UNFREEZE"
        const val PACKAGE_ABSENT = "PACKAGE_ABSENT"
        const val RECOVERY_INSPECTION_UNAVAILABLE = "RECOVERY_INSPECTION_UNAVAILABLE"
        const val REINSTALL_POSTCONDITION_VERIFIED = "REINSTALL_POSTCONDITION_VERIFIED"
        const val RECOVERY_RETRY_REINSTALL = "RECOVERY_RETRY_REINSTALL"
        const val REINSTALL_POSTCONDITION_UNKNOWN = "REINSTALL_POSTCONDITION_UNKNOWN"
        const val CLEAR_CACHE_OUTCOME_UNKNOWN = "CLEAR_CACHE_OUTCOME_UNKNOWN"
    }
}
