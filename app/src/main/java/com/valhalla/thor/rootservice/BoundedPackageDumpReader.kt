// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol

/** Only a completed, exit-zero, fully drained producer can carry output. Never expose a prefix. */
internal data class PackageDumpRead(
    val output: String? = null,
    val reason: Int = Protocol.REASON_NONE,
    val canonicalState: CanonicalSuspensionState? = null,
)

/**
 * A single argv-based dumpsys read owned by the root daemon. The caller waits at most [timeoutMillis]
 * for process startup, output EOF and exit. The read task owns its pipes; timeout/overflow destroys
 * that child and returns no text. An optional typed PackageManager observation shares that budget.
 * An interrupted Binder call may continue in the worker; admission stays held until that worker
 * actually returns. Killing the child does not cancel a system-server Binder dump or mutation.
 */
internal class BoundedPackageDumpReader(
    private val startProcess: (List<String>) -> Process = { arguments ->
        ProcessBuilder(arguments).redirectErrorStream(true).start()
    },
    private val maxOutputBytes: Int = Protocol.MAX_OUTPUT_BYTES,
    private val timeoutMillis: Long = Protocol.READ_TIMEOUT_MILLIS,
) {
    private val inFlight = AtomicBoolean(false)

    fun read(
        packageName: String,
        userId: Int? = null,
        readCanonicalState: (() -> CanonicalSuspensionState?)? = null,
    ): PackageDumpRead {
        if (!Protocol.isValidPackageIdentity(packageName) ||
            (readCanonicalState != null && (userId == null || userId < 0))
        ) {
            return PackageDumpRead(reason = Protocol.REASON_INVALID_ARGUMENT)
        }
        require(maxOutputBytes > 0 && timeoutMillis > 0)
        val admission = inFlight
        if (!admission.compareAndSet(false, true)) return PackageDumpRead(reason = Protocol.REASON_BUSY)
        // Copy constructor state into locals, avoiding an accessor back into this object from the
        // worker class. A daemon worker also cannot hold the root process alive after shutdown.
        val factory = startProcess
        val byteLimit = maxOutputBytes
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val deadline = System.nanoTime() + timeoutNanos
        val child = AtomicReference<Process?>()
        val abandoned = AtomicBoolean(false)
        val task = FutureTask {
            try {
                if (abandoned.get()) {
                    return@FutureTask PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                }
                val canonicalState = readCanonicalState?.invoke()
                if (readCanonicalState != null && (canonicalState == null ||
                        canonicalState.packageName != packageName || canonicalState.userId != userId ||
                        (!canonicalState.installed && canonicalState.suspended))
                ) return@FutureTask PackageDumpRead(reason = Protocol.REASON_CANONICAL_STATE_UNAVAILABLE)
                if (abandoned.get()) {
                    return@FutureTask PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                }
                val process = factory(listOf("dumpsys", "-t", "5", "package", packageName))
                child.set(process)
                if (abandoned.get()) {
                    return@FutureTask PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                }
                process.outputStream.close()
                val bytes = ByteArrayOutputStream(minOf(byteLimit, 8_192))
                process.inputStream.use { input ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (abandoned.get()) {
                            return@FutureTask PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                        }
                        if (count > byteLimit - bytes.size()) {
                            return@FutureTask PackageDumpRead(reason = Protocol.REASON_OUTPUT_LIMIT)
                        }
                        bytes.write(buffer, 0, count)
                    }
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    return@FutureTask PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                }
                if (process.exitValue() != 0) {
                    return@FutureTask PackageDumpRead(reason = Protocol.REASON_READ_FAILED)
                }
                val output = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                if (output.lineSequence().any { isDumpTimeoutLine(it.trim()) }) {
                    PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
                } else if (output.isBlank()) {
                    PackageDumpRead(reason = Protocol.REASON_MALFORMED_OUTPUT)
                } else {
                    PackageDumpRead(output = output, canonicalState = canonicalState)
                }
            } finally {
                // This also runs if startup completes after the caller timed out. The worker owns
                // stream closure: closing a pipe concurrently with its blocked read can itself wait.
                child.get()?.let { process ->
                    runCatching { process.destroyForcibly() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }
                admission.set(false)
            }
        }
        val worker = Thread(task, "thor-suspension-read").apply { isDaemon = true }
        try {
            worker.start()
        } catch (_: Exception) {
            admission.set(false)
            return PackageDumpRead(reason = Protocol.REASON_READ_FAILED)
        }
        return try {
            task.get(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            abandoned.set(true)
            runCatching { child.get()?.destroyForcibly() }
            worker.interrupt()
            PackageDumpRead(reason = Protocol.REASON_TIMEOUT)
        } catch (_: InterruptedException) {
            abandoned.set(true)
            runCatching { child.get()?.destroyForcibly() }
            worker.interrupt()
            Thread.currentThread().interrupt()
            PackageDumpRead(reason = Protocol.REASON_READ_FAILED)
        } catch (_: ExecutionException) {
            PackageDumpRead(reason = Protocol.REASON_READ_FAILED)
        }
    }
}
