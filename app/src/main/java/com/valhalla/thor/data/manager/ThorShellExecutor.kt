package com.valhalla.thor.data.manager

import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.extension.api.ShellExecutor

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
                .getOrElse { -1 to it.message }
        } catch (e: Exception) {
            -1 to e.message
        }
    }
}
