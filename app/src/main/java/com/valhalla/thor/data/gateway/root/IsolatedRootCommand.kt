// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.superuser.JobHandle
import com.valhalla.superuser.JobOutcome
import com.valhalla.superuser.JobOutcomeKind
import com.valhalla.superuser.ktx.await
import com.valhalla.thor.domain.model.IsolatedRootExecutionException
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

internal interface IsolatedRootJob {
    fun submit()
    fun cancel()
    suspend fun await(): RootJobOutcome
}

internal class OdinIsolatedRootJob(private val handle: JobHandle) : IsolatedRootJob {
    override fun submit() { handle.submit() }
    override fun cancel() { handle.cancel() }
    override suspend fun await(): RootJobOutcome = handle.await().toRootJobOutcome()
}

internal fun JobOutcome.toRootJobOutcome(): RootJobOutcome = RootJobOutcome(
    kind = when (kind) {
        JobOutcomeKind.EXITED -> RootJobOutcomeKind.EXITED
        JobOutcomeKind.CANCELLED -> RootJobOutcomeKind.CANCELLED
        JobOutcomeKind.FAILED -> RootJobOutcomeKind.FAILED
        JobOutcomeKind.TERMINATION_UNCONFIRMED -> RootJobOutcomeKind.TERMINATION_UNCONFIRMED
    },
    exitCode = exitCode,
    stdout = Collections.unmodifiableList(stdout.toList()),
    stderr = Collections.unmodifiableList(stderr.toList()),
    started = started,
    terminationConfirmed = terminationConfirmed,
    outputDrained = outputDrained,
    shellReusable = shellReusable,
    failure = failure,
)

/** The caller owns its transport/lease until this function records the complete acknowledgement. */
internal suspend fun executeIsolatedRootCommand(
    command: RootCommand,
    job: IsolatedRootJob,
): RootCommandResult {
    var submitted = false
    var recordingAttempted = false
    var terminalFailure: IsolatedRootExecutionException? = null

    suspend fun record(outcome: RootJobOutcome) {
        command.rootOutcome = outcome
        if (!recordingAttempted) {
            recordingAttempted = true
            command.execution.rootExecutionObserver?.onOutcome(outcome)
        }
    }

    suspend fun cancelAndRecord(primary: Throwable): RootJobOutcome = withContext(NonCancellable) {
        try {
            job.cancel()
        } catch (failure: Exception) {
            if (failure !== primary) primary.addSuppressed(failure)
        }
        val outcome = command.rootOutcome ?: try {
            job.await()
        } catch (failure: Exception) {
            if (failure !== primary) primary.addSuppressed(failure)
            // An exceptionally completed observer is not proof that dispatched work stopped.
            RootJobOutcome(
                kind = if (submitted) RootJobOutcomeKind.TERMINATION_UNCONFIRMED else RootJobOutcomeKind.FAILED,
                exitCode = null,
                stdout = emptyList(),
                stderr = emptyList(),
                started = submitted,
                terminationConfirmed = !submitted,
                outputDrained = !submitted,
                shellReusable = false,
                failure = failure.message ?: failure.javaClass.simpleName,
            )
        }
        try {
            record(outcome)
        } catch (failure: Exception) {
            if (failure !== primary) primary.addSuppressed(failure)
        }
        outcome
    }

    try {
        currentCoroutineContext().ensureActive()
        command.execution.rootExecutionObserver?.beforeSubmit()
        currentCoroutineContext().ensureActive()
        // An already-ready select clause may run undispatched; recheck before committing work.
        select {
            CompletableDeferred(Unit).onAwait {
                currentCoroutineContext().ensureActive()
                submitted = true
                job.submit()
            }
        }
        val outcome = job.await()
        withContext(NonCancellable) { record(outcome) }
        currentCoroutineContext().ensureActive()
        if (outcome.kind != RootJobOutcomeKind.EXITED || !outcome.cleanupConfirmed || outcome.exitCode == null) {
            throw IsolatedRootExecutionException(outcome).also { terminalFailure = it }
        }
        return RootCommandResult(outcome.exitCode, outcome.stdout, outcome.stderr, outcome)
    } catch (cancelled: CancellationException) {
        val outcome = cancelAndRecord(cancelled)
        cancelled.addSuppressed(IsolatedRootExecutionException(outcome))
        throw cancelled
    } catch (failure: Exception) {
        if (failure === terminalFailure) throw failure
        throw IsolatedRootExecutionException(cancelAndRecord(failure), failure)
    }
}
