// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

@Single
internal class RootFallbackCoordinator(
    private val statuses: DefaultRootLaneStatusSource,
) {
    private val mainShellMutex = Mutex()
    private val activeOwner = AtomicReference<PrivilegeExecutionLane?>(null)

    suspend fun executeInteractive(
        main: MainShellCommandExecutor,
        command: RootCommand,
    ): RootCommandResult {
        check(command.execution.lane == PrivilegeExecutionLane.INTERACTIVE)
        if (!mainShellMutex.tryLock()) {
            throw ShellLaneBusy(activeOwner.get() ?: PrivilegeExecutionLane.INTERACTIVE)
        }
        activeOwner.set(PrivilegeExecutionLane.INTERACTIVE)
        statuses.commandStarted(
            lane = PrivilegeExecutionLane.INTERACTIVE,
            commandClass = command.execution.commandClass,
        )
        try {
            return main.execute(command)
        } catch (cancelled: CancellationException) {
            if (cancelled is ShellCommandCancelled) throw cancelled
            throw ShellCommandCancelled(command.execution.commandClass, cancelled)
        } finally {
            statuses.commandFinished(PrivilegeExecutionLane.INTERACTIVE)
            release(PrivilegeExecutionLane.INTERACTIVE)
        }
    }

    suspend fun executeDegraded(
        main: MainShellCommandExecutor,
        command: RootCommand,
    ): RootCommandResult {
        val lane = command.execution.lane
        require(lane != PrivilegeExecutionLane.INTERACTIVE) {
            "Interactive MainShell commands must use immediate admission"
        }

        mainShellMutex.lock()
        activeOwner.set(lane)
        try {
            currentCoroutineContext().ensureActive()
            statuses.commandStarted(
                lane = lane,
                commandClass = command.execution.commandClass,
                fallbackOwner = lane,
            )
            return executeSubmittedCommandToDrain(main, command)
        } finally {
            statuses.commandFinished(lane)
            release(lane)
        }
    }

    private suspend fun executeSubmittedCommandToDrain(
        main: MainShellCommandExecutor,
        command: RootCommand,
    ): RootCommandResult {
        try {
            val timeout = command.execution.commandTimeout
            val outcome = if (timeout == null) {
                CommandOutcome.Completed(
                    withContext(NonCancellable) {
                        main.execute(command)
                    },
                )
            } else {
                withTimeoutOrNull(timeout) {
                    CommandOutcome.Completed(
                        withContext(NonCancellable) {
                            main.execute(command)
                        },
                    )
                } ?: CommandOutcome.TimedOut
            }

            currentCoroutineContext().ensureActive()
            return when (outcome) {
                is CommandOutcome.Completed -> outcome.result
                CommandOutcome.TimedOut -> throw ShellCommandTimedOut(command.execution.commandClass)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is ShellCommandCancelled) throw cancelled
            throw ShellCommandCancelled(command.execution.commandClass, cancelled)
        }
    }

    private fun release(owner: PrivilegeExecutionLane) {
        mainShellMutex.unlock()
        activeOwner.compareAndSet(owner, null)
    }

    private sealed interface CommandOutcome {
        data class Completed(val result: RootCommandResult) : CommandOutcome

        data object TimedOut : CommandOutcome
    }
}
