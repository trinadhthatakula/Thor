// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellLaneUnavailable
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
) : RootCommandExecutor {
    private val archiveRoutingMutex = Mutex()
    private val sweepRoutingMutex = Mutex()

    override suspend fun execute(command: RootCommand): RootCommandResult =
        when (command.execution.lane) {
            PrivilegeExecutionLane.INTERACTIVE -> fallback.executeInteractive(main, command)
            PrivilegeExecutionLane.ARCHIVE -> archiveRoutingMutex.withLock {
                executeBackground(command, archive)
            }

            PrivilegeExecutionLane.SWEEP -> sweepRoutingMutex.withLock {
                executeBackground(command, sweep)
            }
        }

    private suspend fun executeBackground(
        command: RootCommand,
        dedicated: OwnedRootShellExecutor,
    ): RootCommandResult {
        val lane = command.execution.lane
        if (statuses.isDegraded(lane)) {
            return fallback.executeDegraded(main, command)
        }

        statuses.commandStarted(lane, command.execution.commandClass)
        return try {
            dedicated.execute(command)
        } catch (unavailable: ShellLaneUnavailable) {
            statuses.markDegraded(lane, unavailable.cause ?: unavailable)
            fallback.executeDegraded(main, command)
        } finally {
            statuses.commandFinished(lane)
        }
    }
}
