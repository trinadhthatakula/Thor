// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.Shell
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [RootShellSessionFactory::class])
internal class OdinRootShellSessionFactory(
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : RootShellSessionFactory {
    override suspend fun open(): RootShellSession = withContext(ioDispatcher) {
        try {
            OdinRootShellSession(Shell.Builder.create().build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw RootShellTransportException(failure)
        }
    }
}

internal class OdinRootShellSession(
    private val shell: Shell,
) : RootShellSession {
    override val isAlive: Boolean get() = shell.isAlive

    override suspend fun execute(command: String): RootCommandResult =
        suspendCancellableCoroutine { continuation ->
            try {
                if (!shell.isAlive) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RootShellTransportException())
                    }
                    return@suspendCancellableCoroutine
                }
                shell.newJob().add(command).submit { result ->
                    if (!continuation.isActive) return@submit
                    if (result.code == Shell.Result.JOB_NOT_EXECUTED || !shell.isAlive) {
                        continuation.resumeWithException(RootShellTransportException())
                    } else {
                        continuation.resume(
                            RootCommandResult(
                                exitCode = result.code,
                                stdout = result.stdout,
                                stderr = result.stderr,
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
}
