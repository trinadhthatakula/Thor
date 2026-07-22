// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.extension.api.ShellExecutor
import kotlinx.coroutines.CancellationException

/**
 * The [ShellExecutor] handed to extensions. Routes raw commands through the
 * active privilege gateway (Root / Shizuku / Dhizuku) via [SystemRepository],
 * so extensions run with the same privilege as in-app actions — not the
 * root-only shell, which fails on Shizuku/Dhizuku devices.
 */
class ThorShellExecutor(
    private val systemRepository: SystemRepository
) : ShellExecutor {
    override suspend fun execute(command: String): Pair<Int, String?> {
        return try {
            systemRepository.executeShellCommand(command)
                .getOrElse { error ->
                    // Preserve structured concurrency: never swallow cancellation.
                    if (error is CancellationException) throw error
                    -1 to error.message
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1 to e.message
        }
    }
}
