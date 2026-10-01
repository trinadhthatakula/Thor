// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.escapeShellArg
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Generated input reads only; callers retain ownership of their URI/FD and final cache copy. */
internal class PrivilegedReadStaging(context: Context) {
    private val rootStaging = RootExportStaging(File(context.noBackupFilesDir, DIRECTORY_NAME))

    /** [execute] must remain bound to the gateway selected before this call, including cleanup. */
    suspend fun copy(
        sourcePath: String,
        destination: File,
        maxBytes: Long?,
        execution: PrivilegeExecutionContext,
        isRoot: Boolean,
        execute: suspend (String, PrivilegeExecutionContext) -> Result<Pair<Int, String?>>,
    ) {
        require(sourcePath.startsWith('/') && '\u0000' !in sourcePath) { "Expected an absolute input path" }
        require(maxBytes == null || maxBytes in 0 until Long.MAX_VALUE) { "Invalid staging byte limit" }
        currentCoroutineContext().ensureActive()
        val routed = execution.copy(
            lane = PrivilegeExecutionLane.ARCHIVE,
            commandTimeout = execution.commandTimeout?.coerceAtMost(9.minutes) ?: 9.minutes,
        ).also { it.provenance = execution.provenance }
        if (isRoot) {
            rootStaging.sweep()
            rootStaging.copy(sourcePath, destination, routed,
                validatePayload = { validateReadPayload(it, maxBytes) },
            ) { source, payload, isolated ->
                execute(boundedReadCommand(source, payload, maxBytes), isolated).map { result ->
                    if (result.first != 0) throw IOException("Could not copy the selected file.")
                }
            }
            return
        }

        // Shell/Shizuku cannot write Thor's private payload. Preserve its existing temporary-file
        // route and synchronous provider behavior; Odin acknowledgement applies only to root.
        val temporary = File("/data/local/tmp/thor_read_${UUID.randomUUID()}")
        val persistent = routed.copy(rootExecutionPolicy = RootExecutionPolicy.PERSISTENT)
            .also { it.provenance = execution.provenance }
        var primary: Throwable? = null
        try {
            val command = boundedReadCommand(sourcePath, temporary.absolutePath, maxBytes) +
                " && chmod 666 ${temporary.absolutePath.escapeShellArg()} 2>/dev/null"
            val result = execute(command, persistent).getOrThrow()
            if (result.first != 0) throw IOException("Could not copy the selected file.")
            currentCoroutineContext().ensureActive()
            validateReadPayload(temporary, maxBytes)
            copyReadPayload(temporary, destination, maxBytes)
            validateReadPayload(destination, maxBytes)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    val result = execute("rm -f ${temporary.absolutePath.escapeShellArg()}", persistent).getOrThrow()
                    if (result.first != 0) throw IOException("Could not remove the temporary input copy.")
                } catch (cleanup: Exception) {
                    // A completed private read remains usable if only legacy temporary cleanup
                    // fails. On a primary failure, preserve the cleanup evidence as suppressed.
                    primary?.addSuppressed(cleanup)
                }
            }
        }
    }

    companion object {
        const val DIRECTORY_NAME = "root_read_staging"
    }
}

/** Recheck the bound while copying the legacy shared temporary file, which can still change. */
internal suspend fun copyReadPayload(source: File, destination: File, maxBytes: Long?) {
    currentCoroutineContext().ensureActive()
    source.inputStream().use { input ->
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                if (maxBytes != null && read > maxBytes - copied) {
                    throw IOException("The selected file exceeds the staging byte limit.")
                }
                output.write(buffer, 0, read)
                copied += read
            }
        }
    }
}

/** One excess byte detects an oversized preview without first copying an unbounded source. */
internal fun boundedReadCommand(source: String, destination: String, maxBytes: Long?): String {
    val read = if (maxBytes == null) "cat" else "head -c ${maxBytes + 1}"
    return "$read ${source.escapeShellArg()} > ${destination.escapeShellArg()} 2>/dev/null"
}

internal fun validateReadPayload(payload: File, maxBytes: Long?) {
    if (!payload.isFile || payload.length() == 0L) throw IOException("Could not open the selected file.")
    if (maxBytes != null && payload.length() > maxBytes) {
        throw IOException("The selected file is larger than ${maxBytes / (1024 * 1024)} MB and was not staged.")
    }
}
