// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.thor.domain.model.ShellTransportDied
import org.koin.core.annotation.Single

/** Executes through Odin's process-wide MainShell. Coordination belongs to [RootFallbackCoordinator]. */
@Single
internal class MainShellCommandExecutor(
    private val shellRepository: ShellRepository,
) : RootCommandExecutor {
    override suspend fun execute(command: RootCommand): RootCommandResult {
        val result = shellRepository.exec(command.text)
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
