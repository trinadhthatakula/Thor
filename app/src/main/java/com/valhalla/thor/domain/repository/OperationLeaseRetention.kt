// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Exact in-process ownership retained independently of the coroutine that acquired it. */
internal fun interface RetainedOperationLease {
    suspend fun release()
}

/** Publish this ownership alongside any outer leases; release it after the last reference ends. */
internal suspend fun <T> withRetainableOperationLease(
    release: suspend () -> Unit,
    block: suspend () -> T,
): T {
    val reference = OperationLeaseReference(release)
    try {
        val outer = currentCoroutineContext()[OperationLeaseContext]?.references.orEmpty()
        return withContext(OperationLeaseContext(outer + reference)) { block() }
    } finally {
        withContext(NonCancellable) { reference.finishLexical() }
    }
}

/** Retain every actual lease held by this scope before handing work to an asynchronous producer. */
internal suspend fun retainOperationLeases(): RetainedOperationLease {
    val retained = ArrayList<OperationLeaseReference>()
    try {
        currentCoroutineContext()[OperationLeaseContext]?.references?.forEach { reference ->
            reference.retain()
            retained += reference
        }
    } catch (failure: Throwable) {
        try {
            RetainedOperationLeaseGroup(retained).release()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
    return RetainedOperationLeaseGroup(retained)
}

private class OperationLeaseContext(val references: List<OperationLeaseReference>) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationLeaseContext>
}

private class OperationLeaseReference(private val releaseOwnership: suspend () -> Unit) {
    private val lock = Any()
    private var references = 1
    private var lexicalActive = true

    fun retain() = synchronized(lock) {
        check(lexicalActive) { "An exited operation scope cannot retain ownership" }
        references++
    }

    suspend fun finishLexical() {
        val last = synchronized(lock) {
            check(lexicalActive) { "An operation scope must finish once" }
            lexicalActive = false
            --references == 0
        }
        if (last) releaseOwnership()
    }

    suspend fun releaseRetained() {
        val last = synchronized(lock) {
            check(references > 0) { "Released operation ownership cannot be released again" }
            --references == 0
        }
        if (last) releaseOwnership()
    }
}

private class RetainedOperationLeaseGroup(
    private val references: List<OperationLeaseReference>,
) : RetainedOperationLease {
    private val releasing = AtomicBoolean()
    private val released = CompletableDeferred<Unit>()

    override suspend fun release() = withContext(NonCancellable) {
        if (releasing.compareAndSet(false, true)) {
            var failure: Throwable? = null
            for (reference in references.asReversed()) {
                try {
                    reference.releaseRetained()
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
        }
        released.await()
    }
}
