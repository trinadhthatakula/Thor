// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.ShellLaneUnavailable
import com.valhalla.thor.domain.repository.RootAdmissionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [RootCommandExecutor::class])
internal class RootCommandRouter(
    private val main: MainShellCommandExecutor,
    @Named("archive") private val archive: OwnedRootShellExecutor,
    @Named("sweep") private val sweep: OwnedRootShellExecutor,
    private val fallback: RootFallbackCoordinator,
    private val statuses: DefaultRootLaneStatusSource,
    private val rootAdmission: RootAdmissionController,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) : RootCommandExecutor {
    private val archiveRoutingMutex = Mutex()
    private val sweepRoutingMutex = Mutex()
    private var archiveRevision = rootAdmission.state.value.confirmedRevision
    private var sweepRevision = rootAdmission.state.value.confirmedRevision

    init {
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            rootAdmission.state.collect {
                // Never queue refresh cleanup ahead of accepted work. A busy lane performs the
                // same revision check at its next idle command boundary.
                recoverIfIdle(PrivilegeExecutionLane.ARCHIVE, archive, archiveRoutingMutex)
                recoverIfIdle(PrivilegeExecutionLane.SWEEP, sweep, sweepRoutingMutex)
            }
        }
    }

    override suspend fun execute(command: RootCommand): RootCommandResult =
        when (command.execution.lane) {
            PrivilegeExecutionLane.INTERACTIVE -> fallback.executeInteractive(main, command)
            PrivilegeExecutionLane.ARCHIVE -> executeOwned(command, archive, archiveRoutingMutex)
            PrivilegeExecutionLane.SWEEP -> executeOwned(command, sweep, sweepRoutingMutex)
        }

    private suspend fun executeOwned(
        command: RootCommand,
        executor: OwnedRootShellExecutor,
        routingMutex: Mutex,
    ): RootCommandResult = try {
        routingMutex.withLock {
            rootAdmission.withRootAdmission {
                recoverAtIdleBoundary(command.execution.lane, executor)
                executeBackground(command, executor)
            }
        }
    } finally {
        // Admission release can finish an idle refresh before the routing lock is released.
        // Recheck after unlocking so a skipped observer emission cannot retain an idle session.
        withContext(NonCancellable) { recoverIfIdle(command.execution.lane, executor, routingMutex) }
    }

    private suspend fun recoverIfIdle(
        lane: PrivilegeExecutionLane,
        executor: OwnedRootShellExecutor,
        routingMutex: Mutex,
    ) {
        if (!routingMutex.tryLock()) return
        try {
            recoverAtIdleBoundary(lane, executor)
        } finally {
            routingMutex.unlock()
        }
    }

    private suspend fun recoverAtIdleBoundary(
        lane: PrivilegeExecutionLane,
        executor: OwnedRootShellExecutor,
    ) {
        val observation = rootAdmission.state.value
        val lastRevision = if (lane == PrivilegeExecutionLane.ARCHIVE) archiveRevision else sweepRevision
        if (observation.confirmedRevision <= lastRevision) return

        executor.retireIdleSession()
        if (observation.confirmation == RootConfirmation.ROOT) statuses.markRecovered(lane)
        if (lane == PrivilegeExecutionLane.ARCHIVE) archiveRevision = observation.confirmedRevision
        else sweepRevision = observation.confirmedRevision
    }

    private suspend fun executeBackground(
        command: RootCommand,
        dedicated: OwnedRootShellExecutor,
    ): RootCommandResult {
        val lane = command.execution.lane
        if (statuses.isDegraded(lane)) {
            command.execution.provenance.recordDegradedRootFallback()
            return fallback.executeDegraded(main, command)
        }

        statuses.commandStarted(lane, command.execution.commandClass)
        return try {
            dedicated.execute(command)
        } catch (unavailable: ShellLaneUnavailable) {
            statuses.markDegraded(lane, unavailable.cause ?: unavailable)
            command.execution.provenance.recordDegradedRootFallback()
            fallback.executeDegraded(main, command)
        } finally {
            statuses.commandFinished(lane)
            withContext(NonCancellable) { recoverAtIdleBoundary(lane, dedicated) }
        }
    }
}
