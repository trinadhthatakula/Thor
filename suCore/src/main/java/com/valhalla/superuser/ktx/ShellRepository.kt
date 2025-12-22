package com.valhalla.superuser.repository

import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.await
import com.valhalla.superuser.ktx.getShellAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Lazy check for root status that doesn't block the UI thread
    override suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        // Ensure we have a shell first
        getShellAwait().isRoot
    }

    override suspend fun runCommand(command: String): Result<List<String>> {
        return runInternal(command)
    }

    override suspend fun runCommands(vararg commands: String): Result<List<String>> {
        return runInternal(*commands)
    }

    private suspend fun runInternal(vararg commands: String): Result<List<String>> = withContext(Dispatchers.IO) {
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
}

/*
 * Koin Module Suggestion:
 *
 * val shellModule = module {
 * single<ShellRepository> { RealShellRepository() }
 * }
 */