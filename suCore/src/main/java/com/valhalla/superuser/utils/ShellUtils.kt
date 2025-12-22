package com.valhalla.superuser.utils

import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.await

/**
 * Modern replacement for the legacy ShellUtils object.
 * Uses extension functions for cleaner syntax.
 */

/**
 * Checks if the output list contains valid data.
 */
fun List<String?>?.isValidOutput(): Boolean {
    return this?.any { !it.isNullOrEmpty() } == true
}

/**
 * Escapes a string for use in a shell command.
 * Replaces `ShellUtils.escapedString(str)`.
 */
fun String.escapeForShell(): String {
    return "'" + this.replace("'", "'\\''") + "'"
}

/**
 * Quickly runs a command and returns the first line of output or empty string.
 * Now a suspend function to prevent blocking the Main Thread.
 */
suspend fun Shell.fastCmd(vararg commands: String): String {
    val result = this.newJob().add(*commands).to(ArrayList()).await()
    val out = result.out
    return if (out.isValidOutput()) out.lastOrNull() ?: "" else ""
}

/**
 * Quickly checks if a command succeeds (exit code 0).
 */
suspend fun Shell.fastCmdResult(vararg commands: String): Boolean {
    return this.newJob().add(*commands).to(ArrayList(), null).await().isSuccess
}