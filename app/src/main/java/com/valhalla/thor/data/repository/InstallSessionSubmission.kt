// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** After commit returns, retain the caller's lease until completion, even during cancellation. */
internal suspend fun trackInstallSessionSubmission(
    abandon: () -> Unit,
    onSubmissionFailure: suspend (Throwable) -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
    awaitCompletion: suspend () -> Unit,
    submit: suspend (markSubmitted: () -> Unit) -> Unit,
): Boolean {
    var submitted = false
    try {
        submit { submitted = true }
    } catch (failure: Throwable) {
        if (!submitted) runCatching(abandon)
        if (failure is CancellationException) throw failure
        if (submitted) onCleanupFailure(failure) else onSubmissionFailure(failure)
    } finally {
        // Neither cancellation nor closing the session handle stops an accepted installation.
        if (submitted) withContext(NonCancellable) { awaitCompletion() }
    }
    currentCoroutineContext().ensureActive()
    return submitted
}
