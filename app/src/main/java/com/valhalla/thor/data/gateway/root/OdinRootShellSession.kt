// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.Shell
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [RootShellSessionFactory::class])
internal class OdinRootShellSessionFactory(
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : RootShellSessionFactory {
    override suspend fun open(): RootShellSession =
        openOdinRootShellSession(ioDispatcher) {
            Shell.Builder.create().build()
        }
}

internal suspend fun openOdinRootShellSession(
    ioDispatcher: CoroutineDispatcher,
    buildShell: () -> Shell,
): RootShellSession {
    val pendingShell = AtomicReference<Shell?>()
    try {
        val shell = withContext(ioDispatcher) {
            buildShell().also { createdShell ->
                pendingShell.set(createdShell)
                if (!createdShell.isRoot) throw RootShellTransportException()
            }
        }
        pendingShell.getAndSet(null)
        return OdinRootShellSession(shell)
    } catch (cancelled: CancellationException) {
        closePendingShell(pendingShell, ioDispatcher)
        throw cancelled
    } catch (transport: RootShellTransportException) {
        closePendingShell(pendingShell, ioDispatcher)
        throw transport
    } catch (failure: Exception) {
        closePendingShell(pendingShell, ioDispatcher)
        throw RootShellTransportException(failure)
    }
}

private suspend fun closePendingShell(
    pendingShell: AtomicReference<Shell?>,
    ioDispatcher: CoroutineDispatcher,
) {
    val shell = pendingShell.getAndSet(null) ?: return
    withContext(NonCancellable + ioDispatcher) {
        try {
            shell.close()
        } catch (_: Exception) {
            // Preserve the construction failure or cancellation that selected cleanup.
        }
    }
}

internal class OdinRootShellSession(
    private val shell: Shell,
) : RootShellSession {
    override val isAlive: Boolean get() = shell.isAlive

    override suspend fun execute(command: String): RootCommandResult =
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine

            val submissionClaimed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                submissionClaimed.compareAndSet(false, true)
            }
            try {
                if (!shell.isAlive) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RootShellTransportException())
                    }
                    return@suspendCancellableCoroutine
                }
                // Cancellation and submission race for one claim. Once submission claims it, the
                // caller invalidates this exact shell generation rather than retrying the command.
                if (!submissionClaimed.compareAndSet(false, true)) {
                    return@suspendCancellableCoroutine
                }

                val stdout = mutableListOf<String?>()
                val stderr = mutableListOf<String?>()
                shell.newJob()
                    .to(stdout, stderr)
                    .add(command)
                    .submit(DIRECT_CALLBACK_EXECUTOR) { result ->
                        if (!continuation.isActive) return@submit
                        if (result.code == Shell.Result.JOB_NOT_EXECUTED || !shell.isAlive) {
                            continuation.resumeWithException(RootShellTransportException())
                        } else {
                            continuation.resume(
                                RootCommandResult(
                                    exitCode = result.code,
                                    stdout = immutableSnapshot(stdout),
                                    stderr = immutableSnapshot(stderr),
                                )
                            )
                        }
                    }
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) continuation.resumeWithException(cancelled)
            } catch (failure: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(RootShellTransportException(failure))
                }
            }
        }

    override fun close() {
        shell.close()
    }

    private companion object {
        val DIRECT_CALLBACK_EXECUTOR = Executor { callback -> callback.run() }

        fun immutableSnapshot(lines: List<String?>): List<String> =
            Collections.unmodifiableList(lines.filterNotNull())
    }
}
