// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneUnavailable
import com.valhalla.thor.domain.model.ShellTransportDied
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class OwnedRootShellExecutor(
    private val lane: PrivilegeExecutionLane,
    sessionFactory: RootShellSessionFactory,
    ioDispatcher: CoroutineDispatcher,
) : RootCommandExecutor {
    private val mutex = Mutex()
    private val generationOwner = RootShellGenerationOwner(sessionFactory, ioDispatcher)

    /** Called at a lane's idle boundary; active work keeps its exact generation until completion. */
    suspend fun retireIdleSession() = mutex.withLock {
        generationOwner.retireCurrentGeneration()
    }

    override suspend fun execute(command: RootCommand): RootCommandResult = mutex.withLock {
        command.rootOutcome = null
        var lease: RootShellGenerationOwner.SessionLease? = null
        try {
            lease = generationOwner.healthySessionOrOpen()
            currentCoroutineContext().ensureActive()
            when (val outcome = executeWithOptionalTimeout(lease, command)) {
                is CommandExecutionOutcome.Completed -> outcome.result
                CommandExecutionOutcome.TimedOut -> {
                    if (command.execution.rootExecutionPolicy == RootExecutionPolicy.PERSISTENT) {
                        generationOwner.invalidateExactGeneration(lease)
                    }
                    throw ShellCommandTimedOut(command.execution.commandClass, command.rootOutcome)
                }
            }
        } catch (cancelled: CancellationException) {
            if (command.execution.rootExecutionPolicy == RootExecutionPolicy.ISOLATED) throw cancelled
            withContext(NonCancellable) {
                lease?.let { generationOwner.invalidateExactGeneration(it) }
                throw ShellCommandCancelled(command.execution.commandClass, cancelled)
            }
        } catch (transport: RootShellTransportException) {
            val cause = transport.cause ?: transport
            if (lease == null) {
                throw ShellLaneUnavailable(lane, cause)
            }
            generationOwner.invalidateExactGeneration(lease)
            throw ShellTransportDied(lane, cause)
        } finally {
            if (command.rootOutcome?.shellReusable == false) {
                withContext(NonCancellable) {
                    lease?.let { generationOwner.invalidateExactGeneration(it) }
                }
            }
        }
    }

    private suspend fun executeWithOptionalTimeout(
        lease: RootShellGenerationOwner.SessionLease,
        command: RootCommand,
    ): CommandExecutionOutcome {
        val timeout = command.execution.commandTimeout
        return if (timeout == null) {
            CommandExecutionOutcome.Completed(lease.session.execute(command))
        } else {
            withTimeoutOrNull(timeout) {
                CommandExecutionOutcome.Completed(lease.session.execute(command))
            } ?: CommandExecutionOutcome.TimedOut
        }
    }

    private sealed interface CommandExecutionOutcome {
        data class Completed(val result: RootCommandResult) : CommandExecutionOutcome

        data object TimedOut : CommandExecutionOutcome
    }
}

/**
 * Owns generation identity separately from command admission so cleanup can be tested at the
 * dispatcher boundary. Production callers serialize all methods through [OwnedRootShellExecutor].
 */
internal class RootShellGenerationOwner(
    private val sessionFactory: RootShellSessionFactory,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var currentLease: SessionLease? = null
    private var generation: Long = 0

    suspend fun retireCurrentGeneration() {
        currentLease?.let { invalidateExactGeneration(it) }
    }

    suspend fun healthySessionOrOpen(): SessionLease {
        currentLease?.let { lease ->
            if (lease.session.isAlive) return lease
            invalidateExactGeneration(lease)
        }

        val session = sessionFactory.open()
        return SessionLease(
            generation = ++generation,
            session = session,
        ).also { currentLease = it }
    }

    suspend fun invalidateExactGeneration(lease: SessionLease) {
        val ownedLease = currentLease
        if (ownedLease?.generation != lease.generation || ownedLease.session !== lease.session) return

        currentLease = null
        withContext(NonCancellable + ioDispatcher) {
            try {
                lease.session.close()
            } catch (_: Exception) {
                // The failed generation is already detached; preserve the command outcome.
            }
        }
    }

    data class SessionLease(
        val generation: Long,
        val session: RootShellSession,
    )
}
