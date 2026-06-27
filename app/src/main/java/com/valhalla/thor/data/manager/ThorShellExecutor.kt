package com.valhalla.thor.data.manager

import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.extension.api.ShellExecutor
import kotlinx.coroutines.runBlocking

class ThorShellExecutor(
    private val shellRepository: ShellRepository
) : ShellExecutor {
    override fun execute(command: String): Pair<Int, String?> {
        return try {
            val result = runBlocking { shellRepository.runCommand(command) }
            if (result.isSuccess) {
                0 to result.getOrNull()?.joinToString("\n")
            } else {
                1 to result.exceptionOrNull()?.message
            }
        } catch (e: Exception) {
            -1 to e.message
        }
    }
}
