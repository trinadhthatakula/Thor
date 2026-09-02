// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

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
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

internal fun interface PrivilegeSweepPackageStateReader {
    fun stateOf(packageName: String): FreezeState
}

@Single(binds = [PrivilegeSweepPackageStateReader::class])
internal class DefaultPrivilegeSweepPackageStateReader(
    private val reader: AppFreezeStateReader,
) : PrivilegeSweepPackageStateReader {
    override fun stateOf(packageName: String): FreezeState = reader.stateOf(packageName)
}

internal fun interface PrivilegeSweepItemExecutor {
    suspend fun execute(
        snapshot: StoredPrivilegeSweep,
        packageName: String,
    ): SweepAttemptOutcome
}

@Single(binds = [PrivilegeSweepItemExecutor::class])
internal class DefaultPrivilegeSweepItemExecutor(
    private val manageApp: ManageAppUseCase,
    private val stateReader: PrivilegeSweepPackageStateReader,
) : PrivilegeSweepItemExecutor {

    override suspend fun execute(
        snapshot: StoredPrivilegeSweep,
        packageName: String,
    ): SweepAttemptOutcome = try {
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandClass = snapshot.operation.commandClass(),
            packageName = packageName,
            workRequestId = snapshot.workId,
            sweepRequestId = snapshot.requestId,
            commandTimeout = PrivilegeExecutionTimeouts.SWEEP_COMMAND,
        )
        when (
            val lease = manageApp.withPackageOperation(
                packageName = packageName,
                owner = snapshot.operation.owner(),
                execution = execution,
            ) {
                val state = stateReader.stateOf(packageName)
                when (snapshot.operation) {
                    PrivilegeSweepOperation.FREEZE -> when (state) {
                        FreezeState.FROZEN -> SweepAttemptOutcome.SUCCEEDED
                        FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                        FreezeState.ACTIVE -> when (snapshot.freezerMode) {
                            FreezerMode.FREEZE -> manageApp
                                .setAppDisabledUncoordinated(packageName, true, execution)
                                .toAttemptOutcome()

                            FreezerMode.SUSPEND -> manageApp
                                .setAppSuspendedUncoordinated(packageName, true, execution)
                                .toAttemptOutcome()

                            null -> SweepAttemptOutcome.FAILED
                        }
                    }

                    PrivilegeSweepOperation.UNFREEZE -> when (state) {
                        FreezeState.ACTIVE -> SweepAttemptOutcome.SUCCEEDED
                        FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                        FreezeState.FROZEN -> manageApp
                            .forceUnfreezeUncoordinated(packageName, execution)
                            .toAttemptOutcome()
                    }

                    PrivilegeSweepOperation.CLEAR_CACHE -> when (state) {
                        FreezeState.ABSENT -> SweepAttemptOutcome.FAILED
                        FreezeState.ACTIVE, FreezeState.FROZEN -> manageApp
                            .clearCacheUncoordinated(packageName, execution)
                            .toAttemptOutcome()
                    }

                    PrivilegeSweepOperation.REINSTALL -> manageApp
                        .reinstallAppWithGoogleUncoordinated(packageName, execution)
                        .toAttemptOutcome()
                }
            }
        ) {
            is PackageLeaseResult.Acquired -> lease.value
            is PackageLeaseResult.Busy -> SweepAttemptOutcome.BUSY
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: PackageOperationBusy) {
        SweepAttemptOutcome.BUSY
    } catch (_: Exception) {
        SweepAttemptOutcome.FAILED
    }

    private fun Result<*>.toAttemptOutcome(): SweepAttemptOutcome {
        val failure = exceptionOrNull() ?: return SweepAttemptOutcome.SUCCEEDED
        return when (failure) {
            is CancellationException -> throw failure
            is PackageOperationBusy -> SweepAttemptOutcome.BUSY
            else -> SweepAttemptOutcome.FAILED
        }
    }

    private fun PrivilegeSweepOperation.owner(): PackageOperationOwner = when (this) {
        PrivilegeSweepOperation.FREEZE -> PackageOperationOwner.FREEZE
        PrivilegeSweepOperation.UNFREEZE -> PackageOperationOwner.UNFREEZE
        PrivilegeSweepOperation.CLEAR_CACHE -> PackageOperationOwner.CLEAR_CACHE
        PrivilegeSweepOperation.REINSTALL -> PackageOperationOwner.REINSTALL
    }

    private fun PrivilegeSweepOperation.commandClass(): PrivilegeCommandClass =
        PrivilegeCommandClass(
            when (this) {
                PrivilegeSweepOperation.FREEZE -> "sweep.freeze"
                PrivilegeSweepOperation.UNFREEZE -> "sweep.unfreeze"
                PrivilegeSweepOperation.CLEAR_CACHE -> "sweep.clear_cache"
                PrivilegeSweepOperation.REINSTALL -> "sweep.reinstall"
            }
        )
}
