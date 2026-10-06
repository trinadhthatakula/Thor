// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome

data class RootCommand(
    val text: String,
    val execution: PrivilegeExecutionContext,
) {
    /** Per-invocation receipt, including cleanup after a local timeout stops awaiting a result. */
    @Volatile internal var rootOutcome: RootJobOutcome? = null
}

data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val rootOutcome: RootJobOutcome? = null,
)

internal class RootShellTransportException(cause: Throwable? = null) :
    Exception("Root shell transport unavailable", cause)

internal interface RootShellSession {
    val isAlive: Boolean

    @Throws(RootShellTransportException::class)
    suspend fun execute(command: String): RootCommandResult

    suspend fun execute(command: RootCommand): RootCommandResult {
        check(command.execution.rootExecutionPolicy == RootExecutionPolicy.PERSISTENT) {
            "This root session does not support isolated execution"
        }
        return execute(command.text)
    }

    fun close()
}

internal fun interface RootShellSessionFactory {
    suspend fun open(): RootShellSession
}

internal interface RootCommandExecutor {
    suspend fun execute(command: RootCommand): RootCommandResult
}
