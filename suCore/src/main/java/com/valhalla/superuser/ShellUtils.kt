package com.valhalla.superuser

import android.os.Looper
import java.io.IOException
import java.io.InputStream

/**
 * Some handy utility methods that are used in `libSu`.
 *
 *
 * These methods are for internal use. I personally find them pretty handy, so I gathered them here.
 * However, since these are meant to be used internally, they are not stable APIs.
 * I would change them without too much consideration if needed. Also, these methods are not well
 * tested for public usage, many might not handle some edge cases correctly.
 * **You have been warned!!**
 */
@Suppress("unused")
object ShellUtils {
    /**
     * Test whether the list is `null` or empty or all elements are empty strings.
     * @param out the output of a shell command.
     * @return `false` if the list is `null` or empty or all elements are empty strings.
     */
    fun isValidOutput(out: List<String?>): Boolean {
        return out.any { !it.isNullOrEmpty() }
    }

    /**
     * Run commands with the main shell and get a single line output.
     * @param commands the commands.
     * @return the last line of the output of the command, empty string if no output is available.
     */
    fun fastCmd(vararg commands: String?): String {
        return fastCmd(Shell.shell, *commands)
    }

    /**
     * Run commands and get a single line output.
     * @param shell a shell instance.
     * @param commands the commands.
     * @return the last line of the output of t
     * the command, empty string if no output is available.
     */
    fun fastCmd(shell: Shell, vararg commands: String?): String {
        val out = shell.newJob().apply {
            commands.forEach { cmd ->
                if (cmd != null) add(cmd)
            }
            to(ArrayList(), null)
        }.exec().out
        return (if (isValidOutput(out)) out.last() else "") ?: ""
    }

    /**
     * Run commands with the main shell and return whether exits with 0 (success).
     * @param commands the commands.
     * @return `true` if the commands succeed.
     */
    fun fastCmdResult(vararg commands: String?): Boolean {
        return fastCmdResult(Shell.shell, *commands)
    }

    /**
     * Run commands and return whether exits with 0 (success).
     * @param shell a shell instance.
     * @param commands the commands.
     * @return `true` if the commands succeed.
     */
    fun fastCmdResult(shell: Shell, vararg commands: String?): Boolean {
        return shell.newJob().apply {
            commands.forEach { cmd ->
                if (cmd != null) add(cmd)
            }
            to(ArrayList(), null)
        }.exec().isSuccess
    }

    /**
     * Check if current thread is main thread.
     * @return `true` if the current thread is the main thread.
     */
    fun onMainThread(): Boolean {
        return Looper.getMainLooper().thread === Thread.currentThread()
    }

    /**
     * Discard all data currently available in an [InputStream].
     * @param inputStream the [InputStream] to be cleaned.
     */
    fun cleanInputStream(inputStream: InputStream) {
        try {
            while (inputStream.available() != 0) inputStream.skip(inputStream.available().toLong())
        } catch (_: IOException) {
        }
    }

    private const val SINGLE_QUOTE = '\''

    /**
     * Format string to quoted and escaped string suitable for shell commands.
     * @param s the string to be formatted.
     * @return the formatted string.
     */

    fun escapedString(s: String): String {
        val sb = StringBuilder()
        sb.append(SINGLE_QUOTE)
        val len = s.length
        for (i in 0..<len) {
            val c = s[i]
            if (c == SINGLE_QUOTE) {
                sb.append("'\\''")
                continue
            }
            sb.append(c)
        }
        sb.append(SINGLE_QUOTE)
        return sb.toString()
    }

    /**
     * Get the greatest common divisor of 2 integers with binary algorithm.
     * @param u an integer.
     * @param v an integer.
     * @return the greatest common divisor.
     */
    fun gcd(u: Long, v: Long): Long {
        var u = u
        var v = v
        if (u == 0L) return v
        if (v == 0L) return u
        var shift = 0
        while (((u or v) and 1L) == 0L) {
            u = u shr 1
            v = v shr 1
            ++shift
        }
        while ((u and 1L) == 0L) u = u shr 1
        do {
            while ((v and 1L) == 0L) v = v shr 1

            if (u > v) {
                val t = v
                v = u
                u = t
            }
            v -= u
        } while (v != 0L)

        return u shl shift
    }
}
