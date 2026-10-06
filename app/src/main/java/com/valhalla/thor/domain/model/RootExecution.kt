// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Shell state/lifetime semantics are independent of scheduling lane and diagnostic command class. */
enum class RootExecutionPolicy { PERSISTENT, ISOLATED }

enum class RootJobOutcomeKind { EXITED, CANCELLED, FAILED, TERMINATION_UNCONFIRMED }

/** Acknowledgement is not rollback; deliberately detached descendants are outside this contract. */
data class RootJobOutcome(
    val kind: RootJobOutcomeKind,
    val exitCode: Int?,
    val stdout: List<String>,
    val stderr: List<String>,
    val started: Boolean,
    val terminationConfirmed: Boolean,
    val outputDrained: Boolean,
    val shellReusable: Boolean,
    val failure: String?,
) {
    val cleanupConfirmed: Boolean
        get() = kind != RootJobOutcomeKind.TERMINATION_UNCONFIRMED && terminationConfirmed && outputDrained
}

/**
 * Per-command hooks retained when an execution context is copied or routed to another shell.
 * [beforeSubmit] may persist resource ownership and reject dispatch. [onOutcome] runs without
 * coroutine cancellation, before the shell lease is released, including prepared cancellation.
 */
interface RootExecutionObserver {
    suspend fun beforeSubmit() {}
    suspend fun onOutcome(outcome: RootJobOutcome)
}

/** Also attached to an original CancellationException as suppressed acknowledgement metadata. */
class IsolatedRootExecutionException(
    val outcome: RootJobOutcome,
    cause: Throwable? = null,
) : PrivilegeExecutionException("Isolated root execution ${outcome.kind}", cause)
