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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class OwnedRootShellExecutor(
    private val lane: PrivilegeExecutionLane,
    private val sessionFactory: RootShellSessionFactory,
    private val ioDispatcher: CoroutineDispatcher,
) : RootCommandExecutor {
    private val mutex = Mutex()
    private var currentLease: SessionLease? = null
    private var generation: Long = 0

    override suspend fun execute(command: RootCommand): RootCommandResult = mutex.withLock {
        val lease = healthySessionOrOpen()
        try {
            executeWithOptionalTimeout(lease, command)
        } catch (timeout: TimeoutCancellationException) {
            invalidateExactGeneration(lease)
            throw ShellCommandTimedOut(command.execution.commandClass)
        } catch (cancelled: CancellationException) {
            invalidateExactGeneration(lease)
            throw ShellCommandCancelled(command.execution.commandClass, cancelled)
        } catch (transport: RootShellTransportException) {
            invalidateExactGeneration(lease)
            throw ShellTransportDied(lane, transport)
        }
    }

    private suspend fun healthySessionOrOpen(): SessionLease {
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

    private suspend fun executeWithOptionalTimeout(
        lease: SessionLease,
        command: RootCommand,
    ): RootCommandResult {
        val timeout = command.execution.commandTimeout
        return if (timeout == null) {
            lease.session.execute(command.text)
        } else {
            withTimeout(timeout) {
                lease.session.execute(command.text)
            }
        }
    }

    private suspend fun invalidateExactGeneration(lease: SessionLease) {
        val ownedLease = currentLease
        if (ownedLease?.generation != lease.generation || ownedLease.session !== lease.session) return

        withContext(NonCancellable + ioDispatcher) {
            val latestLease = currentLease
            if (
                latestLease?.generation == lease.generation &&
                latestLease.session === lease.session
            ) {
                currentLease = null
                try {
                    lease.session.close()
                } catch (_: Exception) {
                    // The failed generation is already detached; preserve the command outcome.
                }
            }
        }
    }

    private data class SessionLease(
        val generation: Long,
        val session: RootShellSession,
    )
}
