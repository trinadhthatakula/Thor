// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.domain.model.ShellTransportDied
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

internal fun interface MainShellPendingCommand {
    fun submit(completion: (Result<ShellResult>) -> Unit)
}

internal fun interface MainShellJobFactory {
    suspend fun create(command: RootCommand): MainShellPendingCommand
}

@Single(binds = [MainShellJobFactory::class])
internal class OdinMainShellJobFactory : MainShellJobFactory {
    override suspend fun create(command: RootCommand): MainShellPendingCommand {
        val shell = getShellAwait()
        val stdout = ArrayList<String?>()
        val stderr = ArrayList<String?>()
        val job = shell.newJob().add(command.text).to(stdout, stderr)
        return MainShellPendingCommand { completion ->
            try {
                job.submit(null) { result ->
                    completion(
                        Result.success(
                            ShellResult(
                                code = result.code,
                                stdout = result.stdout,
                                stderr = result.stderr,
                            ),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                completion(Result.failure(failure))
            }
        }
    }
}

/** Executes through Odin's process-wide MainShell. Coordination belongs to [RootFallbackCoordinator]. */
@Single
internal class MainShellCommandExecutor(
    private val jobFactory: MainShellJobFactory,
) : RootCommandExecutor {
    override suspend fun execute(command: RootCommand): RootCommandResult {
        val pending = prepare(command)
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
            val outcome = completion.await()
            return toCommandResult(command, outcome)
        } catch (cancelled: CancellationException) {
            if (submissionWon) {
                withContext(NonCancellable) { completion.await() }
            }
            throw cancelled
        }
    }

    private suspend fun prepare(command: RootCommand): MainShellPendingCommand = try {
        jobFactory.create(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw ShellTransportDied(command.execution.lane, failure)
    }

    private fun toCommandResult(
        command: RootCommand,
        outcome: Result<ShellResult>,
    ): RootCommandResult {
        val result = outcome.getOrElse { failure ->
            throw ShellTransportDied(command.execution.lane, failure)
        }
        if (result.code == ShellResult.JOB_NOT_EXECUTED) {
            throw ShellTransportDied(command.execution.lane)
        }
        return RootCommandResult(
            exitCode = result.code,
            stdout = result.stdout,
            stderr = result.stderr,
        )
    }
}
