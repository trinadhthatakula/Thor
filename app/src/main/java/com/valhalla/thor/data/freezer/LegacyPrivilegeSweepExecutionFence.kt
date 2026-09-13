// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.koin.core.annotation.Single

/** Process-local, one-shot admission barrier between legacy Workers and the service queue. */
@Single
internal class LegacyPrivilegeSweepExecutionFence {
    private val lock = Any()
    private var admissionOpen = true
    private var activeExecutions = 0
    private var quiescent = CompletableDeferred(Unit)

    fun tryRegister(): Registration? = synchronized(lock) {
        if (!admissionOpen) return@synchronized null
        if (activeExecutions == 0) quiescent = CompletableDeferred()
        activeExecutions++
        Registration(::release)
    }

    fun closeAdmission() {
        synchronized(lock) {
            admissionOpen = false
            if (activeExecutions == 0) quiescent.complete(Unit)
        }
    }

    suspend fun awaitQuiescence() {
        val completion = synchronized(lock) { quiescent }
        completion.await()
    }

    internal fun isAdmissionOpenForTest(): Boolean = synchronized(lock) { admissionOpen }

    internal fun activeExecutionsForTest(): Int = synchronized(lock) { activeExecutions }

    private fun release() {
        synchronized(lock) {
            check(activeExecutions > 0) { "Privilege sweep execution registration underflow" }
            activeExecutions--
            if (activeExecutions == 0) quiescent.complete(Unit)
        }
    }

    class Registration internal constructor(
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
