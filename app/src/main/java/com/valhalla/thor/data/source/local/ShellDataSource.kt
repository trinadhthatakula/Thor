package com.valhalla.thor.data.source.local

import com.valhalla.superuser.Shell
import com.valhalla.thor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A clean data source for executing shell commands.
 * No logic, no formatting, just execution.
 */
class ShellDataSource {

    init {
        // Initialize LibSu configuration once, cleanly.
        // In a real app, you might want to do this in your Application class,
        // but it's safe to ensure it's set here.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        // LibSu caches this, so it's safe to call.
        Shell.rootAccess()
    }

    /**
     * Executes a command with Root privileges.
     * Returns true if exit code is 0 (Success).
     */
    suspend fun executeRootCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd(command).exec()
        result.isSuccess
    }

    /**
     * Executes a command and returns the output (STDOUT).
     * Useful for things like getting file paths or disk stats.
     */
    suspend fun executeRootCommandWithOutput(command: String): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd(command).exec()
        if (result.isSuccess) {
            result.out.joinToString("\n")
        } else {
            throw Exception("Command failed: $command | Error: ${result.err.joinToString("\n")}")
        }
    }
}