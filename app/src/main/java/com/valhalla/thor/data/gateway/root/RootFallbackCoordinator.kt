// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

@Single
internal class RootFallbackCoordinator(
    private val statuses: DefaultRootLaneStatusSource,
) {
    private val coordinationState = MutableStateFlow<MainShellLease?>(null)

    suspend fun executeInteractive(
        main: MainShellCommandExecutor,
        command: RootCommand,
    ): RootCommandResult {
        check(command.execution.lane == PrivilegeExecutionLane.INTERACTIVE)
        val lease = acquireInteractiveImmediately()
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
            release(lease)
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

        val lease = acquireWhenAvailable(lane)
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
            release(lease)
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
                    executeCancellableUntilSubmitted(main, command),
                )
            } else {
                withTimeoutOrNull(timeout) {
                    CommandOutcome.Completed(
                        executeCancellableUntilSubmitted(main, command),
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

    private suspend fun executeCancellableUntilSubmitted(
        main: MainShellCommandExecutor,
        command: RootCommand,
    ): RootCommandResult {
        val pending = main.prepare(command)
        val completion = CompletableDeferred<Result<ShellResult>>()
        val submissionGate = CompletableDeferred(Unit)
        var submissionWon = false
        try {
            select {
                submissionGate.onAwait {
                    // Selection and cancellation compete atomically. Once this clause wins,
                    // submission is committed and its callback must drain before release.
                    pending.submit(completion::complete)
                    submissionWon = true
                }
            }
            val outcome = withContext(NonCancellable) { completion.await() }
            return main.toCommandResult(command, outcome)
        } catch (cancelled: CancellationException) {
            if (submissionWon) {
                withContext(NonCancellable) { completion.await() }
            }
            throw cancelled
        }
    }

    private fun acquireInteractiveImmediately(): MainShellLease {
        val requested = MainShellLease(PrivilegeExecutionLane.INTERACTIVE)
        while (true) {
            val active = coordinationState.value
            if (active != null) throw ShellLaneBusy(active.owner)
            if (coordinationState.compareAndSet(expect = null, update = requested)) return requested
        }
    }

    private suspend fun acquireWhenAvailable(owner: PrivilegeExecutionLane): MainShellLease {
        val requested = MainShellLease(owner)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (coordinationState.compareAndSet(expect = null, update = requested)) return requested
            coordinationState.first { active -> active == null }
        }
    }

    private fun release(lease: MainShellLease) {
        check(coordinationState.compareAndSet(expect = lease, update = null)) {
            "MainShell lease ownership changed before release"
        }
    }

    private class MainShellLease(val owner: PrivilegeExecutionLane)

    private sealed interface CommandOutcome {
        data class Completed(val result: RootCommandResult) : CommandOutcome

        data object TimedOut : CommandOutcome
    }
}
