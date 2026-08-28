// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
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

    override suspend fun execute(command: RootCommand): RootCommandResult = mutex.withLock {
        var lease: RootShellGenerationOwner.SessionLease? = null
        try {
            lease = generationOwner.healthySessionOrOpen()
            currentCoroutineContext().ensureActive()
            when (val outcome = executeWithOptionalTimeout(lease, command)) {
                is CommandExecutionOutcome.Completed -> outcome.result
                CommandExecutionOutcome.TimedOut -> {
                    generationOwner.invalidateExactGeneration(lease)
                    throw ShellCommandTimedOut(command.execution.commandClass)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                lease?.let { generationOwner.invalidateExactGeneration(it) }
                throw ShellCommandCancelled(command.execution.commandClass, cancelled)
            }
        } catch (transport: RootShellTransportException) {
            lease?.let { generationOwner.invalidateExactGeneration(it) }
            throw ShellTransportDied(lane, transport)
        }
    }

    private suspend fun executeWithOptionalTimeout(
        lease: RootShellGenerationOwner.SessionLease,
        command: RootCommand,
    ): CommandExecutionOutcome {
        val timeout = command.execution.commandTimeout
        return if (timeout == null) {
            CommandExecutionOutcome.Completed(lease.session.execute(command.text))
        } else {
            withTimeoutOrNull(timeout) {
                CommandExecutionOutcome.Completed(lease.session.execute(command.text))
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
