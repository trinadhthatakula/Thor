// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.model.RootExecutionPolicy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

internal fun interface MainShellPendingCommand {
    fun submit(completion: (Result<ShellResult>) -> Unit)
    fun cancel() {}
    val isolatedJob: IsolatedRootJob? get() = null
    suspend fun retireTransport() {}
}

internal fun interface MainShellJobFactory {
    suspend fun create(command: RootCommand): MainShellPendingCommand
}

@Single(binds = [MainShellJobFactory::class])
internal class OdinMainShellJobFactory : MainShellJobFactory {
    override suspend fun create(command: RootCommand): MainShellPendingCommand {
        val shell = getShellAwait()
        if (!shell.isRoot) throw RootShellTransportException()
        if (command.execution.rootExecutionPolicy == RootExecutionPolicy.ISOLATED) {
            val prepared = OdinIsolatedRootJob(shell.prepareIsolatedJob(command.text))
            return object : MainShellPendingCommand {
                override val isolatedJob: IsolatedRootJob = prepared
                override fun submit(completion: (Result<ShellResult>) -> Unit) {
                    error("An isolated command must use its acknowledged execution contract")
                }
                override suspend fun retireTransport() {
                    withContext(NonCancellable + Dispatchers.IO) { shell.close() }
                }
            }
        }
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
        command.rootOutcome = null
        val pending = prepare(command)
        if (command.execution.rootExecutionPolicy == RootExecutionPolicy.ISOLATED) {
            try {
                return executeIsolatedRootCommand(command, checkNotNull(pending.isolatedJob))
            } finally {
                if (command.rootOutcome?.shellReusable == false) {
                    withContext(NonCancellable) {
                        try {
                            pending.retireTransport()
                        } catch (_: Exception) {
                            // The outcome already describes an unusable transport; preserve it.
                        }
                    }
                }
            }
        }
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
                pending.cancel()
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
