package com.valhalla.superuser.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Reads streams using Coroutines on the IO dispatcher.
 * Replaces the legacy 'StreamGobbler' class.
 */
internal abstract class CoroutineStreamGobbler(
    private val inputStream: InputStream,
    private val list: MutableList<String?>?
) {

    suspend fun process(): String? = withContext(Dispatchers.IO) {
        val br = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        var lastLine: String? = null

        while (true) {
            val line = br.readLine() ?: break

            // Logic ported from legacy StreamGobbler to handle UUID markers
            val len = line.length
            val isEnd = line.startsWith(JobTask.END_UUID, len - JobTask.UUID_LEN)

            var content = line
            if (isEnd) {
                if (len == JobTask.UUID_LEN) {
                    // Just the UUID, means we are done
                    lastLine = br.readLine() // Read the return code line
                    break
                }
                content = line.substring(0, len - JobTask.UUID_LEN)
            }

            if (list != null) {
                // Warning: list is likely not thread-safe, ensure the caller handles synchronization
                // or use a thread-safe list.
                synchronized(list) {
                    list.add(content)
                }
                Utils.log("SHELL_OUT", content)
            }

            if (isEnd) {
                lastLine = br.readLine()
                break
            }
        }
        lastLine
    }

    class Out(inputStream: InputStream, list: MutableList<String?>?) :
        CoroutineStreamGobbler(inputStream, list) {

        suspend fun readCode(): Int {
            val codeStr = process()
            return try {
                codeStr?.toInt() ?: 1
            } catch (_: NumberFormatException) {
                1
            }
        }
    }

    class Err(inputStream: InputStream, list: MutableList<String?>?) :
        CoroutineStreamGobbler(inputStream, list) {

        suspend fun drain() {
            process()
        }
    }
}