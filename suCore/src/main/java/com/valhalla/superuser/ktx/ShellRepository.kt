package com.valhalla.superuser.ktx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Interface for interacting with the Root Shell.
 * Inject this into your ViewModels via Koin.
 * DO NOT use static Shell.shell calls in your UI layer.
 */
interface ShellRepository {
    suspend fun isRootGranted(): Boolean
    suspend fun runCommand(command: String): Result<List<String>>
    suspend fun runCommands(vararg commands: String): Result<List<String>>
}

class RealShellRepository : ShellRepository {

    // Lazy, bounded, failure-safe root check. A hard shell-init failure now resumes getShellAwait()
    // exceptionally (via onShellDied); the timeout additionally covers a worker that never returns.
    // Either way this UI-gating probe resolves to false instead of suspending indefinitely.
    override suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(SHELL_INIT_TIMEOUT_MS) {
            try {
                getShellAwait().isRoot
            } catch (e: Exception) {
                // Rethrow cancellation so withTimeoutOrNull handles the timeout (and structured
                // concurrency is preserved); any other failure resolves the probe to false.
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        } ?: false
    }

    override suspend fun runCommand(command: String): Result<List<String>> {
        return runInternal(command)
    }

    override suspend fun runCommands(vararg commands: String): Result<List<String>> {
        return runInternal(*commands)
    }

    private suspend fun runInternal(vararg commands: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val shell = getShellAwait()
                val jobResult = shell.newJob().add(*commands).to(ArrayList()).await()

                if (jobResult.isSuccess) {
                    // Filter out nulls which legacy lib-su might produce
                    Result.success(jobResult.out.filterNotNull())
                } else {
                    val errorMsg = jobResult.err.filterNotNull().joinToString("\n")
                    Result.failure(IOException("Command failed with code ${jobResult.code}: $errorMsg"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {
        // Upper bound for shell-init before the root probe gives up and reports "no root".
        private const val SHELL_INIT_TIMEOUT_MS = 10_000L
    }
}