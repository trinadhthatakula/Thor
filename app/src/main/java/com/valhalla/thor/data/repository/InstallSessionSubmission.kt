// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.CancellationException

/** After commit returns, cleanup cannot revoke submission or authorize a second install. */
internal suspend fun trackInstallSessionSubmission(
    abandon: () -> Unit,
    onSubmissionFailure: suspend (Throwable) -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
    submit: suspend (markSubmitted: () -> Unit) -> Unit,
): Boolean {
    var submitted = false
    try {
        submit { submitted = true }
    } catch (failure: Throwable) {
        if (!submitted) runCatching(abandon)
        if (failure is CancellationException) throw failure
        if (submitted) onCleanupFailure(failure) else onSubmissionFailure(failure)
    }
    return submitted
}
