// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.SystemAppFreezeFailure
import com.valhalla.thor.domain.model.SystemAppFreezeFailureReason
import com.valhalla.thor.util.Logger
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

internal fun interface PrivilegeSweepPackageStateReader {
    fun stateOf(packageName: String): FreezeState

    /** Null means unavailable, never an assertion that an app is not suspended. */
    fun isSuspended(packageName: String): Boolean? = null
}

@Single(binds = [PrivilegeSweepPackageStateReader::class])
internal class DefaultPrivilegeSweepPackageStateReader(
    private val reader: AppFreezeStateReader,
) : PrivilegeSweepPackageStateReader {
    override fun stateOf(packageName: String): FreezeState = reader.stateOf(packageName)
    override fun isSuspended(packageName: String): Boolean? = reader.isSuspended(packageName)
}

internal data class PrivilegeSweepItemExecutionResult(
    val outcome: SweepAttemptOutcome,
    val rootLaneDegraded: Boolean,
    val failureReason: SystemAppFreezeFailureReason? = null,
)

internal fun interface PrivilegeSweepItemExecutor {
    suspend fun execute(
        snapshot: StoredPrivilegeSweep,
        packageName: String,
    ): PrivilegeSweepItemExecutionResult
}

@Single(binds = [PrivilegeSweepItemExecutor::class])
internal class DefaultPrivilegeSweepItemExecutor(
    private val manageApp: ManageAppUseCase,
    private val stateReader: PrivilegeSweepPackageStateReader,
    private val freezerRepository: FreezerRepository,
) : PrivilegeSweepItemExecutor {

    override suspend fun execute(
        snapshot: StoredPrivilegeSweep,
        packageName: String,
    ): PrivilegeSweepItemExecutionResult {
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandClass = snapshot.operation.commandClass(),
            packageName = packageName,
            workRequestId = snapshot.executionId,
            sweepRequestId = snapshot.requestId,
            commandTimeout = PrivilegeExecutionTimeouts.SWEEP_COMMAND,
        )
        var failureReason: SystemAppFreezeFailureReason? = null
        fun recordFailure(failure: Throwable) {
            failureReason = (failure as? SystemAppFreezeFailure)?.reason
            Logger.e("PrivilegeSweepItemExecutor",
                "${snapshot.operation} failed for $packageName", failure)
        }
        val outcome = try {
            when (
                val lease = manageApp.withPackageOperation(
                    packageName = packageName,
                    owner = snapshot.operation.owner(),
                    execution = execution,
                ) {
                    val state = stateReader.stateOf(packageName)
                    val result = when (snapshot.operation) {
                        PrivilegeSweepOperation.FREEZE -> when (state) {
                            FreezeState.FROZEN -> SweepAttemptOutcome.SUCCEEDED
                            FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                            FreezeState.ACTIVE -> when (snapshot.freezerMode) {
                                FreezerMode.FREEZE -> manageApp
                                    .setAppDisabledUncoordinated(packageName, true, execution)
                                    .toAttemptOutcome(::recordFailure)

                                FreezerMode.SUSPEND -> manageApp
                                    .setAppSuspendedUncoordinated(packageName, true, execution)
                                    .toAttemptOutcome(::recordFailure)

                                null -> SweepAttemptOutcome.FAILED
                            }
                        }

                        PrivilegeSweepOperation.UNFREEZE -> when (state) {
                            FreezeState.ACTIVE -> SweepAttemptOutcome.SUCCEEDED
                            FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                            FreezeState.FROZEN -> manageApp
                                .forceUnfreezeUncoordinated(packageName, execution)
                                .toAttemptOutcome(::recordFailure)
                        }

                        PrivilegeSweepOperation.SUSPEND,
                        PrivilegeSweepOperation.UNSUSPEND -> {
                            val suspended = stateReader.isSuspended(packageName)
                            val desired = snapshot.operation == PrivilegeSweepOperation.SUSPEND
                            when {
                                suspended == null -> SweepAttemptOutcome.FAILED
                                suspended == desired -> SweepAttemptOutcome.SUCCEEDED
                                else -> manageApp
                                    .setAppSuspendedUncoordinated(packageName, desired, execution)
                                    .toAttemptOutcome(::recordFailure)
                            }
                        }

                        PrivilegeSweepOperation.CLEAR_CACHE -> when (state) {
                            FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                            FreezeState.ACTIVE, FreezeState.FROZEN -> manageApp
                                .clearCacheUncoordinated(packageName, execution)
                                .toAttemptOutcome(::recordFailure)
                        }

                        PrivilegeSweepOperation.REINSTALL -> manageApp
                            .reinstallAppWithGoogleUncoordinated(packageName, execution)
                            .toAttemptOutcome(::recordFailure)
                    }
                    // Tracking is part of the durable request, after a successful/idempotent
                    // freeze and inside its package lease. An unchecked request never removes
                    // pre-existing membership. Failed/busy/absent targets must not be added.
                    if (snapshot.addToFreezer && result == SweepAttemptOutcome.SUCCEEDED) {
                        freezerRepository.add(packageName)
                    }
                    result
                }
            ) {
                is PackageLeaseResult.Acquired -> lease.value
                is PackageLeaseResult.Busy -> SweepAttemptOutcome.BUSY
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: PackageOperationBusy) {
            SweepAttemptOutcome.BUSY
        } catch (failure: Exception) {
            recordFailure(failure)
            SweepAttemptOutcome.FAILED
        }
        return PrivilegeSweepItemExecutionResult(
            outcome = outcome,
            rootLaneDegraded = execution.provenance.usedDegradedRootFallback,
            failureReason = failureReason,
        )
    }

    private fun Result<*>.toAttemptOutcome(recordFailure: (Throwable) -> Unit): SweepAttemptOutcome {
        val failure = exceptionOrNull() ?: return SweepAttemptOutcome.SUCCEEDED
        return when (failure) {
            is CancellationException -> throw failure
            is PackageOperationBusy -> SweepAttemptOutcome.BUSY
            else -> {
                recordFailure(failure)
                SweepAttemptOutcome.FAILED
            }
        }
    }

    private fun PrivilegeSweepOperation.owner(): PackageOperationOwner = when (this) {
        PrivilegeSweepOperation.FREEZE, PrivilegeSweepOperation.SUSPEND -> PackageOperationOwner.FREEZE
        PrivilegeSweepOperation.UNFREEZE, PrivilegeSweepOperation.UNSUSPEND -> PackageOperationOwner.UNFREEZE
        PrivilegeSweepOperation.CLEAR_CACHE -> PackageOperationOwner.CLEAR_CACHE
        PrivilegeSweepOperation.REINSTALL -> PackageOperationOwner.REINSTALL
    }

    private fun PrivilegeSweepOperation.commandClass(): PrivilegeCommandClass =
        PrivilegeCommandClass(
            when (this) {
                PrivilegeSweepOperation.FREEZE -> "sweep.freeze"
                PrivilegeSweepOperation.UNFREEZE -> "sweep.unfreeze"
                PrivilegeSweepOperation.SUSPEND -> "sweep.suspend"
                PrivilegeSweepOperation.UNSUSPEND -> "sweep.unsuspend"
                PrivilegeSweepOperation.CLEAR_CACHE -> "sweep.clear_cache"
                PrivilegeSweepOperation.REINSTALL -> "sweep.reinstall"
            }
        )
}
