// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

fun interface ReadyShareRetentionCleanup {
    suspend fun sweep()
}

/** Best-effort, once per process; never gates ordinary application startup. */
@Single
class ReadyShareRetentionStartup(private val cleanup: ReadyShareRetentionCleanup) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                cleanup.sweep()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep unclean rows recoverable for the next process. No paths or raw errors logged.
            }
        }
    }
}
